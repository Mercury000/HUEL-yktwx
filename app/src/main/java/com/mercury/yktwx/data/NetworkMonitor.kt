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
 * publishes connectivity transitions, debouncing short Wi-Fi/cellular handovers.
 */
internal class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow(NetworkState(networkState(app)))
    override val state: StateFlow<NetworkState> = mutable.asStateFlow()
    private var network: Network? = connectivity?.activeNetwork
    private var started = false
    private var pendingOnline: Runnable? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule(immediate = false)
        override fun onLost(network: Network) = schedule(immediate = true)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            schedule(immediate = false)

        private fun schedule(immediate: Boolean) {
            if (!started) return
            pendingOnline?.let(handler::removeCallbacks)
            val run = Runnable { if (started) publish() }
            pendingOnline = run
            handler.postDelayed(run, if (immediate) 0 else 350L)
        }
    }

    fun start() {
        if (started) return
        started = true
        connectivity?.registerDefaultNetworkCallback(callback, handler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        pendingOnline?.let(handler::removeCallbacks)
        pendingOnline = null
        connectivity?.unregisterNetworkCallback(callback)
    }

    override fun refresh(): NetworkState {
        pendingOnline?.let(handler::removeCallbacks)
        publish()
        return mutable.value
    }

    private fun publish() {
        pendingOnline = null
        val previous = mutable.value
        val nextNetwork = connectivity?.activeNetwork
        val changedNetwork = network != null && nextNetwork != null && network != nextNetwork
        network = nextNetwork ?: network
        val online = networkState(app)
        val restored = previous.online == false && online != false
        val epoch = previous.epoch + if (restored || changedNetwork && online != false) 1 else 0
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
