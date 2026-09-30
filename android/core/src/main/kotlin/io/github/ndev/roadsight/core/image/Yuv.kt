package io.github.ndev.roadsight.core.image

/**
 * Video frames (YUV 4:2:0, as decoders give them) to ARGB pixels: shrunk by a whole factor (averaging
 * the brightness over each block, so small text stays readable) and turned upright.
 */
object Yuv {
    /** One plane of a frame: its bytes, the step between rows and the step between pixels. */
    class Plane(val data: ByteArray, val rowStride: Int, val pixelStride: Int)

    /** The size of the result: width and height after shrinking by `factor` and turning by `rotation`. */
    fun outSize(w: Int, h: Int, factor: Int, rotation: Int): Pair<Int, Int> {
        val sw = w / factor
        val sh = h / factor
        return if (rotation == 90 || rotation == 270) sh to sw else sw to sh
    }

    /** The smallest whole factor that brings the longer side down to `maxSide` or less. */
    fun factorFor(w: Int, h: Int, maxSide: Int): Int {
        val long = maxOf(w, h)
        var f = 1
        while (long / f > maxSide) f++
        return f
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    /**
     * Converts the `w` x `h` area at (`x0`, `y0`) of a frame (the decoder's crop) into `out`, as ARGB.
     * `factor` shrinks it (1 = full size); `rotation` (0, 90, 180 or 270, clockwise) turns it upright.
     * `hd` picks the HD colour matrix (BT.709) rather than the SD one (BT.601). Returns the result's size.
     */
    fun toArgb(
        y: Plane,
        u: Plane,
        v: Plane,
        x0: Int,
        y0: Int,
        w: Int,
        h: Int,
        factor: Int,
        rotation: Int,
        hd: Boolean,
        out: IntArray,
    ): Pair<Int, Int> {
        val f = maxOf(1, factor)
        val sw = w / f
        val sh = h / f
        val (ow, oh) = outSize(w, h, f, rotation)
        require(out.size >= ow * oh) { "out is too small" }
        // Fixed point (x1024) limited-range YUV to RGB.
        val cy = 1192 // 1.164
        val crv: Int
        val cgu: Int
        val cgv: Int
        val cbu: Int
        if (hd) {
            crv = 1836 // 1.793
            cgu = 218 // 0.213
            cgv = 546 // 0.533
            cbu = 2163 // 2.112
        } else {
            crv = 1634 // 1.596
            cgu = 400 // 0.391
            cgv = 833 // 0.813
            cbu = 2066 // 2.018
        }
        val yd = y.data
        val ud = u.data
        val vd = v.data
        val area = f * f
        val half = f / 2
        for (sy in 0 until sh) {
            val fy = y0 + sy * f
            // Chroma for this block: the sample under its middle.
            val cyRow = (fy + half) shr 1
            for (sx in 0 until sw) {
                val fx = x0 + sx * f
                var luma: Int
                if (f == 1) {
                    luma = yd[fy * y.rowStride + fx * y.pixelStride].toInt() and 0xff
                } else {
                    var sum = 0
                    for (dy in 0 until f) {
                        val row = (fy + dy) * y.rowStride
                        for (dx in 0 until f) sum += yd[row + (fx + dx) * y.pixelStride].toInt() and 0xff
                    }
                    luma = sum / area
                }
                val cx = (fx + half) shr 1
                val uu = (ud[cyRow * u.rowStride + cx * u.pixelStride].toInt() and 0xff) - 128
                val vv = (vd[cyRow * v.rowStride + cx * v.pixelStride].toInt() and 0xff) - 128
                val l = (luma - 16) * cy
                val r = clamp((l + crv * vv + 512) shr 10)
                val g = clamp((l - cgu * uu - cgv * vv + 512) shr 10)
                val b = clamp((l + cbu * uu + 512) shr 10)
                val i = when (rotation) {
                    90 -> sx * ow + (sh - 1 - sy)
                    180 -> (sh - 1 - sy) * ow + (sw - 1 - sx)
                    270 -> (sw - 1 - sx) * ow + sy
                    else -> sy * ow + sx
                }
                out[i] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return ow to oh
    }
}
