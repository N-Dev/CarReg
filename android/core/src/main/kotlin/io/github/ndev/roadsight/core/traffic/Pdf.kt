package io.github.ndev.roadsight.core.traffic

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.min

/**
 * A small PDF writer: A4 pages of text, lines and rectangles in the standard Helvetica fonts, which
 * every PDF reader has built in (so nothing is embedded). Coordinates are points from the top-left
 * corner. A port of TrafficSight's pdf.js, so the report comes out the same as the web app's.
 */
class Pdf(private val title: String = "", private val author: String = "", private val producer: String = "RoadSight") {
    companion object {
        const val W = 595.28
        const val H = 841.89

        // Character widths (1/1000 em) for ASCII 32..126, from the standard Helvetica font metrics.
        private val W_REG = intArrayOf(
            278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
            278, 278, 584, 584, 584, 556, 1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722,
            667, 944, 667, 667, 611, 278, 278, 278, 469, 556, 333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556,
            333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
        )
        private val W_BOLD = intArrayOf(
            278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278, 556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
            333, 333, 584, 584, 584, 611, 975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722,
            667, 944, 667, 667, 611, 333, 278, 333, 584, 556, 333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611,
            389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
        )

        // Characters outside ASCII that WinAnsiEncoding has, with their byte and an approximate width.
        private val EXTRA: Map<Int, IntArray> = mapOf(
            '–'.code to intArrayOf(0x96, 556), '—'.code to intArrayOf(0x97, 1000), '‘'.code to intArrayOf(0x91, 222), '’'.code to intArrayOf(0x92, 222),
            '“'.code to intArrayOf(0x93, 333), '”'.code to intArrayOf(0x94, 333), '•'.code to intArrayOf(0x95, 350), '…'.code to intArrayOf(0x85, 1000),
            '€'.code to intArrayOf(0x80, 556), '·'.code to intArrayOf(0xb7, 278), '°'.code to intArrayOf(0xb0, 400), '×'.code to intArrayOf(0xd7, 584),
            '±'.code to intArrayOf(0xb1, 584),
        )

        /** WinAnsi byte and width for one character (accented Latin letters take their base letter's width). */
        private fun glyph(cp: Int, bold: Boolean): IntArray {
            val w = if (bold) W_BOLD else W_REG
            if (cp in 32..126) return intArrayOf(cp, w[cp - 32])
            EXTRA[cp]?.let { return it }
            if (cp in 0xa0..0xff) {
                val base = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)[0].code
                return intArrayOf(cp, if (base in 32..126) w[base - 32] else 556)
            }
            return intArrayOf(63, w[63 - 32]) // '?'
        }

        private fun codePoints(s: String): IntArray = s.codePoints().toArray()

        /** Width of a string in points at a font size. */
        fun textWidth(str: String, size: Double, bold: Boolean = false): Double {
            var w = 0
            for (cp in codePoints(str)) w += glyph(cp, bold)[1]
            return w * size / 1000
        }

        /** A PDF string literal: printable ASCII as is, everything else as octal escapes. */
        fun pdfString(str: String, bold: Boolean = false): String {
            val out = StringBuilder("(")
            for (cp in codePoints(str)) {
                val b = glyph(cp, bold)[0]
                when {
                    b == 40 || b == 41 || b == 92 -> out.append('\\').append(b.toChar())
                    b in 32..126 -> out.append(b.toChar())
                    else -> out.append('\\').append(Integer.toOctalString(b).padStart(3, '0'))
                }
            }
            return out.append(')').toString()
        }

        /** A number with at most two decimals, like JavaScript prints Math.round(v * 100) / 100. */
        fun num(v: Double): String {
            val r = floor(v * 100 + 0.5) / 100
            val s = BigDecimal(r).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            return if (s == "-0") "0" else s
        }

