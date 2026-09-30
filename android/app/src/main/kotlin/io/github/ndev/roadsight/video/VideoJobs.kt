package io.github.ndev.roadsight.video

import android.graphics.Bitmap
import android.net.Uri
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.SENS
import io.github.ndev.roadsight.TRAFFIC_SENS
import io.github.ndev.roadsight.ai.BitmapFrame
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.core.plate.PlateDet
import io.github.ndev.roadsight.core.plate.PlatePipeline
import io.github.ndev.roadsight.core.plate.PlateResult
import io.github.ndev.roadsight.core.plate.Track
import io.github.ndev.roadsight.core.plate.Tracker
import io.github.ndev.roadsight.core.plate.TrackerOptions
import io.github.ndev.roadsight.core.traffic.Counter
import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Kinds
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.core.traffic.TrafficRecord
import io.github.ndev.roadsight.core.traffic.VehicleDetector
import io.github.ndev.roadsight.core.traffic.regionFor
import io.github.ndev.roadsight.debug.DebugLog
import io.github.ndev.roadsight.plates.PlateScanner
import io.github.ndev.roadsight.plates.Watchlist
import io.github.ndev.roadsight.traffic.RoadBox
import kotlinx.coroutines.flow.MutableStateFlow

/** How far through a video the AI is, and what it has seen so far. */
class VideoProgress(
    /** Video time analysed (ms) and the video's length. */
    val t: Double,
    val duration: Double,
    val frames: Int,
    /** How many times faster than real time. */
    val speed: Double,
    /** A small copy of the frame, and what's on it (fractions of the frame). */
    val preview: Bitmap?,
    val boxes: List<RoadBox> = emptyList(),
    /** Traffic: kind -> [total, direction 1, direction 2]. */
    val totals: Map<String, IntArray> = emptyMap(),
    /** Plates found so far. */
    val plates: List<VideoPlate> = emptyList(),
)

/** A small copy of a frame for the screen (the decoder reuses its bitmap). */
private fun previewOf(b: Bitmap, width: Int = 640): Bitmap {
    val w = minOf(width, b.width)
    return Bitmap.createScaledBitmap(b, w, maxOf(1, Math.round(b.height * w.toDouble() / b.width).toInt()), true)
}

/** The lines and details for counting a video. `start` is when the video was recorded (epoch ms). */
class VideoSetup(
    val lines: Lines,
    val distanceM: Double,
    val limit: Int,
    val dir1: String,
    val dir2: String,
    val site: String,
    val start: Long,
)

/**
 * Counts the traffic in a video with the same AI and counting as the live camera: road users crossing
 * the lines, their directions and speeds, about 15 frames a second of the video. Saved as a counting
 * session (marked as from a video) when it gets to the end.
 */
class VideoCountJob(private val app: App, private val uri: Uri, private val info: VideoInfo, private val setup: VideoSetup) {
    @Volatile
    var cancelled = false
    val progress = MutableStateFlow<VideoProgress?>(null)

