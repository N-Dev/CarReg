package io.github.ndev.roadsight.video

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.core.traffic.Kinds
import io.github.ndev.roadsight.core.traffic.Report
import io.github.ndev.roadsight.core.traffic.directionNames
import io.github.ndev.roadsight.core.traffic.regionFor
import io.github.ndev.roadsight.plates.PlateSheet
import io.github.ndev.roadsight.plates.PlateView
import io.github.ndev.roadsight.traffic.CountRow
import io.github.ndev.roadsight.traffic.SetupForm
import io.github.ndev.roadsight.traffic.TrafficOverlay
import io.github.ndev.roadsight.traffic.TrafficUi
import io.github.ndev.roadsight.traffic.clock
import io.github.ndev.roadsight.traffic.fmtDistance
import io.github.ndev.roadsight.traffic.moved
import io.github.ndev.roadsight.traffic.pick
import io.github.ndev.roadsight.traffic.presetLines
import io.github.ndev.roadsight.traffic.videoRect
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.PlateGraphic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A video given to the app (shared, or picked), and what to do with it. */
class VideoRequest(val uri: Uri, val mode: String) {
    companion object {
        const val PLATES = "plates"
        const val TRAFFIC = "traffic"
    }
}

private class Loaded(val info: VideoInfo, val still: Bitmap)

/** Reads the video's details and a frame to show, off the main thread. */
@Composable
private fun rememberVideo(uri: Uri): Pair<Loaded?, String?> {
    val context = LocalContext.current
    var loaded by remember(uri) { mutableStateOf<Loaded?>(null) }
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(uri) {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val d = VideoDecoder(context.applicationContext, uri)
                val info = d.info()
                Loaded(info, d.still(1280) ?: throw IllegalStateException("no frames could be read"))
            }
        }
        r.onSuccess { loaded = it }.onFailure { failure = "That video can’t be read here (${it.message ?: it.javaClass.simpleName})." }
    }
    return loaded to failure
}

