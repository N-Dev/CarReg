package io.github.ndev.roadsight.plates

import android.graphics.Bitmap
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.SENS
import io.github.ndev.roadsight.ai.BitmapFrame
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.camera.FrameSink
import io.github.ndev.roadsight.core.plate.Adaptive
import io.github.ndev.roadsight.core.plate.Board
import io.github.ndev.roadsight.core.plate.Idle
import io.github.ndev.roadsight.core.plate.PlateDet
import io.github.ndev.roadsight.core.plate.PlatePipeline
import io.github.ndev.roadsight.core.plate.PlateResult
import io.github.ndev.roadsight.core.plate.Track
import io.github.ndev.roadsight.core.plate.Tracker
import io.github.ndev.roadsight.core.plate.TrackerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

/** A plate box on the live picture (frame pixels), with its reading so far. */
class LiveBox(val box: DoubleArray, val text: String?, val confirmed: Boolean, val valid: Boolean)

/** A plate in the tray under the live picture. */
class TrayItem(val key: String, val result: PlateResult, val thumb: Bitmap?, val order: Long)

/** Everything the live plate screen shows. */
data class PlateUi(
    val ready: Boolean = false,
    val status: String = "Starting the AI…",
    val frameW: Int = 0,
    val frameH: Int = 0,
    val region: IntArray? = null,
    val boxes: List<LiveBox> = emptyList(),
    val tray: List<TrayItem> = emptyList(),
    val fps: Double = 0.0,
    val msFind: Double = 0.0,
    val msRead: Double = 0.0,
    val msTotal: Double = 0.0,
    val tier: String = "Fast",
    val idle: Boolean = false,
    val newPlate: Long = 0,
)

/**
 * Live plate reading: every camera frame goes through the plate finder and reader, plates are tracked
 * from frame to frame and voted on, and a plate is confirmed once the reads agree (PlateSight's scan.js).
 */
class PlateScanner(private val app: App) : FrameSink {
    val ui = MutableStateFlow(PlateUi())
    private val lock = Any()
    private var tracker = Tracker(TrackerOptions())
    private val board = Board(120)
    private val adaptive = Adaptive(budget = 70.0)
    private val idle = Idle()
    private var pipeline: PlatePipeline? = null
    private var frame: BitmapFrame? = null
    private var running = false
    private var lastT = 0.0
    private var fps = 0.0
    private var nextAt = 0.0
    private val order = HashMap<String, Long>()
    private var counter = 0L

    fun start() {
        synchronized(lock) {
            tracker = Tracker(TrackerOptions(format = app.prefs.format))
            adaptive.setMode(app.prefs.quality, 0.0)
            running = true
            lastT = 0.0
            fps = 0.0
            nextAt = 0.0
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            finish(tracker.flush())
            publishTray()
        }
    }

    fun removeFromTray(key: String) {
        synchronized(lock) {
            board.remove(key)
            publishTray()
        }
    }

    fun clearTray() {
        synchronized(lock) {
            board.clear()
            publishTray()
        }
    }

    override fun wants(timeMs: Double): Boolean = running && timeMs >= nextAt

    override fun onFrame(bitmap: Bitmap, timeMs: Double) {
        synchronized(lock) { analyse(bitmap, timeMs) }
    }

