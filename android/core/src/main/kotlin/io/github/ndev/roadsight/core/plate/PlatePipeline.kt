package io.github.ndev.roadsight.core.plate

import io.github.ndev.roadsight.core.Json
import io.github.ndev.roadsight.core.image.Frame
import io.github.ndev.roadsight.core.image.IntRect
import io.github.ndev.roadsight.core.image.area
import io.github.ndev.roadsight.core.image.iou
import io.github.ndev.roadsight.core.image.jsRound
import io.github.ndev.roadsight.core.net.Net
import io.github.ndev.roadsight.core.net.Tensor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The plate reader's settings (models/ocr.json): alphabet, character slots, input size and countries. */
class OcrConfig(
    val slots: Int,
    val alphabet: String,
    val pad: Char,
    val height: Int,
    val width: Int,
    val regions: List<String>,
) {
    companion object {
        fun parse(json: String): OcrConfig {
            val m = Json.obj(json)
            @Suppress("UNCHECKED_CAST")
            return OcrConfig(
                slots = (m["max_plate_slots"] as Double).toInt(),
                alphabet = m["alphabet"] as String,
                pad = (m["pad_char"] as String)[0],
                height = (m["img_height"] as Double).toInt(),
                width = (m["img_width"] as Double).toInt(),
                regions = (m["plate_regions"] as? List<String>) ?: emptyList(),
            )
        }
    }
}

/** A plate the finder found: box in frame pixels, score, whether it touches the edge of the analysed area, and its reads. */
class PlateBox(val box: DoubleArray, val score: Double, val edge: Boolean) {
    var reads: List<Read>? = null
    var model: String? = null
}

/** What the pipeline found in one frame, with how long each step took (ms). */
class PlateFrame(
    val width: Int,
    val height: Int,
    val boxes: List<PlateBox>,
    val region: IntRect,
    val tiles: List<IntRect>?,
    val msFind: Double,
    val msRead: Double,
    val msTotal: Double,
)

/** A slightly different crop of a plate for the reader: grow (or shrink) by these fractions of its size. */
class Variant(val ex: Double, val ey: Double)

/**
 * Finds plates with the YOLOv9 plate finder and reads them with the fast-plate-ocr reader.
 * The same steps as PlateSight's engine-worker.js, so results match the web app.
 */
class PlatePipeline(private val ocr: OcrConfig) {
    private var detInput = FloatArray(0)
    private var px = IntArray(0)

    companion object {
        val V0 = Variant(0.0, 0.0)

        /** Photo mode: as detected, a wider crop and a tighter crop, voted together. */
        val TTA = listOf(V0, Variant(0.06, 0.12), Variant(-0.03, 0.0))

        /** The padded area around a plate used for its photo (and the thumbnail width): x, y, w, h, outW, outH. */
        fun thumbRect(box: DoubleArray, frameW: Int, frameH: Int): IntArray {
            val w = box[2] - box[0]
            val h = box[3] - box[1]
            val sx = max(0.0, floor(box[0] - w * 0.12)).toInt()
            val sy = max(0.0, floor(box[1] - h * 0.35)).toInt()
            val sw = max(1, min(frameW - sx, ceil(w * 1.24).toInt()))
            val sh = max(1, min(frameH - sy, ceil(h * 1.7).toInt()))
            val tw = 240
            val th = jsRound(tw.toDouble() * sh / sw).toInt().coerceIn(24, 240)
            return intArrayOf(sx, sy, sw, sh, tw, th)
        }
    }

