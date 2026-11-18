package com.mercury.yktwx.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Android 14+ reports screenshots without reading images or blocking capture. */
@Composable
internal fun PaymentCodeScreenshotEffect(enabled: Boolean, onScreenshot: () -> Unit) {
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnScreenshot = rememberUpdatedState(onScreenshot)
    DisposableEffect(enabled, activity, lifecycle) {
        var registered = false
        val callback = Activity.ScreenCaptureCallback {
            // A previously queued notification must not outlive this visible page or foreground session.
            if (registered && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                latestOnScreenshot.value()
            }
        }
        fun stop() {
            if (registered) {
                registered = false
                activity?.unregisterScreenCaptureCallback(callback)
            }
        }
        fun update() {
            // Screenshot UI can pause the page while it is still visible; stop only at ON_STOP.
            if (enabled && activity != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (!registered) {
                    activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
                    registered = true
                }
            } else stop()
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
}
