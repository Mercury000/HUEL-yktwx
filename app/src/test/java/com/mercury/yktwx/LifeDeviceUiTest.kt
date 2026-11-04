package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.pages.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.*
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeDeviceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val viewModels = ViewModelStore()
    private fun model(): LifeDevicesViewModel {
        val store = object : LifeDeviceStore {
            override fun account(): LifeAccount? = null
            override fun session(): WaterSession? = null
            override fun saveAccount(account: LifeAccount) { error("UI test does not sign in") }
            override fun saveSession(session: WaterSession) { error("UI test does not control devices") }
            override fun clearAccount() {}
            override fun clearSession() {}
        }
        return LifeDevicesViewModel(compose.activity.application,
            LifeDeviceApi("test-secret", { error("No live requests in UI tests") }), store).also { viewModels.put("life", it) }
    }
    @After fun cleanup() { viewModels.clear() }
    private fun settings() = ThemeSettings(compose.activity.application).apply { setTheme(1) }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        val file = File("../tools/life-device-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun bothEntriesOpenSameNativeLoginDrawerWithNoInventedFields() {
        val model = model()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    val state by model.state.collectAsState()
                    LifeDeviceEntries(model::openScan)
                    LifeDeviceLoginSheet(state, model, active = true)
                } }
            }
        }
        compose.waitUntil { !model.state.value.restoring }
        compose.onNodeWithTag("hall-life-entry-water").performClick()
        compose.onNodeWithTag("life-login-sheet").assertIsDisplayed()
        compose.onNodeWithTag("life-login-phone").assertTextContains("手机号")
        compose.onNodeWithTag("life-login-code").assertIsDisplayed()
        compose.onNodeWithTag("life-login-submit").assertIsNotEnabled()
        compose.onNodeWithText("密码").assertDoesNotExist()
        capture("01-shared-login-drawer")
        val phone = compose.onNodeWithTag("life-login-phone")
        phone.performTextInput("138")
        phone.performTextInput("1234")
        phone.assertTextContains("1381234")
        assertEquals(TextRange(7), phone.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        val code = compose.onNodeWithTag("life-login-code")
        code.performTextInput("12")
        code.performTextInput("34")
        code.assertTextContains("1234")
        assertEquals(TextRange(4), code.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { model.dismissLogin() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hall-life-entry-laundry").performClick()
        compose.onNodeWithTag("life-login-sheet").assertIsDisplayed()
        assertEquals(0, model.state.value.entryRequest)
    }

    @Test fun laundryWithoutDeviceUsesSharedScannerAndInventsNoPrograms() {
        val model = model()
        val settings = settings()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                LifeDevicePage(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, scanning = true), settings, model, active = false, onBack = {})
            } }
        }
        compose.onNodeWithTag("device-scanner").assertIsDisplayed()
        compose.onNodeWithText("洗衣暂未开放").assertDoesNotExist()
        compose.onNodeWithText("开始洗衣").assertDoesNotExist()
        compose.onNodeWithText("账单").assertDoesNotExist()
        compose.onNodeWithTag("water-primary-action").assertDoesNotExist()
        capture("02-laundry-shared-scanner")
    }

    @Test fun capturedWaterDeviceFitsNativePageAndDoesNotInventPrices() {
        val fixture = File("../tools/life-device-capture.json")
        assumeTrue("Optional local HAR device fixture", fixture.isFile)
        val payloads = JSONObject(fixture.readText())
        val api = LifeDeviceApi("test-secret", { url -> OfflineResponse(url, payloads.getJSONObject(url.path).toString()) })
        val device = runBlocking { api.device("867369079842630", "test-token") }
        val times = payloads.getJSONObject("__waterSession")
        val captureClock = { times.getLong("runningAt") }
        val model = model()
        val settings = settings()
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Water, device = device,
            selectedSkuId = device.skus.first().id))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                LifeDevicePage(state, settings, model, active = true, onBack = {}, nowMillis = captureClock)
            } }
        }
        compose.onNodeWithText(device.name).assertIsDisplayed()
        compose.onNodeWithText("开始接水").assertIsDisplayed()
        compose.onNodeWithText("账单").assertDoesNotExist()
        compose.onAllNodes(hasText("¥", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("待确认", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("接一杯热水").assertDoesNotExist()
        compose.onNodeWithText("设备空闲").assertDoesNotExist()
        compose.onNodeWithText("计价规则").assertDoesNotExist()
        compose.onNodeWithText("重新扫码").assertDoesNotExist()
        val hero = compose.onNodeWithTag("life-device-hero").fetchSemanticsNode().boundsInRoot
        val glyph = compose.onNodeWithTag("life-device-glyph").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("life-device-name").fetchSemanticsNode().boundsInRoot
        assertEquals(hero.center.x, glyph.center.x, 1f)
        assertEquals(hero.center.x, name.center.x, 1f)
        val footer = compose.onNodeWithTag("water-action-footer").fetchSemanticsNode().boundsInRoot
        val notice = compose.onNodeWithTag("water-usage-notice").fetchSemanticsNode().boundsInRoot
        assertTrue("Content must fit above fixed action", notice.bottom < footer.top)
        capture("03-water-device-from-capture")
        compose.runOnIdle { state = state.copy(water = WaterSession(device, device.skus.first().id,
            phase = WaterPhase.Running, startedAtMillis = times.getLong("startedAt"))) }
        compose.onNodeWithText("结束接水").assertIsDisplayed()
        compose.onNodeWithTag("water-elapsed").assertIsDisplayed()
        compose.onNodeWithText("本地参考计时").assertIsDisplayed()
        val timer = compose.onNodeWithTag("water-elapsed").fetchSemanticsNode().boundsInRoot
        assertEquals(hero.center.x, timer.center.x, 1f)
        compose.onAllNodes(hasText("¥", substring = true)).assertCountEquals(0)
        capture("05-water-running-from-capture")
        compose.runOnIdle { state = state.copy(water = WaterSession(device, device.skus.first().id,
            phase = WaterPhase.Ended, finalized = true, startedAtMillis = times.getLong("startedAt"), endedAtMillis = times.getLong("endedAt"))) }
        compose.onNodeWithText("接水已结束").assertIsDisplayed()
        compose.onNodeWithTag("water-ended-icon").assertIsDisplayed()
        compose.onNodeWithTag("water-fee-notice").assertIsDisplayed()
        compose.onNodeWithText("本地参考时长").assertIsDisplayed()
        compose.onAllNodes(hasText("¥", substring = true)).assertCountEquals(0)
        capture("04-water-ended-no-amount")
    }

    @Test fun scannerFillsPageAndManualEntryUsesDrawer() {
        val model = model()
        val settings = settings()
        var active by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                Scaffold {
                    LifeDevicePage(LifeDevicesUiState(restoring = false, scanning = true), settings, model, active, onBack = {})
                }
            } }
        }
        val scanner = compose.onNodeWithTag("device-scanner").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("life-device-page").fetchSemanticsNode().boundsInRoot
        val topBar = compose.onNodeWithTag("life-device-top-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(page.top, scanner.top, 1f)
        assertTrue("Camera extends behind the miuix top bar", scanner.top <= topBar.top && scanner.bottom > topBar.bottom)
        assertEquals(page.width, scanner.width, 1f)
        assertEquals(page.height, scanner.height, 1f)
        assertTrue("Scanner must use full page, not a square card", scanner.height > scanner.width * 1.5f)
        compose.onNodeWithTag("water-primary-action").assertDoesNotExist()
        compose.onNodeWithTag("life-device-serial").assertDoesNotExist()
        capture("06-full-page-scanner")
        compose.runOnIdle { active = true }
        compose.onNodeWithText("输入设备编号").performClick()
        compose.onNodeWithTag("life-device-serial").assertIsDisplayed()
        compose.onNodeWithText("识别设备").assertIsNotEnabled()
        capture("07-manual-device-drawer")
        val field = compose.onNodeWithTag("life-device-serial")
        field.performTextInput("867")
        field.performTextInput("369")
        field.assertTextContains("867369")
        assertEquals(TextRange(6), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        field.performTextInputSelection(TextRange(2))
        field.performTextInput("0")
        field.assertTextContains("8607369")
        assertEquals(TextRange(3), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
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
