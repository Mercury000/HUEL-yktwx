package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.*
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeDeviceStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private data class Sessions(val water: WaterSession, val laundry: LaundrySession) {
        val clock: () -> Long = { laundry.startedAtMillis!! + 46_000 }
    }
    private fun sessions(): Sessions = runBlocking {
        fun payload(name: String): JSONObject {
            val file = File("../tools/$name.json")
            assumeTrue("Optional sanitized local HAR fixture", file.isFile)
            return JSONObject(file.readText())
        }
        fun api(payload: JSONObject) = LifeDeviceApi("test-secret", { url ->
            OfflineResponse(url, payload.getJSONObject(if (url.path == "/order/detail") "/order/running" else url.path).toString())
        })
        val waterData = payload("life-device-capture")
        val waterDevice = api(waterData).device("867369079842630", "test-token")
        val water = WaterSession(waterDevice, waterDevice.skus.first().id, WaterPhase.Running,
            startedAtMillis = waterData.getJSONObject("__waterSession").getLong("startedAt"))
        val laundryData = payload("laundry-ui-capture")
        val service = api(laundryData)
        val device = service.laundryDevice(laundryData.getString("__code"), "test-token")
        val reading = service.laundryOrder(laundryData.getJSONObject("/order/running").getJSONObject("data").getString("orderNo"), "test-token")
        Sessions(water, LaundrySession(device, reading.skuId, reading.price, "2", LaundryPhase.Running,
            true, reading.orderNo, reading.orderId, true, true, reading.minutes, reading.paidAtMillis, reading.expectedEndMillis))
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }; compose.waitForIdle()
        val file = File("../tools/life-device-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun home(dark: Boolean, floating: Boolean) {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(if (dark) 2 else 1); setFloating(floating) }
        var water by mutableStateOf<WaterSession?>(null)
        var laundry by mutableStateOf<LaundrySession?>(null)
        var visible by mutableStateOf(true)
        val opened = mutableListOf<LifeFeature>()
        var reserved by mutableStateOf(0.dp)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    MainNavigationScaffold(settings, 0, {}) { padding ->
                        LifeDeviceStatusHost(water, laundry, padding.calculateBottomPadding(), visible, opened::add,
                            nowMillis = sessions.clock) { extra ->
                            SideEffect { reserved = extra }
                            PaymentPage(PaymentUiState(needsLogin = false, error = "付款码暂未加载"), settings,
                                padding.calculateBottomPadding() + 28.dp + extra, onRefresh = {}, isActive = false)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("life-status-capsule").assertDoesNotExist()
        compose.runOnIdle { water = sessions.water; laundry = sessions.laundry }
        capture("home-status-${if (dark) "dark-large" else "light"}-${if (floating) "floating" else "classic"}")
        compose.onNodeWithText("接水中").assertIsDisplayed()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.onNodeWithText("预计剩余 5 分钟").assertIsDisplayed()
        val capsule = compose.onNodeWithTag("life-status-capsule").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("Capsule clears navigation", capsule.bottom < navigation.top)
        assertTrue("Reserve measured space for both services", reserved > 100.dp)
        compose.onNodeWithTag("life-status-water").performClick()
        compose.onNodeWithTag("life-status-laundry").performClick()
        assertEquals(listOf(LifeFeature.Water, LifeFeature.Laundry), opened)
        compose.runOnIdle { water = sessions.water.copy(phase = WaterPhase.Ended, finalized = true) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水中").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.runOnIdle { visible = false }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-status-capsule").assertDoesNotExist()
        assertEquals(0.dp, reserved)
        compose.runOnIdle { visible = true; laundry = sessions.laundry.copy(phase = LaundryPhase.Ended) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-status-capsule").assertDoesNotExist()
    }
    @Test fun homeShowsBothServicesAndResumesEachWithoutCoveringClassicNavigation() = home(false, false)
    @Test @Config(fontScale = 1.3f)
    fun homeFitsLargeTextAndDarkFloatingNavigation() = home(true, true)
    @Test fun hallPlacesBothServiceCardsBeforeUtilities() {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                MainNavigationScaffold(settings, 1, {}) { padding ->
                    HallPage(settings, padding.calculateBottomPadding() + 28.dp,
                        dorm = DormUiState(roomsAvailable = true, loaded = true), isActive = true,
                        waterSession = sessions.water, laundrySession = sessions.laundry)
                }
            } }
        }
        compose.onNodeWithTag("hall-life-status").assertIsDisplayed()
        val status = compose.onNodeWithTag("hall-life-status").fetchSemanticsNode().boundsInRoot
        val utilities = compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom < utilities.top)
        compose.onNodeWithText("待确认").assertDoesNotExist()
        capture("hall-status-light")
    }
    private class OfflineResponse(url: URL, val body: String) : HttpsURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
