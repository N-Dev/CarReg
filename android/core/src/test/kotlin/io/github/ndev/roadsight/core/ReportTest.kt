package io.github.ndev.roadsight.core

import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Pdf
import io.github.ndev.roadsight.core.traffic.Report
import io.github.ndev.roadsight.core.traffic.ReportSession
import io.github.ndev.roadsight.core.traffic.Stats
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ported from the web app's tests/count.test.mjs (the PDF writer and the council report). */
class ReportTest {
    private fun rng(seed: Long): () -> Double {
        var s = seed and 0xFFFFFFFFL
        return {
            s = (s * 1664525L + 1013904223L) and 0xFFFFFFFFL
            s / 4294967296.0
        }
    }

    /** Checks the file's structure (header, xref offsets, trailer) and returns it as text. */
    private fun pdfObjects(bytes: ByteArray): String {
        val s = String(bytes, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4") && s.trimEnd().endsWith("%%EOF"))
        val start = Regex("startxref\n(\\d+)").find(s)!!.groupValues[1].toInt()
        assertEquals("xref", s.substring(start, start + 4), "startxref points at the xref table")
        val m = Regex("xref\n(\\d+) (\\d+)").find(s.substring(start))!!
        assertEquals(0, m.groupValues[1].toInt())
        val n = m.groupValues[2].toInt()
        val entries = s.substring(start).split("\n").subList(2, 2 + n)
        entries.drop(1).forEachIndexed { i, e ->
            assertTrue(Regex("^\\d{10} 00000 n $").matches(e), "xref entry: '$e'")
            assertTrue(s.substring(e.substring(0, 10).toInt()).startsWith("${i + 1} 0 obj"), "object ${i + 1} is where the xref says")
        }
        return s
    }

    @Test
    fun pdfStructureEscapingAndWidths() {
        val p = Pdf("Test (1)")
        p.text(40.0, 60.0, "Hello (world) \\ Dún Laoghaire – 50 km/h", size = 12.0)
        p.rect(40.0, 80.0, 100.0, 20.0, fill = "#2a78d6")
        p.column(40.0, 120.0, 20.0, 50.0, "#eb6834")
        p.line(40.0, 200.0, 200.0, 200.0, dash = doubleArrayOf(3.0, 2.0))
        val s = pdfObjects(p.bytes())
        assertTrue(s.contains("(Hello \\(world\\) \\\\ D\\372n Laoghaire \\226 50 km/h) Tj"), "brackets, backslash, fada and dash encoded")
        assertTrue(abs(Pdf.textWidth("Hello", 10.0) - 22.78) < 0.01)
        assertEquals("0.5", Pdf.num(0.5))
        assertEquals("841.89", Pdf.num(841.89))
        assertEquals("12", Pdf.num(12.004))
        assertEquals("0", Pdf.num(-0.001))
    }

    private fun sampleEvents(): Triple<List<Event>, Long, Long> {
        val zone = ZoneId.systemDefault()
        val t0 = LocalDateTime.of(2026, 9, 29, 7, 0).atZone(zone).toInstant().toEpochMilli()
        val r = rng(11)
        val kinds = listOf("car", "car", "car", "car", "truck", "bus", "bicycle", "person", "dog")
        val events = List(400) {
            val t = t0 + (r() * 5 * 3_600_000).toLong()
            val kind = kinds[(r() * kinds.size).toInt()]
            val dir = if (r() < 0.5) 1 else 2
            val speed = 25 + r() * 40
            val length = 4 + r() * 8
            Event(t, kind, dir, speed, length)
        }
        return Triple(events, t0, t0 + 5 * 3_600_000L)
    }

    @Test
    fun reportIsOnePageWithTheFigures() {
        val (events, from, to) = sampleEvents()
        val session = ReportSession("Main Street, outside no. 12", 20.0, 50, listOf("Towards the village", "Towards the N11"))
        val sum = Stats.summarize(events, from, to, 50)
        val bytes = Report.council(session, sum, release = "1.0", build = "12")
        val s = pdfObjects(bytes)
        assertEquals(1, Regex("/Type /Page ").findAll(s).count(), "one page")
        for (text in listOf("Traffic survey", "Main Street, outside no. 12", "Towards the village", "85th percentile", "No images, video or number plates", "RoadSight 1.0 \\(build 12\\)")) {
            assertTrue(s.contains(text), "mentions $text")
        }
        assertTrue(s.contains("(${Report.fmt(sum.motor)}) Tj"), "the motor-vehicle total is on it")
        // For looking at: build/report-sample.pdf
        runCatching { File("build").mkdirs(); File("build/report-sample.pdf").writeBytes(bytes) }
    }

    @Test
    fun reportTextHelpers() {
        val zone = ZoneId.of("Europe/Dublin")
        val t = LocalDateTime.of(2026, 9, 29, 7, 10).atZone(zone).toInstant().toEpochMilli()
        assertEquals("Tue 29 Sep 2026, 07:10", Report.whenText(t, zone))
        assertEquals("Tue 29 Sep 2026, 07:10 to 19:45", Report.period(t, t + (12 * 60 + 35) * 60_000L, zone))
        assertEquals("Tue 29 Sep 2026, 07:10 to Wed 30 Sep 2026, 01:10", Report.period(t, t + 18 * 3_600_000L, zone))
        assertEquals("1 h 05 min", Report.duration(1 + 5 / 60.0))
        assertEquals("25 min", Report.duration(25 / 60.0))
        assertEquals("1,234", Report.fmt(1234))
        assertEquals("12.5", Report.fmt(12.5))
        assertEquals("traffic-main-street-no-12-2026-09-29", Report.baseName("Main Street, no. 12", t, zone))
        assertEquals("traffic-survey-2026-09-29", Report.baseName("", t, zone))
        val wrapped = Report.wrap2("Towards the village of Kilcullen", 50.0, 8.0)
        assertEquals(2, wrapped.size)
        assertEquals("Towards the", wrapped[0])
        assertTrue(wrapped[1].startsWith("village") && wrapped[1].endsWith("…") && Pdf.textWidth(wrapped[1], 8.0, true) <= 50, wrapped[1])
        assertEquals(5.0, Report.niceStep(17.0))
        assertEquals(1.0, Report.niceStep(2.0))
        assertEquals(50.0, Report.niceStep(180.0))
    }
}
