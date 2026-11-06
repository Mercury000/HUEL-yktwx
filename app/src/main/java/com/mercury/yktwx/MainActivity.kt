package com.mercury.yktwx

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.UsageConsent

class MainActivity : ComponentActivity() {
    private val viewModel: PaymentViewModel by viewModels()
    private val billViewModel: BillViewModel by viewModels()
    private val rechargeViewModel: RechargeViewModel by viewModels()
    private val feeViewModel: FeeViewModel by viewModels()
    private val latestConfig = mutableStateOf<Configuration?>(null)
    private lateinit var consent: UsageConsent
    private var businessStarted = false
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); latestConfig.value = Configuration(newConfig)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = UiPreview.enabled && UiPreview.requested(intent)
        val settings = ThemeSettings(this)
        consent = UsageConsent(this)
        val restoreLogin = consent.accepted
        // Payments and password screens must not leak through screenshots or task previews.
        if (!preview && settings.preventScreenshots.value) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Restore local account data while the first composition is being prepared.
        if (restoreLogin || preview) initializePayment(preview, restoreLogin)
        setContent {
            val agreed = preview || consent.accepted
            LaunchedEffect(agreed) {
                if (agreed) {
                    initializePayment(preview, restoreLogin)
                    billViewModel.initialize(preview)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startBusiness()
                } else stopBusiness()
            }
            val secure = !preview && settings.preventScreenshots.value
            SideEffect {
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            CompositionLocalProvider(LocalConfiguration provides (latestConfig.value ?: LocalConfiguration.current)) {
                val systemDark = isSystemInDarkTheme()
                val dark = when (settings.themeMode.value) { 1 -> false; 2 -> true; else -> systemDark }
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    )
                    window.isNavigationBarContrastEnforced = false
                    onDispose {}
                }
                YktApp(settings, viewModel, billViewModel, consent = consent.takeUnless { preview })
            }
        }
    }
    private fun initializePayment(preview: Boolean, restoreLogin: Boolean) {
        viewModel.initialize(preview, previewLost = UiPreview.enabled && preview && UiPreview.lostRequested(intent), restoreLogin = restoreLogin)
    }
    private fun businessAllowed() = (UiPreview.enabled && UiPreview.requested(intent)) || consent.accepted
    private fun startBusiness() {
        if (businessStarted) return
        businessStarted = true
        viewModel.onForeground(); rechargeViewModel.onForeground(); feeViewModel.onForeground()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            rechargeViewModel.onResumed(window.decorView.hasWindowFocus())
            feeViewModel.onResumed(window.decorView.hasWindowFocus())
        }
    }
    private fun stopBusiness() {
        if (!businessStarted) return
        businessStarted = false
        feeViewModel.onBackground(); rechargeViewModel.onBackground(); viewModel.onBackground()
    }
    override fun onStart() { super.onStart(); if (businessAllowed()) startBusiness() }
    override fun onResume() {
        super.onResume()
        if (businessAllowed()) {
            rechargeViewModel.onResumed(window.decorView.hasWindowFocus())
            feeViewModel.onResumed(window.decorView.hasWindowFocus())
        }
    }
    override fun onPause() {
        rechargeViewModel.onPaused()
        feeViewModel.onPaused()
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::consent.isInitialized && businessAllowed()) {
            rechargeViewModel.onWindowFocusChanged(hasFocus)
            feeViewModel.onWindowFocusChanged(hasFocus)
        }
    }
    override fun onStop() { stopBusiness(); super.onStop() }
}
