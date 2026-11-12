package com.mercury.yktwx.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mercury.yktwx.data.AndroidNetworkMonitor
import com.mercury.yktwx.data.NetworkState
import com.mercury.yktwx.ui.components.FeedbackText

/** Keep connectivity epochs aligned with page ViewModels across Activity recreation. */
@dagger.hilt.android.lifecycle.HiltViewModel
internal class NetworkStateViewModel @javax.inject.Inject constructor(val monitor: AndroidNetworkMonitor) : ViewModel() {
    override fun onCleared() { monitor.stop() }
}

/**
 * The shell owns the only Android connectivity listener. Preview mode is always explicitly online
 * and never registers a real callback.
 */
@Composable
internal fun rememberNetworkState(preview: Boolean): State<NetworkState> {
    if (preview) return remember { mutableStateOf(NetworkState(online = true)) }
    val model: NetworkStateViewModel = viewModel()
    val monitor = model.monitor
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(monitor, lifecycle) {
        monitor.start()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) monitor.refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); monitor.stop() }
    }
    return monitor.state.collectAsState()
}

internal data class ForegroundState(val active: Boolean, val epoch: Long)

/**
 * Foreground is lifecycle state, not a reconnect signal. [ForegroundState.epoch] still changes on
 * every resume so a stop/start that Compose coalesces into one frame cannot drop the re-evaluation.
 */
@Composable
internal fun rememberForegroundState(): State<ForegroundState> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(lifecycle) {
        val active = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        mutableStateOf(ForegroundState(active, if (active) 1L else 0L))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> state.value = ForegroundState(true, state.value.epoch + 1)
                Lifecycle.Event.ON_STOP -> state.value = state.value.copy(active = false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return state
}

/**
 * Block only a known-offline click or dialog entry. Pull-to-refresh deliberately does not use
 * this: it must end with an inline notice, never a Toast.
 */
internal fun allowOnlineEntry(context: Context, online: Boolean?, preview: Boolean = false): Boolean {
    val offline = !preview && online == false
    if (offline) Toast.makeText(context, FeedbackText.Offline, Toast.LENGTH_SHORT).show()
    return !offline
}
