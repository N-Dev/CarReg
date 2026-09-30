package io.github.ndev.roadsight.core.net

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A model run by ONNX Runtime. Inputs are copied into direct buffers that are reused from frame to
 * frame, so running a model doesn't allocate megabytes of garbage each time.
 */
class OrtNet(private val env: OrtEnvironment, val session: OrtSession) : Net {
    override val inputNames: List<String> = session.inputNames.toList()
    override val outputNames: List<String> = session.outputNames.toList()
    private val floatBufs = HashMap<String, FloatBuffer>()
    private val byteBufs = HashMap<String, ByteBuffer>()

    /** One run at a time per model: the reused input buffers aren't shared between threads. */
    @Synchronized
    override fun run(inputs: Map<String, Tensor>): Map<String, Tensor> {
        val tensors = HashMap<String, OnnxTensor>()
        try {
            for ((name, t) in inputs) {
                val floats = t.floats
                tensors[name] = if (floats != null) {
                    var b = floatBufs[name]
                    if (b == null || b.capacity() < floats.size) {
                        b = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        floatBufs[name] = b
                    }
                    b!!.clear()
                    b.put(floats)
                    b.flip()
                    OnnxTensor.createTensor(env, b, t.shape)
                } else {
                    val data = t.bytes!!
                    var b = byteBufs[name]
                    if (b == null || b.capacity() < data.size) {
                        b = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder())
                        byteBufs[name] = b
                    }
                    b!!.clear()
                    b.put(data)
                    b.flip()
                    OnnxTensor.createTensor(env, b, t.shape, OnnxJavaType.UINT8)
                }
            }
            session.run(tensors).use { result ->
                val out = LinkedHashMap<String, Tensor>()
                for (entry in result) {
                    val v = entry.value as OnnxTensor
                    out[entry.key] = Tensor.floats(toFloats(v), *v.info.shape)
                }
                return out
            }
        } finally {
            for (t in tensors.values) t.close()
        }
    }

    private fun toFloats(v: OnnxTensor): FloatArray {
        v.floatBuffer?.let { fb ->
            // ONNX Runtime hands back a fresh copy: use its array as it is rather than copying it again
            // (the traffic finder's output is over a megabyte a frame).
            if (fb.hasArray() && fb.arrayOffset() == 0 && fb.position() == 0 && fb.remaining() == fb.array().size) return fb.array()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
        v.longBuffer?.let { lb -> return FloatArray(lb.remaining()) { lb.get().toFloat() } }
        v.intBuffer?.let { ib -> return FloatArray(ib.remaining()) { ib.get().toFloat() } }
        v.doubleBuffer?.let { db -> return FloatArray(db.remaining()) { db.get().toFloat() } }
        v.byteBuffer?.let { bb -> return FloatArray(bb.remaining()) { (bb.get().toInt() and 0xff).toFloat() } }
        throw IllegalStateException("Unsupported output type ${v.info.type}")
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** A model with ONNX Runtime's default options (used by the tests). */
        @JvmStatic
        fun fromBytes(bytes: ByteArray): OrtNet {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions()
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            return OrtNet(env, env.createSession(bytes, opts))
        }
    }
}