/** While a video screen is open: the screen stays on, and the app turns freely (the video decides). */
@Composable
private fun VideoScreenEffects(busy: Boolean) {
    val app = App.instance
    val view = LocalView.current
    DisposableEffect(Unit) {
        app.videoScreen.value = true
        onDispose { app.videoScreen.value = false }
    }
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
        Text(title, color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { content() }
}

private fun infoText(i: VideoInfo): String = listOfNotNull(
    clock(i.durationMs).removePrefix("0:"),
    "${i.width}×${i.height}",
    i.fps?.let { "${Math.round(it)} fps" },
    i.recorded?.let { "recorded ${Report.whenText(it)}" },
).joinToString(" · ")

/** The frame (a still, or what the AI is looking at), letterboxed like the camera picture. */
@Composable
private fun Frame(bitmap: Bitmap, modifier: Modifier) {
    Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}

// ---------------------------------------------------------------- traffic

/**
 * Counting the traffic in a video: set the lines on a frame of it (as for the camera), then the whole
 * video is analysed, and saved as a counting session with its charts and report.
 */
@Composable
fun VideoCountScreen(uri: Uri, onClose: () -> Unit) {
    val app = App.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val (loaded, error) = rememberVideo(uri)

    var away by remember { mutableStateOf(prefs.roadAway) }
    var lines by remember { mutableStateOf(presetLines(prefs.roadAway)) }
    var distanceText by remember { mutableStateOf(fmtDistance(prefs.distanceM)) }
    var limit by remember { mutableStateOf(prefs.speedLimit) }
    var dir1 by remember { mutableStateOf("") }
    var dir2 by remember { mutableStateOf("") }
    var site by remember { mutableStateOf(prefs.site) }
    var job by remember { mutableStateOf<VideoCountJob?>(null) }
    val none = remember { MutableStateFlow<VideoProgress?>(null) }
    var result by remember { mutableStateOf<Long?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val running = job != null && result == null && failed == null
    VideoScreenEffects(running)
    DisposableEffect(Unit) { onDispose { job?.cancelled = true } }
    BackHandler { if (running) job?.cancelled = true else onClose() }

    val info = loaded?.info
    val aspect = info?.aspect ?: (9.0 / 16)
    LaunchedEffect(info) {
        if (info != null && dir1.isEmpty()) {
            val n = directionNames(lines, aspect)
            dir1 = n.first
            dir2 = n.second
        }
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        TopBar("Count traffic in a video") { if (running) job?.cancelled = true else onClose() }
        if (loaded == null) {
            Centred {
                if (error != null) Text(error, color = C.muted, textAlign = TextAlign.Center) else CircularProgressIndicator(color = C.mint)
            }
            return@Column
        }
        val i = loaded.info
        val progress by (job?.progress ?: none).collectAsState()
        val editing = job == null
        val roi = regionFor(lines, aspect)

        val stage: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(Color.Black)) {
                Frame(progress?.preview ?: loaded.still, Modifier.fillMaxSize())
                val ui = TrafficUi(ready = progress != null && !editing, aspect = aspect, roi = roi, boxes = progress?.boxes ?: emptyList())
                TrafficOverlay(
                    ui = ui, lines = lines, distanceM = distanceText.replace(',', '.').toDoubleOrNull() ?: prefs.distanceM,
                    editing = editing, aspect = aspect, roi = roi, debug = false,
                    modifier = Modifier.fillMaxSize().pointerInput(editing, aspect) {
                        if (!editing) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val r = videoRect(size.width.toFloat(), size.height.toFloat(), aspect)
                            val grab = pick(lines, down.position, r, 30.dp.toPx(), 24.dp.toPx()) ?: return@awaitEachGesture
                            down.consume()
                            val start = lines
                            drag(down.id) { change ->
                                lines = moved(start, grab, change.position, r)
                                change.consume()
                            }
                        }
                    },
                )
            }
        }
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.background(C.bg).imePadding().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Text(infoText(i), color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp))
                    if (editing) {
                        if (i.recorded == null) {
                            Text(
                                "The video doesn’t say when it was recorded, so the counts are dated from now. Rename the site to say when, if it matters.",
                                color = C.amber, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 8.dp),
                            )
                        }
                        SetupForm(
                            away = away,
                            onAway = { a ->
                                away = a
                                lines = presetLines(a)
                                val n = directionNames(lines, aspect)
                                dir1 = n.first
                                dir2 = n.second
                            },
                            zoomSteps = emptyList(), zoom = 1f, onZoom = {},
                            distanceText = distanceText, onDistance = { distanceText = it },
                            limit = limit, onLimit = { limit = it },
                            dir1 = dir1, onDir1 = { dir1 = it }, dir2 = dir2, onDir2 = { dir2 = it },
                            site = site, onSite = { site = it },
                        )
                    } else {
                        val p = progress
                        val frac = if (p != null && p.duration > 0) (p.t / p.duration).toFloat().coerceIn(0f, 1f) else 0f
                        Text(
                            when {
                                failed != null -> failed!!
                                result != null -> "Done: the whole video is counted"
                                else -> "Counting… keep this screen open"
                            },
                            color = if (failed != null) C.red else C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { frac }, color = C.mint, trackColor = C.surface2, modifier = Modifier.fillMaxWidth())
                        if (p != null) {
                            Text(
                                "${clock(p.t.toLong())} of ${clock(p.duration.toLong())} · ${"%.1f".format(p.speed)}× real time · ${p.frames} frames",
                                color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        val t = p?.totals ?: emptyMap()
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            CountRow("", "A→B", "B→A", "Total", header = true)
                            for (k in Kinds.ORDER.filter { (t[it]?.get(0) ?: 0) > 0 || it in listOf("car", "truck", "bicycle", "person") }) {
                                val v = t[k] ?: IntArray(3)
                                CountRow(Kinds[k]!!.label, "${v[1]}", "${v[2]}", "${v[0]}")
                            }
                        }
                        Text("A→B: $dir1 · B→A: $dir2", color = C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        editing -> {
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Cancel") }
                            Button(
                                onClick = {
                                    val dist = distanceText.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 1 } ?: prefs.distanceM
                                    val setup = VideoSetup(
                                        lines, Math.round(dist * 10) / 10.0, limit, dir1.trim().ifEmpty { "A to B" }, dir2.trim().ifEmpty { "B to A" },
                                        site.trim(), i.recorded ?: (System.currentTimeMillis() - i.durationMs),
                                    )
                                    // What was typed is remembered for next time, like the camera's set-up.
                                    prefs.distanceM = setup.distanceM
                                    prefs.speedLimit = limit
                                    if (setup.site.isNotEmpty()) prefs.site = setup.site
                                    val j = VideoCountJob(app, uri, i, setup)
                                    job = j
                                    scope.launch {
                                        val r = withContext(Dispatchers.Default) { runCatching { j.run() } }
                                        r.onSuccess { id -> if (id != null) result = id else onClose() }
                                            .onFailure { failed = "The video couldn’t be counted: ${it.message ?: it.javaClass.simpleName}" }
                                    }
                                },
                                modifier = Modifier.weight(1.4f),
                                colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
                            ) { Text("Start counting", fontWeight = FontWeight.Bold) }
                        }
                        result != null -> {
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Another video") }
                            Button(
                                onClick = { app.openSession.value = result },
                                modifier = Modifier.weight(1.4f),
                                colors = ButtonDefaults.buttonColors(containerColor = C.mint, contentColor = C.bg),
                            ) { Text("See results", fontWeight = FontWeight.Bold) }
                        }
                        failed != null -> OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                        else -> Button(
                            onClick = { job?.cancelled = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = C.red, contentColor = C.bg),
                        ) { Text("Stop", fontWeight = FontWeight.Bold) }
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
            val config = LocalConfiguration.current
            Column(Modifier.fillMaxSize()) {
                stage(Modifier.weight(1f).fillMaxWidth())
                panel(Modifier.fillMaxWidth().heightIn(max = (config.screenHeightDp * 0.55f).dp.coerceAtMost(460.dp)))
            }
        }
    }
}