    /** Plates in `rect` (frame pixels) found by the finder `net` with input size `size`, scoring at least `conf`. */
    fun detect(frame: Frame, net: Net, size: Int, conf: Double, rect: IntRect): List<PlateBox> {
        val s = size
        val r = min(s.toDouble() / rect.w, s.toDouble() / rect.h)
        val nw = max(1, jsRound(rect.w * r).toInt())
        val nh = max(1, jsRound(rect.h * r).toInt())
        val left = jsRound((s - nw) / 2.0 - 0.1).toInt()
        val top = jsRound((s - nh) / 2.0 - 0.1).toInt()
        if (px.size < nw * nh) px = IntArray(nw * nh)
        frame.pixels(rect.x.toDouble(), rect.y.toDouble(), rect.w.toDouble(), rect.h.toDouble(), nw, nh, px)
        val n = s * s
        if (detInput.size != 3 * n) detInput = FloatArray(3 * n)
        val f = detInput
        val grey = 114f / 255f
        java.util.Arrays.fill(f, grey)
        for (y in 0 until nh) {
            val row = (y + top) * s + left
            for (x in 0 until nw) {
                val p = px[y * nw + x]
                val i = row + x
                f[i] = ((p shr 16) and 0xff) / 255f
                f[i + n] = ((p shr 8) and 0xff) / 255f
                f[i + 2 * n] = (p and 0xff) / 255f
            }
        }
        val out = net.run(mapOf(net.inputNames[0] to Tensor.floats(f, 1, 3, s.toLong(), s.toLong())))
        val o = out[net.outputNames[0]] ?: out.values.first()
        val d = o.floats!!
        val rows = if (o.shape.isNotEmpty()) o.shape[0].toInt() else 0
        val cols = if (o.shape.size > 1) o.shape[1].toInt() else 7
        val w = frame.width.toDouble()
        val h = frame.height.toDouble()
        val boxes = ArrayList<PlateBox>()
        for (i in 0 until rows) {
            val b = i * cols
            val score = d[b + 6].toDouble()
            if (!(score >= conf)) continue
            val x1 = ((d[b + 1] - left) / r + rect.x).coerceIn(0.0, w)
            val y1 = ((d[b + 2] - top) / r + rect.y).coerceIn(0.0, h)
            val x2 = ((d[b + 3] - left) / r + rect.x).coerceIn(0.0, w)
            val y2 = ((d[b + 4] - top) / r + rect.y).coerceIn(0.0, h)
            if (x2 - x1 < 4 || y2 - y1 < 3) continue
            // Touching the edge of the analysed area: the plate is probably cut off, so its reading is partial.
            val mx = max(3.0, rect.w * 0.015)
            val my = max(3.0, rect.h * 0.015)
            val edge = x1 <= rect.x + mx || y1 <= rect.y + my || x2 >= rect.x + rect.w - mx || y2 >= rect.y + rect.h - my
            boxes.add(PlateBox(doubleArrayOf(x1, y1, x2, y2), score, edge))
        }
        return boxes
    }

    /** Photos where the plates are tiny: look again in four overlapping tiles at 60% size. */
    fun deepDetect(frame: Frame, net: Net, size: Int, conf: Double): Pair<List<PlateBox>, List<IntRect>> {
        val w = frame.width
        val h = frame.height
        val tw = jsRound(w * 0.6).toInt()
        val th = jsRound(h * 0.6).toInt()
        val found = ArrayList<PlateBox>()
        val tiles = ArrayList<IntRect>()
        for (x in intArrayOf(0, w - tw)) {
            for (y in intArrayOf(0, h - th)) {
                val t = IntRect(x, y, tw, th)
                tiles.add(t)
                found.addAll(detect(frame, net, size, conf, t))
            }
        }
        val keep = ArrayList<PlateBox>()
        for (d in found.sortedByDescending { it.score }) if (keep.all { iou(it.box, d.box) < 0.45 }) keep.add(d)
        return keep to tiles
    }

    private fun variantRect(b: DoubleArray, v: Variant, fw: Int, fh: Int): DoubleArray {
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        val x1 = (b[0] - w * v.ex).coerceIn(0.0, fw - 1.0)
        val y1 = (b[1] - h * v.ey).coerceIn(0.0, fh - 1.0)
        val x2 = (b[2] + w * v.ex).coerceIn(x1 + 1, fw.toDouble())
        val y2 = (b[3] + h * v.ey).coerceIn(y1 + 1, fh.toDouble())
        return doubleArrayOf(x1, y1, x2 - x1, y2 - y1)
    }

