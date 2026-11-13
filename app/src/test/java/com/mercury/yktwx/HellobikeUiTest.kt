package com.mercury.yktwx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.appPreferencesRepositoryFixture
import com.mercury.yktwx.ui.themeSettingsFixture
import com.mercury.yktwx.data.MemoryPreferences
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.LifeDeviceStatusEntry
import com.mercury.yktwx.ui.components.LifeDeviceStatusIslands
import com.mercury.yktwx.ui.pages.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HellobikeUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val models = ViewModelStore()
    private val preferences = MemoryPreferences()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun settings() = themeSettingsFixture(appPreferencesRepositoryFixture(preferences, preferenceScope))
    private fun model(api: FakeHellobikeService): HellobikeViewModel = HellobikeViewModel(
        api, BikeRecords().store(account = null, region = null), FakeBikeLocation(), io = Dispatchers.Main.immediate,
        analytics = AnalyticsFixtures.from(compose.activity.application))
        .also { models.put("hellobike", it); it.initialize(preview = false) }
    @After fun cleanup() { models.clear(); preferenceScope.cancel() }
    private fun capture(name: String, tag: String? = null): Bitmap {
        // Root overlays and their page transitions need snapshot delivery between frames.
        repeat(75) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        val file = File("../build/hellobike-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        val image = (if (tag == null) compose.onRoot() else compose.onNodeWithTag(tag)).captureToImage().asAndroidBitmap()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }
    private fun sliderImage(): HellobikeSlider {
        fun png(width: Int, height: Int, background: Boolean): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(if (background) Color.rgb(210, 219, 225) else Color.rgb(245, 247, 249))
                if (background) drawRect(160f, 40f, 200f, 80f, Paint().apply { color = Color.rgb(126, 143, 156) })
            }
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
        }
        return HellobikeSlider(png(300, 140, true), png(40, 40, false), 40, 300, 140, 40, 40)
    }
    private fun login(dark: Boolean) {
        val api = FakeHellobikeService().apply { sliderImage = sliderImage(); sms = HellobikeSmsResult(true, "challenge") }
        val model = model(api)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) { Scaffold {
                val state by model.state.collectAsState()
                HellobikeLoginSheet(state, model, true)
            } }
        } }
        compose.runOnIdle { model.environment(true); model.connectAccount(); model.phoneChanged(fixtureBikeAccount.phone) }
        val theme = if (dark) "dark" else "light"
        compose.onNodeWithTag("hellobike-login-sheet").assertExists()
        compose.onNodeWithTag("hellobike-region").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-official").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-slider").assertDoesNotExist()
        capture("login-$theme")
        compose.onNodeWithTag("hellobike-send-code").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hellobike-send-code").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-login-submit").assertDoesNotExist()
        compose.onNodeWithText("验证并发送").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-slider-refresh").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, api.smsCalls); assertEquals(2, api.sliderCalls) }
        compose.onNodeWithTag("hellobike-slider-back").assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hellobike-login-phone").assertTextContains(fixtureBikeAccount.phone)
        compose.onNodeWithTag("hellobike-send-code").performClick()
        compose.mainClock.advanceTimeBy(1000)
        // Prime the native text-button layers before the complete overlay capture.
        compose.onNodeWithTag("hellobike-slider-refresh").assertIsDisplayed().captureToImage()
        compose.onNodeWithTag("hellobike-slider-back").assertIsDisplayed().captureToImage()
        capture("verification-$theme")
        capture("verification-content-$theme", "hellobike-login-sheet")
        compose.runOnIdle { api.sms = HellobikeSmsResult() }
        val slider = compose.onNodeWithTag("hellobike-slider-drag")
        if (dark) slider.performSemanticsAction(SemanticsActions.SetProgress) { it(80f) }
        else slider.performTouchInput { swipe(centerLeft, center, 400) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hellobike-slider").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-send-code").assertIsNotEnabled()
        compose.onNodeWithTag("hellobike-login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("hellobike-login-code").performTextInput("123456")
        compose.onNodeWithTag("hellobike-login-submit").assertIsEnabled()
        compose.runOnIdle {
            assertFalse(model.state.value.sliderVerification)
            assertTrue(model.state.value.codeSeconds > 0)
            assertNull(model.state.value.locationPermissionRequest)
            model.environment(false)
        }
        assertEquals(3, api.smsCalls); assertEquals("challenge", api.smsChallenges.last())
        assertTrue(api.smsOffsets.last()!! > 0)
        assertEquals(0, api.loginCalls); assertEquals(0, api.createCalls)
    }

    @Test fun lightLoginUsesTheSharedDrawerAndDragsTheServerSizedPuzzle() { login(false) }
    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun darkLoginUsesTheSharedDrawerAndDragsTheServerSizedPuzzle() { login(true) }

    private fun scenes(dark: Boolean) {
        val api = FakeHellobikeService(); val model = model(api); val settings = settings()
        val base = HellobikeUiState(restoring = false, connectedPhone = fixtureBikeAccount.maskedPhone, region = fixtureBikeRegion,
            scannedBikeNo = fixtureBikeSession.bikeNo, precheck = HellobikePrecheck(fixtureBikeSession.bikeNo, fixtureBikeSession.rideGuid,
                "首个30分钟2元，之后按哈啰计费规则收取", 50_000, fixtureBikeRegion, fixtureBikePosition))
        var snapshot by mutableStateOf(base)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) { Scaffold {
                HellobikeDevicePage(snapshot, settings, model, true, {}, {}, { 1_300_000 }, { 50_000 })
            } }
        } }
        val theme = if (dark) "dark" else "light"
        compose.onNodeWithTag("hellobike-primary-action").assertIsEnabled()
        capture("ready-$theme")
        val rideState = base.copy(scannedBikeNo = null, precheck = null, sessions = listOf(fixtureBikeSession), selectedSessionId = fixtureBikeSession.sessionId)
        compose.runOnIdle { snapshot = rideState.copy(busy = true, sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Opening))) }
        compose.onNodeWithTag("hellobike-primary-action").assertIsNotEnabled(); capture("opening-$theme")
        compose.runOnIdle { snapshot = rideState }
        compose.onNodeWithTag("hellobike-progress").assertExists()
        compose.onNodeWithTag("hellobike-official").assertDoesNotExist(); capture("riding-$theme")
        compose.runOnIdle { snapshot = rideState.copy(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Closing, uncertain = true))) }
        compose.onNodeWithTag("hellobike-primary-action").assertTextContains("查询车辆状态"); capture("closing-$theme")
        val ended = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Pending, endedAtMillis = 1_300_000, amount = "2.00")
        val ending = rideState.copy(sessions = listOf(ended), payDetail = fixtureBikeDetail)
        compose.runOnIdle { snapshot = ending }
        compose.onNodeWithText("本笔费用已结清").assertDoesNotExist(); capture("ended-$theme")
        compose.runOnIdle { snapshot = ending.copy(paymentVisible = true, payOptions = api.channels) }
        compose.onNodeWithTag("hellobike-pay-submit").assertIsEnabled(); capture("payment-$theme")
        compose.runOnIdle { snapshot = ending.copy(paymentVisible = true,
            sessions = listOf(ended.copy(settlement = HellobikeSettlement.Paying, paymentAttempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "001", 1_000_000))),
            paymentFeedback = LifePaymentFeedback(ended.sessionId, RechargePhase.TimedOut, false, "支付结果尚未确认，请查询原订单")) }
        compose.onNodeWithText("查询结果").assertIsEnabled().assertIsDisplayed(); compose.onNodeWithText("支付成功").assertDoesNotExist()
        // Prime PixelCopy's retained shader layers after the page transition; its first
        // complete-window draw can omit new child layers under Robolectric.
        capture("payment-pending-$theme", "hellobike-payment-sheet")
        val query = compose.onNodeWithText("查询结果").captureToImage().asAndroidBitmap()
        val pixels = IntArray(query.width * query.height).also { query.getPixels(it, 0, query.width, 0, 0, query.width, query.height) }
        assertTrue("The query action must paint its primary button background", pixels.any {
            Color.blue(it) > 150 && Color.blue(it) - Color.red(it) > 50
        })
        val resultImage = capture("payment-pending-$theme", "hellobike-payment-sheet")
        val queryBounds = compose.onNodeWithText("查询结果").fetchSemanticsNode().boundsInRoot
        val sheetBounds = compose.onNodeWithTag("hellobike-payment-sheet").fetchSemanticsNode().boundsInRoot
        val queryBackground = resultImage.getPixel((queryBounds.left - sheetBounds.left + queryBounds.width * .2f).toInt(),
            (queryBounds.center.y - sheetBounds.top).toInt())
        assertTrue("The complete payment result sheet must also draw the query button",
            Color.blue(queryBackground) > 150 && Color.blue(queryBackground) - Color.red(queryBackground) > 50)
        compose.runOnIdle { snapshot = ending.copy(sessions = listOf(ended.copy(settlement = HellobikeSettlement.Paid))) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("本笔费用已结清").assertExists(); capture("paid-$theme")
        assertEquals(0, api.readCalls); assertEquals(0, api.payCalls); assertEquals(0, api.createCalls)
    }
    @Test fun lightDeviceAndPaymentStagesUseSharedCardsProgressAndPaymentControls() { scenes(false) }
    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun darkDeviceAndPaymentStagesUseSharedCardsProgressAndPaymentControls() { scenes(true) }

    @Test fun anOutgoingPaymentDrawerDisablesQueriesBeforeItsExitMotionFinishes() {
        val api = FakeHellobikeService(); val model = model(api)
        val state = HellobikeUiState(restoring = false, paymentVisible = true, sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)),
            selectedSessionId = fixtureBikeSession.sessionId, paymentFeedback = LifePaymentFeedback(fixtureBikeSession.sessionId,
                RechargePhase.Checking, false, "请查询原订单"))
        var show by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            HellobikePaymentSheet(show, state, model)
        } } } }
        compose.onNodeWithText("查询结果").assertIsEnabled()
        capture("payment-query-cold")
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { show = false }
            // Deliver snapshot notifications between parent and root-overlay frames.
            repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithText("查询结果").assertIsNotEnabled()
        } finally { compose.mainClock.autoAdvance = true }
        assertEquals(0, api.queryCalls)
    }
    @Test fun anOutgoingLoginDrawerDisablesSmsBeforeItsExitMotionFinishes() {
        val api = FakeHellobikeService(); val model = model(api)
        val state = HellobikeUiState(restoring = false, loginVisible = true, phone = fixtureBikeAccount.phone)
        var active by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            HellobikeLoginSheet(state, model, active)
        } } } }
        compose.onNodeWithTag("hellobike-send-code").assertIsEnabled()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { active = false }
            repeat(3) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithTag("hellobike-send-code").assertIsNotEnabled()
        } finally { compose.mainClock.autoAdvance = true }
        assertEquals(0, api.smsCalls)
    }
    @Test fun dismissingHellobikeReminderKeepsOtherProvidersAndSettingsAccountMetadata() {
        val settings = settings()
        var chosen: LifeProvider? = null
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            PaymentPage(PreviewData.paymentState(false), settings, 0.dp, {}, lifeLoginRequired = true, hui798LoginRequired = true,
                hellobikeLoginRequired = true, onHellobikeLogin = { chosen = LifeProvider.Hellobike })
        } } } }
        compose.onNodeWithTag("hellobike-login-notice").performScrollTo().performClick()
        assertEquals(LifeProvider.Hellobike, chosen)
        compose.onNodeWithTag("hellobike-login-notice-dismiss").performClick()
        compose.waitUntil(5000) { settings.hellobikeLoginNoticeDismissed.value }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hellobike-login-notice").assertDoesNotExist()
        compose.onNodeWithTag("life-login-notice").assertExists(); compose.onNodeWithTag("hui798-login-notice").assertExists()
        val managed = ManagedLifeAccount(LifeProvider.Hellobike, LifeAccountConnection())
        assertEquals("哈啰", managed.name); assertEquals("单车骑行", managed.services); assertEquals("hellobike", managed.tag)
    }

    @Test @Config(qualifiers = "w412dp-h915dp-night-xhdpi")
    fun settingsDispatchesTheThirdAccountAndExplainsUnresolvedOrdersInLargeText() {
        val settings = settings()
        var accounts by mutableStateOf(LifeProvider.entries.map { ManagedLifeAccount(it, LifeAccountConnection()) })
        var loggedIn: LifeProvider? = null
        var removed: LifeProvider? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(density.density, 1.3f)) {
                AppTheme(ColorSchemeMode.Dark) { Scaffold { padding ->
                    AppSettingsContent(Modifier.fillMaxSize(), PaddingValues(top = padding.calculateTopPadding(), bottom = 28.dp),
                        settings, AppUpdateUiState(), {}, {}, accounts, true, {}, {}, { loggedIn = it }, { provider ->
                            removed = provider
                            accounts = accounts.map { if (it.provider == provider) it.copy(connection = LifeAccountConnection()) else it }
                        })
                } }
            }
        }
        compose.onNodeWithTag("panggui-account-row").assertExists()
        compose.onNodeWithTag("hui798-account-row").assertExists()
        compose.onNodeWithTag("hellobike-account-row").performScrollTo().performClick()
        assertEquals(LifeProvider.Hellobike, loggedIn)
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
        compose.runOnIdle { accounts = accounts.map { if (it.provider == LifeProvider.Hellobike)
            it.copy(connection = LifeAccountConnection(fixtureBikeAccount.maskedPhone, LifeAccountStatus.Verified)) else it } }
        compose.onNodeWithTag("hellobike-account-row").performClick()
        compose.onNodeWithText("退出哈啰账户").assertIsDisplayed()
        compose.onNodeWithText("退出后需重新登录。").assertIsDisplayed()
        capture("settings-logout-dark-large")
        compose.onNodeWithTag("life-account-exit").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LifeProvider.Hellobike, removed) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
    }

    @Test fun ridingAndUnsettledEntriesReuseTheStatusIslandAndSettledOrdersLeaveAutomatically() {
        val settings = settings()
        var ride by mutableStateOf(fixtureBikeSession)
        var opened: String? = null
        var dismissed: String? = null
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            LifeDeviceStatusIslands(emptyList(), emptyList(), { _, _ -> fail("Provider-specific entry lost its open callback") },
                { _, _ -> fail("Provider-specific entry lost its dismiss callback") }, settings, Modifier.fillMaxWidth(),
                additionalEntries = listOf(LifeDeviceStatusEntry(LifeFeature.Ride, ride.title, "哈啰单车", ride.region?.label.orEmpty(),
                    "hellobike-${ride.sessionId}", completed = ride.dismissible, dismissible = ride.canDismissRecord(),
                    onOpen = { opened = ride.sessionId }, onDismiss = { dismissed = ride.sessionId })))
        } } } }
        val dismissTag = "dismiss-life-session-hellobike-${ride.sessionId}"
        compose.onNodeWithTag("life-status-ride").assertIsDisplayed().performClick()
        assertEquals(ride.sessionId, opened)
        compose.onNodeWithTag(dismissTag).assertDoesNotExist()
        compose.runOnIdle { ride = ride.copy(phase = HellobikePhase.Closing, uncertain = true) }
        compose.onNodeWithText("还车结果待确认").assertExists()
        compose.onNodeWithTag(dismissTag).assertIsEnabled()
        compose.runOnIdle { ride = ride.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying) }
        compose.onNodeWithText("支付结果待确认").assertExists()
        compose.onNodeWithTag(dismissTag).assertIsEnabled()
        capture("unsettled-status-light")
        compose.runOnIdle { ride = ride.copy(settlement = HellobikeSettlement.Paid) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-status-ride").assertDoesNotExist()
        compose.onNodeWithTag(dismissTag).assertDoesNotExist()
        assertNull(dismissed)
        compose.runOnIdle { ride = ride.copy(phase = HellobikePhase.Opening, settlement = HellobikeSettlement.Unknown, uncertain = false) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("正在开锁").assertIsDisplayed()
        compose.runOnIdle { ride = ride.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Closed) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-status-ride").assertDoesNotExist()
    }
}
