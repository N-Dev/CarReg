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
import io.github.ndev.roadsight.debug.DebugLog
import io.github.ndev.roadsight.service.BackgroundService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream

/** A plate box on the live picture (frame pixels), with its reading so far. */
class LiveBox(val box: DoubleArray, val text: String?, val confirmed: Boolean, val valid: Boolean, val id: Int = 0, val watched: Boolean = false, val reads: Int = 0)

/** A box straight from the plate finder, and what it read, for debug mode. */
class RawPlate(val box: DoubleArray, val score: Double, val text: String?, val conf: Double, val edge: Boolean)

/** A plate in the tray under the live picture. */
class TrayItem(val key: String, val result: PlateResult, val thumb: Bitmap?, val order: Long, val watched: Boolean = false)

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
    /** Debug mode: everything the finder found this frame, why the quality tier is what it is, frames analysed. */
    val raw: List<RawPlate> = emptyList(),
    val reason: String = "",
    val frames: Long = 0,
    val reader: String = "",
    /** Debug mode: the last few seconds of frame times (ms), for the graph. */
    val times: List<Float> = emptyList(),
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
    private val times = ArrayDeque<Float>()

    /** Plates confirmed since the app started (for the background notification). */
    @Volatile
    var confirmedCount = 0L
        private set

    /** Watching for plates with the screen off (the background service is running for it). */
    val watching: Boolean get() = BackgroundService.mode.value == BackgroundService.PLATES

    fun start() {
        synchronized(lock) {
            if (running) return
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

    /** Keeps reading plates with the screen off or another app open. Call while the app is on screen. */
    fun startWatching() {
        start()
        BackgroundService.start(app, BackgroundService.PLATES)
        DebugLog.add("plates", "Watching for plates in the background")
    }

    /** Stops background watching (scanning goes on while the Plates screen is open). */
    fun stopWatching() {
        if (BackgroundService.mode.value == BackgroundService.PLATES) BackgroundService.stop(app)
        DebugLog.add("plates", "Stopped watching in the background")
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

    override fun wants(timeMs: Double): Boolean = running && timeMs >= nextAt && !app.engine.testing

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
        val watch = app.watch
        for (tr in u.confirmed) {
            val r = tr.result!!
            // One of my cars: not kept, not shown, no buzz.
            if (watch.ignored(r.key)) {
                DebugLog.add("plates", "Ignored ${r.text} (one of your cars)")
                continue
            }
            app.haptic()
            order[r.key] = ++counter
            confirmedCount++
            DebugLog.add("plates", "Confirmed ${r.text} (${r.region ?: "?"}, ${Math.round(r.score * 100)}%)")
            watch.seen(r, tr.thumbFor(r.key)?.image as? Bitmap, source())
        }
        finish(u.lost)
        for (tr in tracker.tracks) {
            val r = tr.result ?: continue
            if (tr.confirmed && !watch.ignored(r.key)) board.put(tr)
        }
        adaptive.observe(res.msTotal, t)?.let { c -> DebugLog.add("plates", "Quality ${c.from.label} → ${c.to.label}: ${c.reason}") }
        val isIdle = idle.observe(res.boxes.size, t)
        if (lastT > 0) {
            val inst = 1000 / maxOf(1.0, t - lastT)
            fps = if (fps == 0.0) inst else fps * 0.85 + inst * 0.15
        }
        lastT = t
        val debug = app.prefs.debug
        if (debug) {
            times.addLast(res.msTotal.toFloat())
            while (times.size > 90) times.removeFirst()
        } else if (times.isNotEmpty()) {
            times.clear()
        }
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
                val key = tr.result?.key
                LiveBox(tr.box, tr.result?.text, tr.confirmed, tr.result?.valid == true, tr.id, key != null && watch.modeOf(key) == Watchlist.WATCH, tr.reads.size)
            },
            tray = trayItems(),
            fps = fps,
            msFind = res.msFind,
            msRead = res.msRead,
            msTotal = res.msTotal,
            tier = tier.label,
            idle = isIdle,
            newPlate = if (u.confirmed.isNotEmpty()) System.currentTimeMillis() else ui.value.newPlate,
            raw = if (debug) res.boxes.map { b -> RawPlate(b.box, b.score, b.reads?.firstOrNull()?.text, b.reads?.firstOrNull()?.conf ?: 0.0, b.edge) } else emptyList(),
            reason = adaptive.reason,
            frames = ui.value.frames + 1,
            reader = readerKey,
            times = if (debug) times.toList() else emptyList(),
        )
    }

    /** Where plates are being read from, for the watchlist notification. */
    private fun source(): String = if (watching && !app.camera.hasScreen) "background watching" else "live camera"

    private fun thumbOf(bitmap: Bitmap, box: DoubleArray): Bitmap? = runCatching {
        val r = PlatePipeline.thumbRect(box, bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, r[0], r[1], r[2], r[3])
        val out = Bitmap.createScaledBitmap(crop, r[4], r[5], true)
        if (out != crop) crop.recycle()
        out
    }.getOrNull()

    private fun trayItems(): List<TrayItem> = board.entries()
        .filter { !app.watch.ignored(it.key) }
        .map { TrayItem(it.key, it.result, it.thumb as? Bitmap, order[it.key] ?: 0L, app.watch.modeOf(it.key) == Watchlist.WATCH) }
        .sortedByDescending { it.order }
        .take(24)

    private fun publishTray() {
        ui.value = ui.value.copy(tray = trayItems(), boxes = emptyList())
    }

    /** Plates whose tracks ended: kept in the tray and saved to history. */
    private fun finish(tracks: List<Track>) {
        for (t in tracks) {
            val r = t.result ?: continue
            if (!t.confirmed || app.watch.ignored(r.key)) continue
            // The final reading can differ from the one first confirmed: it may be the watched plate.
            app.watch.seen(r, t.thumbFor(r.key)?.image as? Bitmap, source())
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
