package io.github.ndev.roadsight.core.image

/**
 * A picture the models look at: a camera frame or a photo, the right way up. The app implements it
 * with Android bitmaps, the tests with desktop images.
 */
interface Frame {
    val width: Int
    val height: Int

    /**
     * The area (x, y, w, h) in pixels, scaled to outW × outH, as ARGB ints row by row, written into
     * [out] (which must hold at least outW × outH values) and returned.
     */
    fun pixels(x: Double, y: Double, w: Double, h: Double, outW: Int, outH: Int, out: IntArray): IntArray
}

/** A rectangle in pixels. */
data class IntRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** JavaScript's Math.round (halves round up), so results match the web apps exactly. */
fun jsRound(x: Double): Double = Math.floor(x + 0.5)

fun area(b: DoubleArray): Double = maxOf(0.0, b[2] - b[0]) * maxOf(0.0, b[3] - b[1])

fun iou(a: DoubleArray, b: DoubleArray): Double {
    val w = minOf(a[2], b[2]) - maxOf(a[0], b[0])
    val h = minOf(a[3], b[3]) - maxOf(a[1], b[1])
    if (w <= 0 || h <= 0) return 0.0
    val i = w * h
    return i / (area(a) + area(b) - i)
}
