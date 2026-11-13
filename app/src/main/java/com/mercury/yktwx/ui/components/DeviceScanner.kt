package com.mercury.yktwx.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as CanvasSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mercury.yktwx.BuildConfig
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** Camera preview and on-device QR recognition for device entries. */
@Composable
internal fun DeviceScanner(active: Boolean, onScanned: (String) -> Unit, modifier: Modifier = Modifier,
    immersive: Boolean = false, onManualInput: (() -> Unit)? = null, previewStartAllowed: Boolean = true) {
    val context = LocalContext.current
    // Navigation caps (and can destroy) its entry lifecycle during interrupted transitions.
    // Page visibility owns camera cancellation; the host lifecycle owns background suspension.
    val lifecycle = (LocalActivity.current as? LifecycleOwner) ?: LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val cameraActive = granted && active && resumed
    var attempt by remember(cameraActive) { mutableIntStateOf(0) }
    var cameraError by remember(cameraActive, attempt) { mutableStateOf<String?>(null) }
    var flashAvailable by remember(cameraActive, attempt) { mutableStateOf<Boolean?>(null) }
    var previewReady by remember(cameraActive, attempt) { mutableStateOf(false) }
    var retainedCamera by remember(context) { mutableStateOf<DeviceCamera?>(null) }
    val currentScan by rememberUpdatedState(onScanned)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(active) { if (active && !granted && !asked) { asked = true; permission.launch(Manifest.permission.CAMERA) } }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(context, cameraActive, attempt) {
        val camera = if (cameraActive) DeviceCamera(context, { currentScan(it) }, { cameraError = it },
            { flashAvailable = it }, { previewReady = true }) else null
        if (camera != null) {
            retainedCamera = camera
            camera.prepare()
        }
        // Close hardware at committed exit, while its TextureView retains the last buffer
        // for the navigation animation. A cancelled gesture never changes cameraActive.
        onDispose { camera?.dispose() }
    }
    val shape = if (immersive) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))
    Box(modifier.then(shape).background(Color(0xFF1B2025)).testTag("device-scanner")) {
        retainedCamera?.let { camera -> key(camera) {
            // Once started, a cancelled back gesture must retain the same camera and texture.
            var started by remember(camera) { mutableStateOf(false) }
            LaunchedEffect(cameraActive, previewStartAllowed) { if (cameraActive && previewStartAllowed) started = true }
            LaunchedEffect(cameraActive, torch) { if (cameraActive) camera.torch(torch) }
            LaunchedEffect(cameraError) { if (cameraError != null) camera.dispose() }
            if (started && cameraError == null && (cameraActive || !active && resumed)) {
                AndroidView(factory = { TextureView(it).also(camera::attach) },
                    modifier = Modifier.fillMaxSize().deviceScannerZoomGestures(cameraActive && previewReady,
                        camera::zoomBy, camera::toggleQuickZoom))
                LaunchedEffect(cameraActive, previewReady) {
                    if (cameraActive && !previewReady) {
                        delay(8_000)
                        camera.dispose()
                        cameraError = "相机启动超时，请重试或输入设备编号"
                    }
                }
            }
            if (cameraActive && cameraError == null) {
                if (previewReady) ScannerSweep()
                else Text("正在启动相机…", Modifier.align(Alignment.Center), fontSize = 13.sp, color = Color.White)
            }
        } }
        if (!granted || cameraError != null) Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cameraError ?: "需要相机权限才能扫码", fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center)
            if (active && !granted) TextButton("允许使用相机", onClick = {
                val activity = context as? Activity
                if (asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA))
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                else permission.launch(Manifest.permission.CAMERA)
            })
            if (cameraActive && cameraError != null) TextButton("重试", onClick = { attempt++ })
        }
        if (immersive) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f))))
                .padding(horizontal = 20.dp, vertical = 24.dp).bottomSheetSafeArea(),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("扫描设备上的二维码\n识别后核对设备与支付方式", fontSize = 12.sp, lineHeight = 20.sp,
                    color = Color(0xFFD0D8E2), textAlign = TextAlign.Center)
                ScannerControls(flashAvailable, torch, { torch = !torch }, onManualInput, active)
            }
        } else Text(cameraError ?: "对准设备上的二维码", Modifier.align(Alignment.BottomCenter).padding(18.dp),
            fontSize = 12.sp, color = Color.White)
    }
}

