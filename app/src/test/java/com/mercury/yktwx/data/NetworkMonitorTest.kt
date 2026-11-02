package com.mercury.yktwx.data

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
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

    @Test fun stoppedMonitorIgnoresDebouncedAndLateCallbacks() {
        online()
        val monitor = AndroidNetworkMonitor(app)
        monitor.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = connectivity.activeNetwork!!
        callback.onAvailable(network)
        monitor.refresh()
        monitor.stop()
        offline()
        callback.onLost(network)
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(NetworkState(true, 0), monitor.state.value)
        assertTrue(shadowOf(connectivity).networkCallbacks.isEmpty())
    }
}
