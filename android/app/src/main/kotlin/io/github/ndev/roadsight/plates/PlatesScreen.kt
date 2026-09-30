package io.github.ndev.roadsight.plates

import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.CameraGate
import io.github.ndev.roadsight.camera.CameraPreview
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Ic
import io.github.ndev.roadsight.ui.Pill
import io.github.ndev.roadsight.ui.PlateGraphic

private val LIVE_RES = Size(1920, 1080)

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
    var camera by remember { mutableStateOf<Camera?>(null) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var selected by remember { mutableStateOf<TrayItem?>(null) }

    DisposableEffect(paused) {
        if (!paused) app.plates.start()
        onDispose { app.plates.stop() }
    }
    val view = LocalView.current
    DisposableEffect(paused) {
        view.keepScreenOn = !paused
        onDispose { view.keepScreenOn = false }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!paused) {
            CameraPreview(Modifier.fillMaxSize(), app.plates, LIVE_RES) { cam ->
                camera = cam
                torch = false
                zoom = 1f
            }
            LiveOverlay(ui, Modifier.fillMaxSize())
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
                paused -> "Paused"
                !ui.ready -> ui.status
                else -> "%.0f fps · %.0f ms · %s%s".format(ui.fps, ui.msTotal, ui.tier, if (ui.idle) " · idle" else "")
            }
            Pill(status, dot = if (ui.ready && !paused) C.mint else C.amber)
            Spacer(Modifier.weight(1f))
            val cam = camera
            if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                RoundButton(Ic.torch, on = torch) {
                    torch = !torch
                    cam.cameraControl.enableTorch(torch)
                }
            }
            RoundButton(Ic.photo, onClick = onPick)
            RoundButton(if (paused) Ic.play else Ic.pause) { paused = !paused }
        }

        // Bottom: zoom and the plates found
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            notice?.let {
                Text(it, color = C.amber, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
            }
            val cam = camera
            val maxZoom = cam?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
            if (!paused && maxZoom >= 2f) {
                Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (z in listOf(1f, 2f, 3f).filter { it <= maxZoom }) {
                        val on = z == zoom
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(if (on) C.text else Color(0x99000000))
                                .clickable {
                                    zoom = z
                                    cam?.cameraControl?.setZoomRatio(z)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) { Text("${z.toInt()}×", color = if (on) C.bg else C.text, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
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
