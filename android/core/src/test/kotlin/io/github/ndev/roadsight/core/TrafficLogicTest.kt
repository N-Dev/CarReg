package io.github.ndev.roadsight.core

import io.github.ndev.roadsight.core.traffic.Counter
import io.github.ndev.roadsight.core.traffic.Det
import io.github.ndev.roadsight.core.traffic.Event
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.core.traffic.Stats
import io.github.ndev.roadsight.core.traffic.TrafficRecord
import io.github.ndev.roadsight.core.traffic.crossing
import io.github.ndev.roadsight.core.traffic.dropRiders
import io.github.ndev.roadsight.core.traffic.gapAlong
import io.github.ndev.roadsight.core.traffic.regionFor
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the web app's tests/count.test.mjs. */
class TrafficLogicTest {
    // Frame 1280 x 720 (aspect 9/16). Lines A at x = 0.35 and B at x = 0.65, 20 m apart, so one
    // frame width is 66.7 m along the road.
    private val aspect = 9.0 / 16
    private val lines = Lines(doubleArrayOf(0.35, 0.3, 0.35, 0.95), doubleArrayOf(0.65, 0.3, 0.65, 0.95))
    private val mPerWidth = 20 / 0.3

    private class Mover(
        val cls: String, val kmh: Double, val dir: Int, val lengthM: Double, val y: Double, val h: Double, val start: Double,
        val hideFrom: Double? = null, val hideTo: Double? = null,
    )

    private class Frame(val t: Double, val dets: List<Det>)

    private fun rng(seed: Int): () -> Double {
        var s = seed.toLong() and 0xffffffffL
        return {
            s = (s * 1664525 + 1013904223) and 0xffffffffL
            s / 4294967296.0
        }
    }

    private fun scene(movers: List<Mover>, fps: Double = 9.0, ms: Double = 12000.0, jitter: Double = 0.0015, seed: Int = 7): List<Frame> {
        val r = rng(seed)
        val frames = ArrayList<Frame>()
        var t = 0.0
        while (t <= ms) {
            val dets = ArrayList<Det>()
            for (m in movers) {
                if (t < m.start) continue
                val v = ((m.kmh / 3.6) / mPerWidth / 1000) * m.dir
                val w = m.lengthM / mPerWidth
                val cx = (if (m.dir > 0) -w / 2 else 1 + w / 2) + v * (t - m.start)
                if (m.hideFrom != null && t >= m.hideFrom && t < m.hideTo!!) continue
                val x1 = maxOf(0.0, cx - w / 2)
                val x2 = minOf(1.0, cx + w / 2)
                if (x2 - x1 < 0.003) continue
                fun j() = (r() - 0.5) * 2 * jitter
                dets.add(Det(m.cls, 0.8, doubleArrayOf(x1 + j(), m.y - m.h + j(), x2 + j(), m.y + j())))
            }
            frames.add(Frame(t, dets))
            t += 1000 / fps
        }
        return frames
    }

    private fun count(frames: List<Frame>): List<TrafficRecord> {
        val c = Counter(lines, 20.0, aspect)
        val records = ArrayList<TrafficRecord>()
        for (f in frames) records.addAll(c.update(f.dets, f.t).done)
        records.addAll(c.flush())
        return records
    }

    private fun near(a: Double?, b: Double, tol: Double) = a != null && abs(a - b) <= tol

    @Test
    fun aCarCrossingBothLinesIsCountedOnceWithDirectionSpeedAndLength() {
        val recs = count(scene(listOf(Mover("car", 50.0, 1, 4.5, 0.7, 0.12, 0.0))))
        assertEquals(1, recs.size, recs.toString())
        val r = recs[0]
        assertEquals("car", r.kind)
        assertEquals(1, r.dir)
        assertTrue(near(r.speed, 50.0, 2.0), "speed ${r.speed}")
        assertTrue(near(r.length, 4.5, 0.6), "length ${r.length}")
    }

    @Test
    fun theOtherWayIsDirection2AndALorryIsLong() {
        val recs = count(scene(listOf(Mover("truck", 38.0, -1, 11.0, 0.55, 0.2, 0.0))))
        assertEquals(1, recs.size)
        assertEquals(2, recs[0].dir)
        assertTrue(near(recs[0].speed, 38.0, 2.0), "speed ${recs[0].speed}")
        assertTrue((recs[0].length ?: 0.0) > 9.5, "length ${recs[0].length}")
    }