    /** Counts the whole video and saves it. Returns the session's id, or null if stopped. Run off the main thread. */
    fun run(): Long? {
        val aspect = info.aspect
        val roi = regionFor(setup.lines, aspect)
        val counter = Counter(setup.lines, setup.distanceM, aspect, roi = roi)
        val detector = VehicleDetector()
        val model = if (app.prefs.trafficModel == "nano") Model.VEH_NANO else Model.VEH_TINY
        val conf = TRAFFIC_SENS[app.prefs.trafficSensitivity] ?: 0.3
        val events = ArrayList<Event>()
        val totals = Kinds.ORDER.associateWith { IntArray(3) }
        var frame: BitmapFrame? = null
        val wall0 = System.nanoTime()
        var n = 0
        var shownAt = 0L
        DebugLog.add("video", "Counting a video: ${info.width}x${info.height}, ${info.durationMs / 1000} s, ${model.key}")

        fun keep(records: List<TrafficRecord>) {
            for (r in records) {
                events.add(Event(Math.round(setup.start + r.t), r.kind, r.dir, r.speed, r.length))
                totals[r.kind]?.let { it[0]++; it[r.dir]++ }
            }
        }

        VideoDecoder(app, uri).frames(maxSide = 1280, stepMs = 1000.0 / 15) { bmp, t ->
            if (cancelled) return@frames false
            val f = frame?.also { it.bitmap = bmp } ?: BitmapFrame(bmp).also { frame = it }
            val res = detector.detect(f, app.engine.net(model), roi, conf)
            val out = counter.update(res.dets, t)
            keep(out.done)
            n++
            val now = System.nanoTime()
            if (now - shownAt > 300_000_000L) {
                shownAt = now
                val live = totals.mapValues { it.value.copyOf() }
                for (tr in counter.pending.values) tr.kind?.let { k -> live[k]?.let { it[0]++; it[tr.dir]++ } }
                val boxes = out.tracks.filter { t - it.lastT < 300 }.map { tr ->
                    val speed = if (tr.counted) counter.speedOf(tr) else null
                    RoadBox(tr.box, (Kinds[tr.kind ?: "car"]?.one ?: "car") + (speed?.let { " ${it.toInt()} km/h" } ?: ""), tr.counted, false, tr.id, tr.vx, tr.vy, tr.hits)
                }
                progress.value = VideoProgress(t, info.durationMs.toDouble(), n, t / ((now - wall0) / 1e6).coerceAtLeast(1.0), previewOf(bmp), boxes, live)
            }
            true
        }
        if (cancelled) {
            DebugLog.add("video", "Video counting stopped after $n frames")
            return null
        }
        keep(counter.flush())
        val end = setup.start + info.durationMs
        val id = app.db.newSession(setup.site, setup.start, setup.distanceM, setup.limit, setup.dir1, setup.dir2, setup.lines, model.key, source = "video")
        app.db.addEvents(id, events)
        app.db.endSession(id, end)
        app.dataChanged()
        val secs = (System.nanoTime() - wall0) / 1e9
        DebugLog.add("video", "Video counted: ${events.size} road users in $n frames, ${"%.0f".format(secs)} s (session $id)")
        progress.value = VideoProgress(info.durationMs.toDouble(), info.durationMs.toDouble(), n, info.durationMs / 1000.0 / secs.coerceAtLeast(0.001), progress.value?.preview, emptyList(), totals)
        return id
    }
}

/** A plate found in a video: its reading, a photo of it, and when in the video it was first seen. */
class VideoPlate(val result: PlateResult, val thumb: Bitmap?, val atMs: Double, val sightings: Int, val watched: Boolean)

/**
 * Reads the plates in a video, like live scanning but with the sharper models (there's no hurry):
 * about 10 frames a second, plates tracked and voted on across frames. Found plates are saved to history
 * (from a "video"), watched ones announced, and your own cars left out.
 */
class VideoPlatesJob(private val app: App, private val uri: Uri, private val info: VideoInfo) {
    @Volatile
    var cancelled = false
    val progress = MutableStateFlow<VideoProgress?>(null)

    /** Plates your own cars (the ignore list): seen but not kept. */
    @Volatile
    var ignored = 0
        private set

