package com.mercury.yktwx

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.mercury.yktwx.data.NetworkState
import com.mercury.yktwx.ui.ForegroundState
import com.mercury.yktwx.ui.rememberForegroundState
import com.mercury.yktwx.ui.rememberNetworkState
import org.junit.Assert.*
import org.junit.Rule
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
class NetworkReadRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun connect(manager: ConnectivityManager) {
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
    }

    @Test fun reconnectIncrementsTheEpochAndForegroundNeverDoes() {
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(null)
        var show by mutableStateOf(true)
        var network: NetworkState? = null
        var foreground: ForegroundState? = null
        compose.setContent {
            if (show) {
                network = rememberNetworkState(preview = false).value
                foreground = rememberForegroundState().value
            }
        }
        var firstResume = 0L
        compose.runOnIdle {
            assertFalse(network!!.online == true)
            assertEquals(0L, network!!.epoch)
            assertTrue(foreground!!.active)
            firstResume = foreground!!.epoch
        }
        val callbacks = shadowOf(manager).networkCallbacks.toList()
        assertEquals(1, callbacks.size)

        compose.runOnIdle {
            connect(manager)
            callbacks.single().onAvailable(manager.activeNetwork!!)
            callbacks.single().onCapabilitiesChanged(manager.activeNetwork!!, manager.getNetworkCapabilities(manager.activeNetwork)!!)
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        }
        compose.runOnIdle {
            assertTrue(network!!.online == true)
            assertEquals(1L, network!!.epoch)
        }

        // Returning from the background is a lifecycle event, not a reconnect.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { assertFalse(foreground!!.active); assertEquals(1L, network!!.epoch) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {
            assertTrue(foreground!!.active)
            assertEquals(1L, network!!.epoch)
            assertTrue(foreground!!.epoch > firstResume)
        }

        compose.runOnIdle { show = false }
        compose.runOnIdle { assertTrue(shadowOf(manager).networkCallbacks.isEmpty()) }
    }

    @Test fun previewIsAlwaysOnlineAndNeverRegistersAListener() {
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(null)
        var network: NetworkState? = null
        compose.setContent { network = rememberNetworkState(preview = true).value }
        compose.runOnIdle {
            assertEquals(NetworkState(online = true), network)
            assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
        }
    }

    @Test fun remountingTheShellKeepsConnectionEpochsAndRegistersOnlyOneListener() {
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(null)
        var show by mutableStateOf(true)
        var network: NetworkState? = null
        compose.setContent { if (show) network = rememberNetworkState(preview = false).value }
        compose.runOnIdle {
            connect(manager)
            shadowOf(manager).networkCallbacks.single().onAvailable(manager.activeNetwork!!)
            shadowOf(manager).networkCallbacks.single().onCapabilitiesChanged(manager.activeNetwork!!,
                manager.getNetworkCapabilities(manager.activeNetwork)!!)
            shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        }
        compose.runOnIdle { assertEquals(NetworkState(true, 1), network); show = false }
        compose.runOnIdle { assertTrue(shadowOf(manager).networkCallbacks.isEmpty()); show = true }
        compose.runOnIdle {
            assertEquals(NetworkState(true, 1), network)
            assertEquals(1, shadowOf(manager).networkCallbacks.size)
        }
    }

    @Test fun foregroundResumeRefreshesTheNetworkSnapshotEvenWithoutACallback() {
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        connect(manager)
        var network: NetworkState? = null
        compose.setContent { network = rememberNetworkState(preview = false).value }
        compose.runOnIdle { assertEquals(NetworkState(true, 0), network) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { shadowOf(manager).setActiveNetworkInfo(null) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(NetworkState(false, 0), network) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.runOnIdle { connect(manager) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(NetworkState(true, 1), network) }
    }

    @Test fun activeNetworkWithoutInternetCapabilityIsOffline() {
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        var network: NetworkState? = null
        compose.setContent { network = rememberNetworkState(preview = false).value }
        compose.runOnIdle {
            assertEquals(false, network!!.online)
            assertEquals(0L, network!!.epoch)
        }
    }
}
