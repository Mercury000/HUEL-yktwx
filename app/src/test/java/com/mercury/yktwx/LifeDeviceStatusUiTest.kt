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
import com.mercury.yktwx.data.ReadFailureKind
import com.mercury.yktwx.data.UpdateRelease
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
    private fun capture(name: String, settle: Boolean = true) {
        if (settle) compose.mainClock.advanceTimeBy(1000)
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
        compose.onNodeWithText("预计剩余 5 分钟", substring = true).assertIsDisplayed()
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
                        waterSession = sessions.water, laundrySession = sessions.laundry, lifeNowMillis = sessions.clock)
                }
            } }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hall-life-status").assertIsDisplayed()
        val status = compose.onNodeWithTag("hall-life-status").fetchSemanticsNode().boundsInRoot
        val utilities = compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom < utilities.top)
        compose.onNodeWithText("待确认").assertDoesNotExist()
        compose.onNodeWithTag("life-status-progress-water", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("life-status-progress-laundry", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("预计剩余").assertIsDisplayed()
        compose.onNodeWithText("分钟").assertIsDisplayed()
        compose.onNodeWithText("胖乖余额").assertDoesNotExist()
        val waterEntry = compose.onNodeWithTag("hall-life-entry-water").fetchSemanticsNode().boundsInRoot
        val laundryEntry = compose.onNodeWithTag("hall-life-entry-laundry").fetchSemanticsNode().boundsInRoot
        assertTrue(waterEntry.right < laundryEntry.left)
        assertEquals(waterEntry.top, laundryEntry.top, 1f)
        assertEquals(waterEntry.width, laundryEntry.width, 1f)
        capture("hall-status-light")
    }
    @Test fun hallUpdateAndReadProgressStayAboveLifeDevicesAndUtilities() {
        compose.mainClock.autoAdvance = false
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        var dorm by mutableStateOf(DormUiState(roomsAvailable = true, loaded = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                MainNavigationScaffold(settings, 1, {}) { padding ->
                    HallPage(settings, padding.calculateBottomPadding() + 28.dp,
                        dorm = dorm, isActive = true, waterSession = sessions.water, lifeNowMillis = sessions.clock,
                        updateNotice = {
                            InlineUpdateNotice(AppUpdateUiState(noticeVisible = true,
                                release = UpdateRelease(7, "1.3.0", "", "https://example.com/update")), {}, {})
                        })
                }
            } }
        }
        compose.mainClock.advanceTimeBy(1000)
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val lifeTop = bounds("hall-life-devices-card").top
        val utilitiesTop = bounds("hall-utility-card").top
        assertTrue(bounds("app-update-notice").bottom <= bounds("hall-life-status").top)
        assertEquals("Service and utility cards keep the normal gap", 14 * compose.activity.resources.displayMetrics.density,
            utilitiesTop - bounds("hall-life-devices-card").bottom, 1f)
        compose.runOnIdle { dorm = DormUiState(roomsAvailable = true, loaded = true, loading = true) }
        repeat(40) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        capture("hall-top-update-loading", settle = false)
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        assertTrue(bounds("page-read-progress").bottom <= bounds("app-update-notice").top)
        assertEquals("Refresh must not shift service cards", lifeTop, bounds("hall-life-devices-card").top, 1f)
        assertEquals("Refresh must not shift utility cards", utilitiesTop, bounds("hall-utility-card").top, 1f)
        compose.runOnIdle { dorm = DormUiState(roomsAvailable = true, loaded = true,
            error = "网络连接失败", errorKind = ReadFailureKind.Network) }
        repeat(40) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        assertTrue(bounds("page-network-error").bottom <= bounds("app-update-notice").top)
        assertTrue(bounds("app-update-notice").bottom <= bounds("hall-life-status").top)
        capture("hall-top-update-network-error", settle = false)
    }
    @Test fun hallStatusCardsExpandAndCollapseWithTheFollowingContent() {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        var water by mutableStateOf<WaterSession?>(null)
        var laundry by mutableStateOf<LaundrySession?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                MainNavigationScaffold(settings, 1, {}) { padding ->
                    HallPage(settings, padding.calculateBottomPadding() + 28.dp,
                        dorm = DormUiState(roomsAvailable = true, loaded = true), isActive = true,
                        waterSession = water, laundrySession = laundry, lifeNowMillis = sessions.clock)
                }
            } }
        }
        compose.onNodeWithTag("hall-life-status").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1000)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { water = sessions.water; laundry = sessions.laundry }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val entering = compose.onNodeWithTag("hall-life-status").fetchSemanticsNode().boundsInRoot.height
        capture("hall-status-enter-06", settle = false)
        compose.mainClock.advanceTimeBy(500)
        val full = compose.onNodeWithTag("hall-life-status").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Appearance animates its height ($entering to $full)", entering > 0 && entering < full)
        val utilityFull = compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot.top
        capture("hall-status-both", settle = false)
        compose.runOnIdle { water = null }
        compose.mainClock.advanceTimeBy(96)
        val shrinking = compose.onNodeWithTag("hall-life-status").fetchSemanticsNode().boundsInRoot.height
        capture("hall-status-collapse-06", settle = false)
        compose.mainClock.advanceTimeBy(500)
        val single = compose.onNodeWithTag("hall-life-status").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Both to one animates between heights", shrinking < full && shrinking > single)
        assertTrue(compose.onNodeWithTag("hall-utility-card").fetchSemanticsNode().boundsInRoot.top < utilityFull)
        compose.onNodeWithTag("life-status-water").assertDoesNotExist()
        capture("hall-status-single", settle = false)
        compose.runOnIdle { laundry = null }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("hall-life-status").assertExists()
        capture("hall-status-exit-03", settle = false)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("hall-life-status").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
    @Test fun hallScanEntriesStaySeparateFromSessionHistoryAndCompletedCardsCloseIndividually() {
        val sessions = sessions()
        val finished = sessions.laundry.copy(phase = LaundryPhase.Ended, finishedAtMillis = sessions.laundry.expectedEndMillis!! + 121_000)
        val running = sessions.laundry.copy(sessionId = "another-laundry", device = sessions.laundry.device.copy(name = "另一台洗衣机"))
        val water = sessions.water.copy(phase = WaterPhase.Ended, finalized = true)
        var waters by mutableStateOf(listOf(water))
        var laundries by mutableStateOf(listOf(finished, running))
        val scanned = mutableListOf<LifeFeature>()
        val opened = mutableListOf<Pair<LifeFeature, String>>()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                MainNavigationScaffold(settings, 1, {}) { padding ->
                    HallPage(settings, padding.calculateBottomPadding() + 28.dp,
                        dorm = DormUiState(roomsAvailable = true, loaded = true), isActive = true,
                        waterSessions = waters, laundrySessions = laundries, lifeNowMillis = sessions.clock,
                        onOpenLifeDevice = scanned::add,
                        onOpenLifeSession = { feature, id -> opened.add(feature to id) },
                        onDismissLifeSession = { feature, id ->
                            if (feature == LifeFeature.Water) waters = waters.filterNot { it.sessionId == id }
                            else laundries = laundries.filterNot { it.sessionId == id }
                        })
                }
            } }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onAllNodesWithText("洗衣中").assertCountEquals(1)
        compose.onNodeWithText("洗衣已完成").assertIsDisplayed()
        compose.onNodeWithText("接水已结束").assertIsDisplayed()
        compose.onNodeWithTag("dismiss-life-session-${running.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("life-session-${finished.sessionId}").onChildAt(0).performClick()
        assertEquals(listOf(LifeFeature.Laundry to finished.sessionId), opened)
        compose.onNodeWithTag("hall-life-entry-laundry").performClick()
        compose.onNodeWithTag("hall-life-entry-water").performClick()
        assertEquals(listOf(LifeFeature.Laundry, LifeFeature.Water), scanned)
        capture("hall-multiple-orders-and-completed")
        compose.onNodeWithTag("dismiss-life-session-${finished.sessionId}").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("洗衣已完成").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.onNodeWithText("接水已结束").assertIsDisplayed()
        assertEquals(1, opened.size)
        compose.onNodeWithTag("dismiss-life-session-${water.sessionId}").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水已结束").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        capture("hall-multiple-orders-after-dismiss")
    }
    @Test fun homeKeepsManyLaundryOrdersScrollableAndIdentifiesTheChosenMachine() {
        val sessions = sessions()
        val laundries = (1..6).map { index -> sessions.laundry.copy(sessionId = "washer-$index",
            device = sessions.laundry.device.copy(id = "device-$index", name = "洗衣机 $index")) }
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        val opened = mutableListOf<String>()
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                LifeDeviceStatusHost(null, null, padding.calculateBottomPadding(), visible = true, onOpen = {},
                    laundrySessions = laundries, nowMillis = sessions.clock,
                    onOpenSession = { _, id -> opened.add(id) }) { extra ->
                    PaymentPage(PaymentUiState(needsLogin = false, error = "付款码暂未加载"), settings,
                        padding.calculateBottomPadding() + 28.dp + extra, onRefresh = {})
                }
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        val capsule = compose.onNodeWithTag("life-status-capsule").fetchSemanticsNode().boundsInRoot
        assertTrue(capsule.height <= 240 * compose.activity.resources.displayMetrics.density + 1)
        val navigation = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue(capsule.bottom < navigation.top)
        compose.onNodeWithText("洗衣机 1 · 预计剩余 5 分钟").assertIsDisplayed()
        compose.onNodeWithTag("life-session-washer-6").performScrollTo()
        compose.onNodeWithText("洗衣机 6 · 预计剩余 5 分钟").assertIsDisplayed()
        compose.onNodeWithTag("life-session-washer-6").onChildAt(0).performClick()
        assertEquals(listOf("washer-6"), opened)
        capture("home-many-laundry-orders")
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