    fun run(): List<VideoPlate>? {
        val tracker = Tracker(TrackerOptions(format = app.prefs.format))
        val pipe = PlatePipeline(app.engine.ocrConfig)
        val conf = SENS[app.prefs.sensitivity] ?: 0.35
        val found = LinkedHashMap<String, VideoPlate>()
        val ignoredKeys = HashSet<String>()
        var frame: BitmapFrame? = null
        val wall0 = System.nanoTime()
        var n = 0
        var shownAt = 0L
        DebugLog.add("video", "Reading plates in a video: ${info.width}x${info.height}, ${info.durationMs / 1000} s")

        fun finish(tracks: List<Track>) {
            for (tr in tracks) {
                val r = tr.result ?: continue
                if (!tr.confirmed) continue
                if (app.watch.ignored(r.key)) {
                    ignoredKeys.add(r.key)
                    continue
                }
                val thumb = (tr.thumbFor(r.key)?.image as? Bitmap)
                val watched = app.watch.modeOf(r.key) == Watchlist.WATCH
                if (watched) app.watch.seen(r, thumb, "video")
                val old = found[r.key]
                found[r.key] = if (old == null) {
                    VideoPlate(r, thumb, tr.first, 1, watched)
                } else {
                    // Seen again: keep the first time, and the surer reading and its photo.
                    val better = r.score > old.result.score
                    VideoPlate(if (better) r else old.result, if (better && thumb != null) thumb else old.thumb, old.atMs, old.sightings + 1, watched)
                }
            }
        }

        VideoDecoder(app, uri).frames(maxSide = 1920, stepMs = 100.0) { bmp, t ->
            if (cancelled) return@frames false
            val f = frame?.also { it.bitmap = bmp } ?: BitmapFrame(bmp).also { frame = it }
            val finder = app.engine.net(Model.DET640)
            val reader = app.engine.net(Model.OCR_ACC)
            val res = pipe.analyze(f, finder, 640, reader, Model.OCR_ACC.key, conf, maxPlates = 8, minW = 24.0)
            val dets = res.boxes.map { b ->
                val read = b.reads?.firstOrNull()?.copy(partial = b.edge, model = Model.OCR_ACC.key, weight = 1.25)
                PlateDet(b.box, b.score, read) { crop(bmp, b.box) }
            }
            val u = tracker.update(dets, t)
            finish(u.lost)
            n++
            val now = System.nanoTime()
            if (now - shownAt > 300_000_000L) {
                shownAt = now
                val sx = 1.0 / bmp.width
                val sy = 1.0 / bmp.height
                val boxes = tracker.tracks.filter { t - it.last < 300 }.map { tr ->
                    val b = tr.box
                    RoadBox(doubleArrayOf(b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy), tr.result?.text ?: "", tr.confirmed, false, tr.id)
                }
                progress.value = VideoProgress(t, info.durationMs.toDouble(), n, t / ((now - wall0) / 1e6).coerceAtLeast(1.0), previewOf(bmp), boxes, plates = found.values.sortedBy { it.atMs })
            }
            true
        }
        if (cancelled) return null
        finish(tracker.flush())
        ignored = ignoredKeys.size
        val list = found.values.sortedBy { it.atMs }
        if (app.prefs.history) {
            val keep = app.prefs.keepPhotos
            val saves = list.map { it.result to (if (keep) it.thumb?.let { t -> PlateScanner.jpeg(t) } else null) }
            app.io.execute {
                for ((r, jpg) in saves) runCatching { app.db.savePlate(r, "video", jpg) }
                app.dataChanged()
            }
        }
        val secs = (System.nanoTime() - wall0) / 1e9
        DebugLog.add("video", "Video read: ${list.size} plate(s) in $n frames, ${"%.0f".format(secs)} s: ${list.joinToString { it.result.text }}")
        progress.value = VideoProgress(info.durationMs.toDouble(), info.durationMs.toDouble(), n, info.durationMs / 1000.0 / secs.coerceAtLeast(0.001), progress.value?.preview, emptyList(), plates = list)
        return list
    }

    private fun crop(b: Bitmap, box: DoubleArray): Bitmap? = runCatching {
        val r = PlatePipeline.thumbRect(box, b.width, b.height)
        val c = Bitmap.createBitmap(b, r[0], r[1], r[2], r[3])
        val out = Bitmap.createScaledBitmap(c, r[4], r[5], true)
        if (out != c) c.recycle()
        out
    }.getOrNull()
}
