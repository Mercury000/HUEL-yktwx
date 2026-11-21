package com.mercury.yktwx.ui.components

import android.content.Context
import android.util.Log
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class DeviceCameraPhase { Starting, Previewing, Failed, Closed }
internal data class DeviceCameraState(
    val phase: DeviceCameraPhase = DeviceCameraPhase.Starting,
    val flashAvailable: Boolean? = null,
    val error: String? = null,
)

/** Hardware implementations own their use cases; a session owns UI state and cancellation. */
internal interface DeviceCameraBackend {
    interface Events {
        fun previewReady()
        fun flashAvailable(available: Boolean)
        fun scanned(code: String)
        fun failed(message: String)
    }
    fun createPreviewView(context: Context): View
    fun start(events: Events)
    fun enableRecognition()
    fun torch(enabled: Boolean)
    fun zoomBy(factor: Float)
    fun toggleQuickZoom()
    fun close()
}

/** Every entry has its own state and scope, so callbacks and deadlines cannot cross entries. */
internal class DeviceCameraSession(
    private val backend: DeviceCameraBackend,
    private val onScan: (String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val mutableState = MutableStateFlow(DeviceCameraState())
    val state = mutableState.asStateFlow()
    private var started = false
    private var closed = false
    private var recognitionEnabled = false
    private var startupDeadline: Job? = null
    private var recognitionDeadline: Job? = null
    internal val isClosed: Boolean get() = closed

    fun createPreviewView(context: Context): View = backend.createPreviewView(context)

    fun start() {
        if (started || closed) return
        started = true
        startupDeadline = scope.launch {
            delay(8_000)
            if (mutableState.value.phase == DeviceCameraPhase.Starting) fail("相机启动超时，请重试或输入设备编号")
        }
        // Navigation only defers CPU-heavy recognition. Preview never waits for its motion signal.
        recognitionDeadline = scope.launch { delay(1_000); enableRecognition() }
        try {
            backend.start(object : DeviceCameraBackend.Events {
                override fun previewReady() = dispatch {
                    if (mutableState.value.phase == DeviceCameraPhase.Starting) {
                        mutableState.value = mutableState.value.copy(phase = DeviceCameraPhase.Previewing)
                        startupDeadline?.cancel()
                    }
                }
                override fun flashAvailable(available: Boolean) = dispatch {
                    mutableState.value = mutableState.value.copy(flashAvailable = available)
                }
                override fun scanned(code: String) = dispatch {
                    if (mutableState.value.phase == DeviceCameraPhase.Previewing) onScan(code)
                }
                override fun failed(message: String) = dispatch { fail(message) }
            })
        } catch (error: Exception) {
            Log.w("DeviceScanner", "Unable to start camera session", error)
            fail("相机暂不可用，请输入设备编号")
        }
    }

    private fun dispatch(action: () -> Unit) { scope.launch { if (!closed) action() } }

    fun allowRecognition(allowed: Boolean) { if (allowed) enableRecognition() }
    private fun enableRecognition() {
        if (closed || recognitionEnabled) return
        recognitionEnabled = true
        recognitionDeadline?.cancel()
        backend.enableRecognition()
    }
    fun torch(enabled: Boolean) { if (!closed) backend.torch(enabled) }
    fun zoomBy(factor: Float) { if (!closed) backend.zoomBy(factor) }
    fun toggleQuickZoom() { if (!closed) backend.toggleQuickZoom() }

    private fun fail(message: String) {
        if (closed) return
        mutableState.value = mutableState.value.copy(phase = DeviceCameraPhase.Failed, error = message)
        release()
    }
    fun close() {
        if (closed) return
        mutableState.value = mutableState.value.copy(phase = DeviceCameraPhase.Closed)
        release()
    }
    private fun release() {
        closed = true
        scope.cancel()
        try { backend.close() } catch (error: Exception) { Log.w("DeviceScanner", "Unable to close camera session", error) }
    }
}
