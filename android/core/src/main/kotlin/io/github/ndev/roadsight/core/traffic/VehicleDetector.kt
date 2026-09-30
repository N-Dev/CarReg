package io.github.ndev.roadsight.core.traffic

import io.github.ndev.roadsight.core.image.Frame
import io.github.ndev.roadsight.core.image.iou
import io.github.ndev.roadsight.core.image.jsRound
import io.github.ndev.roadsight.core.net.Net
import io.github.ndev.roadsight.core.net.Tensor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Road users found in one frame, with the analysed area in pixels and how long each step took (ms). */
class VehicleFrame(
    val dets: List<Det>,
    val width: Int,
    val height: Int,
    val crop: IntArray,
    val msPrep: Double,
    val msInfer: Double,
    val msPost: Double,
) {
    val msTotal: Double get() = msPrep + msInfer + msPost
}

/**
 * Finds road users with YOLOX (trained on COCO): people, bicycles, cars, motorbikes, buses, trucks,
 * dogs and horses. The same steps as TrafficSight's worker.js.
 */
class VehicleDetector(private val size: Int = 416) {
    private val input = FloatArray(3 * size * size)
    private var px = IntArray(0)
    private val grid: FloatArray = buildGrid(size)

    companion object {
        /** COCO class numbers worth counting on a road. */
        val COCO = mapOf(0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorbike", 5 to "bus", 7 to "truck", 16 to "dog", 17 to "horse")

        /** Anchor grid for YOLOX's three output scales (strides 8, 16, 32): x, y, stride per row. */
        private fun buildGrid(size: Int): FloatArray {
            val rows = ArrayList<Float>()
            for (s in intArrayOf(8, 16, 32)) {
                val g = size / s
                for (y in 0 until g) for (x in 0 until g) {
                    rows.add(x.toFloat())
                    rows.add(y.toFloat())
                    rows.add(s.toFloat())
                }
            }
            return rows.toFloatArray()
        }

        private fun inside(d: Det, k: Det): Double {
            val w = min(d.box[2], k.box[2]) - max(d.box[0], k.box[0])
            val h = min(d.box[3], k.box[3]) - max(d.box[1], k.box[1])
            return if (w > 0 && h > 0) (w * h) / ((d.box[2] - d.box[0]) * (d.box[3] - d.box[1])) else 0.0
        }

        /**
         * Keeps the best of overlapping boxes within each group: the model often calls one van both a car
         * and a truck, or puts a second, smaller box inside a vehicle.
         */
        fun nms(dets: List<Det>, thr: Double = 0.5): List<Det> {
            val keep = ArrayList<Det>()
            for (d in dets.sortedByDescending { it.score }) {
                val g = Kinds[d.cls]?.group
                if (keep.none { Kinds[it.cls]?.group == g && (iou(it.box, d.box) > thr || inside(d, it) > 0.6) }) keep.add(d)
            }
            return keep
        }
    }

    /**
     * Road users in the part of the frame `roi` (fractions x, y, w, h) scoring at least `conf`. Boxes
     * come back as fractions of the whole frame.
     */
    fun detect(frame: Frame, net: Net, roi: DoubleArray, conf: Double = 0.3): VehicleFrame {
        val t0 = System.nanoTime()
        val fw = frame.width
        val fh = frame.height
        val sx = jsRound(roi[0] * fw).toInt()
        val sy = jsRound(roi[1] * fh).toInt()
        val sw = max(1, jsRound(roi[2] * fw).toInt())
        val sh = max(1, jsRound(roi[3] * fh).toInt())
        val scale = min(size.toDouble() / sw, size.toDouble() / sh)
        val nw = min(size, max(1, jsRound(sw * scale).toInt()))
        val nh = min(size, max(1, jsRound(sh * scale).toInt()))
        if (px.size < nw * nh) px = IntArray(nw * nh)
        frame.pixels(sx.toDouble(), sy.toDouble(), sw.toDouble(), sh.toDouble(), nw, nh, px)
        // As YOLOX was trained: the picture in the top-left corner, grey (114) padding, BGR, 0-255.
        val n = size * size
        java.util.Arrays.fill(input, 114f)
        for (y in 0 until nh) {
            val row = y * size
            for (x in 0 until nw) {
                val p = px[y * nw + x]
                val i = row + x
                input[i] = (p and 0xff).toFloat()
                input[i + n] = ((p shr 8) and 0xff).toFloat()
                input[i + 2 * n] = ((p shr 16) and 0xff).toFloat()
            }
        }
        val t1 = System.nanoTime()
        val out = net.run(mapOf(net.inputNames[0] to Tensor.floats(input, 1, 3, size.toLong(), size.toLong())))
        val o = out[net.outputNames[0]] ?: out.values.first()
        val t2 = System.nanoTime()
        val d = o.floats!!
        val rows = o.shape[1].toInt()
        val cols = o.shape[2].toInt()
        val rx = nw.toDouble() / sw
        val ry = nh.toDouble() / sh
        val found = ArrayList<Det>()
        for (i in 0 until rows) {
            val b = i * cols
            val obj = d[b + 4]
            if (obj < conf) continue
            var best = 0f
            var bi = -1
            for (c in 0 until cols - 5) {
                val v = d[b + 5 + c]
                if (v > best) {
                    best = v
                    bi = c
                }
            }
            val cls = COCO[bi] ?: continue
            val score = (obj * best).toDouble()
            if (score < conf) continue
            val stride = grid[i * 3 + 2]
            val cx = (d[b] + grid[i * 3]) * stride
            val cy = (d[b + 1] + grid[i * 3 + 1]) * stride
            val bw = exp(d[b + 2].toDouble()) * stride
            val bh = exp(d[b + 3].toDouble()) * stride
            // Model input -> frame pixels -> fractions of the frame.
            val x1 = max(0.0, ((cx - bw / 2) / rx + sx) / fw)
            val y1 = max(0.0, ((cy - bh / 2) / ry + sy) / fh)
            val x2 = min(1.0, ((cx + bw / 2) / rx + sx) / fw)
            val y2 = min(1.0, ((cy + bh / 2) / ry + sy) / fh)
            if (x2 - x1 < 0.004 || y2 - y1 < 0.004) continue
            found.add(Det(cls, score, doubleArrayOf(x1, y1, x2, y2)))
        }
        val dets = nms(found)
        val t3 = System.nanoTime()
        return VehicleFrame(dets, fw, fh, intArrayOf(sx, sy, sw, sh), (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6)
    }
}
