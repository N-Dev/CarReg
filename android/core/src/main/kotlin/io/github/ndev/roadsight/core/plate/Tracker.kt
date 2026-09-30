package io.github.ndev.roadsight.core.plate

import io.github.ndev.roadsight.core.image.area
import io.github.ndev.roadsight.core.image.iou
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A plate found in one frame: its box in pixels, the finder's score, the reader's read, and (for the
 * photo shown with the plate) a function that makes a thumbnail of it, called only when needed.
 */
class PlateDet(
    val box: DoubleArray,
    val score: Double,
    val read: Read? = null,
    val thumb: (() -> Any?)? = null,
)

/** An image with the score it was kept for. */
class Scored(val image: Any, val score: Double)

class TrackerOptions(
    var iou: Double = 0.15,
    var maxAge: Double = 1200.0,
    var maxReads: Int = 24,
    var format: String = "auto",
    var minReads: Int = 2,
    var minAgree: Double = 0.6,
    var minScore: Double = 0.0,
)

/** One plate followed from frame to frame, with every read of it and the vote over them. */
class Track internal constructor(val id: Int, det: PlateDet, now: Double, opts: TrackerOptions) {
    var box: DoubleArray = det.box.copyOf()
        private set
    val vel = DoubleArray(4)
    val first = now
    var last = now
        private set
    var hits = 0
        private set
    val reads = ArrayList<Read>()
    var result: PlateResult? = null
        private set
    var confirmed = false
    var ended = false
    var endReason: String? = null
    var score = 0.0
        private set

    // Best photo per distinct reading, so the photo shown always matches the text shown.
    private val thumbs = LinkedHashMap<String, Scored>()

    init {
        observe(det, now, opts, initial = true)
    }

    fun predict(now: Double): DoubleArray {
        val dt = min(max(0.0, now - last), 400.0) / 1000
        return DoubleArray(4) { box[it] + vel[it] * dt }
    }

    /** False when the detection confidently reads as a different plate from the one this track has settled on. */
    fun accepts(det: PlateDet): Boolean {
        val r = result
        val read = det.read
        if (r == null || r.n < 2 || r.conf < 0.6) return true
        if (read == null || read.text.isEmpty() || read.partial || read.conf < 0.7 || read.minP < 0.4) return true
        val k = Formats.validate(read.text, r.profile).key
        return similar(k, r.key) || contains(k, r.key)
    }

    fun observe(det: PlateDet, now: Double, opts: TrackerOptions, initial: Boolean = false) {
        if (!initial) {
            val dt = max(16.0, now - last) / 1000
            for (k in 0..3) vel[k] = vel[k] * 0.5 + ((det.box[k] - box[k]) / dt) * 0.5
        }
        box = det.box.copyOf()
        last = now
        hits += 1
        score = det.score
        val read = det.read
        if (read != null && read.text.isNotEmpty()) {
            reads.add(read)
            if (reads.size > opts.maxReads) reads.removeAt(0)
            recompute(opts.format)
        }
        val key = if (read != null && read.text.isNotEmpty()) Formats.clean(read.text) else ""
        val readConf = read?.conf ?: 0.0
        val s = det.score * area(det.box) * (if (readConf > 0) readConf else 0.3)
        det.thumb?.let { keepBest(key, it, s) }
    }

    private fun keepBest(key: String, make: () -> Any?, score: Double) {
        val cur = thumbs[key]
        if (cur != null && score <= cur.score) return
        val img = make() ?: return
        thumbs[key] = Scored(img, score)
        if (thumbs.size > 6) {
            val worst = thumbs.entries.minByOrNull { it.value.score }!!
            thumbs.remove(worst.key)
        }
    }

    /** Best photo whose reading matches `key` (the plate shown), or null. */
    fun thumbFor(key: String): Scored? {
        var best: Scored? = null
        for ((k, v) in thumbs) if (k.isNotEmpty() && similar(k, key) && (best == null || v.score > best.score)) best = v
        return best
    }

    fun recompute(format: String) {
        result = vote(reads, format)
    }

    /** Reads of the whole plate (not cut off by the frame edge). */
    fun fullReads() = reads.count { !it.partial }

    fun confirmable(opts: TrackerOptions): Boolean {
        val r = result ?: return false
        if (r.n < opts.minReads || r.conf < opts.minAgree) return false
        if (fullReads() < opts.minReads) return false // never confirm a plate only ever seen cut off
        if (opts.minScore > 0 && r.conf * r.prob < opts.minScore) return false
        return r.valid || (r.n >= 4 && r.conf >= 0.8 && r.prob >= 0.85)
    }
}

/** Multi-frame tracking and voting: turns noisy per-frame reads into one stable plate per vehicle. */
class Tracker(val opts: TrackerOptions = TrackerOptions()) {
    val tracks = ArrayList<Track>()
    private var nextId = 1

    class Update(val confirmed: List<Track>, val lost: List<Track>)

