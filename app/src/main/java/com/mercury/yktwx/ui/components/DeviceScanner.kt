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
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as CanvasSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon

/** Camera preview and on-device QR recognition for device entries. */
@Composable
internal fun DeviceScanner(active: Boolean, onScanned: (String) -> Unit, modifier: Modifier = Modifier,
    immersive: Boolean = false, onManualInput: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var torch by remember { mutableStateOf(false) }
    var flashAvailable by remember { mutableStateOf(false) }
    var resumed by remember { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
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
    val shape = if (immersive) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))
    Box(modifier.then(shape).background(Color(0xFF1B2025)).testTag("device-scanner")) {
        if (granted && active && resumed) {
            val camera = remember(context) { DeviceCamera(context, { currentScan(it) }, { cameraError = it }, { flashAvailable = it }) }
            DisposableEffect(camera) { onDispose { camera.dispose() } }
            AndroidView(factory = { TextureView(it).also(camera::attach) }, update = { camera.torch(torch) }, modifier = Modifier.fillMaxSize())
            if (cameraError == null) ScannerSweep()
        }
        if (!granted || cameraError != null) Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(cameraError ?: "需要相机权限才能扫码", fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center)
            if (active && !granted) TextButton("允许使用相机", onClick = {
                val activity = context as? Activity
                if (asked && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA))
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                else permission.launch(Manifest.permission.CAMERA)
            })
        }
        if (immersive) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f))))
                .padding(horizontal = 20.dp, vertical = 24.dp).bottomSheetSafeArea(),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("扫描设备上的二维码\n识别后核对设备与支付方式", fontSize = 12.sp, lineHeight = 20.sp,
                    color = Color(0xFFD0D8E2), textAlign = TextAlign.Center)
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    if (flashAvailable) ScannerAction(if (torch) "关闭手电筒" else "手电筒", AppIcons.Electricity, { torch = !torch }, active)
                    if (onManualInput != null) ScannerAction("输入设备编号", AppIcons.Keyboard, onManualInput, active)
                }
            }
        } else Text(cameraError ?: "对准设备上的二维码", Modifier.align(Alignment.BottomCenter).padding(18.dp),
            fontSize = 12.sp, color = Color.White)
    }
}

@Composable
private fun ScannerSweep() {
    val transition = rememberInfiniteTransition(label = "device-scanner-sweep")
    val progress = transition.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 400, easing = LinearEasing)),
        label = "device-scanner-sweep-progress")
    Canvas(Modifier.fillMaxSize().clipToBounds()) {
        val fraction = progress.value
        val y = size.height * fraction
        val alpha = minOf(fraction * 10f, (1f - fraction) * 10f, 1f).coerceAtLeast(0f)
        val ink = Color(0xFF7CE2B0)
        val trail = 48.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = .12f * alpha)),
            startY = y - trail, endY = y), topLeft = Offset(0f, y - trail), size = CanvasSize(size.width, trail))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = .85f * alpha), Color.Transparent)),
            topLeft = Offset(0f, y), size = CanvasSize(size.width, 2.dp.toPx()))
    }
}

@Composable
private fun ScannerAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, enabled: Boolean) {
    ActionSurface(onClick, enabled = enabled, contentColor = Color(0xFFC8D2DE)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = Color(0xFFC8D2DE))
            Text(label, fontSize = 11.sp, color = Color(0xFFC8D2DE))
        }
    }
}

