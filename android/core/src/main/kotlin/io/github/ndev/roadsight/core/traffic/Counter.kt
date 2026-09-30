package io.github.ndev.roadsight.core.traffic

import io.github.ndev.roadsight.core.image.area
import io.github.ndev.roadsight.core.image.iou
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What gets counted. `group`: detections only continue a track of the same group; `motor`: in the speed figures. */
class Kind(val key: String, val label: String, val one: String, val group: String, val motor: Boolean)

object Kinds {
    val ALL = listOf(
        Kind("car", "Cars", "car", "vehicle", true),
        Kind("truck", "Vans & trucks", "van or truck", "vehicle", true),
        Kind("bus", "Buses", "bus", "vehicle", true),
        Kind("motorbike", "Motorbikes", "motorbike", "two", true),
        Kind("bicycle", "Bicycles", "bicycle", "two", false),
        Kind("person", "People", "person", "person", false),
        Kind("dog", "Dogs", "dog", "animal", false),
        Kind("horse", "Horses", "horse", "animal", false),
    )
    private val BY_KEY = ALL.associateBy { it.key }
    val ORDER: List<String> = ALL.map { it.key }
    operator fun get(key: String): Kind? = BY_KEY[key]
    fun isMotor(key: String) = BY_KEY[key]?.motor == true
}

/** "Long vehicles" in the report: most lorries, coaches and buses. */
const val LONG_VEHICLE_M = 7.5

/** A road user found in one frame: kind, score, and box as fractions of the frame (x1, y1, x2, y2). */
class Det(val cls: String, val score: Double, val box: DoubleArray)

/** Two counting lines, each x1, y1, x2, y2 as fractions of the frame. */
class Lines(val a: DoubleArray, val b: DoubleArray) {
    fun copy() = Lines(a.copyOf(), b.copyOf())

    companion object {
        /** Two upright lines across the middle of the picture. */
        fun default() = Lines(doubleArrayOf(0.35, 0.3, 0.35, 0.95), doubleArrayOf(0.65, 0.3, 0.65, 0.95))
    }
}

/** A counted road user: when (ms), what, which way (1 = A→B, 2 = B→A), speed (km/h) and length (m) if measured. */
data class TrafficRecord(
    val t: Double,
    val kind: String,
    val dir: Int,
    val speed: Double?,
    val length: Double?,
    val conf: Double,
    val frames: Int,
)

private fun centre(b: DoubleArray) = doubleArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2)

/** Where a road user touches the ground: the bottom middle of its box. Lines are drawn on the road. */
fun foot(b: DoubleArray) = doubleArrayOf((b[0] + b[2]) / 2, b[3])

private fun inter(a: DoubleArray, b: DoubleArray): Double {
    val w = min(a[2], b[2]) - max(a[0], b[0])
    val h = min(a[3], b[3]) - max(a[1], b[1])
    return if (w > 0 && h > 0) w * h else 0.0
}

/** A person on a bicycle, motorbike or horse is its rider: dropped, so a cyclist isn't also counted as a pedestrian. */
fun dropRiders(dets: List<Det>): List<Det> {
    val mounts = dets.filter { it.cls == "bicycle" || it.cls == "motorbike" || it.cls == "horse" }
    if (mounts.isEmpty()) return dets
    return dets.filter { d ->
        if (d.cls != "person") return@filter true
        val cx = (d.box[0] + d.box[2]) / 2
        !mounts.any { m -> cx > m.box[0] && cx < m.box[2] && inter(d.box, m.box) > 0.25 * area(d.box) }
    }
}

class Obs(val t: Double, val box: DoubleArray)
class Crossed(val t: Double, val dir: Int, val box: DoubleArray)

/** One road user followed from frame to frame. */
class Road internal constructor(val id: Int, val group: String, t: Double, box: DoubleArray) {
    val votes = LinkedHashMap<String, Double>()
    var hits = 0
    val firstT = t
    var lastT = t
    var box: DoubleArray = box
    val obs = ArrayList<Obs>()
    var prev: Obs? = null
    var vx = 0.0
    var vy = 0.0
    val first: DoubleArray = foot(box)
    val cross = HashMap<String, Crossed>()
    var counted = false
    var dir = 0
    var countT = 0.0

    /** The kind it was seen as most (weighted by the detector's confidence). */
    val kind: String?
        get() {
            var best: String? = null
            for ((k, v) in votes) if (best == null || v > votes[best]!!) best = k
            return best
        }
}

class RoadTracker(var aspect: Double = 9.0 / 16, private val maxAge: Double = 1500.0, private val iouMin: Double = 0.08) {
    val tracks = ArrayList<Road>()
    private var nextId = 1

