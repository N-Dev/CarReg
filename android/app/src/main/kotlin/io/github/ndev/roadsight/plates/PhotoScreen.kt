package io.github.ndev.roadsight.plates

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.SENS
import io.github.ndev.roadsight.ai.BitmapFrame
import io.github.ndev.roadsight.ai.Model
import io.github.ndev.roadsight.core.plate.Formats
import io.github.ndev.roadsight.core.plate.PlatePipeline
import io.github.ndev.roadsight.core.plate.PlateResult
import io.github.ndev.roadsight.core.plate.vote
import io.github.ndev.roadsight.ui.C
import io.github.ndev.roadsight.ui.Ic
import io.github.ndev.roadsight.ui.PlateGraphic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** A plate found in a photo: its reading, where it is, and a crop of it. */
class PhotoPlate(val result: PlateResult, val box: DoubleArray, val thumb: Bitmap?)

private sealed class PhotoState {
    data object Loading : PhotoState()
    class Done(val plates: List<PhotoPlate>, val width: Int, val height: Int, val ms: Double, val deep: Boolean) : PhotoState()
    class Failed(val message: String) : PhotoState()
}

/** The most pixels kept from a photo along its long side (enough for small plates, without huge bitmaps). */
private const val MAX_SIDE = 4096

/**
 * Photo mode (PlateSight's photo.js): the accurate models, three slightly different crops of each plate
 * voted together, and a tiled deep scan when no plate is found at first.
 */
