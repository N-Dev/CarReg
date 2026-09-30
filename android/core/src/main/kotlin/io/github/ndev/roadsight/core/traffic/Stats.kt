package io.github.ndev.roadsight.core.traffic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/** Rounds halves up, like JavaScript's Math.round, so figures match the web app. */
private fun round(x: Double): Double = floor(x + 0.5)

/** Speed figures (km/h) for a set of speeds, against a limit. `n` = 0 when nothing was measured. */
data class SpeedStats(
    val n: Int,
    val mean: Double = 0.0,
    val median: Double = 0.0,
    val p85: Double = 0.0,
    val max: Double = 0.0,
    val over: Int = 0,
    val overPct: Double = 0.0,
    val over10: Int = 0,
)

class HourBin(val start: Long) {
    val kinds = LinkedHashMap<String, Int>().also { m -> for (k in Kinds.ORDER) m[k] = 0 }
    var all = 0
    var motor = 0
    val dirs = intArrayOf(0, 0, 0) // [unused, direction 1, direction 2]
}

class SpeedBand(val from: Int, val to: Int, var n: Int = 0)

/** Everything the results screen and the report show for one counting session. */
class Summary(
    val from: Long,
    val to: Long,
    val hours: Double,
    /** kind -> [total, direction 1, direction 2] */
    val totals: Map<String, IntArray>,
    val all: Int,
    val motor: Int,
    val motorDir: IntArray,
    val perHourAvg: Double,
    val peak: HourBin?,
    val perHour: List<HourBin>,
    val long: Int,
    val limit: Int,
    val speedsAll: SpeedStats,
    val speeds1: SpeedStats,
    val speeds2: SpeedStats,
    val bikeSpeeds: SpeedStats,
    val histogram: List<SpeedBand>,
)

/** A counted road user as stored: time in epoch ms. */
data class Event(val t: Long, val kind: String, val dir: Int, val speed: Double?, val length: Double?)

object Stats {
    private const val HOUR = 3_600_000L

    /** Percentile p (0..100) of sorted numbers, interpolating between neighbours (like PERCENTILE.INC). */
    fun percentile(sorted: List<Double>, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val x = (p / 100) * (sorted.size - 1)
        val i = floor(x).toInt()
        return if (i + 1 < sorted.size) sorted[i] + (x - i) * (sorted[i + 1] - sorted[i]) else sorted[i]
    }

    private fun r1(x: Double) = round(x * 10) / 10

    fun speedStats(speeds: List<Double?>, limit: Int): SpeedStats {
        val s = speeds.filterNotNull().sorted()
        if (s.isEmpty()) return SpeedStats(0)
        val over = s.count { it > limit }
        return SpeedStats(
            n = s.size,
            mean = r1(s.sum() / s.size),
            median = r1(percentile(s, 50.0)!!),
            p85 = r1(percentile(s, 85.0)!!),
            max = r1(s.last()),
            over = over,
            overPct = round(over * 1000.0 / s.size) / 10,
            over10 = s.count { it > limit + 10 },
        )
    }

    /** Start of the local clock hour containing time t. */
    fun hourStart(t: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(t).atZone(zone).truncatedTo(ChronoUnit.HOURS).toInstant().toEpochMilli()

    /** Motor-vehicle speeds in 5 km/h bands, from 0 up to the fastest band used. */
    fun histogram(speeds: List<Double>, step: Int = 5): List<SpeedBand> {
        if (speeds.isEmpty()) return emptyList()
        val fastest = speeds.max()
        val top = (Math.ceil((fastest + 0.001) / step) * step).toInt()
        val bins = ArrayList<SpeedBand>()
        var v = 0
        while (v < top) {
            bins.add(SpeedBand(v, v + step))
            v += step
        }
        for (s in speeds) bins[minOf(bins.size - 1, floor(s / step).toInt())].n++
        return bins
    }

    fun summarize(events: List<Event>, from: Long? = null, to: Long? = null, limit: Int = 50, zone: ZoneId = ZoneId.systemDefault()): Summary {
        val first = events.minOfOrNull { it.t }
        val last = events.maxOfOrNull { it.t }
        val start = from ?: first ?: System.currentTimeMillis()
        val end = maxOf(to ?: start, last ?: start)
        val totals = LinkedHashMap<String, IntArray>().also { m -> for (k in Kinds.ORDER) m[k] = IntArray(3) }
        var motor = 0
        val motorDir = IntArray(3)
        var long = 0
        val hours = LinkedHashMap<Long, HourBin>()
        var h = hourStart(start, zone)
        while (h <= end) {
            hours[h] = HourBin(h)
            h += HOUR
        }
        var all = 0
        for (e in events) {
            val tot = totals[e.kind] ?: continue
            all++
            tot[0]++
            tot[e.dir]++
            val hs = hourStart(e.t, zone)
            val bin = hours.getOrPut(hs) { HourBin(hs) }
            bin.kinds[e.kind] = (bin.kinds[e.kind] ?: 0) + 1
            bin.all++
            if (Kinds.isMotor(e.kind)) {
                motor++
                motorDir[e.dir]++
                bin.motor++
                bin.dirs[e.dir]++
                if (e.length != null && e.length >= LONG_VEHICLE_M) long++
            }
        }
        val perHour = hours.values.sortedBy { it.start }
        var peak: HourBin? = null
        for (b in perHour) if (peak == null || b.motor > peak.motor) peak = b
        val hoursCounted = maxOf((end - start).toDouble() / HOUR, 1.0 / 60)
        fun motorSpeeds(dir: Int?) = events.filter { Kinds.isMotor(it.kind) && (dir == null || it.dir == dir) }.map { it.speed }
        return Summary(
            from = start,
            to = end,
            hours = round(hoursCounted * 100) / 100,
            totals = totals,
            all = all,
            motor = motor,
            motorDir = motorDir,
            perHourAvg = round(motor / hoursCounted * 10) / 10,
            peak = if (peak != null && peak.motor > 0) peak else null,
            perHour = perHour,
            long = long,
            limit = limit,
            speedsAll = speedStats(motorSpeeds(null), limit),
            speeds1 = speedStats(motorSpeeds(1), limit),
            speeds2 = speedStats(motorSpeeds(2), limit),
            bikeSpeeds = speedStats(events.filter { it.kind == "bicycle" }.map { it.speed }, limit),
            histogram = histogram(motorSpeeds(null).filterNotNull()),
        )
    }

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Local date and time as "2026-09-29 18:05:31". */
    fun localStamp(t: Long, zone: ZoneId = ZoneId.systemDefault()): String = STAMP.format(Instant.ofEpochMilli(t).atZone(zone))

    fun csvCell(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.contains('"') || s.contains(',') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    /** One row per road user counted: when, what, which way, speed and length (blank if not measured). */
    fun toCSV(events: List<Event>, dirNames: List<String>, site: String = "", zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder("time,type,direction,speed_kmh,length_m,site\r\n")
        for (e in events.sortedBy { it.t }) {
            val row = listOf(
                localStamp(e.t, zone),
                Kinds[e.kind]?.one ?: e.kind,
                dirNames.getOrNull(e.dir - 1) ?: e.dir.toString(),
                e.speed?.let { fmt1(it) } ?: "",
                e.length?.let { fmt1(it) } ?: "",
                site,
            )
            sb.append(row.joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }

    /** 41.5 → "41.5", 60.0 → "60" (like the web app's CSV). */
    fun fmt1(x: Double): String {
        val r = round(x * 10) / 10
        return if (r == floor(r)) r.toLong().toString() else r.toString()
    }
}
