package io.github.ndev.roadsight.ai

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.core.image.Frame
import io.github.ndev.roadsight.core.net.OrtNet
import io.github.ndev.roadsight.core.plate.OcrConfig
import io.github.ndev.roadsight.core.plate.PlateBox
import io.github.ndev.roadsight.core.plate.PlatePipeline
import io.github.ndev.roadsight.core.plate.vote
import io.github.ndev.roadsight.core.traffic.VehicleDetector
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.EnumSet

/** The AI models in the app (assets/models, copied from the web apps at build time). */
enum class Model(val key: String, val asset: String, val label: String, val size: Int) {
    DET384("det384", "models/plate-detector-384.onnx", "Plate finder (fast)", 384),
    DET640("det640", "models/plate-detector-640.onnx", "Plate finder (sharp)", 640),
    OCR_FAST("ocrFast", "models/plate-ocr-fast.onnx", "Plate reader (fast)", 0),
    OCR_ACC("ocrAcc", "models/plate-ocr-accurate.onnx", "Plate reader (accurate)", 0),
    VEH_NANO("vehNano", "models/vehicles-nano.onnx", "Traffic finder (light)", 416),
    VEH_TINY("vehTiny", "models/vehicles-tiny.onnx", "Traffic finder (standard)", 416),
    ;

    companion object {
        fun of(key: String): Model = entries.first { it.key == key }
    }
}

/** Where a model runs: ONNX Runtime's own CPU kernels, XNNPACK's (also CPU), or NNAPI (Android's route to the GPU and AI chip). */
enum class Accel(val label: String) {
    CPU("CPU"),
    XNNPACK("XNNPACK"),
    NNAPI("NNAPI"),
}

data class RunConfig(val accel: Accel, val threads: Int) {
    override fun toString() = "${accel.name}:$threads"
    val label: String get() = if (accel == Accel.NNAPI) "NNAPI" else "${accel.label}, $threads thread${if (threads == 1) "" else "s"}"

    companion object {
        fun parse(s: String?): RunConfig? {
            val p = s?.split(':') ?: return null
            if (p.size != 2) return null
            val a = runCatching { Accel.valueOf(p[0]) }.getOrNull() ?: return null
            val t = p[1].toIntOrNull() ?: return null
            return RunConfig(a, t)
        }
    }
}

/** Loads the models with ONNX Runtime, each with the setup that suits this phone. */
class Engine(private val app: App) {
    val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    val ocrConfig: OcrConfig by lazy { OcrConfig.parse(app.assets.open("models/ocr.json").bufferedReader().use { it.readText() }) }
    private val nets = HashMap<Model, Pair<RunConfig, OrtNet>>()

    /** Problems worth showing, e.g. "NNAPI isn't available, using the CPU". */
    val notice = MutableStateFlow<String?>(null)

    val cores: Int = Runtime.getRuntime().availableProcessors()
    val defaultThreads: Int = minOf(4, cores)

    fun tunedConfigs(): Map<String, RunConfig> = runCatching {
        Json.obj(app.prefs.tuned).mapNotNull { (k, v) -> RunConfig.parse(v as? String)?.let { k to it } }.toMap()
    }.getOrElse { emptyMap() }

    /**
     * The setup a model runs with: the one chosen in Settings, or the speed test's pick, or XNNPACK (about
     * twice as fast as ONNX Runtime's own CPU code in tests; it falls back to that if it can't run a model).
     */
    fun configFor(m: Model): RunConfig {
        val p = app.prefs
        val threads = if (p.threads > 0) p.threads else defaultThreads
        if (p.accel != "auto") {
            val a = runCatching { Accel.valueOf(p.accel) }.getOrDefault(Accel.CPU)
            return RunConfig(a, threads)
        }
        return tunedConfigs()[m.key] ?: RunConfig(Accel.XNNPACK, threads)
    }

    fun options(cfg: RunConfig): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        when (cfg.accel) {
            Accel.CPU -> o.setIntraOpNumThreads(cfg.threads)
            Accel.XNNPACK -> {
                // XNNPACK has its own thread pool; ONNX Runtime's is kept to one thread that doesn't spin.
                o.addXnnpack(mapOf("intra_op_num_threads" to cfg.threads.toString()))
                o.setIntraOpNumThreads(1)
                o.addConfigEntry("session.intra_op.allow_spinning", "0")
            }
            Accel.NNAPI -> {
                o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                o.setIntraOpNumThreads(cfg.threads)
            }
        }
        return o
    }

    fun modelBytes(m: Model): ByteArray = app.assets.open(m.asset).use { it.readBytes() }

    /** A new session for a model with a given setup (used by the speed test too). */
    fun create(m: Model, cfg: RunConfig, bytes: ByteArray = modelBytes(m)): OrtNet = OrtNet(env, env.createSession(bytes, options(cfg)))

    /** The loaded model, loading it (a second or so) the first time or after the setup changed. */
    @Synchronized
    fun net(m: Model): OrtNet {
        val cfg = configFor(m)
        val cur = nets[m]
        if (cur != null) {
            if (cur.first == cfg) return cur.second
            cur.second.close()
        }
        val bytes = modelBytes(m)
        val net = try {
            create(m, cfg, bytes)
        } catch (e: Throwable) {
            if (cfg.accel == Accel.CPU) throw e
            notice.value = "${cfg.accel.label} couldn’t run the ${m.label.lowercase()}, so it’s using the CPU"
            create(m, RunConfig(Accel.CPU, cfg.threads), bytes)
        }
        nets[m] = cfg to net
        return net
    }

    @Synchronized
    fun isLoaded(m: Model): Boolean = nets.containsKey(m)

    /** Frees models that aren't needed right now (the other screen's). */
    @Synchronized
    fun release(keep: Set<Model>) {
        val drop = nets.keys.filter { it !in keep }
        for (m in drop) nets.remove(m)?.second?.close()
    }

    fun releaseAll() = release(emptySet())

    /** Loads the plate reader's live models in the background at start-up. */
    fun warmUp() {
        net(Model.DET384)
        net(Model.OCR_FAST)
    }
}

