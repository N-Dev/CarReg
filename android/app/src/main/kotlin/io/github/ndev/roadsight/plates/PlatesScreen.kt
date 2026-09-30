package io.github.ndev.roadsight.plates

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.CameraGate
import io.github.ndev.roadsight.camera.CameraHost
import io.github.ndev.roadsight.camera.CameraPreview
import io.github.ndev.roadsight.camera.CameraUse
import io.github.ndev.roadsight.camera.zoomLabel
import io.github.ndev.roadsight.camera.zoomSteps
import io.github.ndev.roadsight.service.BackgroundService
import io.github.ndev.roadsight.ui.rememberNotificationRequest
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Ic
import io.github.ndev.roadsight.ui.Pill
import io.github.ndev.roadsight.ui.PlateGraphic

@Composable
fun PlatesScreen() {
    val app = App.instance
    val ui by app.plates.ui.collectAsState()
    val notice by app.engine.notice.collectAsState()
    var photo by remember { mutableStateOf<android.net.Uri?>(null) }
    val shared by app.sharedPhoto.collectAsState()
    LaunchedEffect(shared) {
        shared?.let {
            photo = it
            app.sharedPhoto.value = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) photo = uri }

    val current = photo
    if (current != null) {
        PhotoScreen(current, onClose = { photo = null })
        return
    }
    // Photos can be read without the camera.
    CameraGate(extra = {
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text("Read a photo instead")
        }
    }) { LiveScreen(app, ui, notice, onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) }
}

@Composable
private fun LiveScreen(app: App, ui: PlateUi, notice: String?, onPick: () -> Unit) {
    val host = app.camera
    val camera by host.camera.collectAsState()
    val zoomInfo by host.zoom.collectAsState()
    val bg by host.background.collectAsState()
    val bgMode by BackgroundService.mode.collectAsState()
    val watching = bgMode == BackgroundService.PLATES
    // The camera is counting traffic in the background: it can't read plates at the same time.
    val busy = bg != null && bg?.sink !== app.plates
    var paused by rememberSaveable { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(app.prefs.platesZoom.toFloat()) }
    var selected by remember { mutableStateOf<TrayItem?>(null) }
    var menu by remember { mutableStateOf(false) }
    val askNotifications = rememberNotificationRequest()
    val use = remember { CameraUse(app.plates, CameraHost.PLATES_RES, "plates") }

    DisposableEffect(paused, busy) {
        if (!paused && !busy) app.plates.start()
        // Watching in the background keeps reading plates when this screen goes.
        onDispose { if (!app.plates.watching) app.plates.stop() }
    }
    DisposableEffect(Unit) { onDispose { app.prefs.platesZoom = zoom.toDouble() } }
    val view = LocalView.current
    DisposableEffect(paused) {
        view.keepScreenOn = !paused
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(camera) { torch = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (busy) {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("The camera is counting traffic", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("It carries on in the background. Stop counting to read plates here.", color = C.muted, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = { app.traffic.stopSession() }) { Text("Stop counting") }
            }
        } else if (!paused) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    // Pinch to zoom, from the ultra-wide lens (if the phone has one) to the longest zoom.
                    detectTransformGestures { _, _, change, _ ->
                        val z = host.zoom.value
                        val next = zoom * change
                        zoom = if (z != null) next.coerceIn(z.min, z.max) else next.coerceIn(0.5f, 10f)
                    }
                },
            ) {
                CameraPreview(Modifier.fillMaxSize(), use, zoom)
                LiveOverlay(ui, Modifier.fillMaxSize())
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Scanning paused", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Tap play to read plates again.", color = C.muted)
            }
        }

        // Top: status and controls
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val status = when {
                busy -> "Counting traffic"
                paused -> "Paused"
                !ui.ready -> ui.status
                else -> (if (watching) "Watching · " else "") + "%.0f fps · %.0f ms · %s%s".format(ui.fps, ui.msTotal, ui.tier, if (ui.idle) " · idle" else "")
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Pill(status, dot = if (busy || !ui.ready || paused) C.amber else if (watching) C.sky else C.mint)
            }
            val cam = camera
            if (!busy && !paused && cam != null && cam.cameraInfo.hasFlashUnit()) {
                RoundButton(Ic.torch, on = torch) {
                    torch = !torch
                    cam.cameraControl.enableTorch(torch)
                }
            }
            RoundButton(Ic.photo, onClick = onPick)
            if (!busy) {
                RoundButton(if (paused) Ic.play else Ic.pause) {
                    if (!paused && watching) app.plates.stopWatching()
                    paused = !paused
                }
            }
            Box {
                RoundButton(Icons.Filled.MoreVert) { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Keep watching with the screen off") },
                        enabled = !busy && !paused,
                        leadingIcon = { Checkbox(checked = watching, onCheckedChange = null) },
                        onClick = {
                            menu = false
                            if (watching) {
                                app.plates.stopWatching()
                            } else {
                                askNotifications()
                                app.plates.startWatching()
                            }
                        },
                    )
                    HorizontalDivider(color = C.line2)
                    OrientationItems(app) { menu = false }
                }
            }
        }

        // Bottom: zoom and the plates found
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            notice?.let {
                Text(it, color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            val steps = zoomSteps(zoomInfo)
            if (!paused && !busy && steps.isNotEmpty()) {
                ZoomChips(steps, zoom, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)) { z ->
                    zoom = z
                    app.prefs.platesZoom = z.toDouble()
                }
            }
            Tray(ui.tray) { selected = it }
        }
    }

    selected?.let { item ->
        PlateSheet(
            PlateView(
                text = item.result.text, key = item.key, region = item.result.region, profile = item.result.profile,
                format = item.result.format, valid = item.result.valid, info = item.result.info, score = item.result.score,
                thumb = item.thumb?.asImageBitmap(),
            ),
            onDismiss = { selected = null },
            onDelete = {
                app.plates.removeFromTray(item.key)
                app.io.execute { runCatching { app.db.deletePlate(item.key) } }
                selected = null
            },
        )
    }
}