    private fun predict(tr: Road, t: Double): DoubleArray {
        val dt = t - tr.lastT
        return doubleArrayOf(tr.box[0] + tr.vx * dt, tr.box[1] + tr.vy * dt, tr.box[2] + tr.vx * dt, tr.box[3] + tr.vy * dt)
    }

    class Update(val seen: List<Road>, val lost: List<Road>)

    fun update(dets: List<Det>, t: Double): Update {
        val pairs = ArrayList<Triple<Double, Int, Int>>()
        val preds = tracks.map { predict(it, t) }
        for ((i, tr) in tracks.withIndex()) {
            val p = preds[i]
            val pc = centre(p)
            val size = max(p[2] - p[0], (p[3] - p[1]) * aspect)
            // With one sighting there's no speed to predict from yet, so look further: a fast car at a low
            // frame rate can move more than its own length between frames.
            val reach = (if (tr.hits < 2) 2.2 else 0.8) * size
            for ((j, d) in dets.withIndex()) {
                if (Kinds[d.cls]?.group != tr.group) continue
                var s = iou(p, d.box)
                if (s < iouMin) {
                    // Fast or briefly hidden: accept a detection near where the track should be, of similar size.
                    val dc = centre(d.box)
                    val dist = hypot(dc[0] - pc[0], (dc[1] - pc[1]) * aspect)
                    val ratio = area(d.box) / max(1e-9, area(p))
                    if (dist > reach || ratio < 0.35 || ratio > 3) continue
                    s = 0.001 * (1 - dist / reach)
                }
                pairs.add(Triple(s, i, j))
            }
        }
        pairs.sortByDescending { it.first }
        val usedT = HashSet<Int>()
        val usedD = HashSet<Int>()
        val seen = ArrayList<Road>()
        for ((_, i, j) in pairs) {
            if (i in usedT || j in usedD) continue
            usedT.add(i)
            usedD.add(j)
            observe(tracks[i], dets[j], t)
            seen.add(tracks[i])
        }
        val existing = tracks.size
        for ((j, d) in dets.withIndex()) {
            if (j in usedD) continue
            val tr = Road(nextId++, Kinds[d.cls]!!.group, t, d.box)
            observe(tr, d, t)
            tracks.add(tr)
            seen.add(tr)
        }
        val lost = ArrayList<Road>()
        val keep = ArrayList<Road>()
        for ((i, tr) in tracks.withIndex()) {
            if (i >= existing || i in usedT || tr.lastT == t) {
                keep.add(tr)
                continue
            }
            val p = preds[i]
            val gone = t - tr.lastT > maxAge || p[2] < -0.02 || p[0] > 1.02 || p[3] < -0.02 || p[1] > 1.02
            if (gone) lost.add(tr) else keep.add(tr)
        }
        tracks.clear()
        tracks.addAll(keep)
        return Update(seen, lost)
    }

    private fun observe(tr: Road, d: Det, t: Double) {
        tr.prev = tr.obs.lastOrNull()
        tr.obs.add(Obs(t, d.box))
        if (tr.obs.size > 60) tr.obs.removeAt(0)
        tr.box = d.box
        tr.lastT = t
        tr.hits++
        tr.votes[d.cls] = (tr.votes[d.cls] ?: 0.0) + (if (d.score != 0.0) d.score else 0.5)
        // Speed of the box over roughly the last half second (steadier than frame to frame).
        var k = tr.obs.size - 1
        while (k > 0 && t - tr.obs[k - 1].t <= 600) k--
        val o = tr.obs[k]
        if (o.t < t) {
            val c0 = centre(o.box)
            val c1 = centre(d.box)
            tr.vx = (c1[0] - c0[0]) / (t - o.t)
            tr.vy = (c1[1] - c0[1]) / (t - o.t)
        }
    }
}

private fun cross2(ax: Double, ay: Double, bx: Double, by: Double) = ax * by - ay * bx

class Crossing(val t: Double, val side: Int)

/**
 * Where the step from p0 (time t0) to p1 (time t1) crosses the line segment, or null. `side` is +1 if
 * it ends up on the line's left, -1 otherwise; `t` is the crossing time, interpolated between the frames.
 */