        fun rgb(c: String): String {
            val h = c.removePrefix("#")
            return listOf(0, 2, 4).joinToString(" ") { i -> num(h.substring(i, i + 2).toInt(16) / 255.0) }
        }
    }

    enum class Align { LEFT, RIGHT, CENTER }

    private val pages = ArrayList<ArrayList<String>>()
    private var ops = ArrayList<String>()

    init {
        addPage()
    }

    fun addPage(): Pdf {
        ops = ArrayList()
        pages.add(ops)
        return this
    }

    /** Text at (x, y = baseline). For RIGHT and CENTER, x is the right edge or the middle. */
    fun text(x: Double, y: Double, str: String, size: Double = 10.0, bold: Boolean = false, color: String = "#111111", align: Align = Align.LEFT): Pdf {
        var tx = x
        if (align != Align.LEFT) {
            val w = textWidth(str, size, bold)
            tx = if (align == Align.RIGHT) x - w else x - w / 2
        }
        ops.add("BT /${if (bold) "F2" else "F1"} ${num(size)} Tf ${rgb(color)} rg ${num(tx)} ${num(H - y)} Td ${pdfString(str, bold)} Tj ET")
        return this
    }

    /** Text wrapped to a width; returns the y of the line after the last. */
    fun paragraph(x: Double, y: Double, str: String, width: Double, size: Double = 9.0, lead: Double = 1.35, bold: Boolean = false, color: String = "#111111"): Double {
        val words = str.split(Regex("\\s+"))
        var line = ""
        var yy = y
        for (w in words) {
            val next = if (line.isNotEmpty()) "$line $w" else w
            if (line.isNotEmpty() && textWidth(next, size, bold) > width) {
                text(x, yy, line, size, bold, color)
                yy += size * lead
                line = w
            } else {
                line = next
            }
        }
        if (line.isNotEmpty()) {
            text(x, yy, line, size, bold, color)
            yy += size * lead
        }
        return yy
    }

    fun rect(x: Double, y: Double, w: Double, h: Double, fill: String? = null, stroke: String? = null, width: Double = 1.0): Pdf {
        val parts = ArrayList<String>()
        if (fill != null) parts.add("${rgb(fill)} rg")
        if (stroke != null) parts.add("${rgb(stroke)} RG ${num(width)} w")
        val op = if (fill != null && stroke != null) "B" else if (fill != null) "f" else "S"
        parts.add("${num(x)} ${num(H - y - h)} ${num(w)} ${num(h)} re $op")
        ops.add("q ${parts.joinToString(" ")} Q")
        return this
    }

    /** A column rising from a baseline at y + h, with its top corners rounded by r. */
    fun column(x: Double, y: Double, w: Double, h: Double, fill: String, r: Double = 2.0): Pdf {
        if (h <= 0 || w <= 0) return this
        val rr = min(r, min(w / 2, h))
        val x0 = x
        val x1 = x + w
        val yb = H - (y + h) // baseline (PDF y goes up)
        val yt = H - y
        val k = 0.5523 * rr
        ops.add(
            "q ${rgb(fill)} rg ${num(x0)} ${num(yb)} m ${num(x0)} ${num(yt - rr)} l " +
                "${num(x0)} ${num(yt - rr + k)} ${num(x0 + rr - k)} ${num(yt)} ${num(x0 + rr)} ${num(yt)} c ${num(x1 - rr)} ${num(yt)} l " +
                "${num(x1 - rr + k)} ${num(yt)} ${num(x1)} ${num(yt - rr + k)} ${num(x1)} ${num(yt - rr)} c ${num(x1)} ${num(yb)} l h f Q",
        )
        return this
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, color: String = "#999999", width: Double = 0.5, dash: DoubleArray? = null): Pdf {
        val d = if (dash != null) "[${dash.joinToString(" ") { num(it) }}] 0 d " else ""
        ops.add("q ${rgb(color)} RG ${num(width)} w $d${num(x1)} ${num(H - y1)} m ${num(x2)} ${num(H - y2)} l S Q")
        return this
    }

    /** The finished file. */
    fun bytes(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): ByteArray {
        val objs = ArrayList<String?>()
        fun add(s: String?): Int {
            objs.add(s)
            return objs.size
        }
        val catalog = add(null)
        val pagesObj = add(null)
        val f1 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val f2 = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        val kids = ArrayList<Int>()
        for (page in pages) {
            val content = page.joinToString("\n")
            val c = add("<< /Length ${content.length} >>\nstream\n$content\nendstream")
            kids.add(
                add(
                    "<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 ${num(W)} ${num(H)}] " +
                        "/Resources << /Font << /F1 $f1 0 R /F2 $f2 0 R >> >> /Contents $c 0 R >>",
                ),
            )
        }
        objs[catalog - 1] = "<< /Type /Catalog /Pages $pagesObj 0 R >>"
        objs[pagesObj - 1] = "<< /Type /Pages /Kids [${kids.joinToString(" ") { "$it 0 R" }}] /Count ${kids.size} >>"
        val stamp = "D:" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(now.withZoneSameInstant(ZoneOffset.UTC)) + "Z"
        val info = add("<< /Title ${pdfString(title)} /Author ${pdfString(author)} /Producer ${pdfString(producer)} /CreationDate ($stamp) >>")

        val out = StringBuilder("%PDF-1.4\n%âãÏÓ\n")
        val offsets = ArrayList<Int>()
        objs.forEachIndexed { i, o ->
            offsets.add(out.length)
            out.append("${i + 1} 0 obj\n").append(o).append("\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n")
        for (off in offsets) out.append(off.toString().padStart(10, '0')).append(" 00000 n \n")
        out.append("trailer\n<< /Size ${objs.size + 1} /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        // Every character is below 256 here (text is escaped), so one character is one byte.
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }
}
