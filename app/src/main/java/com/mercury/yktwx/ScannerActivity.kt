package com.mercury.yktwx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.mercury.yktwx.data.life.DeviceScanRoute
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.data.life.UnifiedDeviceCode
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.CameraXDeviceCameraBackend
import com.mercury.yktwx.ui.components.DeviceCameraBackend
import com.mercury.yktwx.ui.pages.LifeScannerPage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/** Preserve manual platform selection when several providers accept the same number. */
@Serializable
internal data class LifeScannerResult(val raw: String, val manualProvider: LifeProvider? = null) {
    fun route(): DeviceScanRoute? = if (manualProvider == null) UnifiedDeviceCode.identify(raw)
        else UnifiedDeviceCode.identifyManual(raw, manualProvider)

    fun toIntent(): Intent = Intent().putExtra(ExtraRaw, raw).apply {
        manualProvider?.let { putExtra(ExtraProvider, it.name) }
    }

    companion object {
        private const val ExtraRaw = "scanner.raw"
        private const val ExtraProvider = "scanner.manual_provider"
        val StateSaver = Saver<LifeScannerResult?, String>(
            save = { it?.let { result -> Json.encodeToString(result) } ?: "" },
            restore = { runCatching { Json.decodeFromString<LifeScannerResult>(it) }.getOrNull() },
        )

        fun read(resultCode: Int, data: Intent?): LifeScannerResult? {
            if (resultCode != Activity.RESULT_OK || data == null) return null
            val raw = data.getStringExtra(ExtraRaw)?.takeIf { it.isNotBlank() && it.length <= 16_384 } ?: return null
            val providerName = data.getStringExtra(ExtraProvider)
            val provider = providerName?.let { name -> LifeProvider.entries.firstOrNull { it.name == name } }
            if (providerName != null && provider == null) return null
            return LifeScannerResult(raw, provider).takeIf { it.route() != null }
        }
    }
}

/** The window transition carries the Compose UI and camera SurfaceView as one hierarchy. */
class ScannerActivity : ComponentActivity() {
    private var entryFinished by mutableStateOf(false)
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val motion = ScannerWindowMotion.forStyle(intent.getIntExtra(ExtraTransitionStyle, 0))
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, motion.openEnter, motion.openExit, AndroidColor.BLACK)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, motion.closeEnter, motion.closeExit, AndroidColor.BLACK)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        window.isNavigationBarContrastEnforced = false
        setContent {
            AppTheme(ColorSchemeMode.Dark) {
                Scaffold(Modifier.fillMaxSize().background(Color.Black).semantics { testTagsAsResourceId = true }) {
                    var error by remember { mutableStateOf<String?>(null) }
                    val cameraFactory: (Context) -> DeviceCameraBackend = remember {
                        { context -> CameraXDeviceCameraBackend(context, PreviewView.ImplementationMode.PERFORMANCE) }
                    }
                    LifeScannerPage(
                        state = LifeDevicesUiState(restoring = false, scanning = true, error = error),
                        active = true,
                        onBack = ::finish,
                        onScanned = { raw ->
                            val result = LifeScannerResult(raw)
                            if (result.route() == null) error = "未知设备"
                            else complete(result)
                        },
                        // Opening and preview do not wait; native recognition waits for window motion.
                        recognitionStartAllowed = entryFinished,
                        onManualScanned = { route -> complete(LifeScannerResult(route.code, route.provider)) },
                        cameraBackendFactory = cameraFactory,
                    )
                }
            }
        }
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        entryFinished = true
    }

    private fun complete(result: LifeScannerResult) {
        if (completed || isFinishing) return
        completed = true
        setResult(RESULT_OK, result.toIntent())
        finish()
    }

    companion object {
        private const val ExtraTransitionStyle = "scanner.transition_style"
        internal fun intent(context: Context, transitionStyle: Int): Intent =
            Intent(context, ScannerActivity::class.java).putExtra(ExtraTransitionStyle, transitionStyle.coerceIn(0, 1))
    }
}

/** One selection owns both directions for the lifetime of the scanner window. */
internal data class ScannerWindowMotion(val openEnter: Int, val openExit: Int, val closeEnter: Int, val closeExit: Int) {
    companion object {
        fun forStyle(style: Int): ScannerWindowMotion = if (style == 1) ScannerWindowMotion(
            R.anim.scanner_aosp_open_enter, R.anim.scanner_aosp_open_exit,
            R.anim.scanner_aosp_close_reveal, R.anim.scanner_aosp_close_exit,
        ) else ScannerWindowMotion(
            R.anim.scanner_open_enter, R.anim.scanner_open_exit,
            R.anim.scanner_close_reveal, R.anim.scanner_close_exit,
        )
    }
}