@Composable
internal fun ScannerSweep() {
    val transition = rememberInfiniteTransition(label = "device-scanner-sweep")
    val progress = transition.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 400, easing = LinearEasing)),
        label = "device-scanner-sweep-progress")
    Box(Modifier.fillMaxSize().clipToBounds().drawWithCache {
        val ink = Color(0xFF7CE2B0)
        val trail = 48.dp.toPx()
        val trailBrush = Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = .12f)), startY = -trail, endY = 0f)
        val lineBrush = Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = .85f), Color.Transparent))
        val trailSize = CanvasSize(size.width, trail)
        val lineSize = CanvasSize(size.width, 2.dp.toPx())
        onDrawBehind {
            val fraction = progress.value
            val alpha = minOf(fraction * 10f, (1f - fraction) * 10f, 1f).coerceAtLeast(0f)
            if (alpha > 0f) translate(top = size.height * fraction) {
                drawRect(trailBrush, topLeft = Offset(0f, -trail), size = trailSize, alpha = alpha)
                drawRect(lineBrush, size = lineSize, alpha = alpha)
            }
        }
    })
}

@Composable
internal fun ScannerControls(flashAvailable: Boolean?, torch: Boolean, onTorch: () -> Unit, onManualInput: (() -> Unit)?, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        // Reserve the final position while capabilities are queried, without exposing a dead action.
        if (flashAvailable != false) ScannerAction(if (torch) "关闭手电筒" else "手电筒", AppIcons.Electricity, onTorch,
            active && flashAvailable == true, if (flashAvailable == null) Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {} else Modifier)
        if (onManualInput != null) ScannerAction("输入设备编号", AppIcons.Keyboard, onManualInput, active)
    }
}

@Composable
private fun ScannerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean,
    modifier: Modifier = Modifier) {
    ActionSurface(onClick, modifier = modifier, enabled = enabled, contentColor = Color(0xFFC8D2DE)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = Color(0xFFC8D2DE))
            Text(label, fontSize = 11.sp, color = Color(0xFFC8D2DE))
        }
    }
}

