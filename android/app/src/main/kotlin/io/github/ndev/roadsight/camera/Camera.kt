package io.github.ndev.roadsight.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/** Receives camera frames on a background thread: the frame (upright) and when it was captured (ms). */
interface FrameSink {
    /** Whether a frame captured at `timeMs` is wanted (lets idle scanning skip frames cheaply). */
    fun wants(timeMs: Double): Boolean = true
    fun onFrame(bitmap: Bitmap, timeMs: Double)
}

/** Turns CameraX's RGBA frames into bitmaps, reusing one bitmap so a frame doesn't cost megabytes of garbage. */
class FrameConverter {
    private var bitmap: Bitmap? = null

    fun convert(image: ImageProxy): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        var b = bitmap
        if (b == null || b.width != w || b.height != h) {
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bitmap = b
        }
        if (plane.pixelStride == 4 && plane.rowStride == w * 4) {
            val buf = plane.buffer
            buf.rewind()
            b!!.copyPixelsFromBuffer(buf)
        } else {
            b = image.toBitmap()
        }
        val rot = image.imageInfo.rotationDegrees
        if (rot != 0) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            return Bitmap.createBitmap(b!!, 0, 0, b.width, b.height, m, true)
        }
        return b!!
    }
}

/**
 * The back camera: a preview (letterboxed, so what you see is what the AI sees) and upright frames
 * for [sink]. Both use 16:9 so they show the same field of view.
 */
@Composable
fun CameraPreview(
    modifier: Modifier,
    sink: FrameSink,
    resolution: Size,
    onCamera: (Camera?) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentSink by rememberUpdatedState(sink)
    val currentOnCamera by rememberUpdatedState(onCamera)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val holder = remember { arrayOfNulls<ImageAnalysis>(1) }
    val orientation = LocalConfiguration.current.orientation

    DisposableEffect(lifecycleOwner, resolution) {
        val executor = Executors.newSingleThreadExecutor()
        val converter = FrameConverter()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val aspect = AspectRatioStrategy(androidx.camera.core.AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(ResolutionStrategy(resolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setOutputImageRotationEnabled(true)
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .build()
            analysis.setAnalyzer(executor) { image ->
                try {
                    val t = image.imageInfo.timestamp / 1e6
                    val s = currentSink
                    if (s.wants(t)) s.onFrame(converter.convert(image), t)
                } catch (e: Throwable) {
                    android.util.Log.e("RoadSight", "Frame failed", e)
                } finally {
                    image.close()
                }
            }
            holder[0] = analysis
            try {
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                currentOnCamera(cam)
            } catch (e: Throwable) {
                android.util.Log.e("RoadSight", "Camera failed", e)
                currentOnCamera(null)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            holder[0] = null
            currentOnCamera(null)
            executor.shutdown()
        }
    }

    // Turning the phone: frames follow the screen's orientation.
    LaunchedEffect(orientation) {
        holder[0]?.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
