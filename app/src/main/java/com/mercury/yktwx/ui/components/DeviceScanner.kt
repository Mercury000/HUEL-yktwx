package com.mercury.yktwx.ui.components

import com.mercury.yktwx.ui.components.AppTextButton as TextButton
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
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
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.Icon
import kotlin.math.roundToInt

/** Camera preview and on-device QR recognition for device entries. */
@Composable
internal fun DeviceScanner(active: Boolean, onScanned: (String) -> Unit, modifier: Modifier = Modifier,
    immersive: Boolean = false, onManualInput: (() -> Unit)? = null, recognitionStartAllowed: Boolean = true,
    cameraBackendFactory: (Context) -> DeviceCameraBackend = ::CameraXDeviceCameraBackend) {
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
    val currentScan by rememberUpdatedState(onScanned)
    var retainedCamera by remember(context) { mutableStateOf<DeviceCameraSession?>(null) }
    // Create the replacement during composition so re-entry immediately gets a new owner, while a
    // closed owner remains attached long enough to carry its last preview through exit motion.
    val openingCamera = remember(context, cameraActive, attempt) {
        val previous = retainedCamera
        if (cameraActive && (previous == null || previous.isClosed))
            DeviceCameraSession(cameraBackendFactory(context), { currentScan(it) })
        else null
    }
    val camera = openingCamera ?: retainedCamera
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
    DisposableEffect(openingCamera) {
        if (openingCamera != null) {
            retainedCamera = openingCamera
            openingCamera.start()
        }
        // A session owns its callbacks, state and use cases. Exit cancels that whole owner.
        onDispose { openingCamera?.close() }
    }
    val cameraState = key(camera) { camera?.state?.collectAsState()?.value ?: DeviceCameraState() }
    val cameraError = cameraState.error
    val flashAvailable = cameraState.flashAvailable
    val previewReady = cameraState.phase == DeviceCameraPhase.Previewing
    val frame = if (immersive) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))
    Box(modifier.then(frame).background(if (immersive) Color.Black else Color(0xFF1B2025)).testTag("device-scanner")) {
        camera?.let { activeCamera -> key(activeCamera) {
            Box(Modifier.fillMaxSize()
                .background(Color(0xFF1B2025))) {
                LaunchedEffect(cameraActive, recognitionStartAllowed) {
                    if (cameraActive) activeCamera.allowRecognition(recognitionStartAllowed)
                }
                LaunchedEffect(cameraActive, torch) { if (cameraActive) activeCamera.torch(torch) }
                if (cameraError == null && (cameraActive || !active && resumed)) {
                    AndroidView(factory = activeCamera::createPreviewView,
                        modifier = Modifier.fillMaxSize().deviceScannerZoomGestures(cameraActive && previewReady,
                            activeCamera::zoomBy, activeCamera::toggleQuickZoom))
                }
                if (cameraActive && cameraError == null) {
                    if (previewReady) ScannerSweep(Modifier.testTag("device-scanner-preview-ready"))
                    else Text("正在启动相机…", Modifier.align(Alignment.Center), fontSize = 13.sp, color = Color.White)
                }
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
internal fun ScannerSweep(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "device-scanner-sweep")
    val progress = transition.animateFloat(0f, 1f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 400, easing = LinearEasing)),
        label = "device-scanner-sweep-progress")
    val trail = 48.dp
    val line = 2.dp
    Box(modifier.fillMaxSize().clipToBounds()) {
        Box(Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                placeable.place(0, (constraints.maxHeight * progress.value - trail.toPx()).roundToInt())
            }
        }.fillMaxWidth().height(trail + line).graphicsLayer {
            alpha = minOf(progress.value * 10f, (1f - progress.value) * 10f, 1f).coerceAtLeast(0f)
        }.drawWithCache {
            val ink = Color(0xFF7CE2B0)
            val trailPx = trail.toPx()
            val linePx = line.toPx()
            val trailBrush = Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = .12f)),
                startY = 0f, endY = trailPx)
            val lineBrush = Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = .85f), Color.Transparent))
            val trailSize = CanvasSize(size.width, trailPx)
            val lineSize = CanvasSize(size.width, linePx)
            onDrawBehind {
                drawRect(trailBrush, size = trailSize)
                drawRect(lineBrush, topLeft = Offset(0f, trailPx), size = lineSize)
            }
        })
    }
}

@Composable
internal fun ScannerControls(flashAvailable: Boolean?, torch: Boolean, onTorch: () -> Unit, onManualInput: (() -> Unit)?, active: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
        // Reserve the final position while capabilities are queried, without exposing a dead action.
        if (flashAvailable != false) ScannerAction(if (torch) "关闭手电筒" else "手电筒", AppIcons.Flashlight, onTorch,
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