    /** Reads each plate from each variant crop in one batch. Returns the reads per box, in order. */
    fun read(frame: Frame, net: Net, boxes: List<PlateBox>, variants: List<Variant>): List<List<Read>> {
        val hh = ocr.height
        val ww = ocr.width
        val sl = ocr.slots
        val a = ocr.alphabet
        val rects = ArrayList<DoubleArray>()
        for (b in boxes) for (v in variants) rects.add(variantRect(b.box, v, frame.width, frame.height))
        val n = rects.size
        val arr = ByteArray(n * hh * ww * 3)
        if (px.size < ww * hh) px = IntArray(ww * hh)
        for (k in 0 until n) {
            val q = rects[k]
            frame.pixels(q[0], q[1], q[2], q[3], ww, hh, px)
            var o = k * hh * ww * 3
            for (p in 0 until ww * hh) {
                val c = px[p]
                arr[o++] = ((c shr 16) and 0xff).toByte()
                arr[o++] = ((c shr 8) and 0xff).toByte()
                arr[o++] = (c and 0xff).toByte()
            }
        }
        val out = net.run(mapOf(net.inputNames[0] to Tensor.bytes(arr, n.toLong(), hh.toLong(), ww.toLong(), 3)))
        val plate = out["plate"] ?: out[net.outputNames[0]]!!
        val region = out["region"]
        val v = a.length
        val pd = plate.floats!!
        val rd = region?.floats
        val rCount = region?.shape?.last()?.toInt() ?: 0
        val reads = ArrayList<Read>(n)
        for (k in 0 until n) {
            val raw = StringBuilder()
            val probs = DoubleArray(sl)
            for (s in 0 until sl) {
                val base = (k * sl + s) * v
                var bi = 0
                var bp = -1f
                for (c in 0 until v) {
                    val p = pd[base + c]
                    if (p > bp) {
                        bp = p
                        bi = c
                    }
                }
                raw.append(a[bi])
                probs[s] = bp.toDouble()
            }
            var end = raw.length
            while (end > 0 && raw[end - 1] == ocr.pad) end--
            val text = StringBuilder()
            val kept = ArrayList<Double>()
            for (i in 0 until end) {
                if (raw[i] != ocr.pad) {
                    text.append(raw[i])
                    kept.add(probs[i])
                }
            }
            val conf = if (kept.isNotEmpty()) kept.sum() / kept.size else 0.0
            var reg: String? = null
            var regP = 0.0
            if (rd != null && rCount > 0) {
                var bi = 0
                var bp = -1f
                for (c in 0 until rCount) {
                    val p = rd[k * rCount + c]
                    if (p > bp) {
                        bp = p
                        bi = c
                    }
                }
                reg = ocr.regions.getOrNull(bi)
                regP = bp.toDouble()
            }
            reads.add(Read(text.toString(), conf, if (kept.isNotEmpty()) kept.min() else 0.0, reg, regP))
        }
        return List(boxes.size) { b -> reads.subList(b * variants.size, (b + 1) * variants.size).toList() }
    }

    /**
     * Finds and reads the plates in a frame. Portrait camera frames are analysed in their centre square
     * (about 1.8x more pixels per plate than letterboxing the whole tall frame).
     */
    fun analyze(
        frame: Frame,
        finder: Net,
        finderSize: Int,
        reader: Net?,
        readerKey: String? = null,
        conf: Double = 0.35,
        maxPlates: Int = 8,
        minW: Double = 36.0,
        portraitRoi: Boolean = false,
        variants: List<Variant> = listOf(V0),
        deep: Boolean = false,
    ): PlateFrame {
        val t0 = System.nanoTime()
        val w = frame.width
        val h = frame.height
        val region = if (portraitRoi && h > w * 1.15) IntRect(0, jsRound((h - w) / 2.0).toInt(), w, w) else IntRect(0, 0, w, h)
        var boxes = detect(frame, finder, finderSize, conf, region)
        var tiles: List<IntRect>? = null
        if (deep && boxes.isEmpty()) {
            val (b, t) = deepDetect(frame, finder, finderSize, conf)
            boxes = b
            tiles = t
        }
        boxes = boxes.sortedByDescending { area(it.box) }.take(maxPlates)
        val t1 = System.nanoTime()
        val readable = boxes.filter { it.box[2] - it.box[0] >= minW }
        if (reader != null && readable.isNotEmpty()) {
            val groups = read(frame, reader, readable, variants)
            readable.forEachIndexed { i, b ->
                b.reads = groups[i]
                b.model = readerKey
            }
        }
        val t2 = System.nanoTime()
        return PlateFrame(w, h, boxes, region, tiles, (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t2 - t0) / 1e6)
    }
}