    @Test
    fun speedsStayRightAtALowFrameRate() {
        for (kmh in listOf(30.0, 60.0, 90.0)) {
            val recs = count(scene(listOf(Mover("car", kmh, 1, 4.5, 0.7, 0.12, 0.0)), fps = 4.0))
            assertEquals(1, recs.size, "$kmh km/h: $recs")
            assertTrue(near(recs[0].speed, kmh, kmh * 0.05), "$kmh km/h measured ${recs[0].speed}")
        }
    }

    @Test
    fun hiddenForAMomentBetweenTheLinesIsStillOneVehicleWithASpeed() {
        val recs = count(scene(listOf(Mover("car", 45.0, 1, 4.2, 0.8, 0.12, 0.0, 2000.0, 2700.0))))
        assertEquals(1, recs.size, recs.toString())
        assertTrue(near(recs[0].speed, 45.0, 2.5), "speed ${recs[0].speed}")
    }

    @Test
    fun aParkedCarOrSomeoneStandingOnALineIsNeverCounted() {
        val r = rng(3)
        val frames = ArrayList<Frame>()
        var t = 0.0
        while (t < 20000) {
            fun j() = (r() - 0.5) * 0.02
            frames.add(Frame(t, listOf(
                Det("car", 0.9, doubleArrayOf(0.30 + j(), 0.55, 0.40 + j(), 0.7)),
                Det("person", 0.8, doubleArrayOf(0.645 + j(), 0.5, 0.657 + j(), 0.72)),
            )))
            t += 111
        }
        assertEquals(0, count(frames).size)
    }

    @Test
    fun followingVehiclesBothDirectionsAndEveryKind() {
        val recs = count(scene(listOf(
            Mover("car", 48.0, 1, 4.4, 0.75, 0.12, 0.0),
            Mover("car", 48.0, 1, 4.4, 0.75, 0.12, 1300.0),
            Mover("bus", 40.0, -1, 11.5, 0.55, 0.22, 500.0),
            Mover("bicycle", 18.0, 1, 1.8, 0.82, 0.1, 0.0),
            Mover("person", 5.0, -1, 0.6, 0.93, 0.2, 0.0),
            Mover("dog", 5.0, -1, 0.8, 0.935, 0.06, 300.0),
        ), ms = 26000.0))
        assertEquals(listOf("bicycle:1", "bus:2", "car:1", "car:1", "dog:2", "person:2"), recs.map { "${it.kind}:${it.dir}" }.sorted(), recs.toString())
        assertTrue(near(recs.first { it.kind == "bicycle" }.speed, 18.0, 1.5))
    }

    @Test
    fun aCyclistCountsAsABicycleNotAlsoAsAPedestrian() {
        val dets = listOf(
            Det("bicycle", 0.8, doubleArrayOf(0.4, 0.6, 0.48, 0.8)),
            Det("person", 0.8, doubleArrayOf(0.415, 0.45, 0.465, 0.75)),
            Det("person", 0.8, doubleArrayOf(0.7, 0.5, 0.72, 0.9)),
        )
        assertEquals(listOf("bicycle", "person"), dropRiders(dets).map { it.cls })
    }

    @Test
    fun crossingOnlyOneLineCountsWithoutASpeed() {
        val recs = count(scene(listOf(Mover("car", 30.0, 1, 4.5, 0.7, 0.12, 0.0, 3200.0, 1e9))))
        assertEquals(1, recs.size)
        assertNull(recs[0].speed)
        assertNull(recs[0].length)
    }