fun crossing(line: DoubleArray, p0: DoubleArray, t0: Double, p1: DoubleArray, t1: Double, aspect: Double = 1.0): Crossing? {
    val x1 = line[0]
    val y1 = line[1]
    val dx = line[2] - x1
    val dy = (line[3] - y1) * aspect
    val s0 = cross2(dx, dy, p0[0] - x1, (p0[1] - y1) * aspect)
    val s1 = cross2(dx, dy, p1[0] - x1, (p1[1] - y1) * aspect)
    if ((s0 < 0) == (s1 < 0)) return null
    val f = s0 / (s0 - s1)
    val qx = p0[0] + f * (p1[0] - p0[0])
    val qy = (p0[1] + f * (p1[1] - p0[1]) - y1) * aspect
    val den = dx * dx + dy * dy
    val u = ((qx - x1) * dx + qy * dy) / (if (den == 0.0) 1.0 else den)
    if (u < -0.15 || u > 1.15) return null // passed beyond the end of the line
    return Crossing(t0 + f * (t1 - t0), if (s1 > 0) 1 else -1)
}

private fun sideOf(line: DoubleArray, p: DoubleArray, aspect: Double): Int =
    if (cross2(line[2] - line[0], (line[3] - line[1]) * aspect, p[0] - line[0], (p[1] - line[1]) * aspect) > 0) 1 else -1

private fun mid(l: DoubleArray) = doubleArrayOf((l[0] + l[2]) / 2, (l[1] + l[3]) / 2)

/**
 * Distance between the two lines along a direction of travel through point p (in frame widths, heights
 * scaled by `aspect`), or null. Measured at the road user's own position, so it allows for perspective.
 */
fun gapAlong(a: DoubleArray, b: DoubleArray, p: DoubleArray, dir: DoubleArray, aspect: Double = 1.0): Double? {
    val len = hypot(dir[0], dir[1] * aspect)
    if (len == 0.0) return null
    val ux = dir[0] / len
    val uy = (dir[1] * aspect) / len
    fun hit(l: DoubleArray): Double? {
        val lx = l[2] - l[0]
        val ly = (l[3] - l[1]) * aspect
        val den = cross2(ux, uy, lx, ly)
        if (abs(den) < 1e-9) return null
        return cross2(l[0] - p[0], (l[1] - p[1]) * aspect, lx, ly) / den
    }
    val sa = hit(a) ?: return null
    val sb = hit(b) ?: return null
    return abs(sa - sb)
}

/**
 * Counts road users crossing two lines drawn across the road, `distanceM` metres apart. Direction 1 is
 * from line A towards line B; direction 2 the other way. Each road user is counted once, when it first
 * crosses either line; crossing the second line as well gives its speed and length.
 */
