package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import kotlinx.serialization.json.*
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.HttpFixture
import com.mercury.yktwx.data.fixtureLifeDeviceApi
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.pages.*
import kotlinx.coroutines.runBlocking
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
class LaundryUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val models = ViewModelStore()
    @After fun cleanup() { models.clear() }
    private fun model(): LifeDevicesViewModel {
        val store = object : LifeDeviceStore {
            override fun account(): LifeAccount? = null
            override fun session(): WaterSession? = null
            override fun saveAccount(account: LifeAccount) { error("No sign in from UI tests") }
            override fun saveSession(session: WaterSession) { error("No device control") }
            override fun clearAccount() {}
            override fun clearSession() {}
        }
        return lifeDevicesViewModelFixture(compose.activity.application, fixtureLifeDeviceApi("test-secret", { error("No live requests") }), store)
            .also { models.put("life", it) }
    }
    private data class Fixture(val device: LifeDevice, val quote: LaundryQuote, val running: LaundryReading, val ended: LaundryReading)
    private fun fixture(): Fixture {
        val file = File("../tools/laundry-ui-capture.json")
        assumeTrue("Optional local capture fixture", file.isFile)
        val payload = parseObject(file.readText())
        fun service(orderKey: String) = fixtureLifeDeviceApi("test-secret", { url ->
            OfflineResponse(url, payload.objectValue(if (url.path == "/order/detail") orderKey else url.path).toString())
        })
        return runBlocking {
            val api = service("/order/running")
            val device = api.laundryDevice(payload.stringValue("__code"), "test-token")
            val sku = device.skus.first { it.name == "桶自洁" }
            val quote = api.laundryQuote(device, sku.id, "test-token")
            val number = payload.objectValue("/order/running").objectValue("data").stringValue("orderNo")
            Fixture(device, quote, api.laundryOrder(number, "test-token"), service("/order/finished").laundryOrder(number, "test-token"))
        }
    }
    private fun Fixture.session(ended: Boolean = false): LaundrySession {
        val reading = if (ended) this.ended else running
        return LaundrySession(device, quote.skuId, quote.price, "2", if (ended) LaundryPhase.Ended else LaundryPhase.Running,
            true, reading.orderNo, reading.orderId, true, true, reading.minutes, reading.paidAtMillis,
            reading.expectedEndMillis, reading.finishedAtMillis, payTypeName = reading.payTypeName)
    }
    private fun capture(name: String, settle: Boolean = true) {
        if (settle) compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }; compose.waitForIdle()
        val file = File("../tools/life-device-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun blockedDeviceRetryShowsProgressAndRedisplaysTheSameErrorAfterCompletion() {
        val model = model()
        val settings = themeSettingsFixture(compose.activity.application)
        val message = "设备工作中，请更换设备使用"
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, message, listOf(LifeSku("quick", "快洗", true, "2.50", minutes = 25)), emptyList())
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = device, selectedSkuId = "quick", error = message))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LifeDevicePage(state, settings, model, active = true, onBack = {})
            } } }
        }
        compose.onNodeWithTag("laundry-primary-action").assertIsEnabled().assertTextContains("重试")
        val before = compose.onNodeWithTag("laundry-primary-action").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(loadingDevice = true, error = null) }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("laundry-primary-action").assertIsNotEnabled().assertTextContains("正在查询设备…")
        compose.onNodeWithTag("laundry-action-progress", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("laundry-program-quick").assertIsNotEnabled()
        assertEquals(before, compose.onNodeWithTag("laundry-primary-action").fetchSemanticsNode().boundsInRoot)
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText(message).assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loadingDevice = false, error = message) }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("laundry-primary-action").assertIsEnabled().assertTextContains("重试")
        compose.onNodeWithTag("laundry-action-progress", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(message).assertIsDisplayed()
    }
    @Test fun unknownCreationShowsNoIneffectiveRetryOrEndlessBusyIndicator() {
        val model = model(); val settings = themeSettingsFixture(compose.activity.application)
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("quick", "快洗", true, "2.50", minutes = 25)), emptyList())
        val session = LaundrySession(device, "quick", "2.50", "13", LaundryPhase.Creating, createSent = true, creationUncertain = true)
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, device = device,
            laundry = session, error = LaundryCreationUnknownMessage)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            LifeDevicePage(state, settings, model, active = true, onBack = {})
        } } } }
        compose.onNodeWithTag("laundry-primary-action").assertIsEnabled().assertTextContains("重新扫码")
        compose.onNodeWithTag("laundry-action-progress", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("返回").assertIsEnabled()
    }
    @Test fun restoredStartingOrderOffersReadActionWithoutRequiringAnotherError() {
        val model = model(); val settings = themeSettingsFixture(compose.activity.application)
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("quick", "快洗", true, "2.50", minutes = 25)), emptyList())
        val session = LaundrySession(device, "quick", "2.50", "13", LaundryPhase.Starting,
            orderNo = "wash-1", orderId = "wash-id", paid = true)
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, device = device, laundry = session)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            LifeDevicePage(state, settings, model, active = true, onBack = {})
        } } } }
        compose.onNodeWithTag("laundry-primary-action").assertIsEnabled().assertTextContains("查询启动结果")
        compose.onNodeWithTag("laundry-action-progress", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun programSelectionKeepsTheActionAvailableBeforePaymentInformationIsLoaded() {
        val model = model(); val settings = themeSettingsFixture(compose.activity.application)
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("quick", "快洗", true, "2.50", minutes = 25)), emptyList())
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = device, selectedSkuId = "quick"))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LifeDevicePage(state, settings, model, active = true, onBack = {})
            } } }
        }
        compose.onNodeWithTag("laundry-primary-action").assertIsEnabled().assertTextContains("选择支付方式")
        compose.onNodeWithTag("laundry-program-quick").assertTextContains("¥2.50").assertTextContains("约 25 分钟", substring = true)
        compose.runOnIdle { state = state.copy(paymentVisible = true, quoteLoading = true) }
        compose.onNodeWithTag("laundry-primary-action").assertTextContains("选择支付方式")
        compose.onNodeWithText("正在读取价格…").assertDoesNotExist()
        compose.onNodeWithText("正在准备支付…").assertDoesNotExist()
    }
    @Test fun paymentDrawerShowsLoadingAndOffersRetryWithoutReportingMissingChannelsDuringLoading() {
        val model = model()
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            paymentVisible = true, quoteLoading = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(true, state, model)
            } } }
        }
        compose.onNodeWithTag("laundry-payment-loading").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsNotEnabled()
        compose.onNodeWithText("暂无支持的支付方式").assertDoesNotExist()
        compose.onNodeWithTag("laundry-payment-retry").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(quoteLoading = false, error = "付款信息读取失败") }
        compose.onNodeWithTag("laundry-payment-loading").assertDoesNotExist()
        compose.onNodeWithText("付款信息读取失败").assertIsDisplayed()
        compose.onNodeWithTag("laundry-payment-retry").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { state = state.copy(error = null,
            laundryQuote = LaundryQuote("device", "quick", "2.50", 25, listOf(LifePayChannel("2", "余额支付", true, false)))) }
        compose.onNodeWithText("¥2.50").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsEnabled()
        compose.onNodeWithTag("laundry-payment-retry").assertDoesNotExist()
    }
    @Test fun allProgramDurationsAppearBeforeSelectionAndQuoteLoadingKeepsRowsStill() {
        val fixture = fixture(); val model = model()
        val settings = themeSettingsFixture(compose.activity.application).apply { setTheme(1) }
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = fixture.device, selectedSkuId = fixture.device.skus.first().id))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                Scaffold { LifeDevicePage(state, settings, model, active = true, onBack = {}) }
            } }
        }
        fixture.device.skus.forEach { sku ->
            assertNotNull("Program minutes come from the scanned device response", sku.minutes)
            compose.onNodeWithTag("laundry-program-${sku.id}")
                .assertTextContains(sku.feature, substring = true)
                .assertTextContains("约 ${sku.minutes} 分钟", substring = true)
        }
        val before = fixture.device.skus.associate { it.id to compose.onNodeWithTag("laundry-program-${it.id}").fetchSemanticsNode().boundsInRoot }
        capture("laundry-selection-before")
        compose.runOnIdle { state = state.copy(selectedSkuId = fixture.quote.skuId, quoteLoading = true) }
        compose.mainClock.advanceTimeBy(32)
        capture("laundry-selection-frame-02", settle = false)
        compose.mainClock.advanceTimeBy(64)
        capture("laundry-selection-frame-06", settle = false)
        fixture.device.skus.forEach { sku ->
            assertEquals("Quote loading must not expand program rows", before[sku.id],
                compose.onNodeWithTag("laundry-program-${sku.id}").fetchSemanticsNode().boundsInRoot)
        }
        compose.runOnIdle { state = state.copy(laundryQuote = fixture.quote, quoteLoading = false) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("laundry-program-${fixture.quote.skuId}").assertIsSelected()
        compose.onNodeWithTag("laundry-program-${fixture.quote.skuId}")
            .assertTextContains("约 ${fixture.quote.minutes} 分钟", substring = true)
        fixture.device.skus.forEach { sku ->
            assertEquals("Selection must not expand program rows", before[sku.id],
                compose.onNodeWithTag("laundry-program-${sku.id}").fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithText("本次应付").assertDoesNotExist()
        compose.onNodeWithTag("laundry-quote-price").assertDoesNotExist()
        capture("laundry-selection-after")
    }
    private fun renderCapturedFlow(dark: Boolean, largeFont: Boolean) {
        val fixture = fixture(); val model = model()
        val settings = themeSettingsFixture(compose.activity.application).apply { setTheme(if (dark) 2 else 1) }
        val label = (if (dark) "dark" else "light") + if (largeFont) "-large" else ""
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, device = fixture.device,
            selectedSkuId = fixture.quote.skuId, laundryQuote = fixture.quote))
        val clock = { fixture.running.paidAtMillis!! + 46_000 }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    Scaffold { LifeDevicePage(state, settings, model, active = true, onBack = {}, nowMillis = clock) }
                }
            }
        }
        compose.onNodeWithText(fixture.device.name).assertIsDisplayed()
        compose.onNodeWithTag("laundry-primary-action").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("选择洗衣程序").assertDoesNotExist()
        compose.onNodeWithText("待确认").assertDoesNotExist()
        compose.onNodeWithText("账单").assertDoesNotExist()
        val hero = compose.onNodeWithTag("life-device-hero").fetchSemanticsNode().boundsInRoot
        val glyph = compose.onNodeWithTag("life-device-glyph").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithTag("life-device-name").fetchSemanticsNode().boundsInRoot
        assertTrue("Laundry identity follows compact design row", glyph.right < name.left)
        assertEquals(glyph.center.y, hero.center.y, 1f)
        assertEquals(fixture.running.minutes, fixture.quote.minutes)
        capture("laundry-$label-01-programs")
        compose.runOnIdle { state = state.copy(laundry = fixture.session()) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("laundry-remaining").assertIsDisplayed()
        compose.onNodeWithText("结束洗衣").assertDoesNotExist()
        val timer = compose.onNodeWithTag("laundry-remaining").fetchSemanticsNode().boundsInRoot
        assertEquals(hero.center.x, timer.center.x, 1f)
        capture("laundry-$label-02-running")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(laundry = fixture.session(true)) }
        repeat(18) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (!dark && !largeFont && frame % 3 == 0) capture("laundry-motion-${frame.toString().padStart(2, '0')}", settle = false)
            if (frame == 7) capture("laundry-$label-03-completion-motion", settle = false)
        }
        compose.mainClock.advanceTimeBy(600); compose.mainClock.autoAdvance = true
        compose.onNodeWithText("洗衣已完成").assertIsDisplayed()
        compose.onNodeWithText("11-04 12:48").assertIsDisplayed()
        compose.onNodeWithText("再次扫码洗衣").assertIsDisplayed()
        compose.onNodeWithText("结束洗衣").assertDoesNotExist()
        val footer = compose.onNodeWithTag("laundry-action-footer").fetchSemanticsNode().boundsInRoot
        val info = compose.onNodeWithTag("laundry-info").fetchSemanticsNode().boundsInRoot
        if (!largeFont) assertTrue("Content fits above fixed footer", info.bottom < footer.top)
        compose.onNodeWithTag("laundry-usage-notice").assertDoesNotExist()
        capture("laundry-$label-04-completed")
    }
    @Test fun capturedLaundryLightFlowAndCompletionAnimationFitDesign() = renderCapturedFlow(false, false)
    @Test fun capturedLaundryDarkFlowAndCompletionAnimationFitDesign() = renderCapturedFlow(true, false)
    @Test @Config(fontScale = 1.3f) fun capturedLaundryLargeFontKeepsActionsReachable() = renderCapturedFlow(false, true)
    @Test fun laundryShowsServerDescriptionsAndPaymentNamesWithGenericInstructions() {
        val model = model(); val settings = themeSettingsFixture(compose.activity.application)
        val device = LifeDevice("77", "nqt", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("clean", "桶自洁", true, "0.00", "设备桶自洁")), emptyList())
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = device, selectedSkuId = "clean"))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LifeDevicePage(state, settings, model, active = true, onBack = {})
            } } }
        }
        compose.onNodeWithTag("laundry-program-clean").assertTextContains("设备桶自洁", substring = true)
        compose.onNodeWithText("放好衣物并关好门后再支付。").assertIsDisplayed()
        compose.onNodeWithText("桶自洁请按设备说明使用，关好门后启动。").assertDoesNotExist()
        val ended = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Ended, paid = true, payTypeName = "余额")
        compose.runOnIdle { state = state.copy(laundry = ended.copy(phase = LaundryPhase.Running)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("预计时间仅供参考，以设备实际完成为准。").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(laundry = ended) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("余额").assertIsDisplayed()
        compose.onNodeWithText("余额支付").assertDoesNotExist()
        compose.onNodeWithTag("laundry-usage-notice").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(laundry = ended.copy(phase = LaundryPhase.Paying, paid = false,
            payMethod = "13", payTypeName = ""), paymentVisible = true,
            laundryQuote = LaundryQuote(device.id, "clean", "0.00", null, listOf(LifePayChannel("13", "支付宝", true, false))),
            paymentFeedback = LaundryPaymentFeedback(ended.sessionId, RechargePhase.Failed, detail = "支付未完成")) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("支付宝").assertIsDisplayed()
        compose.onNodeWithText("支付宝支付").assertDoesNotExist()
    }
    @Test fun paymentDrawerShowsOnlySupportedReturnedChannelsAndKeepsServerRestrictions() {
        val fixture = fixture(); val model = model()
        val quote = fixture.quote.copy(channels = listOf(
            LifePayChannel("32", "微信支付", true, false),
            LifePayChannel("2", "余额支付", false, false, "余额不足"),
            LifePayChannel("52", "银联支付", true, false),
            LifePayChannel("53", "云闪付无感支付", true, false),
            LifePayChannel("13", "支付宝支付", true, false),
            LifePayChannel("91", "农行掌银", true, false),
            LifePayChannel("999", "其他支付", true, false)
        ))
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, device = fixture.device,
            selectedSkuId = quote.skuId, laundryQuote = quote)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(true, state, model)
            } } }
        }
        compose.onNodeWithTag("laundry-payment-sheet").assertIsDisplayed()
        compose.onNodeWithText("余额支付").assertIsDisplayed()
        compose.onNodeWithText("¥0.00").assertIsDisplayed()
        compose.onNodeWithText("微信支付").assertDoesNotExist()
        compose.onNodeWithText("云闪付无感支付").assertDoesNotExist()
        compose.onAllNodesWithText("银联支付").assertCountEquals(2)
        compose.onNodeWithText("农行掌银").assertDoesNotExist()
        compose.onNodeWithText("其他支付").assertDoesNotExist()
        compose.onNodeWithTag("laundry-pay-submit").assertIsDisplayed().assertIsEnabled().assertTextContains("银联支付")
        compose.onNodeWithText("支付宝支付").performClick()
        compose.onNodeWithTag("laundry-pay-submit").assertTextContains("支付宝支付")
        compose.onNodeWithText("支付后自动读取设备启动结果，请先按设备说明做好准备。").assertDoesNotExist()
        compose.onNodeWithText("77 · 桶自洁").assertDoesNotExist()
        capture("laundry-light-05-payment-drawer")
        compose.onNodeWithText("余额支付").performClick()
        compose.onNodeWithText("余额不足").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsNotEnabled()
    }
    @Test fun paymentDrawerDisablesPaymentWhenNoSupportedChannelIsReturned() {
        val model = model()
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            laundryQuote = LaundryQuote("device", "sku", "5.00", 30,
                listOf(LifePayChannel("32", "微信支付", true, false))))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(true, state, model)
            } } }
        }
        compose.onNodeWithText("微信支付").assertDoesNotExist()
        compose.onNodeWithText("余额支付").assertDoesNotExist()
        compose.onNodeWithText("支付宝支付").assertDoesNotExist()
        compose.onNodeWithText("暂无支持的支付方式").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsNotEnabled()
    }
    @Test fun unionPayFeedbackUsesItsOwnAppNameAndKeepsUnavailableChannelsDisabled() {
        val fixture = fixture(); val model = model()
        val session = LaundrySession(fixture.device, fixture.quote.skuId, "3.50", "52",
            LaundryPhase.Paying, orderNo = "test-wash-52", orderId = "test-id-52")
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            laundry = session, laundryQuote = fixture.quote.copy(price = "3.50", channels = listOf(
                LifePayChannel("52", "银联支付", false, false, "银联暂不可用"))), paymentVisible = true,
            paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.AwaitingPayment)))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(true, state, model)
            } } }
        }
        compose.onNodeWithText("请在银联中完成支付").assertIsDisplayed()
        compose.onNodeWithText("请在支付宝中完成支付").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(paymentFeedback = null) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("银联暂不可用").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsNotEnabled().assertTextContains("银联支付")
    }
    @Test fun paymentDrawerReusesCampusFeedbackFromPreparationThroughAlipayAndResult() {
        val model = model()
        val quote = LaundryQuote("test-device", "test-program", "3.50", 30, listOf(
            LifePayChannel("2", "余额支付", false, false, "余额不足"),
            LifePayChannel("13", "支付宝支付", true, false),
            LifePayChannel("32", "微信支付", true, false),
            LifePayChannel("52", "银联支付", true, false)))
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            laundryQuote = quote, paymentVisible = true,
            paymentFeedback = LaundryPaymentFeedback("attempt", RechargePhase.Paid)))
        compose.setContent {
            val modelState by model.state.collectAsState()
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(state.paymentVisible, state.copy(paymentMethod = modelState.paymentMethod), model)
            } } }
        }
        capture("laundry-payment-paid-initial")
        compose.runOnIdle { state = state.copy(laundryBusy = true,
            paymentFeedback = LaundryPaymentFeedback("attempt", RechargePhase.Creating)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("¥3.50").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsNotEnabled().assertTextContains("正在发起…")
        compose.runOnIdle { state = state.copy(laundryBusy = false,
            paymentFeedback = LaundryPaymentFeedback("attempt", RechargePhase.AwaitingPayment)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("请在支付宝中完成支付").assertIsDisplayed()
        compose.onNodeWithText("请在微信中完成支付").assertDoesNotExist()
        compose.onNodeWithText("¥ 3.50").assertIsDisplayed()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        capture("laundry-payment-awaiting")
        compose.runOnIdle { state = state.copy(
            paymentFeedback = LaundryPaymentFeedback("attempt", RechargePhase.Checking, loading = true)) }
        compose.onNodeWithText("正在查询结果").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(paymentFeedback = LaundryPaymentFeedback(
            "attempt", RechargePhase.Checking, detail = "连接失败，请检查网络后重试")) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("查询结果").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("支付失败").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(paymentFeedback = LaundryPaymentFeedback(
            "attempt", RechargePhase.Failed, detail = "支付已取消，可继续支付同一订单")) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("支付失败").assertIsDisplayed()
        compose.onAllNodesWithText("支付已取消，可继续支付同一订单").assertCountEquals(1)
        compose.onNodeWithText("¥ 3.50").assertDoesNotExist()
        compose.onNodeWithText("余额支付").assertDoesNotExist()
        compose.onNodeWithText("微信支付").assertDoesNotExist()
        compose.onNodeWithText("银联支付").assertIsDisplayed()
        compose.onNodeWithText("继续支付").assertIsNotEnabled()
        compose.onNodeWithText("支付宝支付").performClick()
        compose.onNodeWithText("继续支付").assertIsEnabled()
        val actions = compose.onNodeWithText("继续支付").fetchSemanticsNode().boundsInRoot
        assertTrue("Retry remains inside the drawer viewport", actions.bottom <= compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom)
        capture("laundry-payment-failed")
        compose.runOnIdle { state = state.copy(paymentFeedback = LaundryPaymentFeedback("attempt", RechargePhase.Paid)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("支付成功").assertIsDisplayed()
        compose.onNodeWithText("¥ 3.50").assertIsDisplayed()
        compose.onNodeWithText("完成").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNode(hasText("完成") and hasClickAction()).performTouchInput { click() }
        capture("laundry-payment-paid")
    }
    @Test fun fiveMinuteCountdownKeepsPaymentEnabledUntilServerClosure() {
        val model = model()
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("quick", "快洗", true, "2.50")), emptyList())
        val quote = LaundryQuote(device.id, "quick", "2.50", 25, listOf(LifePayChannel("13", "支付宝", true, false)))
        val paying = LaundrySession(device, "quick", "2.50", "13", LaundryPhase.Paying,
            orderNo = "test-order", orderId = "test-id", createdAtMillis = 100_000L)
        var clock = 100_000L
        val now = { clock }
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, device = device,
            laundry = paying, laundryQuote = quote, paymentVisible = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(true, state, model, now)
            } } }
        }
        compose.onNodeWithText("剩余支付时间 05:00").assertIsDisplayed()
        compose.onNodeWithTag("laundry-pay-submit").assertIsEnabled()
        compose.runOnIdle { clock = 160_000L }; compose.mainClock.advanceTimeBy(1100)
        compose.onNodeWithText("剩余支付时间 04:00").assertIsDisplayed()
        compose.runOnIdle { clock = 400_000L }; compose.mainClock.advanceTimeBy(1600)
        compose.onNodeWithText("建议付款时间已到").assertIsDisplayed()
        compose.onNodeWithText("支付超时").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithTag("laundry-pay-submit").assertIsEnabled()
        compose.runOnIdle { state = state.copy(laundry = paying.copy(phase = LaundryPhase.Closed),
            paymentFeedback = LaundryPaymentFeedback(paying.sessionId, RechargePhase.Failed, detail = "订单已关闭")) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("支付失败").assertIsDisplayed()
        compose.onNodeWithText("订单已关闭").assertIsDisplayed()
        compose.onNodeWithText("查询结果").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("完成").assertIsEnabled()
        capture("laundry-payment-timeout")
    }
    @Test fun pendingOrderCancellationRequiresConfirmationAndDisappearsWhenOrderIsNoLongerPayable() {
        val model = model()
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("quick", "快洗", true, "2.50")), emptyList())
        val pending = LaundrySession(device, "quick", "2.50", "13", LaundryPhase.Paying,
            orderNo = "test-order", orderId = "test-order-id")
        val settings = themeSettingsFixture(compose.activity.application)
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = device, laundry = pending))
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            LifeDevicePage(state, settings, model, active = true, onBack = {})
        } } } }
        compose.onNodeWithTag("laundry-cancel-order").assertIsDisplayed().assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("取消洗衣订单").assertIsDisplayed()
        compose.onNodeWithTag("laundry-confirm-cancel").assertIsDisplayed().assertIsEnabled()
        capture("laundry-cancel-confirmation")
        compose.onNodeWithText("暂不取消").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("laundry-confirm-cancel").assertDoesNotExist()
        compose.onNodeWithTag("laundry-cancel-order").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { state = state.copy(laundry = pending.copy(phase = LaundryPhase.Running, paid = true)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("laundry-confirm-cancel").assertDoesNotExist()
        compose.onNodeWithTag("laundry-cancel-order").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(laundry = pending, laundryBusy = true) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("laundry-cancel-order").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(laundryBusy = false,
            paymentLaunch = LifePaymentLaunch(1, pending.orderNo, "test-payload")) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("laundry-cancel-order").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(paymentLaunch = null, laundry = pending.copy(phase = LaundryPhase.Closed)) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("laundry-cancel-order").assertDoesNotExist()
    }
    @Test fun closedOrderPageShowsClosureAndAllowsScanningAgain() {
        val model = model()
        val device = LifeDevice("77", "test-serial", "77", "00", "22", "22", "shop", "imei", "学校", "",
            "before", false, false, null, listOf(LifeSku("quick", "快洗", true, "2.50")), emptyList())
        val closed = LaundrySession(device, "quick", "2.50", "13", LaundryPhase.Closed, orderNo = "test-order")
        val settings = themeSettingsFixture(compose.activity.application)
        val state = LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry, device = device, laundry = closed)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            LifeDevicePage(state, settings, model, active = true, onBack = {})
        } } } }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("订单已关闭").assertIsDisplayed()
        compose.onNodeWithText("再次扫码洗衣").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithText("洗衣已完成").assertDoesNotExist()
    }
    private class OfflineResponse(url: URL, private val body: String) : HttpFixture(url) {

        override fun close() {}

        override fun statusCode() = 200
        override fun bodyStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream(): OutputStream = ByteArrayOutputStream()

    }
}
