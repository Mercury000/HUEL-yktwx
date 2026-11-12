package com.mercury.yktwx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single network truth for the authenticated shell.
 *
 * [online] is null while Android has an active network whose capabilities are not known yet.
 * Unknown is deliberately treated as "may read": campus gateways can be reachable before
 * Android validates public Internet access.
 *
 * [epoch] changes only when connectivity comes back after an offline period, or when the
 * default network changes. Returning to the foreground is a lifecycle event, not a reconnect.
 */
data class NetworkState(val online: Boolean? = null, val epoch: Long = 0L)

internal fun networkState(context: Context): Boolean? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = connectivity.activeNetwork ?: return false
    // Campus services do not require Android's public Internet validation.
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

internal fun networkAvailable(context: Context): Boolean = networkState(context) != false

internal interface NetworkMonitor {
    val state: StateFlow<NetworkState>
    fun refresh(): NetworkState
}

/**
 * Owns the one default-network callback used by the app. It never performs a request; it only
 * publishes callback payloads, debouncing loss during short Wi-Fi/cellular handovers.
 * Synchronous getters are only a bootstrap snapshot: Android explicitly warns that querying
 * them from a callback races with the callback's state (including per-UID blocking).
 */
internal class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(NetworkState(networkState(app)))
    override val state: StateFlow<NetworkState> = mutable.asStateFlow()
    private var network: Network? = connectivity?.activeNetwork
    private var lastNetwork = network
    private var internet: Boolean? = mutable.value.online
    private var blocked = false
    private var reconnectPending = false
    private var callbackReceived = false
    private var pendingLoss: Runnable? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (callback != null) return
        callbackReceived = false
        val registration = object : ConnectivityManager.NetworkCallback() {
            private fun current() = callback === this

            override fun onAvailable(network: Network) {
                if (!current()) return
                callbackReceived = true
                cancelPendingLoss()
                this@AndroidNetworkMonitor.network = network
                internet = null // Wait for onCapabilitiesChanged; never query the system here.
                blocked = false
                publish(null)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (!current() || network != this@AndroidNetworkMonitor.network) return
                callbackReceived = true
                internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                publish(if (blocked) false else internet)
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (!current() || network != this@AndroidNetworkMonitor.network) return
                callbackReceived = true
                this@AndroidNetworkMonitor.blocked = blocked
                publish(if (blocked) false else internet)
            }

            override fun onLost(network: Network) {
                if (!current() || network != this@AndroidNetworkMonitor.network) return
                callbackReceived = true
                this@AndroidNetworkMonitor.network = null
                internet = null
                blocked = false
                reconnectPending = true
                cancelPendingLoss()
                pendingLoss = Runnable {
                    pendingLoss = null
                    if (current() && this@AndroidNetworkMonitor.network == null) publish(false)
                }.also { handler.postDelayed(it, 350L) }
            }
        }
        callback = registration
        refresh()
        connectivity?.registerDefaultNetworkCallback(registration, handler)
    }

    fun stop() {
        val registration = callback ?: return
        callback = null
        cancelPendingLoss()
        connectivity?.unregisterNetworkCallback(registration)
    }

    override fun refresh(): NetworkState {
        // A foreground snapshot must never overwrite an authoritative callback with stale data.
        if (!callbackReceived) {
            network = connectivity?.activeNetwork
            internet = network?.let { connectivity?.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            blocked = false
            publish(if (network == null && connectivity != null) false else internet)
        }
        return mutable.value
    }

    private fun cancelPendingLoss() {
        pendingLoss?.let(handler::removeCallbacks)
        pendingLoss = null
    }

    private fun publish(online: Boolean?) {
        val previous = mutable.value
        val changedNetwork = lastNetwork != null && network != null && lastNetwork != network
        val restored = previous.online == false && online != false
        val epoch = previous.epoch + if (online != false && (restored || changedNetwork || reconnectPending)) 1 else 0
        if (online != false) {
            lastNetwork = network ?: lastNetwork
            reconnectPending = false
        }
        if (online != previous.online || epoch != previous.epoch) {
            mutable.value = NetworkState(online = online, epoch = epoch)
        }
    }
}

/** A stable monitor for previews and unit tests; never used by the production shell. */
internal class StaticNetworkMonitor(
    online: Boolean? = null,
    private var epoch: Long = 0L,
) : NetworkMonitor {
    private val mutable = MutableStateFlow(NetworkState(online, epoch))
    override val state: StateFlow<NetworkState> = mutable.asStateFlow()
    override fun refresh(): NetworkState = mutable.value
    fun update(online: Boolean?, epoch: Long = this.epoch) {
        this.epoch = epoch
        mutable.value = NetworkState(online, epoch)
    }
}
