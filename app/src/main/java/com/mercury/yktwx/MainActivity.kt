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
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp

class MainActivity : ComponentActivity() {
    private val viewModel: PaymentViewModel by viewModels()
    private val latestConfig = mutableStateOf<Configuration?>(null)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); latestConfig.value = Configuration(newConfig)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preview = BuildConfig.DEBUG && intent.getBooleanExtra("ui_preview", false)
        // Payments and password screens must not leak through screenshots or task previews.
        if (!preview) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        viewModel.initialize(preview)
        setContent {
            CompositionLocalProvider(LocalConfiguration provides (latestConfig.value ?: LocalConfiguration.current)) {
                val settings = remember { ThemeSettings(this) }
                val systemDark = isSystemInDarkTheme()
                val dark = when (settings.themeMode.value) { 1 -> false; 2 -> true; else -> systemDark }
                DisposableEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    )
                    onDispose {}
                }
                YktApp(settings, viewModel)
            }
        }
    }
    override fun onStart() { super.onStart(); viewModel.onForeground() }
    override fun onStop() { viewModel.onBackground(); super.onStop() }
}
