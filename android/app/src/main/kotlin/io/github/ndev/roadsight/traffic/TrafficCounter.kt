package io.github.ndev.roadsight.traffic

import android.graphics.Bitmap
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.TRAFFIC_SENS
import io.github.ndev.roadsight.ai.BitmapFrame
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.camera.FrameSink
import io.github.ndev.roadsight.core.traffic.Counter
import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Kinds
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.core.traffic.VehicleDetector
import io.github.ndev.roadsight.core.traffic.regionFor
import io.github.ndev.roadsight.debug.DebugLog
import io.github.ndev.roadsight.service.BackgroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.hypot

/** A road user on the live picture: box as fractions of the frame, label, whether it's been counted. */
class RoadBox(val box: DoubleArray, val label: String, val counted: Boolean, val flash: Boolean)

/** The last road user counted, for the pill above the counts. */
class LastCounted(val kind: String, val speed: Double?)

/** Everything the traffic screen shows. */
data class TrafficUi(
    val ready: Boolean = false,
    val status: String = "Starting the AI…",
    val aspect: Double = 9.0 / 16,
    val roi: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
    val boxes: List<RoadBox> = emptyList(),
    val fps: Double = 0.0,
    val msAi: Double = 0.0,
    val msPrep: Double = 0.0,
    val model: String = "",
    val idle: Boolean = false,
    val running: Boolean = false,
    val started: Long = 0,
    val sessionId: Long? = null,
    /** kind -> [total, direction 1, direction 2] */
    val totals: Map<String, IntArray> = emptyTotals(),
    val last: LastCounted? = null,
    val counted: Long = 0,
    /** Frames analysed since the app started. */
    val frames: Long = 0,
)

fun emptyTotals(): Map<String, IntArray> = Kinds.ORDER.associateWith { IntArray(3) }

/**
 * Live traffic counting (TrafficSight's main.js): each frame goes through the road-user finder in the
 * area around the two lines; road users are tracked and counted as they cross a line; crossing both
 * gives the speed. Only what passed and when is kept.
 */
class TrafficCounter(private val app: App) : FrameSink {
    val ui = MutableStateFlow(TrafficUi())
    private val lock = Any()
    private val detector = VehicleDetector()
    private var frame: BitmapFrame? = null
    private var counter: Counter? = null
    private var aspect = 0.0
    private var roi = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
    private var lines = Lines.default()
    private var distance = 20.0
    private var running = false
    private var sessionId: Long? = null
    private var started = 0L
    private val totals = HashMap<String, IntArray>()
    private var last: LastCounted? = null
    private var lastMove = 0.0
    private var lastT = 0.0
    private var fps = 0.0
    private var nextAt = 0.0
    private val flash = HashMap<Int, Long>()
    private var model: Model = Model.VEH_TINY
    private val recent = ArrayList<Double>()

    init {
        for (k in Kinds.ORDER) totals[k] = IntArray(3)
    }

    /** Settings changed (lines moved, distance edited): start a fresh counter with them. */
    fun reconfigure() {
        synchronized(lock) {
            counter = null
            publishSetup()
        }
    }

    private fun publishSetup() {
        val p = app.prefs
        lines = p.lines
        distance = p.distanceM
        if (aspect > 0) roi = regionFor(lines, aspect)
        ui.value = ui.value.copy(roi = roi)
    }

    fun startSession() {
        synchronized(lock) {
            if (running) return
            val p = app.prefs
            publishSetup()
            counter = null
            for (v in totals.values) v.fill(0)
            last = null
            started = System.currentTimeMillis()
            sessionId = app.db.newSession(p.site, started, p.distanceM, p.speedLimit, p.dir1, p.dir2, p.lines, model.key)
            running = true
            ui.value = ui.value.copy(running = true, started = started, sessionId = sessionId, totals = copyTotals(), last = null)
            DebugLog.add("traffic", "Counting started (session $sessionId, ${p.distanceM} m, lines ${if (p.lines.level(if (aspect > 0) aspect else 9.0 / 16)) "level" else "upright"})")
        }
        // Counting carries on with the screen off (Android shows a notification meanwhile).
        if (app.prefs.backgroundCounting) BackgroundService.start(app, BackgroundService.TRAFFIC)
    }

    /** Ends the session: everything still in view is saved. Returns the session's id. */
    fun stopSession(): Long? {
        synchronized(lock) {
            if (!running) return null
            running = false
            counter?.let { save(it.flush(), lastT) }
            val id = sessionId
            id?.let { app.db.endSession(it, System.currentTimeMillis()) }
            sessionId = null
            ui.value = ui.value.copy(running = false, sessionId = null)
            DebugLog.add("traffic", "Counting stopped (session $id)")
            if (BackgroundService.mode.value == BackgroundService.TRAFFIC) BackgroundService.stop(app)
            return id
        }
    }

