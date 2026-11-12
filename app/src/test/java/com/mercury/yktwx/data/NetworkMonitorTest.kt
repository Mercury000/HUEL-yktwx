package com.mercury.yktwx.data

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Network
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetwork
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkMonitorTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private fun online() {
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
    }

    private fun offline() {
        shadowOf(connectivity).setActiveNetworkInfo(null)
    }

    private fun available(callback: ConnectivityManager.NetworkCallback, network: Network) {
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, ShadowNetworkCapabilities.newInstance().also {
            shadowOf(it).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        })
        callback.onBlockedStatusChanged(network, false)
    }

    @Test fun callbackPayloadRestoresConnectivityWhileSystemGettersStillReportOffline() {
        offline()
        val monitor = AndroidNetworkMonitor(app)
        try {
            monitor.start()
            val callback = shadowOf(connectivity).networkCallbacks.single()
            available(callback, ShadowNetwork.newInstance(42))
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertNull(connectivity.activeNetwork)
            assertEquals(NetworkState(true, 1), monitor.state.value)
            assertEquals(NetworkState(true, 1), monitor.refresh())
        } finally { monitor.stop() }
    }

    @Test fun blockedPayloadOverridesUnchangedCapabilitiesAndUnblockNeedsNoSnapshotChange() {
        online()
        val monitor = AndroidNetworkMonitor(app)
        try {
            monitor.start()
            val callback = shadowOf(connectivity).networkCallbacks.single()
            val network = connectivity.activeNetwork!!
            available(callback, network)
            callback.onBlockedStatusChanged(network, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(NetworkState(false, 0), monitor.state.value)
            offline()
            callback.onBlockedStatusChanged(network, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(NetworkState(true, 1), monitor.state.value)
        } finally { monitor.stop() }
    }

    @Test fun handoverIgnoresOldNetworkEventsAndLateCallbacksFromPreviousRegistration() {
        online()
        val monitor = AndroidNetworkMonitor(app)
        try {
            monitor.start()
            val oldCallback = shadowOf(connectivity).networkCallbacks.single()
            val oldNetwork = connectivity.activeNetwork!!
            available(oldCallback, oldNetwork)
            oldCallback.onLost(oldNetwork)
            available(oldCallback, ShadowNetwork.newInstance(42))
            oldCallback.onLost(oldNetwork)
            oldCallback.onBlockedStatusChanged(oldNetwork, true)
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertEquals(NetworkState(true, 1), monitor.state.value)
            monitor.stop()
            online()
            monitor.start()
            val state = monitor.state.value
            oldCallback.onLost(connectivity.activeNetwork!!)
            oldCallback.onAvailable(ShadowNetwork.newInstance(99))
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
            assertEquals(state, monitor.state.value)
        } finally { monitor.stop() }
    }

    @Test fun missingDefaultNetworkMeansOffline() {
        offline()
        assertEquals(false, networkState(app))
        assertFalse(networkAvailable(app))
    }

    @Test fun anActiveNetworkWithInternetCapabilityMeansOnline() {
        online()
        assertEquals(true, networkState(app))
        assertTrue(networkAvailable(app))
    }

    @Test fun staticMonitorPublishesStateAndEpoch() {
        val monitor = StaticNetworkMonitor(online = true, epoch = 3)
        assertEquals(NetworkState(true, 3), monitor.state.value)
        monitor.update(online = false, epoch = 3)
        assertEquals(NetworkState(false, 3), monitor.state.value)
        monitor.update(online = true, epoch = 4)
        assertEquals(NetworkState(true, 4), monitor.state.value)
        assertEquals(monitor.state.value, monitor.refresh())
    }

    @Test fun monitorIncrementsEpochOnlyWhenConnectivityReturns() {
        online()
        val monitor = AndroidNetworkMonitor(app)
        try {
            monitor.start()
            assertEquals(NetworkState(true, 0), monitor.state.value)

            monitor.refresh()
            assertEquals(0L, monitor.state.value.epoch)

            offline()
            monitor.refresh()
            assertEquals(NetworkState(false, 0), monitor.state.value)

            // Staying offline is not a new event.
            monitor.refresh()
            assertEquals(0L, monitor.state.value.epoch)

            online()
            monitor.refresh()
            assertEquals(NetworkState(true, 1), monitor.state.value)

            // Staying online does not fake another reconnect.
            monitor.refresh()
            assertEquals(1L, monitor.state.value.epoch)
        } finally {
            monitor.stop()
        }
    }

    @Test fun networkUnblockRefreshesAnOfflineForegroundSnapshot() {
        online()
        val monitor = AndroidNetworkMonitor(app)
        try {
            monitor.start()
            val callback = shadowOf(connectivity).networkCallbacks.single()
            val network = connectivity.activeNetwork!!
            available(callback, network)
            callback.onBlockedStatusChanged(network, true)
            // ON_START can run before Android has finished unblocking the app's UID.
            offline()
            monitor.refresh()
            assertEquals(NetworkState(false, 0), monitor.state.value)

            // No network or capability change is needed when the restriction is lifted.
            online()
            callback.onBlockedStatusChanged(network, false)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(NetworkState(true, 1), monitor.state.value)
        } finally {
            monitor.stop()
        }
    }

    @Test fun stoppedMonitorIgnoresDebouncedAndLateCallbacks() {
        online()
        val monitor = AndroidNetworkMonitor(app)
        monitor.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = connectivity.activeNetwork!!
        available(callback, network)
        callback.onLost(network)
        monitor.refresh()
        monitor.stop()
        offline()
        callback.onLost(network)
        callback.onBlockedStatusChanged(network, false)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(NetworkState(true, 0), monitor.state.value)
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }
}