internal class DeviceCamera(
    private val context: Context, private val onScan: (String) -> Unit, private val onError: (String) -> Unit,
    private val onFlashAvailable: (Boolean) -> Unit,
    private val onPreviewReady: () -> Unit = {},
) : TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val cameraThread = HandlerThread("device-qr-camera").apply { start() }
    private val cameraWorker = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { callback ->
        if (!cameraWorker.post(callback)) throw RejectedExecutionException("Camera worker has stopped")
    }
    // Give rendering a small scheduling advantage without delaying the first scan by a timer.
    private val thread = HandlerThread("device-qr-decoder", Process.THREAD_PRIORITY_LESS_FAVORABLE).apply { start() }
    private val worker = Handler(thread.looper)
    private val manager = context.getSystemService(CameraManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (!disposed && texture?.display?.displayId == displayId) transform()
        }
    }
    private val decoder = WeChatDeviceQrDecoder(context.applicationContext,
        onUnavailable = { error("扫码暂不可用，请输入设备编号") })
    private val frameBuffer = DeviceQrFrameBuffer()
    private val frameGate = DeviceQrFrameGate()
    private val timings = if (BuildConfig.DEBUG) DeviceQrTimings() else null
    @Volatile private var startupTimings: DeviceCameraStartupTimings? = null
    private var texture: TextureView? = null
    // Camera resources and controls are confined to cameraWorker; only view updates use main.
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    @Volatile private var desiredTorch = false
    private var torchEnabled = false
    private var zoom = DeviceCameraZoom()
    private var images: ImageReader? = null
    private var preview: Surface? = null
    private var size = Size(640, 480)
    private var sensorOrientation = 90
    @Volatile private var disposed = false
    private val streamGeneration = AtomicLong()
    private val openLeases = mutableSetOf<DeviceCameraOpenQueue.Lease>()
    private var openingGeneration: Long? = null
    private var configuration: Result<Configuration>? = null
    private var previewReady = false
    private var lastCode = ""
    private var lastCodeAt = 0L
    private data class Configuration(val id: String, val characteristics: CameraCharacteristics, val size: Size)

    /** Open hardware alongside navigation; preview streams still wait for an attached surface. */
    fun prepare() {
        if (disposed) return
        val generation = streamGeneration.get()
        val startup = startup()
        cameraWorker.post { if (isCurrent(generation)) ensureOpen(generation, startup) }
    }

    private fun startup(): DeviceCameraStartupTimings? = startupTimings
        ?: (if (BuildConfig.DEBUG) DeviceCameraStartupTimings() else null).also { startupTimings = it }

    private fun configuration(): Configuration = (configuration ?: runCatching {
        val (id, config) = selectCamera()
        val map = config.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException()
        val previews = map.getOutputSizes(SurfaceTexture::class.java).toSet()
        val size = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888).filter { it in previews && it.width <= 1280 }
            .minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) } ?: throw IllegalStateException()
        Configuration(id, config, size)
    }.also { configuration = it }).getOrThrow()

    fun attach(view: TextureView) {
        texture = view
        displays.registerDisplayListener(displayListener, main)
        view.surfaceTextureListener = this
        if (view.isAvailable) view.surfaceTexture?.let(::start)
    }

    private fun isCurrent(generation: Long) = !disposed && generation == streamGeneration.get()

    private fun start(surface: SurfaceTexture) {
        if (disposed) return
        val generation = streamGeneration.get()
        val startup = startup()
        previewReady = false
        cameraWorker.post {
            if (!isCurrent(generation)) return@post
            try {
                val (_, config, previewSize) = configuration()
                if (!isCurrent(generation)) return@post
                frameGate.resetInterval()
                zoom = DeviceCameraZoom(config.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE),
                    config.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE),
                    config.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
                val flashAvailable = config.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val orientation = config.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                surface.setDefaultBufferSize(previewSize.width, previewSize.height)
                main.post {
                    if (isCurrent(generation)) {
                        size = previewSize
                        sensorOrientation = orientation
                        transform()
                        onFlashAvailable(flashAvailable)
                    }
                }
                preview = Surface(surface)
                images = ImageReader.newInstance(previewSize.width, previewSize.height, android.graphics.ImageFormat.YUV_420_888, 2).apply {
                    setOnImageAvailableListener({ reader -> readFrame(reader, generation, startup) }, cameraWorker)
                }
                // Either the hardware or surface may become ready first; configure exactly once.
                ensureOpen(generation, startup)
                camera?.let { configure(it, generation, startup) }
            } catch (_: Exception) { fail(generation) }
        }
    }

    private fun ensureOpen(generation: Long, startup: DeviceCameraStartupTimings?) {
        if (openingGeneration == generation) return
        openingGeneration = generation
        DeviceCameraOpenQueue.acquire { lease ->
            if (!cameraWorker.post {
                if (isCurrent(generation)) open(generation, startup, lease)
                else lease.release()
            }) lease.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(generation: Long, startup: DeviceCameraStartupTimings?, lease: DeviceCameraOpenQueue.Lease) {
        try {
            val id = configuration().id
            if (!isCurrent(generation)) {
                lease.release()
                return
            }
            openLeases.add(lease)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (!isCurrent(generation)) { device.close(); return }
                    startup?.record("opened")
                    camera = device
                    configure(device, generation, startup)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    fail(generation)
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    fail(generation)
                }
                override fun onClosed(device: CameraDevice) {
                    openLeases.remove(lease)
                    lease.release()
                    finishDisposal()
                }
            }, cameraWorker)
        } catch (_: Exception) {
            openLeases.remove(lease)
            fail(generation)
            lease.release()
            finishDisposal()
        }
    }
    private fun selectCamera(): Pair<String, CameraCharacteristics> {
        val ids = manager.cameraIdList
        var first: Pair<String, CameraCharacteristics>? = null
        for (id in ids) {
            val config = manager.getCameraCharacteristics(id)
            if (first == null) first = id to config
            if (config.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id to config
        }
        return first ?: throw IllegalStateException()
    }
    private fun configure(device: CameraDevice, generation: Long, startup: DeviceCameraStartupTimings?) {
        val output = preview ?: return
        val reader = images ?: return
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!isCurrent(generation) || camera !== device) { session.close(); return }
                    startup?.record("configured")
                    capture = session
                    try {
                        torchEnabled = desiredTorch
                        request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(output); addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                            applyZoom(this)
                        }
                        session.setRepeatingRequest(request!!.build(), null, cameraWorker)
                    } catch (_: Exception) { fail(generation) }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    fail(generation)
                }
            }
            device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(output), OutputConfiguration(reader.surface)), cameraExecutor, callback))
        } catch (_: Exception) { fail(generation) }
    }
    fun torch(enabled: Boolean) {
        // AndroidView can send this update before its SurfaceTexture becomes available.
        desiredTorch = enabled
        val generation = streamGeneration.get()
        cameraWorker.post {
            if (!isCurrent(generation) || desiredTorch == torchEnabled) return@post
            torchEnabled = desiredTorch
            try {
                request?.set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                request?.let { capture?.setRepeatingRequest(it.build(), null, cameraWorker) }
            } catch (_: Exception) { error() }
        }
    }
    fun zoomBy(factor: Float) = updateZoom { zoom.scaleBy(factor) }
    fun toggleQuickZoom() = updateZoom { zoom.toggleQuickZoom() }
    private fun updateZoom(change: () -> Boolean) {
        val generation = streamGeneration.get()
        cameraWorker.post {
            if (!isCurrent(generation)) return@post
            val builder = request ?: return@post
            if (!change()) return@post
            try {
                applyZoom(builder)
                capture?.setRepeatingRequest(builder.build(), null, cameraWorker)
            } catch (_: Exception) { error() }
        }
    }
    private fun applyZoom(builder: CaptureRequest.Builder) {
        // Both preview and decoder targets share this request and the same field of view.
        if (zoom.usesZoomRatio) builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom.ratio)
        else zoom.cropRegion()?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
    }
    private fun readFrame(reader: ImageReader, generation: Long, startup: DeviceCameraStartupTimings?) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        var width = 0
        var height = 0
        var copyStarted = 0L
        var acquired = false
        val bytes = try {
            if (!isCurrent(generation)) return
            startup?.record("firstImage")
            if (!frameGate.tryAcquire(SystemClock.elapsedRealtime())) return
            acquired = true
            if (timings != null) copyStarted = SystemClock.elapsedRealtimeNanos()
            width = image.width
            height = image.height
            val plane = image.planes[0]
            frameBuffer.copy(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
        } catch (_: Exception) {
            if (acquired) frameGate.release()
            return
        } finally {
            // The dense copy owns its pixels, so inference need not retain a camera image.
            image.close()
        }
        val copyNanos = if (timings != null) SystemClock.elapsedRealtimeNanos() - copyStarted else 0L
        if (!worker.post {
            try {
                if (isCurrent(generation)) decode(bytes, width, height, copyNanos, generation)
            } finally {
                frameGate.release()
            }
        }) frameGate.release()
    }
    private fun decode(bytes: ByteArray, width: Int, height: Int, copyNanos: Long, generation: Long) {
        val decodeStarted = if (timings != null) SystemClock.elapsedRealtimeNanos() else 0L
        try {
            val result = try {
                decoder.decode(bytes, width, height)
            } finally {
                timings?.record(copyNanos, SystemClock.elapsedRealtimeNanos() - decodeStarted)
            } ?: return
            if (!isCurrent(generation)) return
            val now = SystemClock.elapsedRealtime()
            if (result != lastCode || now - lastCodeAt >= 2000) {
                lastCode = result
                lastCodeAt = now
                main.post { if (isCurrent(generation)) onScan(result) }
            }
        } catch (_: Exception) { /* Empty frames are normal while aiming. */ }
    }
    private fun transform() {
        val view = texture ?: return
        if (view.width == 0 || view.height == 0) return
        val displayRotation = when (view.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        view.setTransform(devicePreviewTransform(view.width, view.height, size.width, size.height, sensorOrientation, displayRotation))
    }
    private fun error(message: String = "相机暂不可用，请输入设备编号") { if (!disposed) main.post { if (!disposed) onError(message) } }
    private fun fail(generation: Long) {
        if (!isCurrent(generation) || !streamGeneration.compareAndSet(generation, generation + 1)) return
        close()
        main.post {
            if (isCurrent(generation + 1)) {
                startupTimings = null
                onError("相机暂不可用，请输入设备编号")
            }
        }
    }
    private fun close() {
        openingGeneration = null
        capture?.close(); capture = null
        request = null
        camera?.close(); camera = null
        // Closing shares the camera worker with luminance copies and camera callbacks.
        images?.let { reader ->
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        }
        images = null
        preview?.release(); preview = null
    }
    fun dispose() {
        if (disposed) return
        disposed = true
        streamGeneration.incrementAndGet()
        startupTimings = null
        frameGate.close()
        displays.unregisterDisplayListener(displayListener)
        // Keep the listener until destruction so TextureView defers SurfaceTexture.release()
        // until queued camera work has stopped using it.
        texture = null
        cameraWorker.post { close(); finishDisposal() }
        // Release the native decoder after any in-flight recognition on its worker.
        worker.post { decoder.close(); thread.quitSafely() }
    }
    private fun finishDisposal() {
        // Late opens and asynchronous closes still need their callback looper alive.
        if (disposed && openLeases.isEmpty()) cameraThread.quitSafely()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { start(surface) }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transform() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        streamGeneration.incrementAndGet()
        startupTimings = null
        if (!cameraWorker.post { close(); surface.release() }) surface.release()
        return false
    }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        if (disposed || previewReady) return
        previewReady = true
        startupTimings?.record("firstPreview")
        onPreviewReady()
        // Let the preview present its first frame before native model initialization competes for CPU.
        worker.post { if (!disposed && decoder.prepare()) frameGate.start() }
    }
}