    /** Saves the session's end time now and then, so a closed app loses nothing. */
    fun touchSession() {
        sessionId?.let { id -> app.io.execute { runCatching { app.db.endSession(id, System.currentTimeMillis()) } } }
    }

    override fun wants(timeMs: Double): Boolean = timeMs >= nextAt

    override fun onFrame(bitmap: Bitmap, timeMs: Double) {
        synchronized(lock) { analyse(bitmap, timeMs) }
    }

    private fun analyse(bitmap: Bitmap, t: Double) {
        val p = app.prefs
        val a = bitmap.height.toDouble() / bitmap.width
        if (counter == null || abs(a - aspect) > 0.01) {
            // Turned round while counting: what was counted but still in view is saved before starting afresh.
            val old = counter
            if (old != null && running) save(old.flush(), lastT)
            aspect = a
            lines = p.lines
            distance = p.distanceM
            roi = regionFor(lines, aspect)
            counter = Counter(lines, distance, aspect, roi = roi)
        }
        val c = counter!!
        // Auto: the standard model while the phone keeps up (about 15 frames a second), else the light one.
        val want = when (p.trafficModel) {
            "nano" -> Model.VEH_NANO
            "tiny" -> Model.VEH_TINY
            else -> model
        }
        if (want != model) {
            model = want
            recent.clear()
        }
        val net = app.engine.net(model)
        val f = frame?.also { it.bitmap = bitmap } ?: BitmapFrame(bitmap).also { frame = it }
        val res = detector.detect(f, net, roi, TRAFFIC_SENS[p.trafficSensitivity] ?: 0.3)
        val out = c.update(res.dets, t)
        val now = System.currentTimeMillis()
        if (running) {
            for (tr in out.counted) {
                flash[tr.id] = now
                app.haptic(12)
            }
            if (out.done.isNotEmpty()) save(out.done, t)
        }
        // Moving things keep the frame rate up; parked cars don't.
        if (out.tracks.any { t - it.lastT < 400 && hypot(it.vx, it.vy * aspect) > 0.00003 }) lastMove = t
        val idle = t - lastMove > 3000
        if (lastT > 0) {
            val inst = 1000 / maxOf(1.0, t - lastT)
            fps = if (fps == 0.0) inst else fps * 0.85 + inst * 0.15
        }
        lastT = t
        recent.add(res.msInfer)
        if (recent.size > 40) recent.removeAt(0)
        if (p.trafficModel == "auto" && model == Model.VEH_TINY && recent.size >= 30 && recent.sorted()[recent.size / 2] > 70) {
            model = Model.VEH_NANO
            recent.clear()
        }
        val cool = p.trafficPace == "cool"
        val gap = if (idle) (if (cool) 333.0 else 125.0) else (if (cool) 100.0 else 0.0)
        nextAt = t + gap - 5
        val shown = out.tracks.filter { t - it.lastT < 500 }.map { tr ->
            val kind = tr.kind ?: "car"
            val speed = if (tr.counted) c.speedOf(tr) else null
            val label = (Kinds[kind]?.one ?: kind) + (speed?.let { " ${it.toInt()} km/h" } ?: "")
            RoadBox(tr.box, label, tr.counted, (flash[tr.id]?.let { now - it < 600 }) == true)
        }
        if (flash.size > 50) flash.entries.removeIf { now - it.value > 2000 }
        ui.value = ui.value.copy(
            ready = true,
            status = "",
            aspect = aspect,
            roi = roi,
            boxes = shown,
            fps = fps,
            msAi = res.msInfer,
            msPrep = res.msPrep,
            model = if (model == Model.VEH_TINY) "Standard" else "Light",
            idle = idle,
            running = running,
            totals = liveTotals(c),
            last = last,
            counted = if (out.counted.isNotEmpty()) now else ui.value.counted,
            frames = ui.value.frames + 1,
        )
    }

    private fun save(records: List<io.github.ndev.roadsight.core.traffic.TrafficRecord>, frameT: Double) {
        if (records.isEmpty()) return
        // Frame times are the camera's clock; stored as the real date and time.
        val offset = System.currentTimeMillis() - frameT
        val events = records.map { Event(Math.round(it.t + offset), it.kind, it.dir, it.speed, it.length) }
        for (e in events) totals[e.kind]?.let { it[0]++; it[e.dir]++ }
        val r = records.last()
        last = LastCounted(r.kind, r.speed)
        val id = sessionId ?: return
        app.io.execute {
            runCatching { app.db.addEvents(id, events) }
            app.dataChanged()
        }
    }

    private fun copyTotals(): Map<String, IntArray> = totals.mapValues { it.value.copyOf() }

    /** Saved counts plus road users counted but still in view. */
    private fun liveTotals(c: Counter): Map<String, IntArray> {
        val t = copyTotals()
        if (running) for (tr in c.pending.values) {
            val k = tr.kind ?: continue
            t[k]?.let { it[0]++; it[tr.dir]++ }
        }
        return t
    }
}
