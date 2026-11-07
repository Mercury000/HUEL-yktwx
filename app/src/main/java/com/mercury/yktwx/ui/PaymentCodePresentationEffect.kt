package com.mercury.yktwx.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Only a visible, valid foreground code changes this window, never the system setting. */
@Composable
internal fun PaymentCodePresentationEffect(enabled: Boolean, increaseBrightness: Boolean = true) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(enabled, increaseBrightness, context, lifecycle) {
        val window = context.activity()?.window
        val brightness = window?.attributes?.screenBrightness ?: -1f
        val wasKeepingScreenOn = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        fun restore() {
            if (window != null) {
                if (!wasKeepingScreenOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (increaseBrightness) window.attributes = window.attributes.apply { screenBrightness = brightness }
            }
        }
        fun apply() {
            if (enabled && window != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (increaseBrightness) {
                    val original = if (brightness >= 0f) brightness else
                        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
                    window.attributes = window.attributes.apply { screenBrightness = maxOf(original, .65f).coerceAtMost(1f) }
                }
            }
        }
        apply()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) apply()
            else if (enabled && event == Lifecycle.Event.ON_PAUSE) restore()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); if (enabled) restore() }
    }
}
