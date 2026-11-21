package com.mercury.yktwx.ui.components

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.View
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.mercury.yktwx.BuildConfig
import com.google.common.util.concurrent.ListenableFuture
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** CameraX owns asynchronous opening, surface requests, closing and reopening across owners. */
internal class CameraXDeviceCameraBackend(context: Context,
    private val implementationMode: PreviewView.ImplementationMode = PreviewView.ImplementationMode.COMPATIBLE,
    private val providerFuture: (() -> ListenableFuture<ProcessCameraProvider>)? = null,
) : DeviceCameraBackend {
    private val appContext = context.applicationContext
    private val main = ContextCompat.getMainExecutor(appContext)
    private val owner = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
    }
    private val closed = AtomicBoolean()
    private val recognitionEnabled = AtomicBoolean()
    private val previewReady = AtomicBoolean()
    private val frameGate = DeviceQrFrameGate()
    private val frameBuffer = DeviceQrFrameBuffer()
    private val preview = Preview.Builder().build()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
        .build()
    private var events: DeviceCameraBackend.Events? = null
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var view: PreviewView? = null
    private var desiredTorch = false
    private var zoom = DeviceCameraZoom()
    private val decoder = WeChatDeviceQrDecoder(appContext) {
        reportError("扫码暂不可用，请输入设备编号")
    }
    private var lastCode = ""
    private var lastCodeAt = 0L
    private var startedAt = 0L

    override fun createPreviewView(context: Context): View = PreviewView(context).also { output ->
        // The full-screen scanner Activity animates the whole window, so its SurfaceView is
        // carried by the same WindowManager leash as the chrome around it. The fallback mode
        // remains available to callers whose preview is clipped by a Compose graphics layer.
        output.implementationMode = implementationMode
        output.scaleType = PreviewView.ScaleType.FILL_CENTER
        view = output
        if (!closed.get()) {
            preview.setSurfaceProvider(output.surfaceProvider)
            output.previewStreamState.observe(owner) { stream ->
                if (!closed.get() && stream == PreviewView.StreamState.STREAMING && previewReady.compareAndSet(false, true)) {
                    timing("firstPreview")
                    events?.previewReady()
                }
            }
        }
    }

    override fun start(events: DeviceCameraBackend.Events) {
        if (closed.get() || this.events != null) return
        this.events = events
        startedAt = SystemClock.elapsedRealtimeNanos()
        analysis.setAnalyzer(DecoderExecutor.worker, ::analyze)
        // Model copying and native decoder setup run beside camera opening instead of
        // delaying the first recognized frame.
        DecoderExecutor.worker.execute { decoder.prepare() }
        val future = providerFuture?.invoke() ?: ProcessCameraProvider.getInstance(appContext)
        // A process-owned initialization future must not retain an exited page or its preview.
        val pending = WeakReference(this)
        future.addListener({ pending.get()?.bind(future) }, main)
    }

    private fun bind(future: ListenableFuture<ProcessCameraProvider>) {
        if (closed.get()) return
        try {
            val readyProvider = future.get()
            val selector = if (readyProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA))
                CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            provider = readyProvider
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
            val bound = readyProvider.bindToLifecycle(owner, selector, preview, analysis)
            camera = bound
            bound.cameraInfo.zoomState.value?.let { zoom = DeviceCameraZoom(zoomRatioRange = Range(it.minZoomRatio, it.maxZoomRatio)) }
            events?.flashAvailable(bound.cameraInfo.hasFlashUnit())
            if (desiredTorch && bound.cameraInfo.hasFlashUnit()) bound.cameraControl.enableTorch(true)
            bound.cameraInfo.cameraState.observe(owner) { state ->
                if (state.type == CameraState.Type.OPEN) timing("opened")
                // CameraX retries recoverable camera contention itself; terminal errors end this owner.
                if (state.error?.type == CameraState.ErrorType.CRITICAL) reportError("相机暂不可用，请输入设备编号")
            }
        } catch (error: Exception) {
            Log.w("DeviceScanner", "Unable to bind CameraX use cases", error)
            reportError("相机暂不可用，请输入设备编号")
        }
    }

    override fun enableRecognition() {
        if (!closed.get()) { recognitionEnabled.set(true); frameGate.start() }
    }
    private fun analyze(image: ImageProxy) {
        var acquired = false
        var width = 0
        var height = 0
        val pixels = try {
            if (closed.get() || !previewReady.get() || !recognitionEnabled.get() || !frameGate.tryAcquire(SystemClock.elapsedRealtime())) return
            acquired = true
            width = image.width; height = image.height
            val plane = image.planes[0]
            frameBuffer.copy(plane.buffer, width, height, plane.rowStride, plane.pixelStride)
        } catch (error: Exception) {
            if (acquired) frameGate.release()
            return
        } finally { image.close() }
        try {
            if (closed.get()) return
            val code = decoder.decode(pixels, width, height) ?: return
            val now = SystemClock.elapsedRealtime()
            if (code != lastCode || now - lastCodeAt >= 2_000) {
                lastCode = code; lastCodeAt = now
                main.execute { if (!closed.get()) events?.scanned(code) }
            }
        } finally { frameGate.release() }
    }

    override fun torch(enabled: Boolean) {
        desiredTorch = enabled
        val bound = camera ?: return
        if (bound.cameraInfo.hasFlashUnit()) bound.cameraControl.enableTorch(enabled)
    }
    override fun zoomBy(factor: Float) { if (zoom.scaleBy(factor)) camera?.cameraControl?.setZoomRatio(zoom.ratio) }
    override fun toggleQuickZoom() { if (zoom.toggleQuickZoom()) camera?.cameraControl?.setZoomRatio(zoom.ratio) }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        frameGate.close()
        // Unbind only this owner's use cases; CameraX serializes hardware handover to the next owner.
        try {
            owner.lifecycle.currentState = Lifecycle.State.DESTROYED
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
        } finally {
            view?.previewStreamState?.removeObservers(owner)
            view = null; camera = null; provider = null; events = null
            // The process worker outlives pages; native cleanup follows any recognition already running.
            DecoderExecutor.worker.execute { decoder.close() }
        }
    }
    private fun reportError(message: String) { main.execute { if (!closed.get()) events?.failed(message) } }
    private fun timing(stage: String) {
        if (BuildConfig.DEBUG) Log.d("DeviceScanner", "CameraX startup: stage=$stage, elapsedMs=${(SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000.0}")
    }
}

private object DecoderExecutor {
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "device-qr-decoder")
    }
}
