package io.github.ndev.roadsight.core

import io.github.ndev.roadsight.core.image.Frame
import io.github.ndev.roadsight.core.net.Net
import io.github.ndev.roadsight.core.net.Tensor
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/** The repository root: the models and test photos the web apps use. */
object Repo {
    val root: File by lazy {
        System.getProperty("roadsight.repo")?.let { return@lazy File(it) }
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "models/ocr.json").exists()) d = d.parentFile
        d ?: error("Can't find the repository (models/ocr.json)")
    }

    fun file(path: String) = File(root, path)
    fun image(name: String): BufferedImage = ImageIO.read(file("tests/e2e/assets/$name"))
}

/** A desktop image as a [Frame], scaled with bilinear filtering like the phone does. */
class AwtFrame(val img: BufferedImage) : Frame {
    override val width = img.width
    override val height = img.height

    override fun pixels(x: Double, y: Double, w: Double, h: Double, outW: Int, outH: Int, out: IntArray): IntArray {
        val dst = BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val sx = outW / w
        val sy = outH / h
        g.drawImage(img, AffineTransform(sx, 0.0, 0.0, sy, -x * sx, -y * sy), null)
        g.dispose()
        dst.getRGB(0, 0, outW, outH, out, 0, outW)
        return out
    }
}

/**
 * Loads a model: with ONNX Runtime (as in CI, and the app), or through the web tests' Python bridge
 * when -Droadsight.bridge=http://127.0.0.1:PORT is set (for machines without ONNX Runtime for Java).
 */
object Nets {
    private val cache = HashMap<String, Net>()

    fun load(path: String): Net = cache.getOrPut(path) {
        val bytes = Repo.file(path).readBytes()
        val bridge = System.getProperty("roadsight.bridge")
        if (bridge != null) BridgeNet(bridge, bytes)
        else Class.forName("io.github.ndev.roadsight.core.net.OrtNet").getMethod("fromBytes", ByteArray::class.java).invoke(null, bytes) as Net
    }
}

/** Runs a model in the web tests' server (tests/e2e/server.py), which uses Python ONNX Runtime. */
class BridgeNet(private val base: String, model: ByteArray) : Net {
    private val sid: String
    override val inputNames: List<String>
    override val outputNames: List<String>

    init {
        @Suppress("UNCHECKED_CAST")
        val info = Json.obj(String(post("/__ort/load", model)))
        sid = info["sid"] as String
        @Suppress("UNCHECKED_CAST")
        inputNames = info["inputNames"] as List<String>
        @Suppress("UNCHECKED_CAST")
        outputNames = info["outputNames"] as List<String>
    }

    private fun post(path: String, body: ByteArray): ByteArray {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.outputStream.use { it.write(body) }
        if (c.responseCode != 200) error("bridge ${c.responseCode}: ${c.errorStream?.readBytes()?.let { String(it) }}")
        return c.inputStream.use { it.readBytes() }
    }

    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val feeds = ArrayList<Map<String, Any?>>()
        val bufs = ByteArrayOutputStream()
        for ((name, t) in inputs) {
            val fl = t.floats
            val b = if (fl != null) {
                val bb = ByteBuffer.allocate(fl.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                bb.asFloatBuffer().put(fl)
                bb.array()
            } else t.bytes!!
            feeds.add(mapOf("name" to name, "type" to if (fl != null) "float32" else "uint8", "dims" to t.shape.toList(), "byteLength" to b.size))
            bufs.write(b)
        }
        val header = Json.write(mapOf("feeds" to feeds)).toByteArray()
        val body = ByteArrayOutputStream()
        body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(header.size).array())
        body.write(header)
        body.write(bufs.toByteArray())
        val res = post("/__ort/run?sid=$sid", body.toByteArray())
        val hl = ByteBuffer.wrap(res, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        @Suppress("UNCHECKED_CAST")
        val outs = Json.obj(String(res, 4, hl))["outputs"] as List<Map<String, Any?>>
        var o = 4 + hl
        val result = LinkedHashMap<String, Tensor>()
        for (m in outs) {
            val n = (m["byteLength"] as Double).toInt()
            @Suppress("UNCHECKED_CAST")
            val dims = (m["dims"] as List<Double>).map { it.toLong() }.toLongArray()
            val data = if (m["type"] == "uint8") FloatArray(n) { (res[o + it].toInt() and 0xff).toFloat() }
            else FloatArray(n / 4).also { ByteBuffer.wrap(res, o, n).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
            result[m["name"] as String] = Tensor.floats(data, *dims)
            o += n
        }
        return result
    }

    override fun close() {}
}
