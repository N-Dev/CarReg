package io.github.ndev.roadsight.core

import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.core.plate.PlateDet
import io.github.ndev.roadsight.core.plate.Read
import io.github.ndev.roadsight.core.plate.Track
import io.github.ndev.roadsight.core.plate.Tracker
import io.github.ndev.roadsight.core.plate.TrackerOptions
import io.github.ndev.roadsight.core.plate.editDistance
import io.github.ndev.roadsight.core.plate.similar
import io.github.ndev.roadsight.core.plate.vote
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the web app's tests/logic.test.mjs: the same behaviour, checked the same way. */
class PlateLogicTest {
    @Test
    fun irishPlates() {
        assertEquals("241-D-12345", Formats.validateIE("241D12345").text)
        assertEquals("241-D-12345", Formats.validateIE("24lD12345").text) // l -> 1 in the year
        assertEquals("241-DL-2345", Formats.validateIE("24lDl2345").text) // ambiguous: fewest changes wins (Donegal)
        assertEquals("241-D-10345", Formats.validateIE("24lD1O345").text) // O -> 0 in the number
        assertEquals("191-KE-123", Formats.validateIE("191KE123").text)
        assertEquals("06-D-12345", Formats.validateIE("06D12345").text) // pre-2013 two-digit year
        assertEquals("131-CE-7", Formats.validateIE("131CE7").text)
        assertTrue(Formats.validateIE("24ID12345").valid) // I read as 1 in the year
        assertFalse(Formats.validateIE("ABC123").valid)
        assertFalse(Formats.validateIE("243D12345").valid) // half-year must be 1 or 2
        assertFalse(Formats.validateIE("241XX123").valid) // not a county
        val d = Formats.decodeIE("241D12345")!!
        assertEquals(listOf<Any?>(2024, "Jan–Jun", "Dublin", "Baile Átha Cliath"), listOf(d.year, d.period, d.county, d.countyGa))
        assertEquals(2006, Formats.decodeIE("06D12345")!!.year)
    }

    @Test
    fun ukAndNorthernIrishPlates() {
        assertEquals("AB12 CDE", Formats.validateUK("AB12CDE").text)
        assertEquals("AB12 CDE", Formats.validateUK("A812CDE").text) // 8 -> B in a letter slot
        assertEquals("AB12 CDE", Formats.validateUK("AB1ZCDE").text) // Z -> 2 in a digit slot
        assertEquals("AIZ 1234", Formats.validateUK("AIZ1234").text) // NI
        assertTrue(Formats.validateUK("LAZ12").valid)
        assertTrue(Formats.validateUK("IA1234").valid) // 2-letter NI code
        assertFalse(Formats.validateUK("ABC1234").valid) // not an NI code, not GB
        assertEquals("A123 BCD", Formats.validateUK("A123BCD").text) // prefix era
        assertFalse(Formats.validateUK("ZZ12CDE").valid) // Z is never an area letter
        val d = Formats.decodeUK("AB62CDE")!!
        assertEquals(listOf<Any?>("Anglia", 2012, "Sep–Feb"), listOf(d.area, d.year, d.period))
        assertEquals("Northern Ireland", Formats.decodeUK("LAZ12")!!.area)
    }

    @Test
    fun profilesAndFlags() {
        assertEquals("IE", Formats.profileFor("auto", "Ireland"))
        assertEquals("UK", Formats.profileFor("auto", "United Kingdom"))
        assertEquals("ANY", Formats.profileFor("auto", "Czech Republic"))
        assertEquals("UK", Formats.profileFor("UK", "Ireland"))
        assertTrue(Formats.validate("5AU5341", "ANY").valid)
        assertEquals("🇮🇪", Formats.flagFor("Ireland"))
        assertEquals("IRL", Formats.bandFor("Ireland"))
        assertEquals("UK", Formats.bandFor("United Kingdom"))
    }

