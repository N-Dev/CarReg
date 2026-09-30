package io.github.ndev.roadsight.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.BuildConfig
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.ai.SpeedResult
import io.github.ndev.roadsight.ai.SpeedTest
import io.github.ndev.roadsight.history.Confirm
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Card
import io.github.ndev.roadsight.ui.SectionTitle
import io.github.ndev.roadsight.ui.Segmented
import io.github.ndev.roadsight.ui.SettingRow
import io.github.ndev.roadsight.ui.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TNUM = TextStyle(fontFeatureSettings = "tnum")

private val QUALITY_TEXT = mapOf(
    "auto" to "Uses the sharpest models this phone can run smoothly, and eases off when it’s busy or warm.",
    "fast" to "Quickest models only. Best for older phones.",
    "balanced" to "The accurate reader for new plates, the quick one once a plate is confirmed.",
    "sharp" to "Sharper plate finder too, for small or distant plates. Needs a fast phone.",
)

private const val RELEASES = "https://github.com/N-Dev/CarReg/releases/latest"
private const val WEB = "https://n-dev.github.io/CarReg/"

@Composable
fun SettingsScreen() {
    val app = App.instance
    val p = app.prefs
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<String?>(null) }
    var askStrip by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("Settings", color = C.text, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))

        SectionTitle("READING PLATES")
        Card {
            SettingRow("Plate format", "Auto checks the format of the country the AI recognises on each plate.") {
                Segmented(listOf("auto" to "Auto", "IE" to "Ireland", "UK" to "UK", "ANY" to "Any"), p.format) { p.format = it }
            }
            SettingRow("Detection sensitivity", "Higher finds smaller or partly hidden plates but makes more mistakes.") {
                Segmented(listOf("low" to "Low", "medium" to "Medium", "high" to "High"), p.sensitivity) { p.sensitivity = it }
            }
            SettingRow("Live quality", QUALITY_TEXT[p.quality]) {
                Segmented(listOf("auto" to "Auto", "fast" to "Fast", "balanced" to "Balanced", "sharp" to "Sharp"), p.quality) { p.quality = it }
            }
            SwitchRow("Vibrate on a new plate or count", checked = p.haptics) { p.haptics = it }
        }

        SectionTitle("COUNTING TRAFFIC")
        Card {
            SettingRow("Detection model", "Standard is more accurate; Light is quicker on slower phones. Auto uses Standard and moves to Light if the phone can’t keep up.") {
                Segmented(listOf("auto" to "Auto", "tiny" to "Standard", "nano" to "Light"), p.trafficModel) { p.trafficModel = it }
            }
            SettingRow("Frame rate", "Fastest analyses every frame the phone can keep up with. Cooler analyses about 10 a second, still plenty for counting and speeds, and keeps the phone cooler on long or warm days.") {
                Segmented(listOf("fast" to "Fastest", "cool" to "Cooler"), p.trafficPace) { p.trafficPace = it }
            }
            SettingRow("Sensitivity", "Higher picks up smaller and further things, with more false alarms.") {
                Segmented(listOf("low" to "Low", "medium" to "Medium", "high" to "High"), p.trafficSensitivity) { p.trafficSensitivity = it }
            }
            SettingRow("Dim the screen while counting", "Saves battery and heat. Tap to wake.") {
                Segmented(listOf("0" to "Never", "60" to "After 1 min", "300" to "After 5 min"), p.dimAfter.toString()) { p.dimAfter = it.toInt() }
            }
        }

        SectionTitle("AI ENGINE")
        EngineSettings()

        SectionTitle("PRIVACY & DATA")
        Card {
            SwitchRow("Save plates to history", "Kept only on this phone.", checked = p.history) { p.history = it }
            SwitchRow("Keep photos of plates", "Off saves just the text.", checked = p.keepPhotos) {
                p.keepPhotos = it
                if (!it) askStrip = true
            }
            SettingRow("Delete plate history after", "Older plates are deleted automatically.") {
                Segmented(listOf("7" to "7 days", "30" to "30 days", "365" to "1 year", "forever" to "Never"), p.retention) {
                    p.retention = it
                    app.io.execute {
                        val n = runCatching { app.db.prunePlates(p.retentionDays()) }.getOrDefault(0)
                        if (n > 0) app.dataChanged()
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirm = "plates" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) {
                    Text("Delete all plates")
                }
                OutlinedButton(onClick = { confirm = "counts" }, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = C.red)) {
                    Text("Delete all counts")
                }
            }
            Text(
                "The camera picture is analysed on this phone and thrown away. RoadSight keeps only what you see in History: " +
                    "plates you scanned (with a small photo if you choose) and, for traffic, what passed and when. No video, and no " +
                    "number plates of traffic. Nothing leaves the phone unless you share it.",
                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            )
        }

        SectionTitle("ABOUT")
        Card {
            Text("RoadSight ${BuildConfig.VERSION_NAME}", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Build ${BuildConfig.VERSION_CODE}. PlateSight and TrafficSight in one app, running on this phone with ONNX Runtime.",
                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Number plates are personal data, so only scan where you have a good reason to, and don’t share or keep plates of people you don’t know.",
                color = C.muted, fontSize = 12.5.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Models: open-image-models (YOLOv9 plate finder) and fast-plate-ocr, both MIT licensed; YOLOX (Megvii, Apache-2.0) for traffic. " +
                    "Runtime: ONNX Runtime (MIT). Speeds are estimates from the time between the two lines.",
                color = C.muted, fontSize = 12.5.sp,
            )
            Row(Modifier.padding(top = 6.dp)) {
                TextButton(onClick = { open(context, RELEASES) }) { Text("Check for updates", color = C.mint) }
                TextButton(onClick = { open(context, WEB) }) { Text("Web versions", color = C.mint) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    when (confirm) {
        "plates" -> Confirm("Delete all saved plates?", "This can’t be undone.", "Delete all", onDismiss = { confirm = null }) {
            app.io.execute {
                runCatching { app.db.clearPlates() }
                app.dataChanged()
            }
            app.plates.clearTray()
            confirm = null
            Toast.makeText(context, "History cleared", Toast.LENGTH_SHORT).show()
        }
        "counts" -> Confirm("Delete every counting session?", "Their counts, charts and reports go too. This can’t be undone.", "Delete all", onDismiss = { confirm = null }) {
            if (app.traffic.ui.value.running) {
                Toast.makeText(context, "Stop counting first", Toast.LENGTH_SHORT).show()
            } else {
                app.io.execute {
                    runCatching { app.db.clearSessions() }
                    app.dataChanged()
                }
                Toast.makeText(context, "All counts deleted", Toast.LENGTH_SHORT).show()
            }
            confirm = null
        }
    }
    if (askStrip) {
        Confirm("Also remove photos already saved?", "The plates stay in History, without their photos.", "Remove", onDismiss = { askStrip = false }) {
            app.io.execute {
                runCatching { app.db.stripPhotos() }
                app.dataChanged()
            }
            askStrip = false
        }
    }
}

private fun open(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(context, url, Toast.LENGTH_LONG).show() }
}

/** Where the models run (CPU, XNNPACK or NNAPI, and how many threads), and a speed test that finds the fastest. */
@Composable
private fun EngineSettings() {
    val app = App.instance
    val p = app.prefs
    val engine = app.engine
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<SpeedTest?>(null) }
    var progress by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SpeedResult>?>(null) }
    DisposableEffect(Unit) { onDispose { running?.cancelled = true } }

    Card {
        SettingRow(
            "Accelerator",
            when (p.accel) {
                "auto" -> if (p.tuned.isEmpty()) "Auto uses XNNPACK until you run the speed test below, then the fastest setup it found for each model." else "Auto uses the fastest setup the speed test found for each model."
                "CPU" -> "ONNX Runtime’s own CPU code."
                "XNNPACK" -> "XNNPACK: CPU code tuned for phone processors. Often the fastest."
                else -> "NNAPI: Android’s route to the phone’s AI chip or graphics. Fast on some phones, slow or unavailable on others."
            },
        ) {
            Segmented(listOf("auto" to "Auto", "CPU" to "CPU", "XNNPACK" to "XNNPACK", "NNAPI" to "NNAPI"), p.accel) { p.accel = it }
        }
        val threadOptions = listOf(0, 2, 4, 6, 8).filter { it == 0 || it <= engine.cores }
        SettingRow("CPU threads", "This phone has ${engine.cores} cores. More threads are faster up to a point, then just warmer.") {
            Segmented(threadOptions.map { it.toString() to if (it == 0) "Auto" else "$it" }, p.threads.toString()) { p.threads = it.toInt() }
        }
        Text("Running now", color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        for (m in Model.entries) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(m.label, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text(engine.configFor(m).label, color = C.text, fontSize = 12.5.sp)
            }
        }
        HorizontalDivider(color = C.line, modifier = Modifier.padding(vertical = 12.dp))
        Text("Speed test", color = C.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Tries every setup on a sample photo, checks the answers are still right, and times it. Takes a minute or two; keep the app open.",
            color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )
        val test = running
        if (test != null) {
            LinearProgressIndicator(color = C.mint, trackColor = C.surface2, modifier = Modifier.fillMaxWidth())
            Text(progress, color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = { test.cancelled = true }) { Text("Stop", color = C.red) }
        } else {
            Button(
                onClick = {
                    val t = SpeedTest(app)
                    running = t
                    results = null
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            runCatching { t.run(Model.entries) { msg -> progress = msg } }.getOrElse { e ->
                                progress = "The test failed: ${e.message}"
                                emptyList()
                            }
                        }
                        results = r
                        running = null
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
            ) { Text("Test this phone’s speed", fontWeight = FontWeight.Bold) }
        }
        results?.let { r ->
            if (r.isEmpty()) {
                Text(progress.ifEmpty { "Stopped." }, color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp))
            } else {
                SpeedResults(r)
                Button(
                    onClick = {
                        SpeedTest(app).useFastest(r)
                        Toast.makeText(context, "Using the fastest setup for each model", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
                ) { Text("Use the fastest", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SpeedResults(results: List<SpeedResult>) {
    for ((m, rows) in results.groupBy { it.model }) {
        val best = rows.filter { it.ok && it.ms != null }.minByOrNull { it.ms!! }
        Text(m.label, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
        for (r in rows) {
            val isBest = r === best
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.cfg.label, color = if (isBest) C.mint else C.muted, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                Text(
                    when {
                        r.ms == null -> r.note ?: "failed"
                        !r.ok -> "%.0f ms · %s".format(r.ms, r.note ?: "wrong results")
                        else -> "%.0f ms".format(r.ms)
                    },
                    color = if (isBest) C.mint else if (r.ok) C.text else C.amber,
                    fontSize = 12.5.sp, style = TNUM, textAlign = TextAlign.End,
                    fontWeight = if (isBest) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}
