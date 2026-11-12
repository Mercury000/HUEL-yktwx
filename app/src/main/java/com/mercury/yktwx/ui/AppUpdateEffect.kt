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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.Settings

@Composable
internal fun rememberUpdateInstallAction(model: AppUpdateViewModel): () -> Unit {
    val dispatch = remember { mutableStateOf<(android.content.Intent) -> Unit>({}) }
    val install = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installationReturned(dispatch.value)
    }
    val authorization = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installationReturned(dispatch.value)
    }
    val launch: (android.content.Intent) -> Unit = { intent ->
        if (intent.action == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES) authorization.launch(intent)
        else install.launch(intent)
    }
    SideEffect { dispatch.value = launch }
    return { model.downloadAndInstall(launch) }
}

@Composable
internal fun AppUpdateEffect(model: AppUpdateViewModel, reminders: Boolean, allowed: Boolean,
    channel: String = com.mercury.yktwx.BuildConfig.UPDATE_CHANNEL) {
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
    val currentReminders by rememberUpdatedState(reminders)
    val currentAllowed by rememberUpdatedState(allowed)
    val currentChannel by rememberUpdatedState(channel)
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            model.configure(currentReminders, currentAllowed, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), currentChannel)
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            model.configure(reminders = false, allowed = false, inForeground = false)
        }
    }
    LaunchedEffect(model, reminders, allowed, channel) {
        model.configure(reminders, allowed, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED), channel)
    }
}