    private fun r(text: String, conf: Double = 0.95, region: String = "Ireland", regionProb: Double = 0.99) = Read(text, conf, region = region, regionProb = regionProb)
    private fun p(text: String, region: String = "United Kingdom") = r(text, 0.95, region).copy(partial = true)

    @Test
    fun votingFixesNoisyFramesAndPicksTheCountry() {
        val v = vote(listOf(r("241D12345"), r("241D12845", 0.7), r("241D12345"), r("24lD12345", 0.8), r("241012345", 0.6), r("241D12345")))!!
        assertEquals("241-D-12345", v.text)
        assertTrue(v.valid)
        assertEquals("Ireland", v.region)
        assertTrue(v.conf > 0.5, "conf ${v.conf}")
        assertEquals("Dublin", v.info!!.county)
    }

    @Test
    fun votingIgnoresLowConfidenceReadsAndUnknownRegions() {
        assertNull(vote(listOf(r("ABC", 0.1))))
        val v = vote(listOf(r("AB12CDE", 0.9, "Unknown", 0.9), r("AB12CDE", 0.9, "United Kingdom", 0.6)))!!
        assertEquals("United Kingdom", v.region)
        assertEquals("AB12 CDE", v.text)
    }

    @Test
    fun trackerKeepsOneIdentityPerMovingPlateAndConfirmsAfterAgreement() {
        val tr = Tracker(TrackerOptions(format = "auto"))
        val confirmed = ArrayList<Track>()
        for (f in 0 until 5) {
            val x = 100.0 + f * 12 // plate drifting right
            val out = tr.update(
                listOf(
                    PlateDet(doubleArrayOf(x, 200.0, x + 120, 230.0), 0.9, r("241D12345")),
                    PlateDet(doubleArrayOf(600.0, 300.0, 700.0, 325.0), 0.8, r("AB12CDE", 0.9, "United Kingdom", 0.9)),
                ),
                f * 100.0,
            )
            confirmed.addAll(out.confirmed)
        }
        assertEquals(2, tr.tracks.size)
        assertEquals(listOf("241-D-12345", "AB12 CDE"), confirmed.map { it.result!!.text }.sorted())
        val lost = tr.update(emptyList(), 5000.0).lost
        assertEquals(2, lost.size)
        assertEquals(0, tr.tracks.size)
    }

    @Test
    fun singleReadIsNeverConfirmedOnItsOwn() {
        val tr = Tracker()
        assertEquals(0, tr.update(listOf(PlateDet(doubleArrayOf(0.0, 0.0, 100.0, 25.0), 0.9, r("241D12345"))), 0.0).confirmed.size)
    }

    @Test
    fun editDistanceAndSimilarity() {
        assertEquals(0, editDistance("12D15405", "12D15405"))
        assertEquals(1, editDistance("12D15405", "12D15406"))
        assertTrue(similar("241D12345", "241D12845"))
        assertFalse(similar("12D15405", "201D8573"))
    }

    @Test
    fun votesNeverBlendTwoDifferentPlatesIntoAThird() {
        val reads = List(6) { r("12D15405") } + List(4) { r("201D8573") }
        val v = vote(reads)!!
        assertEquals("12-D-15405", v.text)
        assertTrue(v.conf < 0.7, "mixed reads must lower agreement, got ${v.conf}")
    }

    @Test
    fun aDifferentPlateInTheSameSpotStartsANewTrack() {
        val tr = Tracker(TrackerOptions(format = "auto"))
        val box = doubleArrayOf(300.0, 600.0, 520.0, 660.0)
        var t = 0.0
        val confirmed = ArrayList<Track>()
        val lost = ArrayList<Track>()
        for (f in 0 until 4) {
            val out = tr.update(listOf(PlateDet(box, 0.9, r("12D15405"), if (f == 1) ({ "A" }) else null)), t)
            confirmed.addAll(out.confirmed)
            lost.addAll(out.lost)
            t += 100
        }
        // The user re-centres on the next car: same position, different plate.
        for (f in 0 until 4) {
            val out = tr.update(listOf(PlateDet(box, 0.9, r("201D8573"), if (f == 1) ({ "B" }) else null)), t)
            confirmed.addAll(out.confirmed)
            lost.addAll(out.lost)
            t += 100
        }
        assertEquals(listOf("12-D-15405", "201-D-8573"), confirmed.map { it.result!!.text })
        assertEquals(1, lost.size, "the first car is finished as soon as the second plate replaces it")
        assertEquals("12-D-15405", lost[0].result!!.text)
        assertEquals(1.0, lost[0].result!!.conf, "no reads of the second car leaked into the first")
        assertEquals("A", lost[0].thumbFor(lost[0].result!!.key)!!.image)
        assertEquals("B", tr.tracks[0].thumbFor(tr.tracks[0].result!!.key)!!.image)
    }

