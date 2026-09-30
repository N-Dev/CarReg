package io.github.ndev.roadsight.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.ai.SpeedResult
import io.github.ndev.roadsight.ai.SpeedTest
import io.github.ndev.roadsight.ui.C
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * At first launch: an offer to run the speed test, which finds the quickest way to run each AI model on
 * this phone. It runs right there, in a dialog, and the fastest setups are used straight away.
 */
@Composable
fun SpeedTestOffer() {
    val app = App.instance
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf("Starting…") }
    var test by remember { mutableStateOf<SpeedTest?>(null) }
    var results by remember { mutableStateOf<List<SpeedResult>>(emptyList()) }

    // Offered once: "Not now" or finishing the test marks it done.
    when (if (stage == "" && !app.prefs.speedTestOffered) "offer" else stage) {
        "offer" -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false),
            containerColor = C.surface,
            title = { Text("Make RoadSight as quick as it can be?", color = C.text) },
            text = {
                Text(
                    "A short test (a minute or two) tries each way of running the AI on this phone, checks the answers are right, and keeps the fastest. " +
                        "You can run it again any time in Settings.",
                    color = C.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = SpeedTest(app)
                    test = t
                    stage = "running"
                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            runCatching { t.run(Model.entries) { msg -> progress = msg } }.getOrDefault(emptyList())
                        }
                        results = r
                        if (!t.cancelled && r.any { it.ok }) t.useFastest(r)
                        app.prefs.speedTestOffered = true
                        stage = if (t.cancelled) "" else "done"
                    }
                }) { Text("Test now", color = C.mint, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { app.prefs.speedTestOffered = true }) { Text("Not now", color = C.muted) }
            },
        )
        "running" -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            containerColor = C.surface,
            title = { Text("Testing this phone’s speed", color = C.text) },
            text = {
                Column {
                    LinearProgressIndicator(color = C.mint, trackColor = C.surface2, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text(progress, color = C.muted, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { test?.cancelled = true }) { Text("Stop", color = C.red) }
            },
        )
        "done" -> AlertDialog(
            onDismissRequest = { stage = "" },
            containerColor = C.surface,
            title = { Text(if (results.any { it.ok }) "Done: using the fastest" else "The test didn’t finish", color = C.text) },
            text = {
                Column {
                    val best = results.filter { it.ok && it.ms != null }.groupBy { it.model }.mapValues { (_, v) -> v.minBy { it.ms!! } }
                    if (best.isEmpty()) {
                        Text("RoadSight will use the phone’s processor, which works everywhere. You can try again in Settings.", color = C.muted)
                    }
                    for ((m, r) in best) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(m.label, color = C.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text("${r.cfg.label} · %.0f ms".format(r.ms), color = C.text, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { stage = "" }) { Text("OK", color = C.mint) } },
        )
    }
}
