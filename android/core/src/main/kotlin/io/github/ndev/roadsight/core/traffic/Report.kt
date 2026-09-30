package io.github.ndev.roadsight.core.traffic

import io.github.ndev.roadsight.core.traffic.Pdf.Align
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the report needs to know about a counting session. */
class ReportSession(
    val site: String,
    val distanceM: Double,
    val limit: Int,
    val dirNames: List<String>,
    /** Counted from a video recording rather than live. */
    val fromVideo: Boolean = false,
)

/**
 * The one-page report for the council: where and when, counts by type and direction, vehicles per
 * hour, speeds, and how it was measured. A port of TrafficSight's report.js.
 */
object Report {
    // Print colours (validated as a pair on white): direction 1, direction 2, over the limit.
    private const val INK = "#111111"
    private const val INK2 = "#52514e"
    private const val MUTED = "#898781"
    private const val GRID = "#e1e0d9"
    private const val AXIS = "#c3c2b7"
    private val DIR = listOf("#2a78d6", "#eb6834")
    private const val WITHIN = "#2a78d6"
    private const val OVER = "#d03b3b"
    private const val M = 44.0 // page margin

    private val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    private val NF = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.UK)).apply { roundingMode = RoundingMode.HALF_UP }

    /** 1234 → "1,234", 12.5 → "12.5" (like toLocaleString('en-IE')). */
    fun fmt(n: Number?): String = if (n == null) "–" else NF.format(n)

    private fun pad(n: Int) = n.toString().padStart(2, '0')
    private fun at(t: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(t).atZone(zone)
    private fun jsRound(x: Double): Long = floor(x + 0.5).toLong()

    /** "Tue 29 Sep 2026, 07:10" */
    fun whenText(t: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = at(t, zone)
        return "${DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}, ${pad(d.hour)}:${pad(d.minute)}"
    }

    /** "Tue 29 Sep 2026, 07:10 to 19:45", or both dates in full when it runs past midnight. */
    fun period(from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val a = at(from, zone)
        val b = at(to, zone)
        return if (a.toLocalDate() == b.toLocalDate()) "${whenText(from, zone)} to ${pad(b.hour)}:${pad(b.minute)}" else "${whenText(from, zone)} to ${whenText(to, zone)}"
    }

    /** "1 h 05 min" or "25 min". */
    fun duration(hours: Double): String {
        val mins = jsRound(hours * 60).toInt()
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "$h h ${pad(m)} min" else "$m min"
    }

    /** Vehicles an hour, or just the time counted when that's under half an hour (an hourly rate would mislead). */
    fun rate(s: Summary): String = if (s.hours >= 0.5) "${fmt(s.perHourAvg)} an hour on average" else "in ${duration(s.hours)}"

    /** "07:00 to 08:00" for the hour starting at `start`. */
    fun hourRange(start: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val h = at(start, zone).hour
        return "${pad(h)}:00 to ${pad((h + 1) % 24)}:00"
    }

    fun hh(t: Long, zone: ZoneId = ZoneId.systemDefault()): String = pad(at(t, zone).hour)

    /** Neat axis steps: 1, 2, 5, 10, 20, 50 ... so gridlines land on round numbers (whole numbers: these are counts). */
    fun niceStep(maxValue: Double, ticks: Int = 4): Double {
        val raw = max(1.0, maxValue) / ticks
        val p = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * p }.first { it >= raw }
        return max(1.0, step)
    }

    /** A file name for a session's exports: "traffic-main-street-2026-09-29". */
    fun baseName(site: String, started: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val slug = site.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "survey" }
        return "traffic-$slug-${at(started, zone).toLocalDate()}"
    }

    /**
     * The report as PDF bytes. `appName`, `release` and `build` go in the note and the footer.
     */
    fun council(
        session: ReportSession,
        s: Summary,
        appName: String = "RoadSight",
        release: String = "",
        build: String = "",
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ByteArray {
        val pdf = Pdf("Traffic survey – ${session.site.ifEmpty { "untitled site" }}", appName, appName)
        val dirNames = session.dirNames.takeIf { it.size >= 2 } ?: listOf("Direction 1", "Direction 2")
        var y = header(pdf, session.site, "${period(s.from, s.to, zone)} · ${duration(s.hours)} of counting")

        // Key figures.
        val sp = s.speedsAll
        y = tiles(
            pdf, y,
            listOf(
                Triple("Motor vehicles", fmt(s.motor), rate(s)),
                Triple("Busiest hour", s.peak?.let { fmt(it.motor) } ?: "–", s.peak?.let { hourRange(it.start, zone) } ?: "no vehicles"),
                p85Tile(sp),
                overTile(s),
            ),
        )
        y = countsAndSpeeds(pdf, y, s, dirNames)

        // Vehicles per hour, stacked by direction.
        pdf.text(M, y, "Motor vehicles per hour", size = 11.0, bold = true)
        legend(pdf, Pdf.W - M, y, listOf(DIR[0] to dirNames[0], DIR[1] to dirNames[1]))
        y += 12
        y = hourChart(pdf, M, y, Pdf.W - 2 * M, 118.0, s.perHour, zone) + 18

        y = speedSection(pdf, y, s)
        note(pdf, y, method(appName, listOf(session)))
        footer(pdf, appName, release, build, now, zone)
        return pdf.bytes()
    }

    /**
     * A report across several sessions (usually one site on several days): day by day, an average day
     * hour by hour, and the counts and speeds over them all. Two pages.
     */
    fun days(
        sessions: List<ReportSession>,
        d: DaysSummary,
        appName: String = "RoadSight",
        release: String = "",
        build: String = "",
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ByteArray {
        val sites = sessions.map { it.site.trim() }.filter { it.isNotEmpty() }.distinct()
        val site = when (sites.size) {
            0 -> ""
            1 -> sites[0]
            else -> sites.joinToString(" · ")
        }
        val pdf = Pdf("Traffic survey – ${site.ifEmpty { "untitled site" }}", appName, appName)
        // Directions: the sessions' own names when they agree, else A to B and B to A.
        val names = sessions.map { it.dirNames }.distinct()
        val dirNames = names.singleOrNull()?.takeIf { it.size >= 2 } ?: listOf("A to B", "B to A")
        val s = d.all
        val right = Pdf.W - M
        val span = "${dayText(d.from, zone)} to ${dayText(d.to, zone)}"
        var y = header(pdf, site, "$span · ${d.sessions} session${if (d.sessions == 1) "" else "s"}, ${duration(d.hours)} of counting")

        val sp = s.speedsAll
        y = tiles(
            pdf, y,
            listOf(
                Triple("Motor vehicles", fmt(s.motor), if (d.hours >= 0.5) "${fmt(d.perHourAvg)} an hour on average" else "in ${duration(d.hours)}"),
                Triple(
                    "Busiest time of day",
                    d.peak?.let { "${pad(it.hour)}:00" } ?: "–",
                    d.peak?.let { "${fmt(jsRound(it.perHour!! * 10) / 10.0)} an hour on average" } ?: "no vehicles",
                ),
                p85Tile(sp),
                overTile(s),
            ),
        )

        // Day by day.
        pdf.text(M, y, "Day by day", size = 11.0, bold = true)
        y += 18
        val w = Pdf.W - 2 * M
        val xs = doubleArrayOf(M, M + w * 0.34, M + w * 0.46, M + w * 0.58, M + w * 0.69, M + w * 0.8, M + w * 0.9, right)
        val heads = listOf("Day", "Counted", "Motor vehicles", "An hour", "Bicycles", "People", "85% km/h", "Over ${s.limit}")
        y += 9
        heads.forEachIndexed { i, h ->
            val lines = wrap2(h, if (i > 0) w * 0.1 else 200.0, 8.0)
            lines.forEachIndexed { k, l ->
                pdf.text(xs[i], y - (lines.size - 1 - k) * 9, l, size = 8.0, color = INK2, bold = true, align = if (i > 0) Align.RIGHT else Align.LEFT)
            }
        }
        y += 6
        pdf.line(M, y, right, y, color = AXIS, width = 0.5)
        y += 12
        for (r in d.days) {
            val cells = listOf(
                dayText(r.date.atStartOfDay(zone).toInstant().toEpochMilli(), zone),
                duration(r.hours),
                fmt(r.motor),
                r.perHour?.let { fmt(it) } ?: "–",
                fmt(r.totals["bicycle"]?.get(0) ?: 0),
                fmt(r.totals["person"]?.get(0) ?: 0),
                if (r.speeds.n > 0) Stats.fmt1(r.speeds.p85) else "–",
                if (r.speeds.n > 0) "${Stats.fmt1(r.speeds.overPct)}%" else "–",
            )
            cells.forEachIndexed { i, c -> pdf.text(xs[i], y, c, size = 9.0, align = if (i > 0) Align.RIGHT else Align.LEFT, bold = i == 2) }
            y += 14
            if (y > Pdf.H - 330) {
                pdf.text(M, y, "…and ${d.days.size - d.days.indexOf(r) - 1} more days (in the CSV)", size = 8.0, color = MUTED)
                y += 14
                break
            }
        }
        pdf.line(M, y - 9, right, y - 9, color = GRID, width = 0.5)
        y += 14

        // An average day, hour by hour.
        pdf.text(M, y, "Motor vehicles an hour, by time of day", size = 11.0, bold = true)
        legend(pdf, right, y, listOf(DIR[0] to dirNames[0], DIR[1] to dirNames[1]))
        y += 10
        pdf.text(M, y, "Averaged over the time counted in each hour; blank hours weren’t counted.", size = 7.5, color = MUTED)
        y += 10
        y = profileChart(pdf, M, y, w, 130.0, d.profile) + 12
        footer(pdf, appName, release, build, now, zone)

        // Page 2: the counts, the speeds and how it was measured.
        pdf.addPage()
        y = M + 20
        pdf.text(M, y, "Traffic survey", size = 13.0, bold = true)
        pdf.text(right, y, "${site.ifEmpty { "Untitled site" }} · $span", size = 9.0, color = INK2, align = Align.RIGHT)
        y += 28
        y = countsAndSpeeds(pdf, y, s, dirNames)
        y = speedSection(pdf, y, s)
        note(pdf, y, method(appName, sessions))
        footer(pdf, appName, release, build, now, zone)
        return pdf.bytes()
    }

    /** "Tue 29 Sep" */
    private fun dayText(t: Long, zone: ZoneId): String {
        val d = at(t, zone)
        return "${DAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
    }

    private fun header(pdf: Pdf, site: String, sub: String): Double {
        var y = M + 20
        pdf.text(M, y, "Traffic survey", size = 22.0, bold = true)
        y += 22
        pdf.text(M, y, site.ifEmpty { "Untitled site" }, size = 13.0, bold = true, color = INK)
        y += 16
        pdf.text(M, y, sub, size = 9.5, color = INK2)
        y += 26
        return y
    }

    private fun p85Tile(sp: SpeedStats) = Triple(
        "85% drove at or under",
        if (sp.n > 0) "${jsRound(sp.p85)} km/h" else "–",
        if (sp.n > 0) "from ${fmt(sp.n)} speeds measured" else "no speeds measured",
    )

    private fun overTile(s: Summary): Triple<String, String, String> {
        val sp = s.speedsAll
        return Triple("Over the ${s.limit} km/h limit", if (sp.n > 0) "${Stats.fmt1(sp.overPct)}%" else "–", if (sp.n > 0) "${fmt(sp.over)} vehicle${if (sp.over == 1) "" else "s"}" else "")
    }

    private fun tiles(pdf: Pdf, top: Double, tiles: List<Triple<String, String, String>>): Double {
        var y = top
        val tw = (Pdf.W - 2 * M) / tiles.size
        tiles.forEachIndexed { i, (label, value, sub) ->
            val x = M + i * tw
            pdf.text(x, y, label, size = 8.5, color = INK2)
            pdf.text(x, y + 22, value, size = 19.0, bold = true)
            pdf.text(x, y + 36, sub, size = 8.0, color = MUTED)
        }
        y += 58
        pdf.line(M, y, Pdf.W - M, y, color = GRID, width = 0.75)
        y += 22
        return y
    }

    /** Road users counted (left) and the speeds of motor vehicles (right). */
    private fun countsAndSpeeds(pdf: Pdf, top0: Double, s: Summary, dirNames: List<String>): Double {
        var y = top0
        val right = Pdf.W - M
        val colW = (Pdf.W - 2 * M - 24) / 2
        val top = y
        pdf.text(M, y, "Road users counted", size = 11.0, bold = true)
        y += 18
        val cx = doubleArrayOf(M, M + colW * 0.5, M + colW * 0.75, M + colW)
        // Column headings on up to two lines (direction names can be long), bottom-aligned.
        fun head(yy: Double, cols: List<String>, xs: DoubleArray, width: Double) {
            cols.forEachIndexed { i, c ->
                val lines = wrap2(c, if (i > 0) width else 200.0, 8.0)
                lines.forEachIndexed { k, l ->
                    pdf.text(xs[i], yy - (lines.size - 1 - k) * 9, l, size = 8.0, color = INK2, bold = true, align = if (i > 0) Align.RIGHT else Align.LEFT)
                }
            }
        }
        y += 9
        head(y, listOf("Type", dirNames[0], dirNames[1], "Total"), cx, colW * 0.24)
        y += 6
        pdf.line(M, y, M + colW, y, color = AXIS, width = 0.5)
        y += 12
        val rows = Kinds.ORDER.filter { (s.totals[it]?.get(0) ?: 0) > 0 || it == "car" }
        for (k in rows) {
            val t = s.totals[k] ?: IntArray(3)
            pdf.text(cx[0], y, Kinds[k]!!.label, size = 9.0)
            pdf.text(cx[1], y, fmt(t[1]), size = 9.0, align = Align.RIGHT)
            pdf.text(cx[2], y, fmt(t[2]), size = 9.0, align = Align.RIGHT)
            pdf.text(cx[3], y, fmt(t[0]), size = 9.0, align = Align.RIGHT, bold = true)
            y += 14
        }
        pdf.line(M, y - 9, M + colW, y - 9, color = GRID, width = 0.5)
        y += 2
        pdf.text(cx[0], y, "All motor vehicles", size = 9.0, bold = true)
        pdf.text(cx[1], y, fmt(s.motorDir[1]), size = 9.0, align = Align.RIGHT, bold = true)
        pdf.text(cx[2], y, fmt(s.motorDir[2]), size = 9.0, align = Align.RIGHT, bold = true)
        pdf.text(cx[3], y, fmt(s.motor), size = 9.0, align = Align.RIGHT, bold = true)
        y += 14
        pdf.text(cx[0], y, "Long vehicles (${Stats.fmt1(LONG_VEHICLE_M)} m or more)", size = 9.0, color = INK2)
        pdf.text(cx[3], y, fmt(s.long), size = 9.0, align = Align.RIGHT, color = INK2)
        val leftEnd = y

        // Speeds table.
        y = top
        val sx0 = M + colW + 24
        pdf.text(sx0, y, "Speeds of motor vehicles (km/h)", size = 11.0, bold = true)
        y += 18
        val sxs = doubleArrayOf(sx0, sx0 + colW * 0.52, sx0 + colW * 0.76, sx0 + colW)
        y += 9
        head(y, listOf("", "Both ways", dirNames[0], dirNames[1]), sxs, colW * 0.23)
        y += 6
        pdf.line(sx0, y, sx0 + colW, y, color = AXIS, width = 0.5)
        y += 12
        val all = listOf(s.speedsAll, s.speeds1, s.speeds2)
        val sRows: List<Triple<String, (SpeedStats) -> String, Boolean>> = listOf(
            Triple("Speeds measured", { x -> fmt(x.n) }, false),
            Triple("Average", { x -> if (x.n > 0) Stats.fmt1(x.mean) else "–" }, false),
            Triple("85th percentile", { x -> if (x.n > 0) Stats.fmt1(x.p85) else "–" }, true),
            Triple("Fastest", { x -> if (x.n > 0) Stats.fmt1(x.max) else "–" }, false),
            Triple("Over ${s.limit} km/h", { x -> if (x.n > 0) "${fmt(x.over)} (${Stats.fmt1(x.overPct)}%)" else "–" }, false),
            Triple("Over ${s.limit + 10} km/h", { x -> if (x.n > 0) fmt(x.over10) else "–" }, false),
        )
        for ((label, f, bold) in sRows) {
            pdf.text(sxs[0], y, label, size = 9.0, bold = bold)
            all.forEachIndexed { i, x -> pdf.text(sxs[i + 1], y, f(x), size = 9.0, align = Align.RIGHT, bold = bold) }
            y += 14
        }
        y = max(leftEnd, y) + 20
        pdf.line(M, y - 8, right, y - 8, color = GRID, width = 0.75)
        y += 8
        return y
    }

    /** The speed distribution chart with its title and legend. */
    private fun speedSection(pdf: Pdf, top: Double, s: Summary): Double {
        var y = top
        pdf.text(M, y, "How fast motor vehicles went", size = 11.0, bold = true)
        legend(pdf, Pdf.W - M, y, listOf(WITHIN to "Within ${s.limit} km/h", OVER to "Over ${s.limit} km/h"))
        y += 12
        y = speedChart(pdf, M, y, Pdf.W - 2 * M, 104.0, s) + 16
        return y
    }

    /** How it was measured: the same words as the web app for a live count; a video count says so. */
    fun method(appName: String, sessions: List<ReportSession>): String {
        val distances = sessions.map { it.distanceM }.distinct().sorted()
        val dist = if (distances.size <= 1) "${Stats.fmt1(distances.firstOrNull() ?: 20.0)} m" else "${Stats.fmt1(distances.first())} to ${Stats.fmt1(distances.last())} m"
        val video = sessions.count { it.fromVideo }
        val how = when {
            video == 0 -> "road users were counted automatically by $appName, an app running on a phone " +
                "camera that analyses the picture on the phone itself. No images, video or number plates were recorded or kept. "
            video == sessions.size -> "road users were counted automatically by $appName, an app that analysed a video recording " +
                "on the phone itself. The app kept no images or number plates from it. "
            else -> "road users were counted automatically by $appName, an app that analyses a phone camera's picture " +
                "(or, for ${if (video == 1) "one session" else "$video sessions"}, a video recording) on the phone itself. The app kept no images or number plates. "
        }
        return "How this was measured: " + how +
            "Each road user is counted once, when it first crosses one of two lines marked across the road; its speed is the " +
            "$dist between the lines divided by the time it took, so speeds are estimates that have not been " +
            "checked against a calibrated speed device, and depend on how accurately that distance was measured. Vans and " +
            "lorries are counted together; long vehicles are those measured at ${Stats.fmt1(LONG_VEHICLE_M)} m or more. Vehicles hidden behind others " +
            "can be missed, so counts are a minimum."
    }

    private fun note(pdf: Pdf, y: Double, text: String) {
        pdf.paragraph(M, min(y, Pdf.H - 76), text, Pdf.W - 2 * M, size = 7.5, color = INK2, lead = 1.4)
    }

    private fun footer(pdf: Pdf, appName: String, release: String, build: String, now: Long, zone: ZoneId) {
        val buildText = if (build.isNotEmpty() && build != "dev") " (build $build)" else ""
        pdf.text(M, Pdf.H - 26, "Generated ${whenText(now, zone)} · $appName $release$buildText", size = 7.0, color = MUTED)
    }

    /** Text split over at most two lines of the given width (the second shortened with … if needed). */
    fun wrap2(text: String, width: Double, size: Double): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = arrayListOf("")
        for (w in words) {
            val cur = lines.last()
            val next = if (cur.isNotEmpty()) "$cur $w" else w
            if (cur.isNotEmpty() && Pdf.textWidth(next, size, true) > width && lines.size < 2) lines.add(w) else lines[lines.size - 1] = next
        }
        var last = lines.last()
        while (Pdf.textWidth(last, size, true) > width && last.length > 1) last = last.dropLast(2) + "…"
        lines[lines.size - 1] = last
        return lines
    }

    private fun legend(pdf: Pdf, rightX: Double, y: Double, items: List<Pair<String, String>>) {
        var x = rightX
        for ((color, label) in items.reversed()) {
            val w = Pdf.textWidth(label, 8.0)
            x -= w
            pdf.text(x, y, label, size = 8.0, color = INK2)
            x -= 12
            pdf.rect(x, y - 7, 8.0, 8.0, fill = color)
            x -= 14
        }
    }

    /** Columns per hour, direction 1 at the bottom, with a 1.5 pt gap between segments and between columns. */
    private fun hourChart(pdf: Pdf, x: Double, y: Double, w: Double, h: Double, bins: List<HourBin>, zone: ZoneId): Double {
        val maxV = max(1, bins.maxOfOrNull { it.dirs[1] + it.dirs[2] } ?: 0).toDouble()
        val step = niceStep(maxV)
        val top = ceil(maxV / step) * step
        val axisW = 28.0
        val px = x + axisW
        val pw = w - axisW
        var v = 0.0
        while (v <= top) {
            val gy = y + h - (v / top) * h
            pdf.line(px, gy, px + pw, gy, color = if (v > 0) GRID else AXIS, width = if (v > 0) 0.4 else 0.6)
            pdf.text(px - 5, gy + 3, fmt(v), size = 7.0, color = MUTED, align = Align.RIGHT)
            v += step
        }
        val n = max(1, bins.size)
        val slot = pw / n
        val bw = min(18.0, max(1.5, slot - 2))
        val every = ceil(n / 24.0).toInt() * (if (n > 12) 2 else 1)
        bins.forEachIndexed { i, b ->
            val bx = px + i * slot + (slot - bw) / 2
            val h1 = b.dirs[1] / top * h
            val h2 = b.dirs[2] / top * h
            val gap = if (h1 > 0 && h2 > 0) 1.5 else 0.0
            if (h1 > 0) pdf.column(bx, y + h - h1, bw, h1, DIR[0], if (h2 > 0) 0.0 else 2.0)
            if (h2 > 0) pdf.column(bx, y + h - h1 - gap - h2, bw, h2, DIR[1], 2.0)
            if (i % every == 0) pdf.text(bx + bw / 2, y + h + 11, hh(b.start, zone), size = 7.0, color = MUTED, align = Align.CENTER)
        }
        if (bins.none { it.motor > 0 }) pdf.text(px + pw / 2, y + h / 2, "No motor vehicles counted", size = 9.0, color = MUTED, align = Align.CENTER)
        pdf.text(px + pw, y + h + 22, "hour of the day", size = 7.0, color = MUTED, align = Align.RIGHT)
        return y + h + 22
    }

    /**
     * An average day: motor vehicles an hour for each hour of the day (averaged over the time counted in
     * that hour), direction 1 at the bottom. Hours not counted are left blank.
     */
    private fun profileChart(pdf: Pdf, x: Double, y: Double, w: Double, h: Double, hours: List<HourOfDay>): Double {
        val maxV = max(1.0, hours.maxOfOrNull { it.perHour ?: 0.0 } ?: 0.0)
        val step = niceStep(maxV)
        val top = ceil(maxV / step) * step
        val axisW = 28.0
        val px = x + axisW
        val pw = w - axisW
        var v = 0.0
        while (v <= top) {
            val gy = y + h - (v / top) * h
            pdf.line(px, gy, px + pw, gy, color = if (v > 0) GRID else AXIS, width = if (v > 0) 0.4 else 0.6)
            pdf.text(px - 5, gy + 3, fmt(v), size = 7.0, color = MUTED, align = Align.RIGHT)
            v += step
        }
        val slot = pw / 24
        val bw = min(18.0, max(1.5, slot - 2))
        for (b in hours) {
            val bx = px + b.hour * slot + (slot - bw) / 2
            if (b.perHour != null) {
                val h1 = b.perHourDir(1) / top * h
                val h2 = b.perHourDir(2) / top * h
                val gap = if (h1 > 0 && h2 > 0) 1.5 else 0.0
                if (h1 > 0) pdf.column(bx, y + h - h1, bw, h1, DIR[0], if (h2 > 0) 0.0 else 2.0)
                if (h2 > 0) pdf.column(bx, y + h - h1 - gap - h2, bw, h2, DIR[1], 2.0)
            }
            if (b.hour % 2 == 0) pdf.text(bx + bw / 2, y + h + 11, pad(b.hour), size = 7.0, color = MUTED, align = Align.CENTER)
        }
        if (hours.none { it.motor > 0 }) pdf.text(px + pw / 2, y + h / 2, "No motor vehicles counted", size = 9.0, color = MUTED, align = Align.CENTER)
        pdf.text(px + pw, y + h + 22, "hour of the day", size = 7.0, color = MUTED, align = Align.RIGHT)
        return y + h + 22
    }

    /** The 5 km/h bands a speed chart shows (from a little below the slowest, or the limit, to past the limit), and the range they span. */
    class Bands(val bands: List<SpeedBand>, val first: Int, val last: Int)

    fun speedBands(s: Summary): Bands? {
        val bins = s.histogram
        if (bins.isEmpty()) return null
        val lastBand = max(bins.last().to, s.limit + 10)
        val used = bins.firstOrNull { it.n > 0 }
        val firstBand = max(0, min(floor((used?.from ?: 0) / 10.0).toInt() * 10 - 10, s.limit - 20))
        val bands = ArrayList<SpeedBand>()
        var v = firstBand
        while (v < lastBand) {
            bands.add(bins.firstOrNull { it.from == v } ?: SpeedBand(v, v + 5, 0))
            v += 5
        }
        return Bands(bands, firstBand, lastBand)
    }

    /** Speeds in 5 km/h bands; bands above the limit in red; the limit and 85th percentile marked. */
    private fun speedChart(pdf: Pdf, x: Double, y: Double, w: Double, h: Double, s: Summary): Double {
        val axisW = 28.0
        val px = x + axisW
        val pw = w - axisW
        val range = speedBands(s)
        if (range == null || range.bands.isEmpty()) {
            pdf.text(px + pw / 2, y + h / 2, "No speeds measured", size = 9.0, color = MUTED, align = Align.CENTER)
            return y + h + 12
        }
        val bands = range.bands
        val firstBand = range.first
        val lastBand = range.last
        val maxV = max(1, bands.maxOf { it.n }).toDouble()
        val step = niceStep(maxV)
        val top = ceil(maxV / step) * step
        var v = 0.0
        while (v <= top) {
            val gy = y + h - (v / top) * h
            pdf.line(px, gy, px + pw, gy, color = if (v > 0) GRID else AXIS, width = if (v > 0) 0.4 else 0.6)
            pdf.text(px - 5, gy + 3, fmt(v), size = 7.0, color = MUTED, align = Align.RIGHT)
            v += step
        }
        val slot = pw / bands.size
        val bw = min(18.0, max(1.5, slot - 2))
        fun xAt(kmh: Double) = px + (kmh - firstBand) / (lastBand - firstBand) * pw
        bands.forEachIndexed { i, b ->
            val bh = b.n / top * h
            val bx = px + i * slot + (slot - bw) / 2
            if (bh > 0) pdf.column(bx, y + h - bh, bw, bh, if (b.from >= s.limit) OVER else WITHIN, 2.0)
            if (b.from % 10 == 0) pdf.text(px + i * slot, y + h + 11, b.from.toString(), size = 7.0, color = MUTED, align = Align.CENTER)
        }
        pdf.text(px + pw, y + h + 22, "km/h", size = 7.0, color = MUTED, align = Align.RIGHT)
        val lx = xAt(s.limit.toDouble())
        pdf.line(lx, y - 4, lx, y + h, color = INK2, width = 0.8, dash = doubleArrayOf(3.0, 2.0))
        pdf.text(lx + 3, y + 3, "${s.limit} km/h limit", size = 7.5, color = INK2)
        val sp = s.speedsAll
        if (sp.n > 0) {
            val qx = xAt(sp.p85)
            pdf.line(qx, y + 10, qx, y + h, color = INK, width = 0.8)
            val label = "85% at or under ${jsRound(sp.p85)}"
            // Near the right edge, the label goes on the line's left.
            if (qx + 3 + Pdf.textWidth(label, 7.5) > px + pw) pdf.text(qx - 3, y + 16, label, size = 7.5, color = INK, align = Align.RIGHT)
            else pdf.text(qx + 3, y + 16, label, size = 7.5, color = INK)
        }
        return y + h + 22
    }
}