private class DeviceCamera(
    private val context: Context, private val onScan: (String) -> Unit, private val onError: (String) -> Unit,
    private val onFlashAvailable: (Boolean) -> Unit,
) : TextureView.SurfaceTextureListener {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("device-qr-decoder").apply { start() }
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
    private var texture: TextureView? = null
    private var camera: CameraDevice? = null
    private var capture: CameraCaptureSession? = null
    private var request: CaptureRequest.Builder? = null
    private var torchEnabled = false
    private var images: ImageReader? = null
    private var preview: Surface? = null
    private var size = Size(640, 480)
    private var sensorOrientation = 90
    @Volatile private var disposed = false
    private var opening = false
    private var lastFrame = 0L
    private var lastCode = ""
    private var lastCodeAt = 0L
    fun attach(view: TextureView) {
        texture = view
        displays.registerDisplayListener(displayListener, main)
        view.surfaceTextureListener = this
        if (view.isAvailable) open()
    }

    @SuppressLint("MissingPermission")
    private fun open() {
        if (disposed || opening || camera != null || texture?.isAvailable != true) return
        opening = true
        try {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull() ?: throw IllegalStateException()
            val config = manager.getCameraCharacteristics(id)
            onFlashAvailable(config.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true)
            sensorOrientation = config.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val map = config.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: throw IllegalStateException()
            val previews = map.getOutputSizes(SurfaceTexture::class.java).toSet()
            size = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888).filter { it in previews && it.width <= 1280 }
                .minByOrNull { kotlin.math.abs(it.width * it.height - 1280 * 720) } ?: throw IllegalStateException()
            texture!!.surfaceTexture!!.setDefaultBufferSize(size.width, size.height)
            transform()
            preview = Surface(texture!!.surfaceTexture)
            images = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.YUV_420_888, 2).apply {
                setOnImageAvailableListener({ reader -> decode(reader) }, worker)
            }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    opening = false
                    if (disposed || texture?.isAvailable != true) { device.close(); return }
                    camera = device
                    configure(device)
                }
                override fun onDisconnected(device: CameraDevice) { opening = false; device.close(); camera = null; error() }
                override fun onError(device: CameraDevice, error: Int) { opening = false; device.close(); camera = null; error() }
            }, main)
        } catch (_: Exception) { opening = false; error() }
    }
    @Suppress("DEPRECATION")
    private fun configure(device: CameraDevice) {
        val output = preview ?: return
        val reader = images ?: return
        try {
            device.createCaptureSession(listOf(output, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (disposed || camera !== device) { session.close(); return }
                    capture = session
                    try {
                        request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(output); addTarget(reader.surface)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.FLASH_MODE, if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                        }
                        session.setRepeatingRequest(request!!.build(), null, main)
                    } catch (_: Exception) { error() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { session.close(); error() }
            }, main)
        } catch (_: Exception) { error() }
    }
    fun torch(enabled: Boolean) {
        if (enabled == torchEnabled) return
        torchEnabled = enabled
        try {
            request?.set(CaptureRequest.FLASH_MODE, if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            request?.let { capture?.setRepeatingRequest(it.build(), null, main) }
        } catch (_: Exception) { error() }
    }
    private fun decode(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (disposed || SystemClock.elapsedRealtime() - lastFrame < 400) return
            lastFrame = SystemClock.elapsedRealtime()
            val plane = image.planes[0]
            val buffer = plane.buffer.duplicate()
            val start = buffer.position()
            val bytes = ByteArray(image.width * image.height)
            for (y in 0 until image.height) {
                if (plane.pixelStride == 1) {
                    buffer.position(start + y * plane.rowStride)
                    buffer.get(bytes, y * image.width, image.width)
                } else for (x in 0 until image.width)
                    bytes[y * image.width + x] = buffer.get(start + y * plane.rowStride + x * plane.pixelStride)
            }
            val result = decoder.decode(bytes, image.width, image.height) ?: return
            val now = SystemClock.elapsedRealtime()
            if (result != lastCode || now - lastCodeAt >= 2000) {
                lastCode = result
                lastCodeAt = now
                main.post { if (!disposed) onScan(result) }
            }
        } catch (_: Exception) { /* Empty frames are normal while aiming. */ }
        finally { image.close() }
    }
    private fun transform() {
        val view = texture ?: return
        if (view.width == 0 || view.height == 0) return
        val displayRotation = when (view.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        view.setTransform(devicePreviewTransform(view.width, view.height, size.width, size.height, sensorOrientation, displayRotation))
    }
    private fun error(message: String = "相机暂不可用，请输入设备编号") { if (!disposed) main.post { if (!disposed) onError(message) } }
    private fun close() {
        capture?.close(); capture = null
        request = null
        camera?.close(); camera = null
        images?.close(); images = null
        preview?.release(); preview = null
    }
    fun dispose() {
        disposed = true
        displays.unregisterDisplayListener(displayListener)
        texture?.surfaceTextureListener = null
        close()
        // Release the native decoder after any in-flight recognition on its worker.
        worker.post { decoder.close() }
        thread.quitSafely()
    }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) { open() }
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) { transform() }
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { close(); return true }
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
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