// ---------------------------------------------------------------- plates

/** Reading the plates in a video: the whole video is analysed and the plates are listed with when they appear. */
@Composable
fun VideoPlatesScreen(uri: Uri, onClose: () -> Unit) {
    val app = App.instance
    val (loaded, error) = rememberVideo(uri)
    var job by remember { mutableStateOf<VideoPlatesJob?>(null) }
    val none = remember { MutableStateFlow<VideoProgress?>(null) }
    var done by remember { mutableStateOf<List<VideoPlate>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<VideoPlate?>(null) }
    val running = job != null && done == null && failed == null
    VideoScreenEffects(running)
    DisposableEffect(Unit) { onDispose { job?.cancelled = true } }
    BackHandler { if (running) job?.cancelled = true else onClose() }

    // It starts straight away: there's nothing to set up.
    LaunchedEffect(loaded) {
        val l = loaded ?: return@LaunchedEffect
        if (job != null) return@LaunchedEffect
        val j = VideoPlatesJob(app, uri, l.info)
        job = j
        val r = withContext(Dispatchers.Default) { runCatching { j.run() } }
        r.onSuccess { list -> if (list != null) done = list else onClose() }
            .onFailure { failed = "The video couldn’t be read: ${it.message ?: it.javaClass.simpleName}" }
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        TopBar("Plates in a video") { if (running) job?.cancelled = true else onClose() }
        if (loaded == null) {
            Centred {
                if (error != null) Text(error, color = C.muted, textAlign = TextAlign.Center) else CircularProgressIndicator(color = C.mint)
            }
            return@Column
        }
        val progress by (job?.progress ?: none).collectAsState()
        val p = progress
        val plates = done ?: p?.plates ?: emptyList()
        Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).background(Color.Black)) {
            val bmp = p?.preview ?: loaded.still
            Frame(bmp, Modifier.fillMaxWidth().heightIn(max = 260.dp))
            if (done == null && p != null) PlateBoxes(p, bmp.height.toDouble() / bmp.width, Modifier.matchParentSize())
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(infoText(loaded.info), color = C.muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    failed != null -> failed!!
                    done != null -> "Done: ${plates.size} plate${if (plates.size == 1) "" else "s"} found" +
                        (job?.ignored?.takeIf { it > 0 }?.let { " (and $it of your cars, left out)" } ?: "")
                    else -> "Reading the plates… keep this screen open"
                },
                color = if (failed != null) C.red else C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            )
            if (done == null && failed == null) {
                Spacer(Modifier.height(8.dp))
                val frac = if (p != null && p.duration > 0) (p.t / p.duration).toFloat().coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, color = C.mint, trackColor = C.surface2, modifier = Modifier.fillMaxWidth())
                if (p != null) {
                    Text(
                        "${clock(p.t.toLong())} of ${clock(p.duration.toLong())} · ${"%.1f".format(p.speed)}× real time",
                        color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            for (v in plates) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(C.surface)
                        .border(if (v.watched) 2.dp else 1.dp, if (v.watched) C.sky else C.line, RoundedCornerShape(14.dp))
                        .clickable { selected = v }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    v.thumb?.let {
                        Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        PlateGraphic(v.result.text, Formats.bandFor(v.result.region), yellow = v.result.profile == "UK")
                        val bits = listOfNotNull(
                            "at ${clock(v.atMs.toLong()).removePrefix("0:")}",
                            if (v.sightings > 1) "seen ${v.sightings} times" else null,
                            "${Math.round(v.result.score * 100)}% sure",
                            if (v.watched) "on your watchlist" else null,
                        )
                        Text(bits.joinToString(" · "), color = if (v.watched) C.sky else C.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
            if (done != null) {
                OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Another video") }
                Text(
                    if (app.prefs.history) "The plates are saved in History (from a video)." else "Saving to history is off, so these aren’t kept.",
                    color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(bottom = 20.dp),
                )
            } else if (running) {
                OutlinedButton(onClick = { job?.cancelled = true }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("Stop") }
            }
        }
    }

    selected?.let { v ->
        val r = v.result
        PlateSheet(
            PlateView(
                text = r.text, key = r.key, region = r.region, profile = r.profile, format = r.format, valid = r.valid, info = r.info,
                score = r.score, thumb = v.thumb?.asImageBitmap(),
            ),
            onDismiss = { selected = null },
        )
    }
}

/** Plate boxes over the preview (boxes are fractions of the frame). */
@Composable
private fun PlateBoxes(p: VideoProgress, aspect: Double, modifier: Modifier) {
    Canvas(modifier) {
        val r = videoRect(size.width, size.height, aspect)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 12.sp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for (b in p.boxes) {
            val l = r.x + (b.box[0] * r.w).toFloat()
            val t = r.y + (b.box[1] * r.h).toFloat()
            val w = ((b.box[2] - b.box[0]) * r.w).toFloat()
            val h = ((b.box[3] - b.box[1]) * r.h).toFloat()
            val color = if (b.counted) C.mint else Color.White.copy(alpha = 0.7f)
            drawRoundRect(color, Offset(l, t), Size(w, h), CornerRadius(4f, 4f), style = Stroke(2.dp.toPx()))
            if (b.label.isNotEmpty()) {
                paint.color = color.toArgb()
                drawContext.canvas.nativeCanvas.drawText(b.label, l, t - 4.dp.toPx(), paint)
            }
        }
    }
}