    @Test
    fun anUnsureReadDoesNotSplitATrack() {
        val tr = Tracker()
        val box = doubleArrayOf(0.0, 0.0, 200.0, 50.0)
        for (f in 0 until 3) tr.update(listOf(PlateDet(box, 0.9, r("12D15405"))), f * 100.0)
        tr.update(listOf(PlateDet(box, 0.9, r("201D8573", 0.5))), 400.0) // blurry frame, low confidence
        assertEquals(1, tr.tracks.size)
        assertEquals("12-D-15405", tr.tracks[0].result!!.text)
    }

    @Test
    fun aPlateEnteringCutOffIsReadAsOnePlateOnceFullyVisible() {
        val tr = Tracker()
        val confirmed = ArrayList<Track>()
        fun box(x: Double) = doubleArrayOf(x, 300.0, x + 200, 350.0)
        var t = 0.0
        for (read in listOf(p("AB12"), p("AB12"), p("AB12C"))) {
            t += 100
            confirmed.addAll(tr.update(listOf(PlateDet(box(700 - t / 10), 0.9, read)), t).confirmed)
        }
        assertEquals(0, confirmed.size, "never confirmed from partial reads")
        for (f in 0 until 3) {
            t += 100
            confirmed.addAll(tr.update(listOf(PlateDet(box(690 - t / 10), 0.9, r("AB12CDE", 0.95, "United Kingdom"))), t).confirmed)
        }
        assertEquals(1, tr.tracks.size)
        assertEquals(listOf("AB12 CDE"), confirmed.map { it.result!!.text })
    }

    @Test
    fun aPlateLeavingTheFrameKeepsItsFullReading() {
        val tr = Tracker()
        val box = doubleArrayOf(0.0, 300.0, 200.0, 350.0)
        for (f in 0 until 3) tr.update(listOf(PlateDet(box, 0.9, r("241D12345"))), f * 100.0)
        tr.update(listOf(PlateDet(doubleArrayOf(0.0, 300.0, 120.0, 350.0), 0.8, p("D12345", "Denmark"))), 300.0)
        tr.update(listOf(PlateDet(doubleArrayOf(0.0, 300.0, 90.0, 350.0), 0.8, p("12345", "Denmark"))), 400.0)
        assertEquals(1, tr.tracks.size)
        assertEquals("241-D-12345", tr.tracks[0].result!!.text)
    }

    @Test
    fun aPlateOnlyEverSeenCutOffIsNeverConfirmed() {
        val tr = Tracker(TrackerOptions(format = "ANY"))
        val out = ArrayList<Track>()
        for (f in 0 until 6) out.addAll(tr.update(listOf(PlateDet(doubleArrayOf(0.0, 0.0, 90.0, 40.0), 0.9, p("D12345", "Denmark"))), f * 100.0).confirmed)
        out.addAll(tr.flush().filter { it.confirmed })
        assertEquals(0, out.size)
    }

    @Test
    fun jsonRoundTrip() {
        val m = Json.obj("""{"a": [1, 2.5, "x\"y"], "b": {"c": null, "d": true}, "e": "Dún"}""")
        assertEquals(listOf(1.0, 2.5, "x\"y"), m["a"])
        assertEquals(m, Json.obj(Json.write(m)))
    }
}