/** Debug-only milestones measure camera startup independently of recognition. */
private class DeviceCameraStartupTimings {
    private val started = SystemClock.elapsedRealtimeNanos()
    private val recorded = mutableSetOf<String>()

    @Synchronized
    fun record(stage: String) {
        if (!recorded.add(stage)) return
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
        Log.d("DeviceScanner", "Camera startup: stage=$stage, elapsedMs=$elapsedMs")
    }
}

/** Debug-only aggregates contain timings, never decoded payloads or images. */
private class DeviceQrTimings {
    private val copy = LongArray(20)
    private val decode = LongArray(20)
    private var count = 0

    fun record(copyNanos: Long, decodeNanos: Long) {
        copy[count] = copyNanos
        decode[count] = decodeNanos
        if (++count < copy.size) return
        val averageCopy = copy.sum() / count / 1_000_000.0
        val averageDecode = decode.sum() / count / 1_000_000.0
        decode.sort()
        val p95 = decode[18] / 1_000_000.0
        Log.d("DeviceScanner", "QR frames=$count, copyAvgMs=$averageCopy, decodeAvgMs=$averageDecode, decodeP95Ms=$p95")
        count = 0
    }
}

/** Camera SurfaceTexture already applies sensor orientation; only undo display rotation here. */
internal fun devicePreviewTransform(viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int,
    sensorOrientation: Int, displayRotation: Int): Matrix {
    val nativeWidth = if (sensorOrientation % 180 == 0) bufferWidth else bufferHeight
    val nativeHeight = if (sensorOrientation % 180 == 0) bufferHeight else bufferWidth
    val swapped = displayRotation % 180 != 0
    val scale = maxOf(viewWidth.toFloat() / if (swapped) nativeHeight else nativeWidth,
        viewHeight.toFloat() / if (swapped) nativeWidth else nativeHeight)
    val centerX = viewWidth / 2f
    val centerY = viewHeight / 2f
    return Matrix().apply {
        setScale(nativeWidth * scale / viewWidth, nativeHeight * scale / viewHeight, centerX, centerY)
        postRotate(-displayRotation.toFloat(), centerX, centerY)
    }
}
