package io.github.ndev.roadsight.traffic

import android.app.Activity
import android.content.res.Configuration
import android.os.BatteryManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.camera.CameraHost
import io.github.ndev.roadsight.camera.CameraPreview
import io.github.ndev.roadsight.camera.CameraUse
import io.github.ndev.roadsight.camera.zoomLabel
import io.github.ndev.roadsight.camera.zoomSteps
import io.github.ndev.roadsight.core.traffic.directionNames
import io.github.ndev.roadsight.plates.RoundButton
import io.github.ndev.roadsight.plates.ZoomChips
import io.github.ndev.roadsight.service.BackgroundService
import io.github.ndev.roadsight.ui.Segmented
import io.github.ndev.roadsight.ui.rememberNotificationRequest
import io.github.ndev.roadsight.core.traffic.Kinds
import io.github.ndev.roadsight.core.traffic.Lines
import io.github.ndev.roadsight.core.traffic.regionFor
import io.github.ndev.roadsight.plates.corners
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Pill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

private val LIMITS = listOf(30, 50, 60, 80, 100, 120)
private val TNUM = TextStyle(fontFeatureSettings = "tnum")

/** Where the camera picture sits in a box of `w` x `h` (letterboxed, centred). `aspect` = frame height / width. */
class VideoRect(val x: Float, val y: Float, val w: Float, val h: Float)

fun videoRect(w: Float, h: Float, aspect: Double): VideoRect {
    val fw = min(w, (h / aspect).toFloat())
    val fh = (fw * aspect).toFloat()
    return VideoRect((w - fw) / 2, (h - fh) / 2, fw, fh)
}

/** A line end (end 0 or 1), or a whole line (end -1), being dragged. */
private class Grab(val line: Char, val end: Int, val from: Offset)

/** The lines to start from: across the picture, or for a road running away from the camera. */
fun presetLines(away: Boolean): Lines = if (away) Lines.away() else Lines.default()