    fun setFormat(format: String) {
        opts.format = format
        for (t in tracks) t.recompute(format)
    }

    /** dets: this frame's plates; now: ms. Returns newly confirmed and ended tracks. */
    fun update(dets: List<PlateDet>, now: Double): Update {
        val preds = tracks.map { it.predict(now) }
        val pairs = ArrayList<Triple<Double, Int, Int>>()
        val displaced = HashSet<Int>()
        for ((i, d) in dets.withIndex()) {
            for ((j, p) in preds.withIndex()) {
                val o = iou(d.box, p)
                val cx = (d.box[0] + d.box[2]) / 2 - (p[0] + p[2]) / 2
                val cy = (d.box[1] + d.box[3]) / 2 - (p[1] + p[3]) / 2
                val size = max(max(p[2] - p[0], p[3] - p[1]), 1.0)
                val dist = hypot(cx, cy) / size
                val s = if (o >= opts.iou) o else if (dist < 0.8) (0.8 - dist) * 0.1 else 0.0
                if (s <= 0) continue
                if (tracks[j].accepts(d)) pairs.add(Triple(s, i, j))
                // A different plate now sits where this track's plate was: the camera has moved on to another car.
                else if (o >= 0.3 || dist < 0.5) displaced.add(j)
            }
        }
        pairs.sortByDescending { it.first }
        val usedD = HashSet<Int>()
        val usedT = HashSet<Int>()
        for ((_, i, j) in pairs) {
            if (i in usedD || j in usedT) continue
            usedD.add(i)
            usedT.add(j)
            tracks[j].observe(dets[i], now, opts)
        }
        for (j in displaced) {
            if (j !in usedT) {
                tracks[j].ended = true
                tracks[j].endReason = "displaced"
            }
        }
        for ((i, d) in dets.withIndex()) if (i !in usedD) tracks.add(Track(nextId++, d, now, opts))

        val confirmed = ArrayList<Track>()
        val lost = ArrayList<Track>()
        val keep = ArrayList<Track>()
        for (t in tracks) {
            if (!t.confirmed && t.confirmable(opts)) {
                t.confirmed = true
                confirmed.add(t)
            }
            if (!t.ended && now - t.last > opts.maxAge) {
                t.ended = true
                t.endReason = "timeout"
            }
            (if (t.ended) lost else keep).add(t)
        }
        tracks.clear()
        tracks.addAll(keep)
        return Update(confirmed, lost)
    }

    /** Ends all tracks (when scanning stops) and returns them. */
    fun flush(): List<Track> {
        val all = ArrayList(tracks)
        tracks.clear()
        for (t in all) {
            if (!t.confirmed && t.confirmable(opts)) t.confirmed = true
            t.ended = true
            t.endReason = "flush"
        }
        return all
    }
}

/** A plate confirmed during one live scan, for the tray. */
class BoardEntry(val key: String, var result: PlateResult, var resultTrack: Int, var first: Double, var last: Double) {
    val tracks = HashSet<Int>()
    var thumb: Any? = null
    var thumbScore = 0.0
    var brief = true
}

/**
 * Plates found during one live scan, keyed by plate. Follows each track, so if a track's reading
 * improves its entry moves with it, and keeps a photo that matches the text.
 */
class Board(private val max: Int = Int.MAX_VALUE) {
    private val map = LinkedHashMap<String, BoardEntry>()
    private val byTrack = HashMap<Int, String>()

    fun put(t: Track): BoardEntry? {
        val r = t.result ?: return null
        val prev = byTrack[t.id]
        if (prev != null && prev != r.key) {
            map[prev]?.let { pe ->
                pe.tracks.remove(t.id)
                if (pe.tracks.isEmpty()) map.remove(prev)
            }
        }
        var e = map[r.key]
        if (e == null) {
            e = BoardEntry(r.key, r, t.id, t.first, t.last)
            map[r.key] = e
            // Plates are saved to history when their track ends, so dropping old ones here loses nothing.
            if (map.size > max) remove(map.keys.first())
        }
        e.tracks.add(t.id)
        byTrack[t.id] = r.key
        if (r.score >= e.result.score || e.result.n < r.n) {
            e.result = r
            e.resultTrack = t.id
        }
        e.first = min(e.first, t.first)
        e.last = max(e.last, t.last)
        e.brief = e.brief && !t.confirmed
        val th = t.thumbFor(r.key)
        if (th != null && th.score > e.thumbScore) {
            e.thumb = th.image
            e.thumbScore = th.score
        }
        return e
    }

    operator fun get(key: String): BoardEntry? = map[key]
    fun entries(): List<BoardEntry> = map.values.toList()
    val size: Int get() = map.size

    fun remove(key: String): Boolean {
        val e = map.remove(key) ?: return false
        for (id in e.tracks) byTrack.remove(id)
        return true
    }

    fun clear() {
        map.clear()
        byTrack.clear()
    }
}
