package com.mercury.yktwx.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import com.mercury.yktwx.ui.components.FeedbackText
import kotlinx.coroutines.flow.first

internal fun isNetworkAvailable(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val network = connectivity.activeNetwork ?: return false
    // Campus services can be reachable even when Android has not validated the connection.
    return connectivity.getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != false
}

internal data class NetworkReadAvailability(
    val online: Boolean, val foreground: Boolean, val recoveryId: Long = 0,
    val reconnected: Boolean = false,
) {
    fun update(online: Boolean = this.online, foreground: Boolean = this.foreground): NetworkReadAvailability {
        val restored = online && !this.online
        val resumed = foreground && !this.foreground
        val recover = online && foreground && (restored || resumed)
        return copy(online = online, foreground = foreground,
            recoveryId = recoveryId + if (recover) 1 else 0,
            reconnected = if (recover) restored || reconnected && !this.foreground
                else if (restored && !foreground) true
                else if (!foreground && this.foreground) false else reconnected)
    }
}

/** One connection listener for the authenticated shell, including a fresh snapshot on foreground. */
@Composable
internal fun rememberNetworkReadAvailability(preview: Boolean): State<NetworkReadAvailability> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val state = remember(context, lifecycle, preview) { mutableStateOf(NetworkReadAvailability(
        online = preview || isNetworkAvailable(context),
        foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))) }
    DisposableEffect(context, lifecycle, preview) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val handler = Handler(Looper.getMainLooper())
        var observing = true
        fun update(foreground: Boolean = state.value.foreground) {
            state.value = state.value.update(preview || isNetworkAvailable(context), foreground)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            private fun changed() {
                val online = isNetworkAvailable(context)
                handler.post { if (observing) state.value = state.value.update(online = online) }
            }
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> update(foreground = true)
                Lifecycle.Event.ON_STOP -> update(foreground = false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (!preview) connectivity?.registerDefaultNetworkCallback(callback)
        onDispose {
            observing = false
            lifecycle.removeObserver(observer)
            if (!preview) connectivity?.unregisterNetworkCallback(callback)
        }
    }
    return state
}

/** A recovery event retries a failed read once; its own result cannot trigger another retry. */
@Composable
internal fun ReadRecoveryEffect(availability: NetworkReadAvailability, page: Any?, loading: Boolean,
    networkFailed: Boolean, onRetry: () -> Unit) {
    var handledId by remember { mutableLongStateOf(0) }
    val latestLoading by rememberUpdatedState(loading)
    val latestFailure by rememberUpdatedState(networkFailed)
    val retry by rememberUpdatedState(onRetry)
    LaunchedEffect(availability.recoveryId, availability.online, availability.foreground, page) {
        if (page == null || !availability.online || !availability.foreground ||
            availability.recoveryId == handledId) return@LaunchedEffect
        // Foreground/entry handlers may already have started a read. A reconnection during an
        // offline request waits for that request to settle, then retries only if it still failed.
        if (latestLoading && !availability.reconnected) {
            handledId = availability.recoveryId
            return@LaunchedEffect
        }
        snapshotFlow { latestLoading }.first { !it }
        handledId = availability.recoveryId
        if (latestFailure) retry()
    }
}

/** Block only a known offline entry. Connected networks can still fail at the service. */
internal fun allowOnlineEntry(context: Context, preview: Boolean = false): Boolean {
    val offline = !preview && !isNetworkAvailable(context)
    if (offline) Toast.makeText(context, FeedbackText.Offline, Toast.LENGTH_SHORT).show()
    return !offline
}