/** Zoom buttons (the current one highlighted; a zoom between steps highlights none). */
@Composable
fun ZoomChips(steps: List<Float>, zoom: Float, modifier: Modifier = Modifier, onZoom: (Float) -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (z in steps) {
            val on = kotlin.math.abs(z - zoom) < 0.05f
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) C.text else Color(0x99000000))
                    .clickable { onZoom(z) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text(zoomLabel(z), color = if (on) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Menu items for which way round the app is: follow the phone, or stay portrait or landscape. */
@Composable
fun OrientationItems(app: App, onDone: () -> Unit) {
    for ((value, label) in listOf("auto" to "Turn with the phone", "portrait" to "Keep portrait", "landscape" to "Keep landscape")) {
        DropdownMenuItem(
            text = { Text(label) },
            leadingIcon = { RadioButton(selected = app.prefs.orientation == value, onClick = null) },
            onClick = {
                app.prefs.orientation = value
                onDone()
            },
        )
    }
}

@Composable
fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean = false, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.padding(start = 6.dp).size(42.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (on) C.amber else Color(0x99141821), contentColor = if (on) C.bg else C.text),
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun Tray(items: List<TrayItem>, onClick: (TrayItem) -> Unit) {
    if (items.isEmpty()) {
        Text(
            "Point the camera at a number plate",
            color = C.muted, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().background(Color(0xCC07090D)).padding(16.dp),
        )
        return
    }
    LazyRow(
        Modifier.fillMaxWidth().background(Color(0xCC07090D)),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.key }) { e ->
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line2, RoundedCornerShape(14.dp))
                    .clickable { onClick(e) }.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                e.thumb?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 54.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(8.dp))
                }
                Column {
                    PlateGraphic(e.result.text, Formats.bandFor(e.result.region), yellow = e.result.profile == "UK")
                    Text("${Formats.flagFor(e.result.region)} ${Math.round(e.result.score * 100)}%", color = C.muted, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** Plate boxes over the picture, which is letterboxed exactly like the frames the AI sees. */
@Composable
private fun LiveOverlay(ui: PlateUi, modifier: Modifier) {
    Canvas(modifier) {
        if (ui.frameW == 0) return@Canvas
        val s = minOf(size.width / ui.frameW, size.height / ui.frameH)
        val ox = (size.width - ui.frameW * s) / 2
        val oy = (size.height - ui.frameH * s) / 2
        fun x(v: Double) = ox + (v * s).toFloat()
        fun y(v: Double) = oy + (v * s).toFloat()
        ui.region?.let { r -> corners(x(r[0].toDouble()), y(r[1].toDouble()), x((r[0] + r[2]).toDouble()), y((r[1] + r[3]).toDouble())) }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 13.sp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for (b in ui.boxes) {
            val color = when {
                b.confirmed && b.valid -> C.mint
                b.confirmed -> C.amber
                else -> Color.White.copy(alpha = 0.7f)
            }
            val l = x(b.box[0])
            val t = y(b.box[1])
            drawRoundRect(color, Offset(l, t), GSize(x(b.box[2]) - l, y(b.box[3]) - t), CornerRadius(6f, 6f), style = Stroke(width = if (b.confirmed) 3.dp.toPx() else 1.5.dp.toPx()))
            b.text?.let { text ->
                val w = paint.measureText(text) + 12.dp.toPx()
                val h = 20.dp.toPx()
                drawRoundRect(Color(0xCC000000), Offset(l, t - h - 4), GSize(w, h), CornerRadius(8f, 8f))
                paint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(text, l + 6.dp.toPx(), t - 4 - 6.dp.toPx(), paint)
            }
        }
    }
}

/** The analysed area, as faint corner marks. */
fun DrawScope.corners(l: Float, t: Float, r: Float, b: Float) {
    val k = 16.dp.toPx()
    val c = Color.White.copy(alpha = 0.35f)
    val w = 1.5.dp.toPx()
    for ((x, y, dx, dy) in listOf(listOf(l, t, 1f, 1f), listOf(r, t, -1f, 1f), listOf(l, b, 1f, -1f), listOf(r, b, -1f, -1f))) {
        drawLine(c, Offset(x, y), Offset(x + dx * k, y), w)
        drawLine(c, Offset(x, y), Offset(x, y + dy * k), w)
    }
}
