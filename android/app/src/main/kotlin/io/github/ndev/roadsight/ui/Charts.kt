package io.github.ndev.roadsight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.core.traffic.HourBin
import io.github.ndev.roadsight.core.traffic.Report
import io.github.ndev.roadsight.core.traffic.Summary
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The results charts (TrafficSight's charts.js): thin columns with rounded tops, a 2 px gap between
// touching marks, hairline grid, and a tap tooltip on every column.
private val GRID = Color(0x12FFFFFF)
private val AXIS = Color(0x2EFFFFFF)

private fun hourOf(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).hour
private fun pad2(n: Int) = n.toString().padStart(2, '0')

/** A column from the baseline (y + h) up, top corners rounded. */
private fun DrawScope.column(x: Float, y: Float, w: Float, h: Float, color: Color, round: Boolean = true) {
    if (h <= 0.5f) return
    val r = if (round) min(4.dp.toPx(), min(w / 2, h)) else 0f
    val path = Path().apply {
        addRoundRect(RoundRect(x, y, x + w, y + h, CornerRadius(r), CornerRadius(r), CornerRadius.Zero, CornerRadius.Zero))
    }
    drawPath(path, color)
}

/** Gridlines with round-number labels; returns the value at the top. */
private fun DrawScope.axes(left: Float, top: Float, width: Float, height: Float, maxValue: Int): Double {
    val step = Report.niceStep(maxValue.toDouble())
    val vmax = (ceil(maxValue / step) * step).let { if (it == 0.0) step else it }
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 11.sp.toPx()
        color = C.muted.toArgb()
        textAlign = android.graphics.Paint.Align.RIGHT
    }
    var v = 0.0
    while (v <= vmax) {
        val y = top + height - (v / vmax * height).toFloat()
        drawLine(if (v > 0) GRID else AXIS, Offset(left, y), Offset(left + width, y), 1.dp.toPx())
        drawContext.canvas.nativeCanvas.drawText(Report.fmt(v), left - 6.dp.toPx(), y + 4.dp.toPx(), paint)
        v += step
    }
    return vmax
}

private fun DrawScope.label(text: String, x: Float, y: Float, color: Color = C.muted, align: android.graphics.Paint.Align = android.graphics.Paint.Align.CENTER) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 11.sp.toPx()
        this.color = color.toArgb()
        textAlign = align
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

/** What a tapped column shows. */
private class Tip(val title: String, val rows: List<Triple<String, String, Color>>)