/** An Android bitmap as a [Frame]: areas are cut and scaled with bilinear filtering on the CPU. */
class BitmapFrame(var bitmap: Bitmap) : Frame {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val scratch = HashMap<Long, Pair<Bitmap, Canvas>>()

    override fun pixels(x: Double, y: Double, w: Double, h: Double, outW: Int, outH: Int, out: IntArray): IntArray {
        val key = (outW.toLong() shl 32) or outH.toLong()
        val (bmp, canvas) = scratch.getOrPut(key) {
            if (scratch.size > 6) scratch.clear()
            val b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            b to Canvas(b)
        }
        canvas.drawColor(Color.BLACK)
        matrix.setTranslate(-x.toFloat(), -y.toFloat())
        matrix.postScale((outW / w).toFloat(), (outH / h).toFloat())
        canvas.drawBitmap(bitmap, matrix, paint)
        bmp.getPixels(out, 0, outW, 0, 0, outW, outH)
        return out
    }
}

/** A result of the speed test: one model with one setup. */
class SpeedResult(val model: Model, val cfg: RunConfig, val ms: Double?, val ok: Boolean, val note: String?)

/**
 * Finds the fastest setup for each model on this phone: every setup is checked on a sample photo (it
 * must still read the plate and find the car correctly) and timed.
 */
class SpeedTest(private val app: App) {
    private val engine = app.engine

    /** Set from another thread to stop after the current setup. */
    @Volatile
    var cancelled = false

    fun configs(): List<RunConfig> {
        val c = engine.cores
        val threads = listOf(2, 4, 6, 8).filter { it <= c }.ifEmpty { listOf(1) }
        return threads.map { RunConfig(Accel.CPU, it) } +
            listOf(RunConfig(Accel.XNNPACK, minOf(4, c)), RunConfig(Accel.XNNPACK, minOf(6, c)).takeIf { c >= 6 }, RunConfig(Accel.NNAPI, minOf(4, c))).filterNotNull()
    }

    fun run(models: List<Model>, progress: (String) -> Unit): List<SpeedResult> {
        val sample = app.assets.open("samples/car_ie.jpg").use { BitmapFactory.decodeStream(it) }
        val frame = BitmapFrame(sample)
        val pipe = PlatePipeline(engine.ocrConfig)
        // Where the plate is, found once on the CPU, for checking the plate readers.
        val cpu = RunConfig(Accel.CPU, engine.defaultThreads)
        val plateBox: PlateBox? = engine.create(Model.DET384, cpu).use { n -> pipe.detect(frame, n, 384, 0.35, fullRect(sample)).maxByOrNull { it.score } }
        val out = ArrayList<SpeedResult>()
        val cfgs = configs()
        var done = 0
        for (m in models) {
            val bytes = engine.modelBytes(m)
            for (cfg in cfgs) {
                if (cancelled) break
                progress("${m.label} · ${cfg.label} (${++done} of ${models.size * cfgs.size})")
                out.add(runOne(m, cfg, bytes, frame, pipe, plateBox))
            }
        }
        sample.recycle()
        return out
    }

    private fun fullRect(b: Bitmap) = io.github.ndev.roadsight.core.image.IntRect(0, 0, b.width, b.height)

    private fun runOne(m: Model, cfg: RunConfig, bytes: ByteArray, frame: BitmapFrame, pipe: PlatePipeline, plateBox: PlateBox?): SpeedResult {
        val net = try {
            engine.create(m, cfg, bytes)
        } catch (e: Throwable) {
            return SpeedResult(m, cfg, null, false, "not available")
        }
        net.use { n ->
            try {
                val veh = VehicleDetector()
                val once: () -> Boolean = when (m) {
                    Model.DET384, Model.DET640 -> { { pipe.detect(frame, n, m.size, 0.35, fullRect(frame.bitmap)).any { it.score > 0.5 } } }
                    Model.OCR_FAST, Model.OCR_ACC -> {
                        { plateBox != null && vote(pipe.read(frame, n, listOf(plateBox), PlatePipeline.TTA.take(1))[0])?.key == "241D12345" }
                    }
                    Model.VEH_NANO, Model.VEH_TINY -> { { veh.detect(frame, n, doubleArrayOf(0.0, 0.0, 1.0, 1.0), 0.3).dets.any { it.cls == "car" || it.cls == "truck" } } }
                }
                val ok = once() && once() // the first run also warms up
                val times = ArrayList<Double>()
                repeat(8) {
                    val t0 = System.nanoTime()
                    once()
                    times.add((System.nanoTime() - t0) / 1e6)
                }
                times.sort()
                return SpeedResult(m, cfg, times[times.size / 2], ok, if (ok) null else "wrong results")
            } catch (e: Throwable) {
                return SpeedResult(m, cfg, null, false, e.message?.take(60) ?: "failed")
            }
        }
    }

    /** Saves the fastest correct setup per model (used when the accelerator setting is Auto). */
    fun useFastest(results: List<SpeedResult>) {
        val best = results.filter { it.ok && it.ms != null }.groupBy { it.model }.mapValues { (_, v) -> v.minBy { it.ms!! }.cfg }
        app.prefs.tuned = Json.write(best.map { (m, c) -> m.key to c.toString() }.toMap())
        app.prefs.accel = "auto"
        app.prefs.threads = 0
    }
}
