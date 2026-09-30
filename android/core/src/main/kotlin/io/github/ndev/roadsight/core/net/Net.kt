package io.github.ndev.roadsight.core.net

/** Input or output values of a model: float32 or uint8, with their shape. */
class Tensor private constructor(val shape: LongArray, val floats: FloatArray?, val bytes: ByteArray?) {
    val size: Int get() = floats?.size ?: bytes!!.size

    companion object {
        fun floats(data: FloatArray, vararg shape: Long) = Tensor(shape, data, null)
        fun bytes(data: ByteArray, vararg shape: Long) = Tensor(shape, null, data)
    }
}

/** A loaded AI model. The app runs models with ONNX Runtime ([OrtNet]); tests can plug in other runners. */
interface Net : AutoCloseable {
    val inputNames: List<String>
    val outputNames: List<String>

    /** Runs the model once. Outputs come back as float tensors, by name. */
    fun run(inputs: Map<String, Tensor>): Map<String, Tensor>
}