class Counter(
    var lines: Lines,
    var distanceM: Double = 20.0,
    aspect: Double = 9.0 / 16,
    maxAgeMs: Double = 1500.0,
    /** The analysed part of the frame (x, y, w, h fractions); boxes touching its edges are cut off. */
    var roi: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0, 1.0),
) {
    val tracker = RoadTracker(aspect, maxAgeMs)
    var aspect: Double = aspect
        set(v) {
            field = v
            tracker.aspect = v
        }

    /** Counted road users still in view. */
    val pending = LinkedHashMap<Int, Road>()

    class Update(val counted: List<Road>, val done: List<TrafficRecord>, val tracks: List<Road>)

    fun update(dets: List<Det>, t: Double): Update {
        val u = tracker.update(dropRiders(dets.filter { Kinds[it.cls] != null }), t)
        val counted = ArrayList<Road>()
        for (tr in u.seen) if (tr.prev != null && check(tr)) counted.add(tr)
        val done = ArrayList<TrafficRecord>()
        for (tr in u.lost) {
            if (!tr.counted) continue
            pending.remove(tr.id)
            done.add(record(tr))
        }
        return Update(counted, done, tracker.tracks)
    }

    /** Ends every track (at the end of a session) and returns the records of those counted. */
    fun flush(): List<TrafficRecord> {
        val done = tracker.tracks.filter { it.counted }.map { record(it) }
        tracker.tracks.clear()
        pending.clear()
        return done
    }

    private fun check(tr: Road): Boolean {
        val prev = tr.prev ?: return false
        val p0 = foot(prev.box)
        val p1 = foot(tr.box)
        var newlyCounted = false
        for ((name, line, other) in listOf(Triple("a", lines.a, lines.b), Triple("b", lines.b, lines.a))) {
            if (tr.cross.containsKey(name)) continue // only the first crossing of each line counts
            val c = crossing(line, p0, prev.t, p1, tr.lastT, aspect) ?: continue
            // Heading towards the other line: A->B is direction 1 when crossing A, direction 2 when crossing B.
            val towardsOther = c.side == sideOf(line, mid(other), aspect)
            val dir = if (name == "a") (if (towardsOther) 1 else 2) else (if (towardsOther) 2 else 1)
            tr.cross[name] = Crossed(c.t, dir, tr.box)
            if (!tr.counted && moved(tr)) {
                tr.counted = true
                tr.dir = dir
                tr.countT = c.t
                pending[tr.id] = tr
                newlyCounted = true
            }
        }
        return newlyCounted
    }

    /** Really moving, not a parked car or someone standing on the line whose box jitters across it. */
    private fun moved(tr: Road): Boolean {
        val p = foot(tr.box)
        val dist = hypot(p[0] - tr.first[0], (p[1] - tr.first[1]) * aspect)
        return tr.hits >= 2 && dist >= max(0.015, 0.35 * (tr.box[2] - tr.box[0]))
    }

    /** Speed (km/h) between the lines, if the track crossed both in its direction of travel. */
    fun speedOf(tr: Road): Double? {
        val a = tr.cross["a"] ?: return null
        val b = tr.cross["b"] ?: return null
        if (a.dir != tr.dir || b.dir != tr.dir) return null
        val dt = if (tr.dir == 1) b.t - a.t else a.t - b.t
        if (dt < 80) return null
        val kmh = (distanceM / (dt / 1000)) * 3.6
        return if (kmh in 1.0..200.0) kmh else null
    }

    /** Length (m) along the direction of travel, from the box sizes between the lines, if measurable. */
    fun lengthOf(tr: Road): Double? {
        val a = tr.cross["a"] ?: return null
        val b = tr.cross["b"] ?: return null
        val t0 = min(a.t, b.t) - 150
        val t1 = max(a.t, b.t) + 150
        val obs = tr.obs.filter { it.t in t0..t1 }
        if (obs.size < 2) return null
        val f0 = foot(obs.first().box)
        val f1 = foot(obs.last().box)
        val dir = doubleArrayOf(f1[0] - f0[0], f1[1] - f0[1])
        val len = hypot(dir[0], dir[1] * aspect)
        if (len == 0.0) return null
        val ux = dir[0] / len
        val uy = (dir[1] * aspect) / len
        val sizes = ArrayList<Double>()
        for (o in obs) {
            val (x1, y1, x2, y2) = o.box.toList()
            if (x1 <= roi[0] + 0.005 || x2 >= roi[0] + roi[2] - 0.005) continue // cut off by the edge
            val extent = abs((x2 - x1) * ux) + abs((y2 - y1) * aspect * uy)
            val gap = gapAlong(lines.a, lines.b, foot(o.box), dir, aspect)
            if (gap != null && gap > 0) sizes.add((extent * distanceM) / gap)
        }
        if (sizes.isEmpty()) return null
        sizes.sort()
        val m = sizes[sizes.size / 2]
        return if (m in 0.3..25.0) m else null
    }

    /** What's kept for a counted road user: when, what, which way, speed and length. No images. */
    fun record(tr: Road): TrafficRecord {
        val speed = speedOf(tr)
        val length = if (speed != null) lengthOf(tr) else null
        val kind = tr.kind ?: "car"
        val conf = (tr.votes[kind] ?: 0.0) / max(1, tr.hits)
        return TrafficRecord(
            t = tr.countT,
            kind = kind,
            dir = tr.dir,
            speed = speed?.let { Math.round(it * 10) / 10.0 },
            length = length?.let { Math.round(it * 10) / 10.0 },
            conf = Math.round(conf * 100) / 100.0,
            frames = tr.hits,
        )
    }
}

/**
 * The part of the frame worth analysing: around both lines, with room either side for road users to be
 * picked up before they reach a line. Returned as fractions of the frame: x, y, w, h.
 */
fun regionFor(lines: Lines, aspect: Double = 9.0 / 16): DoubleArray {
    val xs = doubleArrayOf(lines.a[0], lines.a[2], lines.b[0], lines.b[2])
    val ys = doubleArrayOf(lines.a[1], lines.a[3], lines.b[1], lines.b[3])
    val gap = max(0.12, xs.max() - xs.min())
    var x0 = max(0.0, xs.min() - 0.6 * gap)
    var x1 = min(1.0, xs.max() + 0.6 * gap)
    var y0 = max(0.0, ys.min() - 0.15)
    var y1 = min(1.0, ys.max() + 0.08)
    // Never narrower than half the frame: a road user needs a few frames in view before the first line.
    if (x1 - x0 < 0.5) {
        val c = (x0 + x1) / 2
        x0 = max(0.0, min(0.5, c - 0.25))
        x1 = x0 + 0.5
    }
    // Not a thin strip either (in pixels, at least a third as tall as it is wide), so tall vehicles fit.
    val minH = min(1.0, (0.35 * (x1 - x0)) / aspect)
    if (y1 - y0 < minH) {
        val c = (y0 + y1) / 2
        y0 = max(0.0, min(1 - minH, c - minH / 2))
        y1 = y0 + minH
    }
    return doubleArrayOf(x0, y0, x1 - x0, y1 - y0)
}