@Composable
private fun TipBox(tip: Tip, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(C.surface2).border(1.dp, C.line2, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(tip.title, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        for ((value, label, color) in tip.rows) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text(value, color = C.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(5.dp))
                Text(label, color = C.muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun Legend(items: List<Pair<Color, String>>, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((color, text) in items) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
            Spacer(Modifier.width(5.dp))
            Text(text, color = C.muted, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.width(14.dp))
        }
    }
}

/** Motor vehicles per hour, direction 1 at the bottom of each column. */
@Composable
fun HourChart(perHour: List<HourBin>, dirNames: List<String>, modifier: Modifier = Modifier) {
    var sel by remember(perHour) { mutableStateOf<Int?>(null) }
    Box(modifier.fillMaxWidth().height(190.dp)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(perHour) {
                detectTapGestures { p ->
                    val left = 34.dp.toPx()
                    val slot = (size.width - left - 6.dp.toPx()) / max(1, perHour.size)
                    val i = floor((p.x - left) / slot).toInt()
                    sel = if (i in perHour.indices && sel != i) i else null
                }
            },
        ) {
            val left = 34.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = 24.dp.toPx()
            val pw = size.width - left - 6.dp.toPx()
            val ph = size.height - top - bottom
            val maxV = max(1, perHour.maxOfOrNull { it.dirs[1] + it.dirs[2] } ?: 0)
            val vmax = axes(left, top, pw, ph, maxV).toFloat()
            val n = max(1, perHour.size)
            val slot = pw / n
            val bw = max(2.dp.toPx(), min(24.dp.toPx(), slot - 2.dp.toPx()))
            val every = if (n <= 8) 1 else if (n <= 16) 2 else if (n <= 32) 4 else 6
            perHour.forEachIndexed { i, b ->
                val x = left + i * slot + (slot - bw) / 2
                val h1 = b.dirs[1] / vmax * ph
                val h2 = b.dirs[2] / vmax * ph
                val gap = if (h1 > 0 && h2 > 0) 2.dp.toPx() else 0f
                val dim = sel != null && sel != i
                val c1 = if (dim) C.dir1.copy(alpha = 0.45f) else C.dir1
                val c2 = if (dim) C.dir2.copy(alpha = 0.45f) else C.dir2
                column(x, top + ph - h1, bw, h1, c1, round = h2 <= 0)
                column(x, top + ph - h1 - gap - h2, bw, h2, c2)
                if (i % every == 0) label(pad2(hourOf(b.start)), x + bw / 2, top + ph + 17.dp.toPx())
            }
        }
        sel?.let { i ->
            val b = perHour[i]
            val h = hourOf(b.start)
            TipBox(
                Tip(
                    "${pad2(h)}:00 to ${pad2((h + 1) % 24)}:00",
                    listOf(Triple("${b.dirs[1]}", dirNames.getOrElse(0) { "Direction 1" }, C.dir1), Triple("${b.dirs[2]}", dirNames.getOrElse(1) { "Direction 2" }, C.dir2)),
                ),
                Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** Speeds in 5 km/h bands, bands above the limit in red, with the limit and the 85th percentile marked. */
@Composable
fun SpeedChart(s: Summary, modifier: Modifier = Modifier) {
    val range = remember(s) { Report.speedBands(s) }
    var sel by remember(s) { mutableStateOf<Int?>(null) }
    if (range == null || range.bands.isEmpty()) {
        Box(modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
            Text("No speeds measured yet", color = C.muted, fontSize = 13.sp)
        }
        return
    }
    val bands = range.bands
    Box(modifier.fillMaxWidth().height(170.dp)) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(s) {
                detectTapGestures { p ->
                    val left = 34.dp.toPx()
                    val slot = (size.width - left - 6.dp.toPx()) / bands.size
                    val i = floor((p.x - left) / slot).toInt()
                    sel = if (i in bands.indices && sel != i) i else null
                }
            },
        ) {
            val left = 34.dp.toPx()
            val top = 18.dp.toPx()
            val bottom = 24.dp.toPx()
            val pw = size.width - left - 6.dp.toPx()
            val ph = size.height - top - bottom
            val vmax = axes(left, top, pw, ph, max(1, bands.maxOf { it.n })).toFloat()
            val slot = pw / bands.size
            val bw = max(2.dp.toPx(), min(24.dp.toPx(), slot - 2.dp.toPx()))
            val labelEvery = if (bands.size > 16) 20 else 10
            bands.forEachIndexed { i, b ->
                val x = left + i * slot + (slot - bw) / 2
                val color = if (b.from >= s.limit) C.over else C.dir1
                column(x, top + ph - b.n / vmax * ph, bw, b.n / vmax * ph, if (sel != null && sel != i) color.copy(alpha = 0.45f) else color)
                if (b.from % labelEvery == 0) label("${b.from}", left + i * slot, top + ph + 17.dp.toPx())
            }
            fun xAt(kmh: Double) = left + ((kmh - range.first) / (range.last - range.first)).toFloat() * pw
            val lx = xAt(s.limit.toDouble())
            drawLine(
                C.muted, Offset(lx, top - 6.dp.toPx()), Offset(lx, top + ph), 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
            )
            label("${s.limit} limit", lx + 4.dp.toPx(), top + 2.dp.toPx(), align = android.graphics.Paint.Align.LEFT)
            val sp = s.speedsAll
            if (sp.n > 0) {
                val qx = xAt(sp.p85)
                drawLine(C.text, Offset(qx, top + 10.dp.toPx()), Offset(qx, top + ph), 1.dp.toPx())
                val text = "85%: ${floor(sp.p85 + 0.5).roundToInt()}"
                val paint = android.graphics.Paint().apply { textSize = 11.sp.toPx() }
                val tw = paint.measureText(text)
                val right = qx + 4.dp.toPx() + tw > size.width
                label(text, if (right) qx - 4.dp.toPx() else qx + 4.dp.toPx(), top + 16.dp.toPx(), C.text, if (right) android.graphics.Paint.Align.RIGHT else android.graphics.Paint.Align.LEFT)
            }
        }
        sel?.let { i ->
            val b = bands[i]
            TipBox(
                Tip("${b.from} to ${b.to} km/h", listOf(Triple("${b.n}", if (b.n == 1) "vehicle" else "vehicles", if (b.from >= s.limit) C.over else C.dir1))),
                Modifier.align(Alignment.TopEnd),
            )
        }
    }
}
