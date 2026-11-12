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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mercury.yktwx.data.AppPreferencesRepository
import com.mercury.yktwx.data.PreferencesLoadState
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.PreferencesEntry
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import com.mercury.yktwx.analytics.AppAnalytics
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
    private var modelsInitialized = false
    private var preview = false
    private lateinit var preferences: AppPreferencesRepository
    private val updateViewModel: AppUpdateViewModel by viewModels()
    private val analytics by lazy { AppAnalytics.from(application) }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); latestConfig.value = Configuration(newConfig)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = UiPreview.enabled && UiPreview.requested(intent)
        // Enable protection before the first asynchronous disk read or rendered screen.
        if (!preview) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        preferences = AppPreferencesRepository.from(this)
        val settings = ThemeSettings(preferences)
        consent = UsageConsent(this)
        setContent {
            val loaded by preferences.state.collectAsStateWithLifecycle()
            if (loaded !is PreferencesLoadState.Ready) {
                SideEffect { if (!preview) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                LaunchedEffect(loaded) { stopBusiness() }
                AppTheme(ColorSchemeMode.System, 0) {
                    PreferencesEntry(loaded is PreferencesLoadState.Failed, preferences::retry)
                }
                return@setContent
            }
            val updateState by updateViewModel.state.collectAsStateWithLifecycle()
            if (!updateState.ready) {
                LaunchedEffect(updateState.ready) { stopBusiness() }
                AppTheme(ColorSchemeMode.System, 0) {
                    PreferencesEntry(updateState.loadFailed, updateViewModel::retryLoading)
                }
                return@setContent
            }
            val agreed = preview || consent.accepted
            LaunchedEffect(agreed, updateState.required) {
                initializePayment(preview)
                modelsInitialized = true
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                    analytics.resume(this@MainActivity, consent.accepted, preview)
                if (businessAllowed()) {
                    billViewModel.initialize(preview)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startBusiness()
                } else stopBusiness()
            }
            LaunchedEffect(preferences) {
                preferences.failures.collect {
                    android.widget.Toast.makeText(this@MainActivity, "设置保存失败，请重试", android.widget.Toast.LENGTH_SHORT).show()
                }
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
                YktApp(settings, viewModel, billViewModel, consent = consent.takeUnless { preview },
                    onExit = ::finishAndRemoveTask)
            }
        }
    }
    private fun initializePayment(preview: Boolean) {
        viewModel.initialize(preview, previewLost = UiPreview.enabled && preview && UiPreview.lostRequested(intent))
    }
    private fun businessAllowed() = modelsInitialized && preferences.readyOrNull() != null &&
        (preview || consent.accepted) && updateViewModel.state.value.ready && !updateViewModel.state.value.required
    private fun startBusiness() {
        if (businessStarted || !businessAllowed()) return
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
        analytics.resume(this, consent.accepted, UiPreview.enabled && UiPreview.requested(intent))
        if (businessAllowed()) {
            rechargeViewModel.onResumed(window.decorView.hasWindowFocus())
            feeViewModel.onResumed(window.decorView.hasWindowFocus())
        }
    }
    override fun onPause() {
        analytics.pause(this)
        if (businessStarted) { rechargeViewModel.onPaused(); feeViewModel.onPaused() }
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::consent.isInitialized && businessAllowed()) {
            rechargeViewModel.onWindowFocusChanged(hasFocus)
            feeViewModel.onWindowFocusChanged(hasFocus)
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) analytics.background()
        stopBusiness(); super.onStop()
    }
}
