package io.github.ndev.roadsight.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.FocusMeteringAction
import android.util.Size
import android.view.Surface
import androidx.camera.core.AspectRatio
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.ndev.roadsight.App
import io.github.ndev.roadsight.debug.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Where camera frames go, and at what size. */
data class CameraUse(val sink: FrameSink, val resolution: Size, val name: String)

/** The camera's zoom now, and how far it goes each way (below 1 is the ultra-wide lens). */
class ZoomInfo(val ratio: Float, val min: Float, val max: Float)

/**
 * How the camera is focusing: `mode` is auto (the camera chooses, and refocuses), far (held at a
 * distance), held (on a spot that was tapped) or fixed (a lens that can't focus). For the screens and
 * debug mode.
 */
class FocusInfo(
    val mode: String,
    /** The lens can be held at a distance (manual focus). */
    val manual: Boolean,
    /** Where "far" holds the lens (metres; null = infinity, or the lens can't say). */
    val farMetres: Double?,
    /** Where the lens is focused now (metres; null = infinity or unknown), from the camera's last frame. */
    val lensMetres: Double?,
    val calibrated: Boolean,
    val afState: Int?,
) {
    fun text(): String {
        val af = when (afState) {
            0 -> "idle"; 1 -> "searching"; 2 -> "focused"; 3 -> "searching"; 4 -> "locked"; 5 -> "locked, not sharp"; 6 -> "not sharp"; else -> "?"
        }
        val lens = if (!calibrated) "uncalibrated lens" else lensMetres?.let { "lens at %.1f m".format(it) } ?: "lens at infinity"
        val far = if (mode == "far") farMetres?.let { " (sharp from %.1f m)".format(it / 2) } ?: " (infinity)" else ""
        return "focus $mode$far · $lens · AF $af${if (!manual && mode != "fixed") " · no manual focus" else ""}"
    }
}

/**
 * The back camera, owned by the app rather than by a screen, so it can keep running for counting and
 * plate watching while the screen is off ([keepRunning], used by the background service). A screen
 * shows the picture by attaching its PreviewView ([attach] and [detach]); frames go to one [CameraUse]
 * at a time. Runs on the main thread.
 */
class CameraHost(private val context: Context) : LifecycleOwner {
    companion object {
        val PLATES_RES = Size(1920, 1080)

        /** Traffic needs less detail than plates: the area around the lines is shrunk to 416 pixels anyway. */
        val TRAFFIC_RES = Size(1280, 720)
    }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** The camera while it's set up, for the torch and zoom. */
    val camera = MutableStateFlow<Camera?>(null)
    val zoom = MutableStateFlow<ZoomInfo?>(null)

    /** What keeps the camera running without a screen (counting, plate watching), if anything. */
    val background = MutableStateFlow<CameraUse?>(null)

    /** Frames a second arriving from the camera (whether or not they're analysed), for debug mode. */
    val cameraFps = MutableStateFlow(0.0)

    /** How the camera is focusing ("auto", "far", "held" or "fixed"). */
    val focusMode = MutableStateFlow("auto")

    /** The focus in detail, about twice a second, for debug mode. */
    val focus = MutableStateFlow<FocusInfo?>(null)

    // What the lens can do, read when the camera is set up.
    @Volatile
    private var canFocus = false

    @Volatile
    private var manualFocus = false

    @Volatile
    private var calibrated = false

    /** Where "far" holds the lens, in dioptres (1 / metres; 0 = infinity). */
    @Volatile
    private var farDiopters = 0f
    private var held = false

    @Volatile
    private var lensDiopters: Float? = null

    @Volatile
    private var afState: Int? = null
    private var focusPosted = 0L

