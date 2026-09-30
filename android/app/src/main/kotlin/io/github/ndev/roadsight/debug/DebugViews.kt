package io.github.ndev.roadsight.debug

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.ui.C
import kotlinx.coroutines.delay

/** How the phone is coping: temperature, battery and memory. */
class Health(
    val thermal: String,
    val headroom: Float?,
    val batteryTemp: Double?,
    val battery: Int?,
    val charging: Boolean,
    val heapMb: Long,
    val nativeMb: Long,
) {
    fun text(): String {
        val h = headroom?.takeIf { !it.isNaN() }?.let { " (headroom %.2f)".format(it) } ?: ""
        val b = listOfNotNull(
            batteryTemp?.let { "%.1f°C".format(it) },
            battery?.let { "$it%" },
            if (charging) "charging" else null,
        ).joinToString(" ")
        return "heat: $thermal$h · battery $b · memory ${heapMb + nativeMb} MB"
    }
}

private val THERMAL = mapOf(0 to "none", 1 to "light", 2 to "moderate", 3 to "severe", 4 to "critical", 5 to "emergency", 6 to "shutdown")

fun readHealth(context: Context): Health {
    val pm = context.getSystemService(PowerManager::class.java)
    val thermal = if (Build.VERSION.SDK_INT >= 29 && pm != null) THERMAL[pm.currentThermalStatus] ?: "?" else "n/a"
    val headroom = if (Build.VERSION.SDK_INT >= 30 && pm != null) runCatching { pm.getThermalHeadroom(10) }.getOrNull() else null
    val bi: Intent? = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    val temp = bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
    val level = bi?.let {
        val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (l >= 0 && s > 0) l * 100 / s else null
    }
    val plugged = (bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val rt = Runtime.getRuntime()
    return Health(
        thermal, headroom, temp, level, plugged,
        (rt.totalMemory() - rt.freeMemory()) / 1_048_576, android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576,
    )
}

/** The phone's health, read every two seconds while debug mode shows it. */
@Composable
fun rememberHealth(on: Boolean): Health? {
    val context = LocalContext.current
    var h by remember { mutableStateOf<Health?>(null) }
    LaunchedEffect(on) {
        while (on) {
            h = runCatching { readHealth(context) }.getOrNull()
            delay(2000)
        }
    }
    return if (on) h else null
}

/** Which way each model is running: "det384 CPU 4 · ocrFast CPU 4". */
fun enginesText(models: List<Model>): String {
    val e = App.instance.engine
    return models.joinToString(" · ") { m ->
        val c = e.configFor(m)
        "${m.key} ${if (c.accel.name == "NNAPI") "NNAPI" else "${c.accel.name} ${c.threads}"}"
    }
}

/** Debug mode's readout: lines of numbers and a graph of recent frame times (ms). */
@Composable
fun DebugStrip(lines: List<String>, times: List<Float>, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xB3000000)).padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        for (l in lines) Text(l, color = Color(0xFFD7F5E8), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, lineHeight = 13.sp)
        if (times.size >= 2) {
            Canvas(Modifier.fillMaxWidth().height(28.dp).padding(top = 3.dp)) {
                val maxT = maxOf(50f, times.max())
                val step = size.width / (times.size - 1)
                val p = Path()
                times.forEachIndexed { i, t ->
                    val x = i * step
                    val y = size.height - t / maxT * size.height
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                // Reference line at 66 ms (15 frames a second).
                val ref = size.height - 66f / maxT * size.height
                if (ref > 0) drawLine(Color(0x55FFFFFF), Offset(0f, ref), Offset(size.width, ref), 1f)
                drawPath(p, C.mint, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