@Composable
fun PhotoScreen(uri: Uri, onClose: () -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    // Another photo can be picked from here: it replaces this one.
    var current by remember(uri) { mutableStateOf(uri) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var state by remember { mutableStateOf<PhotoState>(PhotoState.Loading) }
    var selected by remember { mutableStateOf<PhotoPlate?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) current = u }
    BackHandler(onBack = onClose)

    LaunchedEffect(current) {
        val u = current
        state = PhotoState.Loading
        shown = null
        state = withContext(Dispatchers.Default) {
            try {
                val bmp = decode(context, u)
                shown = display(bmp).asImageBitmap()
                read(app, bmp)
            } catch (e: Throwable) {
                android.util.Log.e("RoadSight", "Photo failed", e)
                PhotoState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        (state as? PhotoState.Done)?.let { if (it.plates.isNotEmpty()) app.haptic(24) }
    }

    Column(Modifier.fillMaxSize().background(C.bg).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = C.text) }
            Text("Photo", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(Ic.photo, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Another photo")
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            val img = shown
            val s = state
            if (img != null) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.heightIn(max = 480.dp).aspectRatio(img.width.toFloat() / img.height, matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(14.dp)),
                    ) {
                        Image(img, contentDescription = "The photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                        if (s is PhotoState.Done) PhotoOverlay(s, Modifier.fillMaxSize())
                        if (s is PhotoState.Loading) {
                            Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = C.mint)
                            }
                        }
                    }
                }
            } else if (s is PhotoState.Loading) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = C.mint) }
            }
            Spacer(Modifier.height(14.dp))
            when (s) {
                is PhotoState.Loading -> Text("Reading plates…", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                is PhotoState.Failed -> {
                    Text("Couldn’t read this photo", color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(s.message, color = C.muted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                }
                is PhotoState.Done -> {
                    val n = s.plates.size
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (n > 0) "$n plate${if (n == 1) "" else "s"} found" else "No plates found",
                            color = C.text, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                        )
                        Text("${if (s.deep) "deep scan · " else ""}${"%.1f".format(s.ms / 1000)} s", color = C.muted, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (n == 0) {
                        Text(
                            "Nothing readable here. Try a closer, sharper shot with the plate facing the camera. Glare and steep angles make plates hard to read.",
                            color = C.muted, fontSize = 14.sp,
                        )
                    }
                    s.plates.forEachIndexed { i, p -> PhotoCard(i + 1, p) { selected = p } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    selected?.let { p ->
        PlateSheet(
            PlateView(
                text = p.result.text, key = p.result.key, region = p.result.region, profile = p.result.profile,
                format = p.result.format, valid = p.result.valid, info = p.result.info, score = p.result.score,
                thumb = p.thumb?.asImageBitmap(), source = "photo",
            ),
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun PhotoCard(n: Int, p: PhotoPlate, onClick: () -> Unit) {
    val r = p.result
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(14.dp)).background(C.surface)
            .border(1.dp, C.line, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(if (r.valid) C.mint else C.amber), contentAlignment = Alignment.Center) {
            Text("$n", color = C.bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        p.thumb?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            PlateGraphic(r.text, Formats.bandFor(r.region), yellow = r.profile == "UK")
            val bits = listOfNotNull(
                r.region?.let { "${Formats.flagFor(it)} $it".trim() },
                formatName(r.valid, r.profile, r.format).takeIf { it.isNotEmpty() },
                registered(r.profile, r.info).takeIf { it.isNotEmpty() },
                "${(r.score * 100).roundToInt()}% sure",
            )
            Text(bits.joinToString(" · "), color = C.muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Numbered boxes on the photo, placed like the image (fitted inside the box, centred). */
@Composable
private fun PhotoOverlay(s: PhotoState.Done, modifier: Modifier) {
    Canvas(modifier) {
        val scale = minOf(size.width / s.width, size.height / s.height)
        val ox = (size.width - s.width * scale) / 2
        val oy = (size.height - s.height * scale) / 2
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 12.sp.toPx()
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        s.plates.forEachIndexed { i, p ->
            val color = if (p.result.valid) C.mint else C.amber
            val l = ox + (p.box[0] * scale).toFloat()
            val t = oy + (p.box[1] * scale).toFloat()
            val r = ox + (p.box[2] * scale).toFloat()
            val b = oy + (p.box[3] * scale).toFloat()
            drawRoundRect(color, Offset(l, t), Size(r - l, b - t), CornerRadius(4.dp.toPx()), style = Stroke(2.5.dp.toPx()))
            val rad = 10.dp.toPx()
            val cx = l
            val cy = (t - rad - 2.dp.toPx()).coerceAtLeast(rad)
            drawCircle(color, rad, Offset(cx, cy))
            paint.color = C.bg.toArgb()
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", cx, cy + paint.textSize * 0.36f, paint)
        }
    }
}

/** Reads the photo's plates with the accurate models (and saves them to history). */
private fun read(app: App, bmp: Bitmap): PhotoState {
    val engine = app.engine
    val prefs = app.prefs
    val finder = engine.net(Model.DET640)
    val reader = engine.net(Model.OCR_ACC)
    val pipe = PlatePipeline(engine.ocrConfig)
    val conf = minOf(0.3, SENS[prefs.sensitivity] ?: 0.35)
    val res = pipe.analyze(BitmapFrame(bmp), finder, 640, reader, "ocrAcc", conf, maxPlates = 12, minW = 14.0, variants = PlatePipeline.TTA, deep = true)
    val found = res.boxes.mapNotNull { b ->
        val reads = b.reads ?: return@mapNotNull null
        val r = vote(reads, prefs.format, 0.2) ?: return@mapNotNull null
        if (r.key.length < 3 || r.prob < 0.35) return@mapNotNull null
        PhotoPlate(r, b.box, crop(bmp, b.box))
    }.sortedWith(compareBy<PhotoPlate>({ it.box[0] }, { it.box[1] }))
    if (prefs.history) {
        val keep = prefs.keepPhotos
        val saves = found.map { it.result to (if (keep) it.thumb?.let { t -> PlateScanner.jpeg(t) } else null) }
        app.io.execute {
            for ((r, jpg) in saves) runCatching { app.db.savePlate(r, "photo", jpg) }
            app.dataChanged()
        }
    }
    return PhotoState.Done(found, bmp.width, bmp.height, res.msTotal, res.tiles != null)
}

private fun crop(bmp: Bitmap, box: DoubleArray): Bitmap? = runCatching {
    val r = PlatePipeline.thumbRect(box, bmp.width, bmp.height)
    val c = Bitmap.createBitmap(bmp, r[0], r[1], r[2], r[3])
    val out = Bitmap.createScaledBitmap(c, r[4], r[5], true)
    if (out != c) c.recycle()
    out
}.getOrNull()

/** A copy small enough to draw on screen. */
private fun display(bmp: Bitmap, maxSide: Int = 2048): Bitmap {
    val s = maxSide.toDouble() / max(bmp.width, bmp.height)
    if (s >= 1) return bmp
    return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
}

/** The photo, upright, in memory the CPU can read (not a hardware bitmap), at most [MAX_SIDE] pixels along its long side. */
private fun decode(context: Context, uri: Uri): Bitmap {
    val cr = context.contentResolver
    if (Build.VERSION.SDK_INT >= 28) {
        val src = ImageDecoder.createSource(cr, uri)
        return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val s = MAX_SIDE.toDouble() / max(w, h)
            if (s < 1) decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: error("This file isn’t a picture RoadSight can open")
    val deg = runCatching {
        cr.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)
    if (deg == 0) return bmp
    val m = Matrix().apply { postRotate(deg.toFloat()) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
}
