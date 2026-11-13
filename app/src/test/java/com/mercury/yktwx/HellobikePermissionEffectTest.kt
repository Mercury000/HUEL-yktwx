package com.mercury.yktwx

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.HellobikeLocationPermissionEffect
import com.mercury.yktwx.ui.pages.HellobikeDevicePage
import com.mercury.yktwx.ui.themeSettingsFixture
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HellobikePermissionEffectTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val models = ViewModelStore()
    private data class Launch(val code: Int, val contract: ActivityResultContract<*, *>, val input: Any?)
    private val launches = mutableListOf<Launch>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launches += Launch(requestCode, contract, input)
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
    private val api = FakeHellobikeService()
    @After fun cleanup() { models.clear() }
    private fun model(): HellobikeViewModel {
        shadowOf(compose.activity.application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        return HellobikeViewModel(api, BikeRecords().store(), location, io = Dispatchers.Main.immediate,
            elapsed = { 50_000L }, analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("permission", it) }
    }
    @Test fun staleHistoryCannotSkipTheSystemPromptAndSettingsRequireAnExplicitClick() {
        val bike = model()
        compose.activity.getSharedPreferences("hellobike-location-permission", Context.MODE_PRIVATE)
            .edit().putBoolean("requested", true).commit()
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) { AppTheme {
            val state by bike.state.collectAsState()
            HellobikeLocationPermissionEffect(state.locationPermissionRequest, true, bike)
            HellobikeDevicePage(state, settings, bike, true, {}, {}, elapsedMillis = { 50_000L })
        } } }
        compose.runOnIdle { bike.initialize(false); bike.environment(true); bike.openDevice(fixtureBikeSession.bikeNo) }
        compose.runOnIdle {
            assertEquals(1, launches.size)
            assertTrue("Ask-every-time must still reach Android's request contract", launches.single().contract is ActivityResultContracts.RequestMultiplePermissions)
            registry.dispatchResult(launches.single().code, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false, Manifest.permission.ACCESS_COARSE_LOCATION to false))
        }
        compose.onNodeWithTag("hellobike-primary-action").assertTextContains("去设置开启定位")
        compose.onNodeWithTag("hellobike-device-page").performScrollToNode(hasTestTag("hellobike-device-error"))
        compose.onNodeWithTag("hellobike-device-retry").assertDoesNotExist()
        assertEquals(1, launches.size)
        compose.onNodeWithTag("hellobike-primary-action").performClick()
        compose.runOnIdle {
            assertEquals(2, launches.size)
            assertTrue(launches.last().contract is ActivityResultContracts.StartActivityForResult)
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, (launches.last().input as Intent).action)
            // "Ask every time" in Settings has no current grant. A new prompt must remain possible.
            registry.dispatchResult(launches.last().code, Activity.RESULT_CANCELED, null)
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("hellobike-primary-action").assertTextContains("开启定位")
        compose.onNodeWithTag("hellobike-primary-action").performClick()
        compose.runOnIdle {
            assertEquals(3, launches.size)
            assertTrue(launches.last().contract is ActivityResultContracts.RequestMultiplePermissions)
            location.failure = null
            registry.dispatchResult(launches.last().code, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false, Manifest.permission.ACCESS_COARSE_LOCATION to true))
        }
        compose.runOnIdle { assertNotNull(bike.state.value.precheck); assertEquals(0, api.createCalls); assertEquals(0, api.closeCalls) }
    }
    @Test fun coarseGrantWaitsForTheActiveEffectAndContinuesOnlyTheOriginalCheck() {
        val bike = model()
        var enabled by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            val state by bike.state.collectAsState()
            HellobikeLocationPermissionEffect(state.locationPermissionRequest, enabled, bike)
        } }
        compose.runOnIdle { bike.initialize(false); bike.environment(true); bike.pageVisible(true); bike.openDevice(fixtureBikeSession.bikeNo) }
        compose.runOnIdle { enabled = false }
        compose.runOnIdle {
            location.failure = null
            registry.dispatchResult(launches.single().code, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false, Manifest.permission.ACCESS_COARSE_LOCATION to true))
        }
        compose.runOnIdle { assertNull(bike.state.value.precheck); enabled = true }
        compose.runOnIdle { assertNotNull(bike.state.value.precheck); assertEquals(1, launches.size); assertEquals(0, api.createCalls) }
    }
    @Test fun cancelledRequestCannotReuseThePreviousNativeResultForANewScan() {
        val bike = model()
        compose.setContent { CompositionLocalProvider(LocalActivityResultRegistryOwner provides registryOwner) {
            val state by bike.state.collectAsState()
            HellobikeLocationPermissionEffect(state.locationPermissionRequest, true, bike)
        } }
        compose.runOnIdle { bike.initialize(false); bike.environment(true); bike.pageVisible(true); bike.openDevice(fixtureBikeSession.bikeNo) }
        val first = bike.state.value.locationPermissionRequest
        compose.runOnIdle { bike.dismissLogin(); bike.openDevice("2222222222") }
        val second = bike.state.value.locationPermissionRequest
        assertNotEquals(first, second)
        compose.runOnIdle {
            assertEquals("Only one native request may be outstanding", 1, launches.size)
            registry.dispatchResult(launches.first().code, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false, Manifest.permission.ACCESS_COARSE_LOCATION to true))
        }
        compose.runOnIdle {
            assertEquals(2, launches.size); assertEquals(second, bike.state.value.locationPermissionRequest)
            assertNull(bike.state.value.precheck)
            location.failure = null
            registry.dispatchResult(launches.last().code, mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true))
        }
        compose.runOnIdle { assertEquals("2222222222", bike.state.value.precheck?.bikeNo); assertEquals(0, api.createCalls) }
    }
}
