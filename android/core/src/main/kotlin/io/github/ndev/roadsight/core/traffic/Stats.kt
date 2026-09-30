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

/** One counting session, for figures across several: when it ran and what it counted. */
class Counted(val from: Long, val to: Long, val events: List<Event>)

/** One day of a survey over several days. */
class DayRow(
    val date: java.time.LocalDate,
    /** Hours counted that day. */
    val hours: Double,
    /** kind -> [total, direction 1, direction 2] */
    val totals: Map<String, IntArray>,
    val motor: Int,
    val speeds: SpeedStats,
) {
    /** Motor vehicles an hour counted, or null for under a quarter of an hour. */
    val perHour: Double? get() = if (hours >= 0.25) floor(motor / hours * 10 + 0.5) / 10 else null
}

/** One hour of the day (07:00 to 08:00, say) over every day counted: how long it was counted, and what passed. */
class HourOfDay(val hour: Int) {
    var hours = 0.0
    var motor = 0
    val dirs = intArrayOf(0, 0, 0)

    /** Motor vehicles an hour on average, or null if this hour was counted for under a quarter of an hour. */
    val perHour: Double? get() = if (hours >= 0.25) motor / hours else null
    fun perHourDir(d: Int): Double = if (hours >= 0.25) dirs[d] / hours else 0.0
}

/** Figures for a survey made of several sessions (usually on different days at one site). */
class DaysSummary(
    /** Over every road user counted: totals, speeds and the speed histogram (its hours and hourly figures span the gaps; use the ones below). */
    val all: Summary,
    val sessions: Int,
    val from: Long,
    val to: Long,
    /** Time actually counted, in hours. */
    val hours: Double,
    val perHourAvg: Double,
    val days: List<DayRow>,
    /** Hours of the day 0 to 23. */
    val profile: List<HourOfDay>,
    /** The busiest hour of the day, on average. */
    val peak: HourOfDay?,
)

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

    /**
     * Figures across several sessions: day by day, and by hour of the day averaged over the time actually
     * counted (so a day counted from 7 to 10 and another from 7 to 19 give a fair picture of each hour).
     */
    fun summarizeDays(sessions: List<Counted>, limit: Int = 50, zone: ZoneId = ZoneId.systemDefault()): DaysSummary {
        val events = sessions.flatMap { it.events }.sortedBy { it.t }
        val from = sessions.minOfOrNull { it.from } ?: events.firstOrNull()?.t ?: System.currentTimeMillis()
        val to = maxOf(sessions.maxOfOrNull { it.to } ?: from, events.lastOrNull()?.t ?: from)
        val all = summarize(events, from, to, limit, zone)
        val profile = List(24) { HourOfDay(it) }
        val dayHours = java.util.TreeMap<java.time.LocalDate, Double>()
        // Time counted, split at each clock hour.
        for (c in sessions) {
            var a = c.from
            val end = maxOf(c.to, c.events.maxOfOrNull { it.t } ?: c.to)
            while (a < end) {
                val b = minOf(end, hourStart(a, zone) + HOUR)
                val z = Instant.ofEpochMilli(a).atZone(zone)
                val h = (b - a).toDouble() / HOUR
                profile[z.hour].hours += h
                dayHours[z.toLocalDate()] = (dayHours[z.toLocalDate()] ?: 0.0) + h
                a = b
            }
        }
        val byDay = events.groupBy { Instant.ofEpochMilli(it.t).atZone(zone).toLocalDate() }
        for (e in events) {
            if (!Kinds.isMotor(e.kind)) continue
            val p = profile[Instant.ofEpochMilli(e.t).atZone(zone).hour]
            p.motor++
            p.dirs[e.dir]++
        }
        val days = (dayHours.keys + byDay.keys).toSortedSet().map { d ->
            val ev = byDay[d] ?: emptyList()
            val totals = LinkedHashMap<String, IntArray>().also { m -> for (k in Kinds.ORDER) m[k] = IntArray(3) }
            for (e in ev) totals[e.kind]?.let { it[0]++; it[e.dir]++ }
            DayRow(
                d, round((dayHours[d] ?: 0.0) * 100) / 100, totals, ev.count { Kinds.isMotor(it.kind) },
                speedStats(ev.filter { Kinds.isMotor(it.kind) }.map { it.speed }, limit),
            )
        }
        val hours = maxOf(profile.sumOf { it.hours }, 1.0 / 60)
        val peak = profile.filter { it.perHour != null && it.motor > 0 }.maxByOrNull { it.perHour!! }
        return DaysSummary(
            all = all,
            sessions = sessions.size,
            from = from,
            to = to,
            hours = round(hours * 100) / 100,
            perHourAvg = round(all.motor / hours * 10) / 10,
            days = days,
            profile = profile,
            peak = peak,
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