    @Test
    fun linesInterpolateCrossingTimesAndAllowForPerspective() {
        val c = crossing(doubleArrayOf(0.5, 0.0, 0.5, 1.0), doubleArrayOf(0.4, 0.5), 1000.0, doubleArrayOf(0.6, 0.5), 1100.0)
        assertNotNull(c)
        assertTrue(near(c.t, 1050.0, 1e-6) && c.side == -1)
        assertNull(crossing(doubleArrayOf(0.5, 0.0, 0.5, 0.4), doubleArrayOf(0.4, 0.8), 0.0, doubleArrayOf(0.6, 0.8), 100.0))
        val a = doubleArrayOf(0.3, 0.9, 0.4, 0.3)
        val b = doubleArrayOf(0.8, 0.9, 0.6, 0.3)
        val nearGap = gapAlong(a, b, doubleArrayOf(0.5, 0.85), doubleArrayOf(1.0, 0.0))!!
        val farGap = gapAlong(a, b, doubleArrayOf(0.5, 0.35), doubleArrayOf(1.0, 0.0))!!
        assertTrue(nearGap > farGap * 1.5, "$nearGap vs $farGap")
        val (x, y, w, h) = regionFor(Lines.default(), aspect).toList()
        assertTrue(x < 0.25 && x + w > 0.75 && y < 0.3 && y + h >= 0.95)
    }

    @Test
    fun speedFigures() {
        assertEquals(25.0, Stats.percentile(listOf(10.0, 20.0, 30.0, 40.0), 50.0))
        assertEquals(44.0, Stats.percentile(listOf(10.0, 20.0, 30.0, 40.0, 50.0), 85.0))
        val s = Stats.speedStats(listOf(30.0, 32.0, 35.0, 38.0, 41.0, 44.0, 47.0, 52.0, 58.0, 63.0, null), 50)
        assertEquals(10, s.n)
        assertEquals(3, s.over)
        assertEquals(30.0, s.overPct)
        assertEquals(1, s.over10)
        assertEquals(63.0, s.max)
        assertTrue(near(s.p85, 55.9, 0.05), "p85 ${s.p85}")
        assertEquals(listOf(1, 2, 1), Stats.histogram(listOf(2.0, 7.0, 7.5, 12.0)).map { it.n })
    }

    @Test
    fun totalsPerHourBusiestHourAndLongVehicles() {
        val zone = ZoneId.of("Europe/Dublin")
        val t0 = LocalDateTime.of(2026, 9, 29, 7, 30).atZone(zone).toInstant().toEpochMilli()
        val min = 60_000L
        val events = listOf(
            Event(t0 + 5 * min, "car", 1, 44.0, 4.5),
            Event(t0 + 35 * min, "car", 2, 56.0, 4.4),
            Event(t0 + 40 * min, "truck", 1, 38.0, 11.2),
            Event(t0 + 50 * min, "bus", 2, null, null),
            Event(t0 + 55 * min, "person", 1, 5.0, 0.5),
            Event(t0 + 95 * min, "bicycle", 2, 21.0, 1.7),
        )
        val s = Stats.summarize(events, t0, t0 + 100 * min, 50, zone)
        assertEquals(4, s.motor, "people and bicycles are not motor vehicles")
        assertEquals(listOf(2, 1, 1), s.totals["car"]!!.toList())
        assertEquals(6, s.all)
        assertEquals(listOf(7, 8, 9), s.perHour.map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).hour })
        assertEquals(listOf(1, 3, 0), s.perHour.map { it.motor })
        assertEquals(8, java.time.Instant.ofEpochMilli(s.peak!!.start).atZone(zone).hour)
        assertEquals(1, s.long)
        assertEquals(3, s.speedsAll.n)
        assertEquals(1, s.speedsAll.over)
        assertEquals(2, s.speeds1.n)
        assertEquals(1, s.bikeSpeeds.n)
    }

    @Test
    fun csvOneRowPerRoadUser() {
        val zone = ZoneId.of("Europe/Dublin")
        val t = LocalDateTime.of(2026, 9, 29, 8, 5, 9).atZone(zone).toInstant().toEpochMilli()
        val csv = Stats.toCSV(listOf(Event(t, "truck", 2, 41.5, null)), listOf("To the village", "To the N11, \"main\" road"), "Main St", zone)
        val rows = csv.trim().split("\r\n")
        assertEquals("time,type,direction,speed_kmh,length_m,site", rows[0])
        assertEquals("2026-09-29 08:05:09,van or truck,\"To the N11, \"\"main\"\" road\",41.5,,Main St", rows[1])
    }
}