    private fun analyse(bitmap: Bitmap, timeMs: Double) {
        if (!running) return
        val t = timeMs
        if (adaptive.mode != app.prefs.quality) adaptive.setMode(app.prefs.quality, t)
        if (tracker.opts.format != app.prefs.format) tracker.setFormat(app.prefs.format)
        val tier = adaptive.tier
        // Plates still being worked out get the tier's (more accurate) reader for new plates; once
        // every plate in view is settled, the fast reader is enough to keep tracking them.
        val settled = tracker.tracks.isNotEmpty() && tracker.tracks.all { it.confirmed && (it.result?.conf ?: 0.0) >= 0.9 }
        val readerKey = if (settled) tier.read else tier.readNew
        val eng = app.engine
        if (!ui.value.ready) ui.value = ui.value.copy(status = "Starting the AI…")
        val finder = eng.net(Model.of(tier.finder))
        val reader = eng.net(Model.of(readerKey))
        val pipe = pipeline ?: PlatePipeline(eng.ocrConfig).also { pipeline = it }
        val f = frame?.also { it.bitmap = bitmap } ?: BitmapFrame(bitmap).also { frame = it }
        val conf = SENS[app.prefs.sensitivity] ?: 0.35
        val res = pipe.analyze(f, finder, tier.finderSize, reader, readerKey, conf, maxPlates = 6, minW = 40.0, portraitRoi = true)
        val dets = res.boxes.map { b ->
            val read = b.reads?.firstOrNull()?.copy(partial = b.edge, model = readerKey, weight = if (readerKey == "ocrAcc") 1.25 else 1.0)
            PlateDet(b.box, b.score, read) { thumbOf(bitmap, b.box) }
        }
        val u = tracker.update(dets, t)
        for (tr in u.confirmed) {
            app.haptic()
            order[tr.result!!.key] = ++counter
        }
        finish(u.lost)
        for (tr in tracker.tracks) if (tr.confirmed && tr.result != null) board.put(tr)
        adaptive.observe(res.msTotal, t)
        val isIdle = idle.observe(res.boxes.size, t)
        if (lastT > 0) {
            val inst = 1000 / maxOf(1.0, t - lastT)
            fps = if (fps == 0.0) inst else fps * 0.85 + inst * 0.15
        }
        lastT = t
        // Idle: nothing in view for a few seconds, so look 4 times a second instead of every frame.
        nextAt = if (isIdle) t + idle.idleFrameMs else 0.0
        val r = res.region
        ui.value = ui.value.copy(
            ready = true,
            status = "",
            frameW = res.width,
            frameH = res.height,
            region = intArrayOf(r.x, r.y, r.w, r.h),
            boxes = tracker.tracks.filter { t - it.last < 400 }.map { tr ->
                LiveBox(tr.box, tr.result?.text, tr.confirmed, tr.result?.valid == true)
            },
            tray = trayItems(),
            fps = fps,
            msFind = res.msFind,
            msRead = res.msRead,
            msTotal = res.msTotal,
            tier = tier.label,
            idle = isIdle,
            newPlate = if (u.confirmed.isNotEmpty()) System.currentTimeMillis() else ui.value.newPlate,
        )
    }

    private fun thumbOf(bitmap: Bitmap, box: DoubleArray): Bitmap? = runCatching {
        val r = PlatePipeline.thumbRect(box, bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, r[0], r[1], r[2], r[3])
        val out = Bitmap.createScaledBitmap(crop, r[4], r[5], true)
        if (out != crop) crop.recycle()
        out
    }.getOrNull()

    private fun trayItems(): List<TrayItem> = board.entries()
        .map { TrayItem(it.key, it.result, it.thumb as? Bitmap, order[it.key] ?: 0L) }
        .sortedByDescending { it.order }
        .take(24)

    private fun publishTray() {
        ui.value = ui.value.copy(tray = trayItems(), boxes = emptyList())
    }

    /** Plates whose tracks ended: kept in the tray and saved to history. */
    private fun finish(tracks: List<Track>) {
        for (t in tracks) {
            val r = t.result ?: continue
            if (!t.confirmed) continue
            val e = board.put(t)
            if (!app.prefs.history) continue
            val thumb = if (app.prefs.keepPhotos) (e?.thumb as? Bitmap)?.let { jpeg(it) } else null
            app.io.execute {
                runCatching { app.db.savePlate(r, "live", thumb) }
                app.dataChanged()
            }
        }
    }

    companion object {
        fun jpeg(b: Bitmap, quality: Int = 82): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}
