package io.github.ndev.roadsight.core

import io.github.ndev.roadsight.core.image.Yuv
import io.github.ndev.roadsight.core.traffic.Counted
import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Report
import io.github.ndev.roadsight.core.traffic.ReportSession
import io.github.ndev.roadsight.core.traffic.Stats
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Video frames to pixels, and figures and reports across several counting sessions. */
class VideoAndDaysTest {
    private fun r(p: Int) = (p shr 16) and 0xff
    private fun g(p: Int) = (p shr 8) and 0xff
    private fun b(p: Int) = p and 0xff

    /** A w x h frame as separate planes (I420), luma from `luma(x, y)`, one colour for all chroma. */
    private fun i420(w: Int, h: Int, u: Int, v: Int, luma: (Int, Int) -> Int): Triple<Yuv.Plane, Yuv.Plane, Yuv.Plane> {
        val y = ByteArray(w * h) { luma(it % w, it / w).toByte() }
        val cw = w / 2
        val ch = h / 2
        return Triple(Yuv.Plane(y, w, 1), Yuv.Plane(ByteArray(cw * ch) { u.toByte() }, cw, 1), Yuv.Plane(ByteArray(cw * ch) { v.toByte() }, cw, 1))
    }

    @Test
    fun coloursComeOutRight() {
        // Grey, and the BT.601 red and blue.
        for ((yuv, rgb) in listOf(intArrayOf(126, 128, 128) to intArrayOf(128, 128, 128), intArrayOf(81, 90, 240) to intArrayOf(255, 0, 0), intArrayOf(41, 240, 110) to intArrayOf(0, 0, 255))) {
            val (py, pu, pv) = i420(4, 2, yuv[1], yuv[2]) { _, _ -> yuv[0] }
            val out = IntArray(8)
            Yuv.toArgb(py, pu, pv, 0, 0, 4, 2, 1, 0, false, out)
            val p = out[0]
            assertTrue(abs(r(p) - rgb[0]) <= 3 && abs(g(p) - rgb[1]) <= 3 && abs(b(p) - rgb[2]) <= 3, "YUV ${yuv.toList()} gave ${r(p)},${g(p)},${b(p)}")
            assertEquals(0xff, (p ushr 24), "opaque")
        }
    }

    @Test
    fun framesTurnUprightAndShrink() {
        // Luma encodes the position: 16 + 10x + y, grey chroma.
        val w = 6
        val h = 4
        val (py, pu, pv) = i420(w, h, 128, 128) { x, y -> 16 + 10 * x + y }
        fun lumaAt(out: IntArray, ow: Int, x: Int, y: Int): Int = ((r(out[y * ow + x]) * 219 + 127) / 255) + 16
        for (rot in listOf(0, 90, 180, 270)) {
            val out = IntArray(w * h)
            val (ow, oh) = Yuv.toArgb(py, pu, pv, 0, 0, w, h, 1, rot, false, out)
            assertEquals(if (rot % 180 == 0) w to h else h to w, ow to oh)
            // Where the source's top-left pixel (x 0, y 0) and top-right (x 5, y 0) end up.
            val (tlx, tly) = when (rot) { 90 -> (h - 1) to 0; 180 -> (w - 1) to (h - 1); 270 -> 0 to (w - 1); else -> 0 to 0 }
            val (trx, try_) = when (rot) { 90 -> (h - 1) to (w - 1); 180 -> 0 to (h - 1); 270 -> 0 to 0; else -> (w - 1) to 0 }
            assertTrue(abs(lumaAt(out, ow, tlx, tly) - 16) <= 1, "rotation $rot: top-left")
            assertTrue(abs(lumaAt(out, ow, trx, try_) - 66) <= 1, "rotation $rot: top-right")
        }
        // Halved: each pixel is the average of a 2x2 block.
        val out = IntArray(6)
        val (ow, oh) = Yuv.toArgb(py, pu, pv, 0, 0, w, h, 2, 0, false, out)
        assertEquals(3 to 2, ow to oh)
        assertTrue(abs(lumaAt(out, ow, 1, 1) - (16 + 10 * 2 + 2 + 5)) <= 1, "block average") // x 2-3, y 2-3
        assertEquals(2, Yuv.factorFor(3840, 2160, 1920))
        assertEquals(1, Yuv.factorFor(1920, 1080, 1920))
        assertEquals(2, Yuv.factorFor(1920, 1080, 1280))
    }