@Composable
fun TrafficScreen() {
    val app = App.instance
    val prefs = app.prefs
    val context = LocalContext.current
    val view = LocalView.current
    val ui by app.traffic.ui.collectAsState()
    val notice by app.engine.notice.collectAsState()
    val prefsVersion by prefs.version.collectAsState()
    val config = LocalConfiguration.current
    val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(prefs.lines) }
    var distanceText by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable { mutableStateOf(prefs.speedLimit) }
    var dir1 by rememberSaveable { mutableStateOf("") }
    var dir2 by rememberSaveable { mutableStateOf("") }
    var site by rememberSaveable { mutableStateOf("") }
    var draftZoom by rememberSaveable { mutableFloatStateOf(prefs.trafficZoom.toFloat()) }
    var draftAway by rememberSaveable { mutableStateOf(prefs.roadAway) }
    var menu by remember { mutableStateOf(false) }
    val host = app.camera
    val zoomInfo by host.zoom.collectAsState()
    val bg by host.background.collectAsState()
    // The camera is watching for plates in the background: it can't count traffic at the same time.
    val busy = bg != null && bg?.sink !== app.traffic
    val use = remember { CameraUse(app.traffic, CameraHost.TRAFFIC_RES, "traffic") }
    val askNotifications = rememberNotificationRequest()
    val here = if (landscape) "landscape" else "portrait"

    // Until the first frame arrives, assume the picture is the shape of the screen.
    val aspect = if (ui.ready) ui.aspect else if (landscape) 9.0 / 16 else 16.0 / 9
    val saved = remember(prefsVersion) { prefs.lines }
    val lines = if (editing) draft else saved
    val roi = if (editing) regionFor(draft, aspect) else ui.roi

    fun openEditor(fresh: Boolean = false) {
        if (ui.running) return
        draftAway = prefs.roadAway
        draft = if (fresh) presetLines(draftAway) else prefs.lines.copy()
        draftZoom = prefs.trafficZoom.toFloat()
        distanceText = fmtDistance(prefs.distanceM)
        limit = prefs.speedLimit
        dir1 = prefs.dir1
        dir2 = prefs.dir2
        site = prefs.site
        if (fresh) {
            val n = directionNames(draft, aspect)
            dir1 = n.first
            dir2 = n.second
        }
        editing = true
    }

    fun saveEditor() {
        prefs.lines = draft
        distanceText.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 1 }?.let { prefs.distanceM = Math.round(it * 10) / 10.0 }
        prefs.speedLimit = limit
        prefs.dir1 = dir1.trim().ifEmpty { "A to B" }
        prefs.dir2 = dir2.trim().ifEmpty { "B to A" }
        prefs.site = site.trim()
        prefs.trafficZoom = draftZoom.toDouble()
        prefs.roadAway = draftAway
        // The lines fit the picture this way round (and at this zoom): the Traffic tab keeps to both.
        prefs.trafficOrientation = here
        prefs.setupDone = true
        app.traffic.reconfigure()
        editing = false
    }

    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = ui.running && !editing) {
        scope.launch { snack.showSnackbar("Tap Stop to finish counting") }
    }

    // The screen stays on while the camera is watching the road.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // Without background counting, the camera stops while the app is in the background (or the screen
    // is off), and so does counting.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var stoppedAt = 0L
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && app.traffic.ui.value.running && BackgroundService.mode.value != BackgroundService.TRAFFIC) stoppedAt = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START && stoppedAt > 0) {
                val gap = (System.currentTimeMillis() - stoppedAt) / 1000
                stoppedAt = 0
                if (gap >= 3 && app.traffic.ui.value.running) {
                    scope.launch { snack.showSnackbar("Counting was paused for ${clock(gap * 1000)} while RoadSight wasn’t on screen", duration = SnackbarDuration.Long) }
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // A running session's end time is saved every 30 seconds, so nothing is lost if the phone dies.
    LaunchedEffect(ui.running) {
        while (ui.running) {
            delay(30_000)
            app.traffic.touchSession()
        }
    }

    // Dimming: after a while without a touch, the screen goes dark to save battery and heat.
    val lastTouch = remember { longArrayOf(System.currentTimeMillis()) }
    var dimmed by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var battery by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var warnedBattery by remember { mutableStateOf(false) }
    LaunchedEffect(ui.running, editing, prefsVersion) {
        lastTouch[0] = System.currentTimeMillis()
        var tick = 0
        while (true) {
            now = System.currentTimeMillis()
            val dimAfter = prefs.dimAfter
            dimmed = ui.running && !editing && dimAfter > 0 && now - lastTouch[0] > dimAfter * 1000L
            if (tick++ % 30 == 0) battery = batteryLevel(context)
            val b = battery
            if (ui.running && b != null && !b.second && b.first < 25 && !warnedBattery) {
                warnedBattery = true
                scope.launch { snack.showSnackbar("Battery below 25%. Plug the phone in to keep counting.", duration = SnackbarDuration.Long) }
            }
            delay(1000)
        }
    }
    val window = (context as? Activity)?.window
    DisposableEffect(dimmed) {
        window?.let { w ->
            val lp = w.attributes
            lp.screenBrightness = if (dimmed) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        }
        onDispose {
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    fun startStop() {
        if (ui.running) {
            val totals = ui.totals
            val id = app.traffic.stopSession()
            var motor = 0
            var others = 0
            for ((k, v) in totals) if (Kinds.isMotor(k)) motor += v[0] else others += v[0]
            val msg = "Saved: $motor motor vehicle${if (motor == 1) "" else "s"}, $others other road user${if (others == 1) "" else "s"}"
            scope.launch {
                val r = snack.showSnackbar(msg, actionLabel = "See results", duration = SnackbarDuration.Long)
                if (r == SnackbarResult.ActionPerformed && id != null) app.openSession.value = id
            }
        } else {
            if (editing) saveEditor()
            // Counting keeps to the way round it started, so a knock doesn't move the lines.
            if (prefs.trafficOrientation.isEmpty()) prefs.trafficOrientation = here
            if (prefs.backgroundCounting) askNotifications()
            app.traffic.startSession()
            lastTouch[0] = System.currentTimeMillis()
            warnedBattery = false
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            // Every touch anywhere counts as activity (without taking the touch from what's under it).
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial)
                    lastTouch[0] = System.currentTimeMillis()
                }
            }
        },
    ) {
        val stage: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black)) {
                if (busy) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("The camera is watching for plates", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text("It carries on in the background. Stop watching to count traffic here.", color = C.muted, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { app.plates.stopWatching() }) { Text("Stop watching") }
                    }
                } else {
                    CameraPreview(Modifier.fillMaxSize(), use, if (editing) draftZoom else prefs.trafficZoom.toFloat())
                }
                if (!busy) TrafficOverlay(
                    ui = ui, lines = lines, distanceM = if (editing) distanceText.replace(',', '.').toDoubleOrNull() ?: prefs.distanceM else prefs.distanceM,
                    editing = editing, aspect = aspect, roi = roi,
                    modifier = Modifier.fillMaxSize().pointerInput(editing, aspect) {
                        if (!editing) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val r = videoRect(size.width.toFloat(), size.height.toFloat(), aspect)
                            val grab = pick(draft, down.position, r, 30.dp.toPx(), 24.dp.toPx()) ?: return@awaitEachGesture
                            down.consume()
                            val start = draft
                            drag(down.id) { change ->
                                draft = moved(start, grab, change.position, r)
                                change.consume()
                            }
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val status = when {
                        busy -> "Watching for plates"
                        !ui.ready -> ui.status
                        else -> "${if (ui.running) "Counting" else "Watching"} · ${fps(ui.fps)} fps${if (ui.idle) " · idle" else ""}"
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        Pill(
                            status, dot = if (busy || !ui.ready) C.amber else if (ui.running) C.red else C.mint,
                            modifier = Modifier.clickable {
                                scope.launch {
                                    snack.showSnackbar(
                                        if (ui.ready) "${ui.model} model · AI ${ui.msAi.toInt()} ms a frame · picture ${ui.msPrep.toInt()} ms" else ui.status,
                                    )
                                }
                            },
                        )
                    }
                    Box {
                        RoundButton(Icons.Filled.MoreVert) { menu = true }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val other = if (landscape) "portrait" else "landscape"
                            DropdownMenuItem(
                                text = { Text("Set up for $other") },
                                enabled = !ui.running && !busy,
                                onClick = {
                                    menu = false
                                    // The lines only fit the picture one way round: turn it and set them again.
                                    prefs.trafficOrientation = other
                                    openEditor(fresh = true)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Keep counting with the screen off") },
                                leadingIcon = { Checkbox(checked = prefs.backgroundCounting, onCheckedChange = null) },
                                enabled = !ui.running,
                                onClick = {
                                    menu = false
                                    prefs.backgroundCounting = !prefs.backgroundCounting
                                },
                            )
                        }
                    }
                }
                if (editing) {
                    Text(
                        "Drag the ends of lines A and B onto two marks along the road",
                        color = C.text, fontSize = 13.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(10.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xCC07090D)).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                notice?.let {
                    Text(it, color = C.amber, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
        }
        // The counts (or the set-up form) scroll; the buttons under them are always in view.
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.background(C.bg).imePadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (editing) {
                        SetupForm(
                            away = draftAway,
                            onAway = { a ->
                                draftAway = a
                                draft = presetLines(a)
                                val n = directionNames(draft, aspect)
                                dir1 = n.first
                                dir2 = n.second
                            },
                            zoomSteps = zoomSteps(zoomInfo), zoom = draftZoom, onZoom = { draftZoom = it },
                            distanceText = distanceText, onDistance = { distanceText = it },
                            limit = limit, onLimit = { limit = it },
                            dir1 = dir1, onDir1 = { dir1 = it }, dir2 = dir2, onDir2 = { dir2 = it },
                            site = site, onSite = { site = it },
                        )
                    } else {
                        CountsPanel(ui, now)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (editing) {
                        OutlinedButton(
                            onClick = {
                                draft = presetLines(draftAway)
                                val n = directionNames(draft, aspect)
                                dir1 = n.first
                                dir2 = n.second
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("Reset lines") }
                        Button(onClick = { saveEditor() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg)) {
                            Text("Done", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(onClick = { openEditor() }, enabled = !ui.running && !busy, modifier = Modifier.weight(1f)) { Text("Set up") }
                        Button(
                            onClick = { startStop() },
                            enabled = (ui.ready && !busy) || ui.running,
                            modifier = Modifier.weight(1.4f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (ui.running) C.red else C.mint, contentColor = C.bg),
                        ) { Text(if (ui.running) "Stop" else "Start counting", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }

        if (landscape) {
            Row(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxHeight())
                panel(Modifier.width(320.dp).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxWidth())
                // The counts take up to 45% of the screen (the set-up form 55%); the picture gets the rest.
                val share = if (editing) 0.55f else 0.45f
                panel(Modifier.fillMaxWidth().heightIn(max = (config.screenHeightDp * share).dp.coerceAtMost(if (editing) 460.dp else 380.dp)))
            }
        }

        if (dimmed) DimScreen(ui, now, battery) {
            lastTouch[0] = System.currentTimeMillis()
            dimmed = false
        }
        SnackbarHost(snack, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp)) { data ->
            Snackbar(data, containerColor = C.surface2, contentColor = C.text, actionColor = C.mint)
        }
    }
}

private fun fps(v: Double) = if (v < 10) "%.1f".format(v) else "%.0f".format(v)

private fun batteryLevel(context: android.content.Context): Pair<Int, Boolean>? = runCatching {
    val bm = context.getSystemService(BatteryManager::class.java) ?: return null
    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    if (level < 0 || level > 100) null else level to bm.isCharging
}.getOrNull()

private fun clock(ms: Long): String {
    val secs = max(0L, ms / 1000)
    return "%d:%02d:%02d".format(secs / 3600, secs / 60 % 60, secs % 60)
}

@Composable
private fun ColumnScope.CountsPanel(ui: TrafficUi, now: Long) {
    val prefs = App.instance.prefs
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (ui.running) clock(now - ui.started) else "Not counting", color = C.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, style = TNUM)
            val sub = when {
                ui.running -> "counting" + (prefs.site.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                prefs.site.isNotEmpty() -> prefs.site
                !prefs.setupDone -> "Tap Set up to put the lines on your road"
                else -> ""
            }
            if (sub.isNotEmpty()) Text(sub, color = C.muted, fontSize = 12.5.sp, maxLines = 1)
        }
        ui.last?.let { last ->
            val kind = Kinds[last.kind]?.one ?: last.kind
            Pill("Last: $kind" + (last.speed?.let { " · ${Math.round(it)} km/h" } ?: ""))
        }
    }
    Spacer(Modifier.height(10.dp))
    val t = ui.totals
    val rows = Kinds.ORDER.filter { (t[it]?.get(0) ?: 0) > 0 || it in listOf("car", "truck", "bicycle", "person") }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        CountRow("", "A→B", "B→A", "Total", header = true)
        for (k in rows) {
            val v = t[k] ?: IntArray(3)
            CountRow(Kinds[k]!!.label, "${v[1]}", "${v[2]}", "${v[0]}")
        }
    }
    Text("A→B: ${prefs.dir1} · B→A: ${prefs.dir2}", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    if (prefs.trafficOrientation.isNotEmpty()) {
        Text(
            "Lines set up in ${prefs.trafficOrientation} at ${zoomLabel(prefs.trafficZoom.toFloat())}",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun CountRow(label: String, a: String, b: String, total: String, header: Boolean = false) {
    val color = if (header) C.muted else C.text
    val size = if (header) 12.sp else 14.5.sp
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = size, modifier = Modifier.weight(1.6f))
        Text(a, color = color, fontSize = size, textAlign = TextAlign.End, style = TNUM, modifier = Modifier.weight(1f))
        Text(b, color = color, fontSize = size, textAlign = TextAlign.End, style = TNUM, modifier = Modifier.weight(1f))
        Text(total, color = color, fontSize = size, textAlign = TextAlign.End, style = TNUM, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ColumnScope.SetupForm(
    away: Boolean,
    onAway: (Boolean) -> Unit,
    zoomSteps: List<Float>,
    zoom: Float,
    onZoom: (Float) -> Unit,
    distanceText: String,
    onDistance: (String) -> Unit,
    limit: Int,
    onLimit: (Int) -> Unit,
    dir1: String,
    onDir1: (String) -> Unit,
    dir2: String,
    onDir2: (String) -> Unit,
    site: String,
    onSite: (String) -> Unit,
) {
    Text("Set up the counting lines", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    Text(
        "Put the phone side-on to the road. Drag line A and line B across the road at two marks you can measure between, " +
            "like lamp posts, gateposts or road markings, 10–30 m apart.",
        color = C.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )
    Label("The road runs")
    Segmented(listOf("across" to "Across the picture", "away" to "Away from me"), if (away) "away" else "across") { onAway(it == "away") }
    Spacer(Modifier.height(12.dp))
    if (zoomSteps.isNotEmpty()) {
        Label("Zoom")
        ZoomChips(zoomSteps, zoom) { onZoom(it) }
        Text(
            "Zoom out to see more road. The lines are set on this view, so counting keeps this zoom.",
            color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
    Label("Distance between the lines")
    Row(verticalAlignment = Alignment.CenterVertically) {
        fun step(d: Double) {
            val v = distanceText.replace(',', '.').toDoubleOrNull() ?: App.instance.prefs.distanceM
            onDistance(fmtDistance(max(1.0, floor((v + d) * 2 + 0.5) / 2)))
        }
        OutlinedButton(onClick = { step(-1.0) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("−", fontSize = 20.sp) }
        OutlinedTextField(
            value = distanceText, onValueChange = { v -> onDistance(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
            singleLine = true, suffix = { Text("m") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(120.dp).padding(horizontal = 8.dp), colors = fieldColors(),
        )
        OutlinedButton(onClick = { step(1.0) }, modifier = Modifier.size(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("+", fontSize = 20.sp) }
    }
    Spacer(Modifier.height(12.dp))
    Label("Speed limit (km/h)")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (v in LIMITS) {
            val on = v == limit
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) C.text else C.surface2).clickable { onLimit(v) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$v", color = if (on) C.bg else C.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Label("Traffic going A → B is going…")
    OutlinedTextField(dir1, { onDir1(it.take(40)) }, singleLine = true, placeholder = { Text("e.g. towards the village") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
    Spacer(Modifier.height(8.dp))
    Label("Traffic going B → A is going…")
    OutlinedTextField(dir2, { onDir2(it.take(40)) }, singleLine = true, placeholder = { Text("e.g. towards the main road") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
    Spacer(Modifier.height(8.dp))
    Label("Where is this?")
    OutlinedTextField(site, { onSite(it.take(80)) }, singleLine = true, placeholder = { Text("e.g. Main Street, outside no. 12") }, modifier = Modifier.fillMaxWidth(), colors = fieldColors())
}

@Composable
private fun Label(text: String) {
    Text(text, color = C.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.mint, unfocusedBorderColor = C.line2, cursorColor = C.mint,
    focusedTextColor = C.text, unfocusedTextColor = C.text, focusedPlaceholderColor = C.muted, unfocusedPlaceholderColor = C.muted,
)

/** 20.0 → "20", 22.5 → "22.5" */
fun fmtDistance(d: Double): String = if (d == floor(d)) d.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, d)

/** The dark screen while counting: the running totals, big, and how to wake it. */
@Composable
private fun DimScreen(ui: TrafficUi, now: Long, battery: Pair<Int, Boolean>?, onWake: () -> Unit) {
    val t = ui.totals
    val motor = Kinds.ORDER.filter { Kinds.isMotor(it) }.sumOf { t[it]?.get(0) ?: 0 }
    Column(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { onWake() } },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("$motor", color = Color(0xFF3A4A44), fontSize = 72.sp, fontWeight = FontWeight.Bold, style = TNUM)
        Text("motor vehicles", color = Color(0xFF3A4046), fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            listOf("bicycle", "person", "dog").joinToString(" · ") { "${t[it]?.get(0) ?: 0} ${Kinds[it]!!.label.lowercase()}" },
            color = Color(0xFF3A4046), fontSize = 14.sp,
        )
        Spacer(Modifier.height(28.dp))
        val b = battery?.let { " · battery ${it.first}%${if (it.second) ", charging" else ""}" } ?: ""
        Text(
            "Counting ${clock(now - ui.started)}$b · tap to wake", color = Color(0xFF30363C), fontSize = 12.5.sp,
            textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** The line end or line under the finger (ends first), or null. */
private fun pick(lines: Lines, p: Offset, r: VideoRect, endReach: Float, lineReach: Float): Grab? {
    val fx = (p.x - r.x) / r.w
    val fy = (p.y - r.y) / r.h
    var best: Grab? = null
    var bestD = Float.MAX_VALUE
    val both = listOf('a' to lines.a, 'b' to lines.b)
    for ((name, l) in both) {
        for (end in 0..1) {
            val d = hypot((l[end * 2] - fx).toFloat() * r.w, (l[end * 2 + 1] - fy).toFloat() * r.h)
            if (d < endReach && d < bestD) {
                best = Grab(name, end, p)
                bestD = d
            }
        }
    }
    if (best != null) return best
    for ((name, l) in both) {
        val dx = (l[2] - l[0]).toFloat() * r.w
        val dy = (l[3] - l[1]).toFloat() * r.h
        val len2 = max(1f, dx * dx + dy * dy)
        val u = (((fx - l[0]).toFloat() * r.w) * dx + ((fy - l[1]).toFloat() * r.h) * dy) / len2
        val px = l[0] + u * (l[2] - l[0])
        val py = l[1] + u * (l[3] - l[1])
        val d = hypot((px - fx).toFloat() * r.w, (py - fy).toFloat() * r.h)
        if (u > 0 && u < 1 && d < lineReach && d < bestD) {
            best = Grab(name, -1, p)
            bestD = d
        }
    }
    return best
}

/** The lines with the grabbed end (or line) moved to follow the finger at `p`, kept inside the picture. */
private fun moved(start: Lines, g: Grab, p: Offset, r: VideoRect): Lines {
    val fx = ((p.x - r.x) / r.w).toDouble()
    val fy = ((p.y - r.y) / r.h).toDouble()
    val o = if (g.line == 'a') start.a else start.b
    val l: DoubleArray
    if (g.end >= 0) {
        l = o.copyOf()
        l[g.end * 2] = fx.coerceIn(0.0, 1.0)
        l[g.end * 2 + 1] = fy.coerceIn(0.0, 1.0)
    } else {
        val dx = fx - (g.from.x - r.x) / r.w
        val dy = fy - (g.from.y - r.y) / r.h
        val mx = max(-min(o[0], o[2]), min(dx, 1 - max(o[0], o[2])))
        val my = max(-min(o[1], o[3]), min(dy, 1 - max(o[1], o[3])))
        l = doubleArrayOf(o[0] + mx, o[1] + my, o[2] + mx, o[3] + my)
    }
    return if (g.line == 'a') Lines(l, start.b.copyOf()) else Lines(start.a.copyOf(), l)
}

/** The picture's overlay: the analysed area, road users (counted ones in green), the two lines and their distance. */
@Composable
private fun TrafficOverlay(ui: TrafficUi, lines: Lines, distanceM: Double, editing: Boolean, aspect: Double, roi: DoubleArray, modifier: Modifier) {
    Canvas(modifier) {
        val r = videoRect(size.width, size.height, aspect)
        fun x(v: Double) = r.x + (v * r.w).toFloat()
        fun y(v: Double) = r.y + (v * r.h).toFloat()
        corners(x(roi[0]), y(roi[1]), x(roi[0] + roi[2]), y(roi[1] + roi[3]))
        val canvas = drawContext.canvas.nativeCanvas
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 12.sp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        if (!editing && ui.ready) {
            for (b in ui.boxes) {
                val l = x(b.box[0])
                val t = y(b.box[1])
                val color = if (b.counted) C.mint else Color.White.copy(alpha = 0.55f)
                drawRect(color, Offset(l, t), androidx.compose.ui.geometry.Size(x(b.box[2]) - l, y(b.box[3]) - t), style = Stroke(if (b.flash) 3.dp.toPx() else 1.5.dp.toPx()))
                val tw = paint.measureText(b.label) + 10.dp.toPx()
                val th = 17.dp.toPx()
                drawRect(if (b.counted) Color(0xD906281E) else Color(0x8C000000), Offset(l, t - th - 1), androidx.compose.ui.geometry.Size(tw, th))
                paint.color = if (b.counted) C.mint.toArgb() else android.graphics.Color.WHITE
                paint.textAlign = android.graphics.Paint.Align.LEFT
                canvas.drawText(b.label, l + 5.dp.toPx(), t - 5.dp.toPx(), paint)
            }
        }
        for ((name, l) in listOf("A" to lines.a, "B" to lines.b)) {
            val p0 = Offset(x(l[0]), y(l[1]))
            val p1 = Offset(x(l[2]), y(l[3]))
            drawLine(Color(0x8C000000), p0, p1, 6.dp.toPx(), StrokeCap.Round)
            drawLine(Color.White, p0, p1, 2.5.dp.toPx(), StrokeCap.Round)
            val c = if (lines.level(aspect)) {
                // Level lines (a road running away): the letter goes at the left end.
                val left = if (p0.x <= p1.x) p0 else p1
                Offset(max(left.x - 18.dp.toPx(), 12.dp.toPx()), left.y)
            } else {
                val top = if (l[1] < l[3]) p0 else p1
                Offset(top.x, top.y - 16.dp.toPx())
            }
            drawCircle(Color.White, 11.dp.toPx(), c)
            paint.color = C.bg.toArgb()
            paint.textSize = 13.sp.toPx()
            paint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(name, c.x, c.y + 4.5.dp.toPx(), paint)
            if (editing) {
                for (h in listOf(p0, p1)) {
                    drawCircle(C.mint.copy(alpha = 0.25f), 18.dp.toPx(), h)
                    drawCircle(C.mint, 7.dp.toPx(), h)
                }
            }
        }
        // The measured distance between the lines, under them.
        val label = "${fmtDistance(distanceM)} m"
        paint.textSize = 12.sp.toPx()
        paint.textAlign = android.graphics.Paint.Align.LEFT
        val lw = paint.measureText(label) + 12.dp.toPx()
        val midX = x((lines.a[0] + lines.a[2] + lines.b[0] + lines.b[2]) / 4)
        val below = y(maxOf(lines.a[1], lines.a[3], lines.b[1], lines.b[3])) + 8.dp.toPx()
        val ly = min(below, r.y + r.h - 20.dp.toPx())
        drawRect(Color(0x99000000), Offset(midX - lw / 2, ly), androidx.compose.ui.geometry.Size(lw, 18.dp.toPx()))
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(label, midX - lw / 2 + 6.dp.toPx(), ly + 13.dp.toPx(), paint)
    }
}
