package com.mercury.yktwx

import com.mercury.yktwx.data.HttpFixture
import com.mercury.yktwx.data.fixtureLifeDeviceApi

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.preview.PreviewData
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.*
import java.net.URL

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
            fixtureLifeDeviceApi("test-secret", { error("No live requests in UI tests") }), store).also { viewModels.put("life", it) }
    }
    @After fun cleanup() { viewModels.clear() }

    @Test fun showerPageShowsOutletActionsAndProgressWithoutInventingPrice() {
        val model = model(); val theme = settings()
        val device = LifeDevice("shower-device", "test-shower", "淋浴设备", "08", "org", "org", "shop", "imei",
            "学校", "", "after", false, true, null, listOf(LifeSku("outlet", "01", true, "0.00", "1号出水口")),
            listOf(LifePayChannel("15", "支付宝支付", true, false)))
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Water, device = device,
            selectedSkuId = "outlet"))
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme { LifeDevicePage(state, theme, model, active = true, onBack = {}, nowMillis = { 100_000L }) }
        } }
        compose.onAllNodesWithText("扫码淋浴").onFirst().assertExists()
        compose.onNodeWithTag("water-primary-action").assertTextContains("开始淋浴").assertIsEnabled()
        compose.onNodeWithText("出水口").assertIsDisplayed()
        compose.onNodeWithText("0.00", substring = true).assertDoesNotExist()
        compose.onNodeWithText("请先放稳容器。", substring = true).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(water = WaterSession(device, "outlet", WaterPhase.Running, startedAtMillis = 90_000)) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onAllNodesWithText("淋浴进度").onFirst().assertExists()
        compose.onNodeWithText("淋浴中").assertIsDisplayed()
        compose.onNodeWithTag("water-primary-action").assertTextContains("结束淋浴").assertIsEnabled()
        compose.runOnIdle { state = state.copy(water = state.water!!.copy(phase = WaterPhase.Ended, endedAtMillis = 100_000, finalized = true)) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("淋浴已结束").assertIsDisplayed()
        compose.onNodeWithTag("water-primary-action").assertTextContains("再次扫码淋浴")
    }
    private fun settings() = ThemeSettings(compose.activity.application).apply { setTheme(1) }
    @Test fun sharedScannerOpensWithoutAccountsAndShowsLoginOnlyAfterIdentifyingPanggui() {
        val model = model(); val theme = settings()
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            val state by model.state.collectAsState()
            if (state.entryRequest == 0L) PaymentPage(PreviewData.paymentState(false), theme, 0.dp, {},
                lifeLoginRequired = true, hui798LoginRequired = true, onScan = model::openUnifiedScan)
            else LifeDevicePage(state, theme, model, active = true, onBack = {})
            LifeDeviceLoginSheet(state, model, active = true)
        } } } }
        compose.onNodeWithTag("payment-scan-button").assertIsEnabled().performClick()
        compose.onNodeWithTag("life-device-page").assertExists()
        compose.onNodeWithTag("life-login-sheet").assertDoesNotExist()
        compose.runOnIdle { model.scanned("867369079842630") }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("water-device-login-notice").assertIsDisplayed()
        compose.onNodeWithTag("water-primary-action").assertTextContains("登录并继续").assertIsEnabled()
        capture("scan-account-water")
        compose.onNodeWithTag("water-device-login-notice").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-login-sheet").assertIsDisplayed()
    }

    @Test fun laundryWaitsForAccountRecoveryThenShowsInlineLoginWithTheScannedCode() {
        val model = model(); val theme = settings()
        val serial = "00000000-0000-4000-8000-000000000001"
        var state by mutableStateOf(LifeDevicesUiState(feature = LifeFeature.Laundry, scannedSerial = serial))
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            LifeDevicePage(state, theme, model, active = true, onBack = {})
        } } }
        compose.onNodeWithTag("laundry-primary-action").assertIsNotEnabled()
        compose.onNodeWithTag("laundry-device-login-notice").assertDoesNotExist()
        compose.onNodeWithText(serial).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(restoring = false) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("laundry-device-login-notice").assertIsDisplayed()
        compose.onNodeWithTag("laundry-primary-action").assertTextContains("登录并继续").assertIsEnabled()
        capture("scan-account-laundry")
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        val file = File("../tools/life-device-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun inlineReminderOpensNativeLoginDrawerOnlyWhenClicked() {
        val model = model()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    val state by model.state.collectAsState()
                    PaymentPage(PreviewData.paymentState(false), settings(), 0.dp, {},
                        lifeLoginRequired = true, onLifeLogin = model::connectAccount, scanEnabled = false)
                    LifeDeviceLoginSheet(state, model, active = true)
                } }
            }
        }
        compose.waitUntil { !model.state.value.restoring }
        compose.onNodeWithTag("life-login-sheet").assertDoesNotExist()
        compose.onNodeWithTag("life-login-notice").assertIsDisplayed().performClick()
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
        compose.onNodeWithTag("life-login-notice").performClick()
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

    @Test fun devicePageTitlesCollapseLikeSecondaryPagesAcrossFeaturesAndStages() {
        val model = model()
        val settings = settings()
        val waterDevice = LifeDevice("water", "test-water", "饮水设备", "04", "org", "org", "shop", "imei",
            "学校", "", "after", false, true, null, listOf(LifeSku("water-sku", "冷水", true)), emptyList())
        val laundryDevice = waterDevice.copy(id = "laundry", name = "洗衣设备", category = "00", payment = "before",
            skus = listOf(LifeSku("laundry-sku", "快洗", true, "2.50", minutes = 25)))
        val waterState = LifeDevicesUiState(restoring = false, feature = LifeFeature.Water, device = waterDevice)
        val laundryState = waterState.copy(feature = LifeFeature.Laundry, device = laundryDevice)
        val water = WaterSession(waterDevice, "water-sku", WaterPhase.Running)
        val laundry = LaundrySession(laundryDevice, "laundry-sku", "2.50", "13", LaundryPhase.Running)
        val pages = listOf(
            "扫码接水" to waterState,
            "接水进度" to waterState.copy(water = water),
            "接水结束" to waterState.copy(water = water.copy(phase = WaterPhase.Ended)),
            "扫码洗衣" to laundryState,
            "洗衣进度" to laundryState.copy(laundry = laundry),
            "洗衣完成" to laundryState.copy(laundry = laundry.copy(phase = LaundryPhase.Ended)),
            "洗衣订单" to laundryState.copy(laundry = laundry.copy(phase = LaundryPhase.Closed)),
        )
        var page by mutableIntStateOf(0)
        var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (pages[page].second.feature == LifeFeature.Water) ColorSchemeMode.Light else ColorSchemeMode.Dark) {
                    key(page) { LifeDevicePage(pages[page].second, settings, model, active = false, onBack = { backs++ }) }
                }
            }
        }
        for ((index, entry) in pages.withIndex()) {
            compose.runOnIdle { settings.setBlurTop(entry.second.feature == LifeFeature.Laundry); page = index }
            compose.mainClock.advanceTimeBy(600)
            val title = entry.first
            fun titleBounds() = compose.onAllNodesWithText(title, useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val expanded = titleBounds().maxBy { it.height }
            assertTrue("$title starts with a large title on the left", expanded.center.x < root.center.x)
            compose.onNodeWithText("确认接水").assertDoesNotExist()
            compose.onNodeWithText("选择洗衣程序").assertDoesNotExist()
            if (index == 0 || index == 3) {
                val bar = compose.onNodeWithTag("life-device-top-bar").fetchSemanticsNode().boundsInRoot
                val hero = compose.onNodeWithTag("life-device-hero").fetchSemanticsNode().boundsInRoot
                assertTrue("The first card clears the title", hero.top > bar.bottom)
                capture("secondary-$index-expanded")
            }
            compose.onNodeWithTag("life-device-page").performTouchInput { swipeUp() }
            compose.waitForIdle()
            val collapsed = titleBounds().minBy { it.height }
            assertEquals("$title collapses to the center", root.center.x, collapsed.center.x, 2f)
            assertTrue("$title becomes smaller after scrolling", collapsed.height < expanded.height)
            if (index == 0 || index == 3) capture("secondary-$index-collapsed")
            compose.onNodeWithContentDescription("返回").performClick()
        }
        assertEquals(pages.size, backs)
    }

    @Test fun capturedWaterDeviceFitsNativePageAndDoesNotInventPrices() {
        val fixture = File("../tools/life-device-capture.json")
        assumeTrue("Optional local HAR device fixture", fixture.isFile)
        val payloads = JSONObject(fixture.readText())
        val api = fixtureLifeDeviceApi("test-secret", { url -> OfflineResponse(url, payloads.getJSONObject(url.path).toString()) })
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
        compose.onAllNodesWithText("服务提供方").assertCountEquals(1)
        compose.onNodeWithText("胖乖生活").assertIsDisplayed()
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
        compose.runOnIdle { state = state.copy(water = WaterSession(device, device.skus.first().id, phase = WaterPhase.Starting)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("正在启动设备").assertIsDisplayed()
        compose.onAllNodesWithText("服务提供方").assertCountEquals(1)
        compose.onNodeWithTag("water-device-loading").assertIsDisplayed()
        compose.onNodeWithTag("water-primary-action").assertIsNotEnabled().assertTextContains("正在启动…")
        compose.onNodeWithTag("water-elapsed").assertDoesNotExist()
        assertEquals("Starting keeps the shared device identity in place", hero,
            compose.onNodeWithTag("life-device-hero").fetchSemanticsNode().boundsInRoot)
        capture("water-starting-shared-page")
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
        compose.onAllNodesWithText("服务提供方").assertCountEquals(1)
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
        val scanned = mutableListOf<String>()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                Scaffold {
                    LifeDevicePage(LifeDevicesUiState(restoring = false, scanning = true), settings, model, active, onBack = {}, onScanned = scanned::add)
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
        val hui798Qr = "https://i.hnkzy.com/q/1/123456789012345"
        field.performTextReplacement(hui798Qr)
        compose.onNodeWithText("识别设备").assertIsEnabled().performClick()
        assertEquals(listOf(hui798Qr), scanned)
        assertEquals(LifeProvider.Hui798, UnifiedDeviceCode.identify(scanned.single())?.provider)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("输入设备编号").performClick()
        compose.onNodeWithTag("life-device-serial").assertIsDisplayed().assertTextContains(hui798Qr)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-device-serial").assertDoesNotExist()
        compose.onNodeWithContentDescription("帮助").performClick()
        compose.onNodeWithText("知道了").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("知道了").assertDoesNotExist()
        compose.onNodeWithContentDescription("帮助").performClick()
        compose.onNodeWithText("知道了").assertIsDisplayed()
    }

    @Test fun scannerStageUpdatesKeepPreviewAndAnimateToLaundryAndBack() {
        val model = model()
        val settings = settings()
        val device = LifeDevice("laundry", "test-laundry", "洗衣设备", "00", "org", "org", "shop", "imei",
            "学校", "", "before", false, true, null,
            listOf(LifeSku("sku", "快洗", true, "2.50", minutes = 25)), emptyList())
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, scanning = true, feature = LifeFeature.Laundry))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                Scaffold { LifeDevicePage(state, settings, model, active = false, onBack = {}) }
            } }
        }
        val original = compose.onNodeWithTag("device-scanner").fetchSemanticsNode().id
        compose.runOnIdle { state = state.copy(loadingDevice = true, error = "识别失败") }
        assertEquals(original, compose.onNodeWithTag("device-scanner").fetchSemanticsNode().id)
        compose.onNodeWithText("正在识别设备…").assertIsDisplayed()
        compose.onNodeWithText("识别失败").assertIsDisplayed()
        assertTrue("Scanner errors must clear the measured top bar",
            compose.onNodeWithText("识别失败").fetchSemanticsNode().boundsInRoot.top >
                compose.onNodeWithTag("life-device-top-bar").fetchSemanticsNode().boundsInRoot.bottom)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(scanning = false, loadingDevice = false, error = null, device = device) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("device-scanner").assertExists()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("device-scanner").assertDoesNotExist()
        compose.onNodeWithText("洗衣设备").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(scanning = true, device = null) }
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithText("洗衣设备").assertExists()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("device-scanner").assertIsDisplayed()
        compose.onNodeWithText("洗衣设备").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    private class OfflineResponse(url: URL, val body: String) : HttpFixture(url) {

        override fun close() {}

        override fun statusCode() = 200
        override fun bodyStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream(): OutputStream = ByteArrayOutputStream()

    }
}
