package io.github.ndev.roadsight.core

import io.github.ndev.roadsight.core.plate.OcrConfig
import io.github.ndev.roadsight.core.plate.PlatePipeline
import io.github.ndev.roadsight.core.plate.PlateResult
import io.github.ndev.roadsight.core.plate.vote
import io.github.ndev.roadsight.core.traffic.Counter
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.core.traffic.TrafficRecord
import io.github.ndev.roadsight.core.traffic.VehicleDetector
import io.github.ndev.roadsight.core.traffic.regionFor
import org.junit.Test
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The real models on the test photos, through the same pipeline code the app runs. */
class ModelsTest {
    private val ocr = OcrConfig.parse(Repo.file("models/ocr.json").readText())
    private val det640 get() = Nets.load("models/plate-detector-640.onnx")
    private val det384 get() = Nets.load("models/plate-detector-384.onnx")
    private val ocrAcc get() = Nets.load("models/plate-ocr-accurate.onnx")
    private val ocrFast get() = Nets.load("models/plate-ocr-fast.onnx")

    /** Photo mode: the 640 finder, the accurate reader, three crops voted, deep scan if needed. */
    private fun photo(name: String): List<PlateResult> {
        val frame = AwtFrame(Repo.image(name))
        val res = PlatePipeline(ocr).analyze(frame, det640, 640, ocrAcc, "ocrAcc", conf = 0.3, maxPlates = 12, minW = 14.0, variants = PlatePipeline.TTA, deep = true)
        return res.boxes.mapNotNull { b -> b.reads?.let { vote(it, "auto", 0.2) } }.filter { it.key.length >= 3 && it.prob >= 0.35 }
    }

    @Test
    fun photoModeReadsBothPlatesAndTheirCountries() {
        val found = photo("two_cars.jpg")
        val texts = found.map { it.text }
        assertTrue("241-D-12345" in texts && "AB12 CDE" in texts, texts.toString())
        assertEquals("Ireland", found.first { it.text == "241-D-12345" }.region)
        assertEquals("United Kingdom", found.first { it.text == "AB12 CDE" }.region)
        assertEquals("Dublin", found.first { it.text == "241-D-12345" }.info!!.county)
    }

    @Test
    fun photoModeReadsAUkPlate() {
        assertEquals(listOf("AB12 CDE"), photo("car_uk.jpg").map { it.text })
    }

    @Test
    fun liveModeReadsAPortraitFrameInItsCentreSquare() {
        // A portrait camera frame (1080 x 1920) with the car in the middle.
        val car = Repo.image("car_ie.jpg")
        val tall = BufferedImage(1080, 1920, BufferedImage.TYPE_INT_RGB)
        val g = tall.createGraphics()
        g.color = Color(40, 44, 52)
        g.fillRect(0, 0, 1080, 1920)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(car, 0, 960 - 345, 1080, 690, null)
        g.dispose()
        val res = PlatePipeline(ocr).analyze(AwtFrame(tall), det384, 384, ocrFast, "ocrFast", conf = 0.35, maxPlates = 6, minW = 40.0, portraitRoi = true)
        assertEquals(420, res.region.y, "the centre square of the frame is analysed")
        val reads = res.boxes.mapNotNull { it.reads }.flatten()
        val v = vote(reads, "auto")
        assertNotNull(v, "boxes ${res.boxes.map { it.box.toList() }}")
        assertEquals("241-D-12345", v.text)
        println("live frame: find ${"%.0f".format(res.msFind)} ms, read ${"%.0f".format(res.msRead)} ms")
    }

    @Test
    fun vehiclesAreFoundByBothModels() {
        for (m in listOf("tiny", "nano")) {
            val net = Nets.load("count/models/vehicles-$m.onnx")
            val res = VehicleDetector().detect(AwtFrame(Repo.image("two_cars.jpg")), net, doubleArrayOf(0.0, 0.0, 1.0, 1.0), 0.3)
            val cars = res.dets.filter { it.cls == "car" || it.cls == "truck" || it.cls == "bus" }
            assertTrue(cars.size >= 2, "$m: ${res.dets.map { it.cls + " " + "%.2f".format(it.score) }}")
            println("$m: ${res.dets.size} road users, AI ${"%.0f".format(res.msInfer)} ms")
        }
    }

    /**
     * The traffic counter end to end on a synthetic road (the same scene as the web app's browser
     * test): car A drives left to right at 160 px/s, then car B right to left at 240 px/s. With the
     * lines 288 px apart set to 20 m, that's 40 km/h and 60 km/h.
     */
    @Test
    fun countsAndTimesCarsOnASyntheticRoad() {
        val carA = scaled(Repo.image("car_ie.jpg").getSubimage(140, 125, 1220, 690), 300, 170)
        val carB = scaled(Repo.image("two_cars.jpg").getSubimage(1690, 118, 1190, 690), 220, 128)
        val lines = Lines(doubleArrayOf(0.35, 0.35, 0.35, 0.95), doubleArrayOf(0.65, 0.35, 0.65, 0.95))
        val aspect = 540.0 / 960
        val roi = regionFor(lines, aspect)
        val counter = Counter(lines, 20.0, aspect, roi = roi)
        val det = VehicleDetector()
        val net = Nets.load("count/models/vehicles-nano.onnx")
        val records = ArrayList<TrafficRecord>()
        val fps = 15
        for (i in 0 until 11 * fps) {
            val t = i.toDouble() / fps
            val img = BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.color = Color(0x9a, 0xa7, 0xb4)
            g.fillRect(0, 0, 960, 540)
            g.color = Color(0x3b, 0x3e, 0x44)
            g.fillRect(0, 250, 960, 220)
            g.color = Color(0xc9, 0xc4, 0xb8)
            g.fillRect(0, 470, 960, 70)
            g.color = Color(0xd8, 0xd9, 0xda)
            g.fillRect(0, 357, 960, 5)
            if (t >= 5.2) g.drawImage(carB, Math.round(960 - 240 * (t - 5.2)).toInt(), 350 - 128, null)
            g.drawImage(carA, Math.round(-300 + 160 * t).toInt(), 462 - 170, null)
            g.dispose()
            val res = det.detect(AwtFrame(img), net, roi, 0.3)
            records.addAll(counter.update(res.dets, t * 1000).done)
        }
        records.addAll(counter.flush())
        val east = records.firstOrNull { it.dir == 1 && it.speed != null }
        val west = records.firstOrNull { it.dir == 2 && it.speed != null }
        assertNotNull(east, records.toString())
        assertNotNull(west, records.toString())
        assertTrue(abs(east.speed!! - 40) <= 6, "east ${east.speed}")
        assertTrue(abs(west.speed!! - 60) <= 9, "west ${west.speed}")
        assertTrue(records.count { it.dir == 1 } <= 2 && records.count { it.dir == 2 } <= 2, records.toString())
        println("synthetic road: $records")
    }

    private fun scaled(src: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(src, 0, 0, w, h, null)
        g.dispose()
        return out
    }
}