    /** The lens's position and focus state from each frame the camera takes. */
    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lensDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            afState = result.get(CaptureResult.CONTROL_AF_STATE)
            val now = SystemClock.elapsedRealtime()
            if (now - focusPosted > 500) {
                focusPosted = now
                publishFocus()
            }
        }
    }

    private var provider: ProcessCameraProvider? = null
    private var loading = false

    @Volatile
    private var view: PreviewView? = null

    /** A screen is showing the camera (rather than it running only in the background). */
    val hasScreen: Boolean get() = view != null
    private var surfaceView: PreviewView? = null
    private var shown: CameraUse? = null
    private var bound: CameraUse? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var rotation = Surface.ROTATION_0
    private var wantZoom = 1f

    @Volatile
    private var sink: FrameSink? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val converter = FrameConverter()
    private var lastError = 0L
    private var lastFrameT = 0.0
    private var frameFps = 0.0

    init {
        registry.currentState = Lifecycle.State.CREATED
    }

    /** A screen shows the camera: its picture in `view`, frames to `use`. Refused while the camera is busy in the background with something else. */
    fun attach(view: PreviewView, use: CameraUse, zoom: Float) {
        val bg = background.value
        if (bg != null && bg != use) return
        this.view = view
        shown = use
        wantZoom = zoom
        refresh()
    }

    fun detach(view: PreviewView) {
        if (this.view !== view) return
        this.view = null
        shown = null
        refresh()
    }

    /** Keeps the camera running for `use` without a screen (null: stop doing so). */
    fun keepRunning(use: CameraUse?) {
        background.value = use
        refresh()
    }

    fun setRotation(r: Int) {
        rotation = r
        analysis?.targetRotation = r
        preview?.targetRotation = r
    }

    fun setZoom(z: Float) {
        wantZoom = z
        applyZoom()
    }

    private fun applyZoom() {
        val cam = camera.value ?: return
        val zs = cam.cameraInfo.zoomState.value
        val z = if (zs != null) wantZoom.coerceIn(zs.minZoomRatio, zs.maxZoomRatio) else wantZoom
        runCatching { cam.cameraControl.setZoomRatio(z) }
    }

    /** What the lens can do: focus at all, be held at a distance, and whether its distances are real. */
    private fun readLens(cam: Camera) {
        runCatching {
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            val min = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            val hyper = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE) ?: 0f
            val cal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION)
                ?: CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
            val caps = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val afModes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            canFocus = min > 0f
            calibrated = cal != CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
            manualFocus = canFocus && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps && CameraMetadata.CONTROL_AF_MODE_OFF in afModes
            // "Far" focuses at the hyperfocal distance, which keeps everything from half of it to the
            // horizon sharp; a lens that can't measure its distances is set to infinity.
            farDiopters = if (calibrated && hyper > 0f && hyper < min) hyper else 0f
            DebugLog.add(
                "camera",
                "Lens: " + (if (!canFocus) "fixed focus" else "focuses from ${if (calibrated) "%.2f m".format(1 / min) else "$min (uncalibrated)"}") +
                    ", hyperfocal ${if (hyper > 0 && calibrated) "%.1f m".format(1 / hyper) else "unknown"}, manual focus ${if (manualFocus) "yes" else "no"}",
            )
        }.onFailure { DebugLog.error("camera", "Couldn't read what the lens can do", it) }
    }

    /** Applies the focus setting for what the camera is doing (Settings, or the ⋮ menu). Clears a tapped focus. */
    fun refreshFocus() {
        if (held) runCatching { camera.value?.cameraControl?.cancelFocusAndMetering() }
        held = false
        applyFocus()
    }

    private fun wantsFar(): Boolean {
        val p = App.instance.prefs
        return if (bound?.name == "traffic") p.trafficFocusFar else p.platesFocusFar
    }

    private fun applyFocus() {
        val cam = camera.value ?: return
        if (held) return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val mode = when {
            !canFocus -> "fixed"
            wantsFar() && manualFocus -> "far"
            else -> "auto"
        }
        runCatching {
            if (mode == "far") {
                c2.setCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                        .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, farDiopters)
                        .build(),
                )
            } else {
                c2.clearCaptureRequestOptions()
            }
        }.onFailure { DebugLog.error("camera", "Couldn't set the focus", it) }
        if (mode != focusMode.value) DebugLog.add("camera", "Focus: $mode" + (if (wantsFar() && !manualFocus && canFocus) " (this camera can't hold a distance)" else ""))
        focusMode.value = mode
        publishFocus()
    }

    /** Focuses on the spot tapped (preview coordinates) and holds it there until [refreshFocus]. */
    fun focusAt(x: Float, y: Float): Boolean {
        val cam = camera.value ?: return false
        val v = view ?: return false
        if (!canFocus) return false
        held = true
        focusMode.value = "held"
        val point = v.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).disableAutoCancel().build()
        // Far focus turns autofocus off: back on first, then focus on the spot.
        val cleared = Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
        cleared.addListener({ runCatching { cam.cameraControl.startFocusAndMetering(action) } }, ContextCompat.getMainExecutor(context))
        DebugLog.add("camera", "Focus held on a tapped spot")
        publishFocus()
        return true
    }

    private fun publishFocus() {
        val d = lensDiopters
        focus.value = FocusInfo(
            mode = focusMode.value,
            manual = manualFocus,
            farMetres = if (calibrated && farDiopters > 0f) 1.0 / farDiopters else null,
            lensMetres = if (calibrated && d != null && d > 0f) 1.0 / d else null,
            calibrated = calibrated,
            afState = afState,
        )
    }

    private fun refresh() {
        val use = background.value ?: shown
        if (use == null) {
            // Nothing needs the camera: it closes (the setup stays, so it opens again quickly).
            registry.currentState = Lifecycle.State.CREATED
            setSurface(null)
            return
        }
        val p = provider
        if (p == null) {
            load()
            return
        }
        if (bound != use) bind(p, use)
        sink = use.sink
        // No picture without a screen: the camera then only produces frames for analysis.
        setSurface(view)
        registry.currentState = Lifecycle.State.RESUMED
        applyZoom()
    }

    private fun setSurface(v: PreviewView?) {
        if (surfaceView === v) return
        surfaceView = v
        preview?.setSurfaceProvider(v?.surfaceProvider)
    }

    private fun load() {
        if (loading) return
        loading = true
        val f = ProcessCameraProvider.getInstance(context)
        f.addListener({
            loading = false
            provider = runCatching { f.get() }.getOrElse {
                DebugLog.error("camera", "The camera couldn't start", it)
                null
            }
            if (provider != null) refresh()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bind(p: ProcessCameraProvider, use: CameraUse) {
        p.unbindAll()
        camera.value = null
        surfaceView = null
        val aspect = AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val pvb = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
            .setTargetRotation(rotation)
        Camera2Interop.Extender(pvb).setSessionCaptureCallback(captureCallback)
        val pv = pvb.build()
        val an = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(aspect)
                    .setResolutionStrategy(ResolutionStrategy(use.resolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(rotation)
            .build()
        an.setAnalyzer(executor) { image -> analyze(image) }
        preview = pv
        analysis = an
        try {
            val cam = p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, an)
            bound = use
            camera.value = cam
            cam.cameraInfo.zoomState.removeObservers(this)
            cam.cameraInfo.zoomState.observe(this) { zs -> zoom.value = ZoomInfo(zs.zoomRatio, zs.minZoomRatio, zs.maxZoomRatio) }
            DebugLog.add("camera", "Camera set up for ${use.name}, asking for ${use.resolution}")
            readLens(cam)
            held = false
            applyFocus()
        } catch (e: Throwable) {
            bound = null
            DebugLog.error("camera", "The camera couldn't start", e)
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val t = image.imageInfo.timestamp / 1e6
            if (lastFrameT > 0) {
                val inst = 1000 / maxOf(1.0, t - lastFrameT)
                frameFps = if (frameFps == 0.0) inst else frameFps * 0.9 + inst * 0.1
                cameraFps.value = frameFps
            }
            lastFrameT = t
            val s = sink ?: return
            if (s.wants(t)) s.onFrame(converter.convert(image), t)
        } catch (e: Throwable) {
            val now = System.currentTimeMillis()
            if (now - lastError > 5000) {
                lastError = now
                DebugLog.error("camera", "A frame failed", e)
            }
        } finally {
            image.close()
        }
    }
}

/**
 * The camera picture on a screen, letterboxed so what you see is what the AI sees, with frames going
 * to `use`. The camera itself belongs to the app ([CameraHost]).
 */
@Composable
fun CameraPreview(modifier: Modifier, use: CameraUse, zoom: Float) {
    val context = LocalContext.current
    val root = LocalView.current
    val host = App.instance.camera
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentZoom by rememberUpdatedState(zoom)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    fun rotation() = root.display?.rotation ?: Surface.ROTATION_0

    DisposableEffect(lifecycle, use) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> {
                    host.setRotation(rotation())
                    host.attach(previewView, use, currentZoom)
                }
                Lifecycle.Event.ON_STOP -> host.detach(previewView)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            host.detach(previewView)
        }
    }

    // Turning the phone (including upside down): frames follow the screen.
    DisposableEffect(Unit) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                host.setRotation(rotation())
            }
        }
        dm?.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(listener) }
    }

    LaunchedEffect(zoom) { host.setZoom(zoom) }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Zoom buttons worth offering for a camera: its widest (if below 1×), 1×, and some steps up to its longest. */
fun zoomSteps(z: ZoomInfo?): List<Float> {
    if (z == null) return emptyList()
    val out = ArrayList<Float>()
    if (z.min < 0.95f) out.add(z.min)
    for (s in listOf(1f, 2f, 3f, 5f, 10f)) if (s >= z.min - 0.01f && s <= z.max + 0.01f) out.add(s)
    return if (out.size > 1) out else emptyList()
}

/** "0.5×", "1×", "2.5×" */
fun zoomLabel(z: Float): String {
    val r = Math.round(z * 10) / 10f
    return if (r == Math.round(r).toFloat()) "${Math.round(r)}×" else "$r×"
}