    @Test
    fun interleavedChromaAndACropWork() {
        // NV12: U and V interleaved in one buffer (pixel stride 2), with a padded row stride and a crop.
        val w = 8
        val h = 4
        val stride = 12
        val y = ByteArray(stride * h) { 81.toByte() }
        val uv = ByteArray(stride * h / 2)
        for (i in 0 until uv.size / 2) {
            uv[2 * i] = 90.toByte()
            uv[2 * i + 1] = 240.toByte()
        }
        val u = Yuv.Plane(uv, stride, 2)
        val v = Yuv.Plane(uv.copyOfRange(1, uv.size), stride, 2)
        val out = IntArray(4 * 2)
        Yuv.toArgb(Yuv.Plane(y, stride, 1), u, v, 2, 2, 4, 2, 1, 0, false, out)
        for (p in out) assertTrue(r(p) > 240 && g(p) < 15 && b(p) < 15, "red: ${r(p)},${g(p)},${b(p)}")
    }

    private val zone: ZoneId = ZoneId.of("Europe/Dublin")

    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun severalDaysByDayAndByHourOfTheDay() {
        val d1 = LocalDate.of(2026, 9, 28)
        val d2 = LocalDate.of(2026, 9, 29)
        // Monday 07:00 to 10:00: 30 cars an hour 07-08, 60 08-09, 10 09-10. Tuesday 08:00 to 09:30: 40 in 08-09, 5 in the half hour after.
        val mon = ArrayList<Event>()
        for (i in 0 until 30) mon.add(Event(at(d1, 7) + i * 110_000L, "car", 1 + i % 2, 40.0 + i % 20, null))
        for (i in 0 until 60) mon.add(Event(at(d1, 8) + i * 55_000L, "car", 1, 45.0, null))
        for (i in 0 until 10) mon.add(Event(at(d1, 9) + i * 300_000L, "truck", 2, 55.0, 9.0))
        mon.add(Event(at(d1, 9, 30), "bicycle", 1, 20.0, null))
        val tue = ArrayList<Event>()
        for (i in 0 until 40) tue.add(Event(at(d2, 8) + i * 80_000L, "car", 2, 52.0, null))
        for (i in 0 until 5) tue.add(Event(at(d2, 9) + i * 300_000L, "car", 1, 38.0, null))
        tue.add(Event(at(d2, 9, 10), "person", 1, null, null))
        val d = Stats.summarizeDays(listOf(Counted(at(d1, 7), at(d1, 10), mon), Counted(at(d2, 8), at(d2, 9, 30), tue)), 50, zone)
        assertEquals(2, d.sessions)
        assertEquals(4.5, d.hours)
        assertEquals(145, d.all.motor)
        assertEquals(2, d.days.size)
        assertEquals(3.0, d.days[0].hours)
        assertEquals(100, d.days[0].motor)
        assertEquals(1, d.days[0].totals["bicycle"]!![0])
        assertEquals(1.5, d.days[1].hours)
        assertEquals(30.0, d.days[1].perHour)
        // 08:00 to 09:00 was counted on both days: (60 + 40) over 2 hours.
        assertEquals(2.0, d.profile[8].hours)
        assertEquals(50.0, d.profile[8].perHour)
        // 09:00 to 10:00: 1.5 hours counted, 15 vehicles.
        assertEquals(1.5, d.profile[9].hours)
        assertEquals(10.0, d.profile[9].perHour)
        assertEquals(null, d.profile[12].perHour)
        assertNotNull(d.peak)
        assertEquals(8, d.peak!!.hour)
        assertEquals(Math.round(145 / 4.5 * 10) / 10.0, d.perHourAvg)

        val sessions = listOf(
            ReportSession("Main Street", 20.0, 50, listOf("Towards the village", "Towards the N11")),
            ReportSession("Main Street", 20.0, 50, listOf("Towards the village", "Towards the N11"), fromVideo = true),
        )
        val bytes = Report.days(sessions, d, release = "1.0", build = "12", now = at(d2, 12), zone = zone)
        runCatching { File("build").mkdirs(); File("build/report-days-sample.pdf").writeBytes(bytes) }
        val s = String(bytes, Charsets.ISO_8859_1)
        assertEquals(2, Regex("/Type /Page ").findAll(s).count(), "two pages")
        for (text in listOf("Day by day", "Mon 28 Sep", "Tue 29 Sep", "Busiest time of day", "(08:00) Tj", "Towards the village", "85th percentile", "video recording", "2 sessions")) {
            assertTrue(s.contains(text), "mentions $text")
        }
        assertTrue(Report.method("RoadSight", listOf(sessions[1])).contains("analysed a video recording"))
        assertTrue(Report.method("RoadSight", listOf(sessions[0])).contains("No images, video or number plates were recorded or kept"))
    }
}
