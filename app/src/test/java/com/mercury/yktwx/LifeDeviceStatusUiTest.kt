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
import com.mercury.yktwx.preview.PreviewData
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
    private fun payment(dark: Boolean = false, floating: Boolean = false) {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(if (dark) 2 else 1); setFloating(floating) }
        var water by mutableStateOf<WaterSession?>(sessions.water)
        var laundry by mutableStateOf< LaundrySession?>(sessions.laundry)
        val opened = mutableListOf<Pair<LifeFeature, String>>()
        var scans = 0
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                MainNavigationScaffold(settings, 0, {}) { padding ->
                    PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                        onScan = { scans++ }, waterSessions = listOfNotNull(water), laundrySessions = listOfNotNull(laundry),
                        onOpenLifeSession = { feature, id -> opened.add(feature to id) },
                        onDismissLifeSession = { feature, _ -> if (feature == LifeFeature.Water) water = null else laundry = null },
                        lifeNowMillis = sessions.clock)
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("payment-life-status").assertIsDisplayed()
        compose.onNodeWithText("接水中").assertIsDisplayed()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.onNodeWithText("预计剩余").assertIsDisplayed()
        val status = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot
        val code = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot
        assertTrue(status.bottom <= code.top)
        compose.onAllNodesWithTag("payment-scan-button").assertCountEquals(1)
        val scan = compose.onNodeWithTag("payment-scan-button").fetchSemanticsNode().boundsInRoot
        assertTrue(scan.center.x > status.center.x && scan.bottom <= status.top)
        compose.onNodeWithTag("payment-scan-button").performClick()
        assertEquals(1, scans)
        compose.onNodeWithTag("life-status-water").performClick()
        compose.onNodeWithTag("life-status-laundry").performClick()
        assertEquals(listOf(LifeFeature.Water to sessions.water.sessionId, LifeFeature.Laundry to sessions.laundry.sessionId), opened)
        capture("payment-progress-${if (dark) "dark-large" else "light"}")
        compose.runOnIdle { water = sessions.water.copy(phase = WaterPhase.Ended, finalized = true) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水已结束").assertIsDisplayed()
        compose.onNodeWithTag("dismiss-life-session-${sessions.water.sessionId}").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水已结束").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
    }
    @Test fun paymentShowsBothProgressCardsAndOneScannerInTheTopRight() = payment()
    @Test @Config(fontScale = 1.3f)
    fun paymentProgressFitsLargeTextAndDarkFloatingNavigation() = payment(true, true)

    @Test fun hallContainsNoLifeDeviceContentAndKeepsReadFeedbackAboveUtilities() {
        compose.mainClock.autoAdvance = false
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        var dorm by mutableStateOf(DormUiState(roomsAvailable = true, loaded = true))
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 1, {}) { padding ->
                HallPage(settings, padding.calculateBottomPadding() + 28.dp, dorm = dorm, isActive = true,
                    updateNotice = { InlineUpdateNotice(AppUpdateUiState(noticeVisible = true,
                        release = UpdateRelease(7, "1.3.0", "", "https://example.com/update")), {}, {}) })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val utilitiesTop = bounds("hall-utility-card").top
        assertTrue(bounds("app-update-notice").bottom <= utilitiesTop)
        compose.onNodeWithText("胖乖生活").assertDoesNotExist()
        compose.onNodeWithText("生活设备").assertDoesNotExist()
        compose.onNodeWithText("扫码接水").assertDoesNotExist()
        compose.onNodeWithText("扫码洗衣").assertDoesNotExist()
        compose.onNodeWithTag("payment-scan-button").assertDoesNotExist()
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
        compose.runOnIdle { dorm = DormUiState(roomsAvailable = true, loaded = true, loading = true) }
        repeat(40) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("page-read-progress").assertIsDisplayed()
        assertTrue(bounds("page-read-progress").bottom <= bounds("app-update-notice").top)
        assertEquals("Refresh must not shift utilities", utilitiesTop, bounds("hall-utility-card").top, 1f)
        capture("hall-without-life-devices", settle = false)
        compose.runOnIdle { dorm = DormUiState(roomsAvailable = true, loaded = true,
            error = "网络连接失败", errorKind = ReadFailureKind.Network) }
        repeat(40) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        assertTrue(bounds("page-network-error").bottom <= bounds("app-update-notice").top)
    }
    @Test fun paymentStatusCardsAnimateWithThePaymentCode() {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        var water by mutableStateOf<WaterSession?>(null)
        var laundry by mutableStateOf<LaundrySession?>(null)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    waterSessions = listOfNotNull(water), laundrySessions = listOfNotNull(laundry), lifeNowMillis = sessions.clock)
            }
        } } }
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1000); compose.mainClock.autoAdvance = false
        val originalCodeTop = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { water = sessions.water; laundry = sessions.laundry }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle(); compose.mainClock.advanceTimeBy(96)
        val entering = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(500)
        val full = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Appearance animates its height", entering > 0 && entering < full)
        val codeFull = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { water = null }
        compose.mainClock.advanceTimeBy(96)
        val shrinking = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot.height
        compose.mainClock.advanceTimeBy(500)
        val single = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot.height
        assertTrue(shrinking < full && shrinking > single)
        assertTrue(compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top < codeFull)
        compose.runOnIdle { laundry = null }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("payment-life-status").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
        assertEquals(originalCodeTop, compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
    @Test fun paymentAllowsDismissalAsSoonAsWaterEndIsConfirmed() {
        val device = LifeDevice("water", "serial", "开水", "04", "org", "org", "shop", "imei",
            "学校", "", "after", false, true, null, emptyList(), emptyList())
        val running = WaterSession(device, "sku", WaterPhase.Running, sessionId = "water-with-incomplete-response")
        var waters by mutableStateOf(listOf(running))
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    waterSessions = waters, onDismissLifeSession = { _, id -> waters = waters.filterNot { it.sessionId == id } })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("dismiss-life-session-${running.sessionId}").assertDoesNotExist()
        compose.runOnIdle { waters = listOf(running.copy(phase = WaterPhase.Ended, finalized = false)) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水已结束").assertIsDisplayed()
        compose.onNodeWithTag("dismiss-life-session-${running.sessionId}").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水已结束").assertDoesNotExist()
        assertTrue(waters.isEmpty())
    }
    @Test fun paymentKeepsMultipleCompletedRecordsUntilIndividualDismissal() {
        val sessions = sessions()
        val finished = sessions.laundry.copy(phase = LaundryPhase.Ended, finishedAtMillis = sessions.laundry.expectedEndMillis!! + 121_000)
        val running = sessions.laundry.copy(sessionId = "another-laundry", device = sessions.laundry.device.copy(name = "另一台洗衣机"))
        val water = sessions.water.copy(phase = WaterPhase.Ended, finalized = true)
        var waters by mutableStateOf(listOf(water))
        var laundries by mutableStateOf(listOf(finished, running))
        val opened = mutableListOf<Pair<LifeFeature, String>>()
        var scans = 0
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    waterSessions = waters, laundrySessions = laundries, lifeNowMillis = sessions.clock,
                    onScan = { scans++ }, onOpenLifeSession = { feature, id -> opened.add(feature to id) },
                    onDismissLifeSession = { feature, id ->
                        if (feature == LifeFeature.Water) waters = waters.filterNot { it.sessionId == id }
                        else laundries = laundries.filterNot { it.sessionId == id }
                    })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.onNodeWithText("洗衣已完成").assertIsDisplayed()
        compose.onNodeWithText("接水已结束").assertIsDisplayed()
        compose.onNodeWithTag("dismiss-life-session-${running.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("life-session-${finished.sessionId}").onChildAt(0).performClick()
        assertEquals(listOf(LifeFeature.Laundry to finished.sessionId), opened)
        compose.onNodeWithTag("payment-scan-button").performClick(); assertEquals(1, scans)
        capture("payment-multiple-orders-and-completed")
        compose.onNodeWithTag("dismiss-life-session-${finished.sessionId}").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("洗衣已完成").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.onNodeWithText("接水已结束").assertIsDisplayed(); assertEquals(1, opened.size)
        compose.onNodeWithTag("dismiss-life-session-${water.sessionId}").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("接水已结束").assertDoesNotExist()
        capture("payment-after-dismiss")
    }
    @Test fun inlineLoginReminderSharesUpdateStyleAndDisappearsAfterConnection() {
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1) }
        var loginRequired by mutableStateOf(true)
        var clicks = 0
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    lifeLoginRequired = loginRequired, onLifeLogin = { clicks++ }, scanEnabled = !loginRequired,
                    updateNotice = { InlineUpdateNotice(AppUpdateUiState(noticeVisible = true,
                        release = UpdateRelease(7, "1.3.0", "", "https://example.com/update")), {}, {}) })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-login-notice").assertIsDisplayed()
        compose.onNodeWithTag("payment-scan-button").assertIsNotEnabled()
        val notice = compose.onNodeWithTag("life-login-notice").fetchSemanticsNode().boundsInRoot
        val update = compose.onNodeWithTag("app-update-notice").fetchSemanticsNode().boundsInRoot
        assertEquals(update.height, notice.height, 1f)
        assertTrue(update.bottom < notice.top)
        compose.onNodeWithTag("life-login-notice").performClick(); assertEquals(1, clicks)
        capture("payment-inline-login")
        compose.runOnIdle { loginRequired = false }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-login-notice").assertDoesNotExist()
        compose.onNodeWithTag("payment-scan-button").assertIsEnabled()
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
