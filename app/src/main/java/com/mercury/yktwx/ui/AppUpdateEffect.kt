package com.mercury.yktwx.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.mercury.yktwx.AppUpdateViewModel
import com.mercury.yktwx.UpdateFeedback
import com.mercury.yktwx.R

@Composable
internal fun AppUpdateEffect(model: AppUpdateViewModel, automatic: Boolean, allowed: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.messages.collect { message ->
                val resource = when (message) {
                    UpdateFeedback.Current -> R.string.update_current
                    UpdateFeedback.NoRelease -> R.string.update_no_release
                    UpdateFeedback.Incompatible -> R.string.update_incompatible
                    UpdateFeedback.Network -> R.string.update_network_failed
                    UpdateFeedback.Failed -> R.string.update_failed
                    UpdateFeedback.Preview -> R.string.update_preview
                }
                Toast.makeText(context, context.getString(resource), Toast.LENGTH_SHORT).show()
            }
        }
    }
    val currentAutomatic by rememberUpdatedState(automatic)
    val currentAllowed by rememberUpdatedState(allowed)
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            model.configure(currentAutomatic, currentAllowed, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.configure(automatic = false, allowed = false, inForeground = false)
        }
    }
    LaunchedEffect(model, automatic, allowed) {
        model.configure(automatic, allowed, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
}
