package com.mercury.yktwx

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import com.mercury.yktwx.ui.NetworkReadAvailability
import com.mercury.yktwx.ui.ReadRecoveryEffect
import com.mercury.yktwx.ui.rememberNetworkReadAvailability
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NetworkReadRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun reconnectRetriesOnceAndDoesNotLoopWhenTheRetryAlsoFails() {
        var availability by mutableStateOf(NetworkReadAvailability(online = false, foreground = true))
        var loading by mutableStateOf(false)
        var error by mutableStateOf<String?>("网络连接失败")
        var retries = 0
        compose.setContent {
            ReadRecoveryEffect(availability, "hall", loading, error) {
                retries++; loading = true; error = null
            }
        }
        compose.runOnIdle { assertEquals(0, retries); availability = availability.update(online = true) }
        compose.runOnIdle { assertEquals(1, retries); loading = false; error = "网络连接失败" }
        compose.runOnIdle { assertEquals(1, retries); availability = availability.update(online = true) }
        compose.runOnIdle { assertEquals(1, retries); availability = availability.update(online = false) }
        compose.runOnIdle { availability = availability.update(online = true) }
        compose.runOnIdle { assertEquals(2, retries) }
    }

    @Test fun reconnectDuringAReadWaitsAndRetriesOnlyIfThatReadFails() {
        var availability by mutableStateOf(NetworkReadAvailability(online = false, foreground = true))
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var retries = 0
        compose.setContent { ReadRecoveryEffect(availability, "my", loading, error) { retries++ } }
        compose.runOnIdle { availability = availability.update(online = true) }
        compose.runOnIdle { assertEquals(0, retries); error = "网络连接失败"; loading = false }
        compose.runOnIdle { assertEquals(1, retries); availability = availability.update(online = false) }
        compose.runOnIdle { loading = true; error = null; availability = availability.update(online = true) }
        compose.runOnIdle { assertEquals(1, retries); loading = false }
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun foregroundDoesNotDuplicateAnExistingReadAndCoveredPagesWaitUntilVisible() {
        var availability by mutableStateOf(NetworkReadAvailability(online = true, foreground = false))
        var loading by mutableStateOf(true)
        var page by mutableStateOf<String?>("payment")
        var retries = 0
        compose.setContent { ReadRecoveryEffect(availability, page, loading, "网络连接失败") { retries++ } }
        compose.runOnIdle { availability = availability.update(foreground = true) }
        compose.runOnIdle { loading = false }
        compose.runOnIdle { assertEquals(0, retries); page = null; availability = availability.update(online = false) }
        compose.runOnIdle { availability = availability.update(online = true) }
        compose.runOnIdle { assertEquals(0, retries); page = "hall" }
        compose.runOnIdle { assertEquals(1, retries); page = "bill" }
        compose.runOnIdle { assertEquals(1, retries); availability = availability.update(foreground = false) }
        compose.runOnIdle { availability = availability.update(foreground = true) }
        compose.runOnIdle { assertEquals(2, retries) }
    }

    @Test fun networkListenerRecoversWithoutAndroidValidationAndUnregistersWithItsShell() {
        val manager = compose.activity.getSystemService(ConnectivityManager::class.java)
        shadowOf(manager).setActiveNetworkInfo(null)
        var show by mutableStateOf(true)
        var current: NetworkReadAvailability? = null
        compose.setContent { if (show) current = rememberNetworkReadAvailability(preview = false).value }
        compose.runOnIdle { assertFalse(current!!.online); assertEquals(0L, current!!.recoveryId) }
        val callbacks = shadowOf(manager).networkCallbacks.toList()
        assertEquals(1, callbacks.size)
        compose.runOnIdle {
            shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
            callbacks.single().onCapabilitiesChanged(manager.activeNetwork!!, capabilities)
        }
        compose.runOnIdle { assertTrue(current!!.online); assertEquals(1L, current!!.recoveryId) }
        compose.runOnIdle { callbacks.single().onAvailable(manager.activeNetwork!!) }
        compose.runOnIdle { assertEquals(1L, current!!.recoveryId) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertTrue(current!!.foreground); assertEquals(2L, current!!.recoveryId); show = false }
        compose.runOnIdle { assertTrue(shadowOf(manager).networkCallbacks.isEmpty()) }
    }

    @Test fun coveringAReadThatIsWaitingForRecoveryDoesNotConsumeTheRecovery() {
        var availability by mutableStateOf(NetworkReadAvailability(online = false, foreground = true))
        var page by mutableStateOf<String?>("hall")
        var loading by mutableStateOf(true)
        var retries = 0
        compose.setContent { ReadRecoveryEffect(availability, page, loading, "网络连接失败") { retries++ } }
        compose.runOnIdle { availability = availability.update(online = true) }
        compose.runOnIdle { page = null }
        compose.runOnIdle { loading = false }
        compose.runOnIdle { assertEquals(0, retries); page = "hall" }
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
