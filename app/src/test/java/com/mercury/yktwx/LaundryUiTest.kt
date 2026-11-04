package com.mercury.yktwx

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
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LaundryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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
        return LifeDevicesViewModel(compose.activity.application, LifeDeviceApi("test-secret", { error("No live requests") }), store)
            .also { models.put("life", it) }
    }
    private data class Fixture(val device: LifeDevice, val quote: LaundryQuote, val running: LaundryReading, val ended: LaundryReading)
    private fun fixture(): Fixture {
        val file = File("../tools/laundry-ui-capture.json")
        assumeTrue("Optional local capture fixture", file.isFile)
        val payload = JSONObject(file.readText())
        fun service(orderKey: String) = LifeDeviceApi("test-secret", { url ->
            OfflineResponse(url, payload.getJSONObject(if (url.path == "/order/detail") orderKey else url.path).toString())
        })
        return runBlocking {
            val api = service("/order/running")
            val device = api.laundryDevice(payload.getString("__code"), "test-token")
            val sku = device.skus.first { it.name == "桶自洁" }
            val quote = api.laundryQuote(device, sku.id, "test-token")
            val number = payload.getJSONObject("/order/running").getJSONObject("data").getString("orderNo")
            Fixture(device, quote, api.laundryOrder(number, "test-token"), service("/order/finished").laundryOrder(number, "test-token"))
        }
    }
    private fun Fixture.session(ended: Boolean = false): LaundrySession {
        val reading = if (ended) this.ended else running
        return LaundrySession(device, quote.skuId, quote.price, "2", if (ended) LaundryPhase.Ended else LaundryPhase.Running,
            true, reading.orderNo, reading.orderId, true, true, reading.minutes, reading.paidAtMillis,
            reading.expectedEndMillis, reading.finishedAtMillis)
    }
    private fun capture(name: String, settle: Boolean = true) {
        if (settle) compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }; compose.waitForIdle()
        val file = File("../tools/life-device-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun allProgramDurationsAppearBeforeSelectionAndQuoteLoadingKeepsRowsStill() {
        val fixture = fixture(); val model = model()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1) }
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, feature = LifeFeature.Laundry,
            device = fixture.device, selectedSkuId = fixture.device.skus.first().id))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                Scaffold { LifeDevicePage(state, settings, model, active = true, onBack = {}) }
            } }
        }
        fixture.device.skus.forEach { sku ->
            assertNotNull("Captured machine has program durations", sku.minutes)
            compose.onNodeWithTag("laundry-program-minutes-${sku.id}", useUnmergedTree = true)
                .assertTextContains("约 ${sku.minutes} 分钟", substring = true)
        }
        val before = fixture.device.skus.associate { it.id to compose.onNodeWithTag("laundry-program-${it.id}").fetchSemanticsNode().boundsInRoot }
        capture("laundry-selection-before")
        compose.runOnIdle { state = state.copy(selectedSkuId = fixture.quote.skuId, quoteLoading = true) }
        compose.mainClock.advanceTimeBy(32)
        capture("laundry-selection-frame-02", settle = false)
        compose.mainClock.advanceTimeBy(64)
        capture("laundry-selection-frame-06", settle = false)
        compose.runOnIdle { state = state.copy(laundryQuote = fixture.quote, quoteLoading = false) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("laundry-program-${fixture.quote.skuId}").assertIsSelected()
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
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(if (dark) 2 else 1) }
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
        val notice = compose.onNodeWithTag("laundry-usage-notice").fetchSemanticsNode().boundsInRoot
        if (!largeFont) assertTrue("Content fits above fixed footer", notice.bottom < footer.top)
        capture("laundry-$label-04-completed")
    }
    @Test fun capturedLaundryLightFlowAndCompletionAnimationFitDesign() = renderCapturedFlow(false, false)
    @Test fun capturedLaundryDarkFlowAndCompletionAnimationFitDesign() = renderCapturedFlow(true, false)
    @Test @Config(fontScale = 1.3f) fun capturedLaundryLargeFontKeepsActionsReachable() = renderCapturedFlow(false, true)
    @Test fun paymentDrawerShowsOnlySupportedReturnedChannelsAndKeepsServerRestrictions() {
        val fixture = fixture(); val model = model()
        val quote = fixture.quote.copy(channels = listOf(
            LifePayChannel("32", "微信支付", true, false),
            LifePayChannel("2", "余额支付", false, false, "余额不足"),
            LifePayChannel("52", "银联支付", true, false),
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
        compose.onNodeWithText("银联支付").assertDoesNotExist()
        compose.onNodeWithText("农行掌银").assertDoesNotExist()
        compose.onNodeWithText("其他支付").assertDoesNotExist()
        compose.onNodeWithTag("laundry-pay-submit").assertIsDisplayed().assertIsEnabled().assertTextContains("支付宝支付")
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
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
                LaundryPaymentSheet(state.paymentVisible, state, model)
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
        compose.onNodeWithText("银联支付").assertDoesNotExist()
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
    @Test fun fiveMinutePaymentCountdownExpiresToReadOnlyQueryAndServerClosureIsFinal() {
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
        compose.onNodeWithText("付款时间已到，请查询订单结果").assertIsDisplayed()
        compose.onNodeWithText("查询结果").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("支付超时").assertDoesNotExist()
        compose.onNodeWithText("继续支付").assertDoesNotExist()
        compose.onNodeWithTag("laundry-pay-submit").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(laundry = paying.copy(phase = LaundryPhase.Closed),
            paymentFeedback = LaundryPaymentFeedback(paying.sessionId, RechargePhase.TimedOut, detail = "订单已关闭")) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("支付超时").assertIsDisplayed()
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
        val settings = ThemeSettings(compose.activity.application)
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
            paymentLaunch = LifeAlipayLaunch(1, pending.orderNo, "test-payload")) }
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
        val settings = ThemeSettings(compose.activity.application)
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
    private class OfflineResponse(url: URL, private val body: String) : HttpsURLConnection(url) {
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
