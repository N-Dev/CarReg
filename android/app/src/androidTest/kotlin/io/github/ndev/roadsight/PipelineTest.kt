package io.github.ndev.roadsight

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.ndev.roadsight.ai.Accel
import io.github.ndev.roadsight.ai.BitmapFrame
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.ai.RunConfig
import io.github.ndev.roadsight.core.image.IntRect
import io.github.ndev.roadsight.core.plate.PlatePipeline
import io.github.ndev.roadsight.core.plate.vote
import io.github.ndev.roadsight.core.traffic.VehicleDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The AI models on this device through the app's own engine: the same answers as on the web and the JVM. */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val app: App get() = ApplicationProvider.getApplicationContext()

    @Test
    fun liveModelsReadTheSamplePlate() {
        val frame = BitmapFrame(Shots.sample("car_ie.jpg"))
        val pipe = PlatePipeline(app.engine.ocrConfig)
        for ((det, ocr) in listOf(Model.DET384 to Model.OCR_FAST, Model.DET640 to Model.OCR_ACC)) {
            val finder = app.engine.net(det)
            val reader = app.engine.net(ocr)
            pipe.analyze(frame, finder, det.size, reader, ocr.key, 0.3) // warm-up
            val res = pipe.analyze(frame, finder, det.size, reader, ocr.key, 0.3)
            val best = res.boxes.mapNotNull { b -> b.reads?.let { vote(it) } }.maxByOrNull { it.score }
            Shots.log("${det.key} + ${ocr.key}: ${best?.text} · find ${res.msFind.toInt()} ms, read ${res.msRead.toInt()} ms (${app.engine.configFor(det).label})")
            assertEquals("241D12345", best?.key)
        }
    }

    @Test
    fun photoModeReadsBothPlates() {
        val frame = BitmapFrame(Shots.sample("two_cars.jpg"))
        val res = PlatePipeline(app.engine.ocrConfig).analyze(
            frame, app.engine.net(Model.DET640), 640, app.engine.net(Model.OCR_ACC), "ocrAcc", 0.3,
            maxPlates = 12, minW = 14.0, variants = PlatePipeline.TTA, deep = true,
        )
        val found = res.boxes.mapNotNull { b -> b.reads?.let { vote(it, "auto", 0.2) } }.filter { it.key.length >= 3 && it.prob >= 0.35 }
        Shots.log("photo: ${found.map { "${it.text} (${it.region})" }} in ${res.msTotal.toInt()} ms")
        val texts = found.map { it.text }
        assertTrue(texts.toString(), "241-D-12345" in texts && "AB12 CDE" in texts)
    }

    @Test
    fun trafficModelsFindTheCars() {
        val frame = BitmapFrame(Shots.sample("two_cars.jpg"))
        for (m in listOf(Model.VEH_TINY, Model.VEH_NANO)) {
            val det = VehicleDetector()
            val net = app.engine.net(m)
            val all = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
            det.detect(frame, net, all, 0.3) // warm-up
            val res = det.detect(frame, net, all, 0.3)
            val cars = res.dets.filter { it.cls == "car" || it.cls == "truck" || it.cls == "bus" }
            Shots.log("${m.key}: ${cars.size} vehicles · AI ${res.msInfer.toInt()} ms, picture ${res.msPrep.toInt()} ms")
            assertTrue(res.dets.map { it.cls }.toString(), cars.size >= 2)
        }
    }

    @Test
    fun acceleratorsRunOrFallBack() {
        val frame = BitmapFrame(Shots.sample("car_ie.jpg"))
        val pipe = PlatePipeline(app.engine.ocrConfig)
        val all = IntRect(0, 0, frame.width, frame.height)
        for (cfg in listOf(RunConfig(Accel.CPU, 2), RunConfig(Accel.XNNPACK, 2), RunConfig(Accel.NNAPI, 2))) {
            val r = runCatching {
                app.engine.create(Model.DET384, cfg).use { n ->
                    pipe.detect(frame, n, 384, 0.35, all)
                    val t0 = System.nanoTime()
                    val boxes = pipe.detect(frame, n, 384, 0.35, all)
                    "${boxes.size} plate(s) in ${(System.nanoTime() - t0) / 1_000_000} ms"
                }
            }
            Shots.log("plate finder on ${cfg.label}: ${r.getOrElse { "not available (${it.message?.take(80)})" }}")
            // NNAPI depends on the device; the app falls back to the CPU without it.
            if (cfg.accel != Accel.NNAPI) assertTrue(r.exceptionOrNull()?.toString() ?: "", r.isSuccess)
        }
    }
}
