package com.mercury.yktwx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.mercury.yktwx.ui.components.InlineErrorGroup
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
    @Test fun emptyCardsFadeAndResizeWhileDisclosureAndLogoutKeepStableSections() {
        val bike = HellobikeViewModel(FakeHellobikeService(), BikeRecords().store(), FakeBikeLocation(), io = Dispatchers.Main.immediate,
            analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("account-motion", it) }
        var display by mutableStateOf(HellobikeCardsState(loading = true, cardsLoading = true,
            audit = HellobikeStudentAudit(2, 1), productsLoading = true))
        compose.setContent { AppTheme { Scaffold { padding ->
            val bikeState by bike.state.collectAsState()
            val account = ManagedLifeAccount(LifeProvider.Hellobike,
                if (bikeState.connected) LifeAccountConnection(fixtureBikeAccount.maskedPhone, LifeAccountStatus.Verified) else LifeAccountConnection(),
                owner = if (bikeState.connected) fixtureBikeAccount.userGuid else "", busy = bikeState.accountBusy)
            HellobikeAccountContent(Modifier.fillMaxSize(), padding, account, display, bike, true, {}, {})
        } } }
        compose.runOnIdle { bike.initialize(false) }
        compose.mainClock.advanceTimeBy(1000)
        compose.mainClock.autoAdvance = false
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        fun height() = compose.onNodeWithTag("hellobike-my-cards-section").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }
        fun darkest(): Int {
            val image = compose.onNodeWithTag("hellobike-my-cards-section").captureToImage().asAndroidBitmap()
            var min = 255
            for (y in 0 until image.height) for (x in 0 until image.width) min = minOf(min, Color.red(image.getPixel(x, y)))
            return min
        }
        val sectionId = compose.onNodeWithTag("hellobike-my-cards-section").fetchSemanticsNode().id
        val campusId = compose.onNodeWithTag("hellobike-campus-section").fetchSemanticsNode().id
        val beforeHeight = height()
        val beforePaint = darkest()
        compose.runOnIdle { display = display.copy(loading = false, cardsLoading = false, productsLoading = false) }
        val heights = mutableListOf<Float>()
        val paint = mutableListOf<Int>()
        repeat(18) { frame(); heights += height(); paint += darkest() }
        val finalHeight = height()
        val finalPaint = darkest()
        assertTrue("Empty cards must resize through intermediate heights: $heights",
            heights.any { it > minOf(beforeHeight, finalHeight) + 1 && it < maxOf(beforeHeight, finalHeight) - 1 })
        assertTrue("Empty text must fade into view: $paint",
            paint.any { it > minOf(beforePaint, finalPaint) + 2 && it < maxOf(beforePaint, finalPaint) - 2 })
        assertEquals(sectionId, compose.onNodeWithTag("hellobike-my-cards-section").fetchSemanticsNode().id)
        assertEquals(campusId, compose.onNodeWithTag("hellobike-campus-section").fetchSemanticsNode().id)
        compose.onNodeWithText("暂无骑行卡").assertIsDisplayed()
        val ordinary = HellobikeCampusProduct("ordinary-motion", "普通月卡", "6.90", "官方使用说明",
            com.mercury.yktwx.data.jsonObjectOf("businessType" to 1), campus = false)
        compose.runOnIdle { display = display.copy(ordinaryExpanded = true, ordinaryLoaded = true, ordinaryProducts = listOf(ordinary)) }
        repeat(18) { frame() }
        val buttonId = compose.onNodeWithTag("hellobike-other-cards").fetchSemanticsNode().id
        val footerId = compose.onNodeWithTag("hellobike-logout").fetchSemanticsNode().id
        val expandedY = compose.onNodeWithTag("hellobike-logout").getUnclippedBoundsInRoot().top.value
        compose.runOnIdle { display = display.copy(ordinaryExpanded = false) }
        val footerPositions = (0 until 18).map { frame(); compose.onNodeWithTag("hellobike-logout").getUnclippedBoundsInRoot().top.value }
        val collapsedY = footerPositions.last()
        assertTrue("Footer must move with the collapse, not jump after it: $footerPositions",
            footerPositions.any { it > minOf(expandedY, collapsedY) + 1 && it < maxOf(expandedY, collapsedY) - 1 })
        assertEquals(buttonId, compose.onNodeWithTag("hellobike-other-cards").fetchSemanticsNode().id)
        assertEquals(footerId, compose.onNodeWithTag("hellobike-logout").fetchSemanticsNode().id)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("退出登录").performClick()
        compose.onNodeWithTag("life-account-exit").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("登录哈啰").assertIsDisplayed()
        compose.onNodeWithText("查看我的骑行卡").assertIsDisplayed()
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-logout").assertDoesNotExist()
        compose.onNodeWithText("暂无骑行卡").assertDoesNotExist()
    }
    @Test fun collapseAtListBottomPreservesFooterAnchorAndLogoutResetsInOneTransition() {
        val bike = HellobikeViewModel(FakeHellobikeService(), BikeRecords().store(), FakeBikeLocation(), io = Dispatchers.Main.immediate,
            analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("account-scroll", it) }
        val card = HellobikeUserCard("card", 1, "月卡", 23, 0, 1796572799L, "官方使用说明", renewable = true)
        val product = HellobikeCampusProduct("product", "普通月卡", "6.90", "官方商品说明",
            com.mercury.yktwx.data.jsonObjectOf("businessType" to 1), campus = false)
        var display by mutableStateOf(HellobikeCardsState(cards = (1..8).map { card.copy(guid = "card-$it") }, audit = HellobikeStudentAudit(0, 1),
            ordinaryExpanded = true, ordinaryLoaded = true, ordinaryProducts = (1..5).map { product.copy(key = "product-$it") }))
        lateinit var list: androidx.compose.foundation.lazy.LazyListState
        lateinit var bar: top.yukonga.miuix.kmp.basic.ScrollBehavior
        compose.setContent { AppTheme {
            val bikeState by bike.state.collectAsState()
            list = androidx.compose.foundation.lazy.rememberLazyListState()
            bar = top.yukonga.miuix.kmp.basic.MiuixScrollBehavior(top.yukonga.miuix.kmp.basic.rememberTopAppBarState())
            Scaffold(topBar = { com.mercury.yktwx.ui.components.DashboardTopBar("哈啰", scrollBehavior = bar) }) { padding ->
                val account = ManagedLifeAccount(LifeProvider.Hellobike, if (bikeState.connected)
                    LifeAccountConnection(fixtureBikeAccount.maskedPhone, LifeAccountStatus.Verified) else LifeAccountConnection(), busy = bikeState.accountBusy)
                HellobikeAccountContent(Modifier.fillMaxSize().nestedScroll(bar.nestedScrollConnection), padding, account, display, bike, true, {}, {}, scroll = bar, listState = list)
            }
        } }
        compose.runOnIdle { bike.initialize(false) }
        compose.onNodeWithTag("hellobike-account-page").performScrollToNode(hasTestTag("hellobike-logout"))
        compose.onNodeWithTag("hellobike-logout").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        val footerId = compose.onNodeWithTag("hellobike-logout").fetchSemanticsNode().id
        compose.runOnIdle { display = display.copy(ordinaryExpanded = false) }
        repeat(18) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("hellobike-logout").assertIsDisplayed()
            compose.runOnIdle { assertTrue("Collapse must retain the bottom anchor", list.firstVisibleItemIndex > 0) }
        }
        assertEquals(footerId, compose.onNodeWithTag("hellobike-logout").fetchSemanticsNode().id)
        compose.runOnIdle { assertEquals(7, list.layoutInfo.totalItemsCount); bar.state.heightOffset = bar.state.heightOffsetLimit; bar.state.contentOffset = -200f }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("退出登录").performClick()
        compose.onNodeWithTag("life-account-exit").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex); assertEquals(0, list.firstVisibleItemScrollOffset)
            assertEquals(0f, bar.state.heightOffset); assertEquals(0f, bar.state.contentOffset) }
        compose.onNodeWithText("登录哈啰").assertIsDisplayed()
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
    }
    @Test fun accountUsesSkeletonsUntilCloudRepliesAndButtonExpandsOtherCards() {
        val auditReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        var cardsReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val productsReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        val ordinaryReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        var ordinaryCalls = 0
        var dark by mutableStateOf(false)
        val card = HellobikeUserCard("cloud-card", 1, "校园月卡", 23, 0, 1796572799L, "官方使用说明", renewable = true)
        val product = HellobikeCampusProduct("cloud-campus", "校园月卡", "6.90", "官方商品说明",
            com.mercury.yktwx.data.jsonObjectOf("businessType" to 1))
        val api = object : FakeHellobikeService() {
            override suspend fun studentAudit(account: HellobikeAccount): HellobikeStudentAudit {
                auditReply.await(); return HellobikeStudentAudit(2, 1)
            }
            override suspend fun userCards(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?, businessType: Int, page: Int): HellobikeUserCardPage {
                cardsReply.await(); return HellobikeUserCardPage(if (businessType == 1) listOf(card) else emptyList(), false)
            }
            override suspend fun campusProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCampusProducts {
                productsReply.await(); return HellobikeCampusProducts("校园骑行卡", listOf(product))
            }
            override suspend fun ordinaryProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): List<HellobikeCampusProduct> {
                ordinaryCalls++; ordinaryReply.await(); return listOf(product.copy(key = "cloud-ordinary", name = "普通月卡", campus = false))
            }
        }
        val bike = HellobikeViewModel(api, BikeRecords().store(), FakeBikeLocation(), io = Dispatchers.Main.immediate,
            analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("loading-account", it) }
        val account = ManagedLifeAccount(LifeProvider.Hellobike, LifeAccountConnection(fixtureBikeAccount.maskedPhone, LifeAccountStatus.Verified), owner = fixtureBikeAccount.userGuid)
        compose.setContent { AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
            Scaffold(topBar = { com.mercury.yktwx.ui.components.DashboardTopBar("哈啰") }) { padding ->
                val state by bike.cards.state.collectAsState()
                HellobikeAccountContent(Modifier.fillMaxSize(), padding, account, state, bike, true, {}, {})
            }
        } }
        compose.runOnIdle { bike.initialize(false); bike.environment(true); bike.cards.pageVisible(true) }
        compose.onNodeWithTag("hellobike-my-cards-loading").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-student-loading").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-campus-loading").assertIsDisplayed()
        compose.onNodeWithText("暂无骑行卡").assertDoesNotExist()
        compose.onNodeWithText("未认证").assertDoesNotExist()
        fun snapshot(name: String) {
            repeat(30) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File("../build/hellobike-campus-card/$name.png").absoluteFile.also { it.parentFile?.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        snapshot("account-skeleton-light")
        compose.runOnIdle { dark = true }; snapshot("account-skeleton-dark")
        compose.runOnIdle { dark = false; auditReply.complete(Unit) }
        compose.onNodeWithText("已认证").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-my-cards-loading").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-campus-loading").assertIsDisplayed()
        compose.runOnIdle { cardsReply.complete(Unit) }
        compose.onNodeWithText("剩余 23 天").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-my-cards-loading").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-campus-loading").assertIsDisplayed()
        compose.runOnIdle { productsReply.complete(Unit) }
        compose.onNodeWithTag("hellobike-buy-cloud-campus").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-campus-loading").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, ordinaryCalls) }
        compose.onNodeWithTag("hellobike-other-cards").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hellobike-ordinary-loading").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("收起其他骑行卡").assertExists()
        compose.runOnIdle { ordinaryReply.complete(Unit) }
        compose.onNodeWithTag("hellobike-buy-cloud-ordinary").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("hellobike-ordinary-loading").assertDoesNotExist()
        snapshot("account-other-cards-expanded-light")
        compose.runOnIdle { dark = true }; snapshot("account-other-cards-expanded-dark")
        compose.onNodeWithTag("hellobike-other-cards").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hellobike-other-cards-content").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-other-cards").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { assertEquals(1, ordinaryCalls); cardsReply = kotlinx.coroutines.CompletableDeferred(); bike.cards.refresh() }
        compose.onNodeWithTag("hellobike-account-page").performScrollToIndex(0)
        compose.onNodeWithText("剩余 23 天").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-my-cards-loading").assertDoesNotExist()
        compose.runOnIdle { cardsReply.complete(Unit) }
    }
    @Test fun purchaseWithDelayedChannelsUsesOfficialFallbackWithoutSendingPaymentAutomatically() {
        val channelReply = kotlinx.coroutines.CompletableDeferred<Unit>()
        var orders = 0
        val product = HellobikeCampusProduct("no-default", "校园月卡", "6.90", "官方说明",
            com.mercury.yktwx.data.jsonObjectOf("businessType" to 1))
        val api = object : FakeHellobikeService() {
            override suspend fun studentAudit(account: HellobikeAccount) = HellobikeStudentAudit(2, 1)
            override suspend fun userCards(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?, businessType: Int, page: Int) = HellobikeUserCardPage(emptyList(), false)
            override suspend fun campusProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?) = HellobikeCampusProducts("校园骑行卡", listOf(product))
            override suspend fun preOrderCard(account: HellobikeAccount, product: HellobikeCampusProduct, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCardPurchase {
                orders++
                return HellobikeCardPurchase(product.name, listOf(HellobikePayOrder("pay", "6.90", "payment", "28")), "6.90", "28", "2801", region)
            }
            override suspend fun cardPayChannels(account: HellobikeAccount, purchase: HellobikeCardPurchase): List<HellobikePayOption> {
                channelReply.await()
                return listOf(HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "6.90", defaultSelected = false))
            }
        }
        val bike = HellobikeViewModel(api, BikeRecords().store(), FakeBikeLocation(), io = Dispatchers.Main.immediate,
            analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("delayed-purchase", it) }
        val account = ManagedLifeAccount(LifeProvider.Hellobike, LifeAccountConnection(fixtureBikeAccount.maskedPhone, LifeAccountStatus.Verified), owner = fixtureBikeAccount.userGuid)
        compose.setContent { AppTheme { Scaffold { padding ->
            val state by bike.cards.state.collectAsState()
            HellobikeAccountContent(Modifier.fillMaxSize(), padding, account, state, bike, true, {}, {})
        } } }
        compose.runOnIdle { bike.initialize(false); bike.environment(true); bike.cards.pageVisible(true) }
        compose.onNodeWithTag("hellobike-buy-no-default").assertIsEnabled().performClick()
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithTag("hellobike-card-pay-submit").assertIsNotEnabled()
        compose.runOnIdle { channelReply.complete(Unit) }
        compose.mainClock.advanceTimeBy(1200)
        compose.onNodeWithText("支付宝").assertIsDisplayed()
        compose.onNodeWithText("支付宝").assertIsSelected()
        compose.onNodeWithTag("hellobike-card-pay-submit").assertIsEnabled()
        compose.onNodeWithTag("hellobike-card-pay-cancel").performClick()
        compose.runOnIdle { assertEquals(1, orders); assertEquals(0, api.payCalls); assertFalse(bike.cards.state.value.paymentVisible) }
    }
    @Test fun accountDetailShowsAllCardsAboveCampusPurchaseAndPromptsUnapprovedStudents() {
        val bike = HellobikeViewModel(FakeHellobikeService(), BikeRecords().store(), FakeBikeLocation(), io = Dispatchers.Main.immediate,
            analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("account-bike", it) }
        var approved by mutableStateOf(true)
        var empty by mutableStateOf(false)
        var dark by mutableStateOf(false)
        var paymentShown by mutableStateOf(false)
        val card = HellobikeUserCard("my-card", 1, "校园·30天月卡", 23, 0, 1796572799L, "限亲子车、普通单车使用，单笔最多抵扣120分钟。", renewable = true)
        val product = HellobikeCampusProduct("month", "校园·30天月卡", "6.90", "30天内有效，限郑州市、开封市使用。",
            com.mercury.yktwx.data.jsonObjectOf("businessType" to 1))
        val account = ManagedLifeAccount(LifeProvider.Hellobike, LifeAccountConnection(fixtureBikeAccount.maskedPhone, LifeAccountStatus.Verified), owner = fixtureBikeAccount.userGuid)
        compose.setContent { AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
            val bikeState by bike.state.collectAsState()
            LaunchedEffect(Unit) { bike.initialize(false) }
            Scaffold(topBar = { com.mercury.yktwx.ui.components.DashboardTopBar("哈啰") }) { padding ->
                if (!bikeState.restoring) HellobikeAccountContent(Modifier.fillMaxSize(), PaddingValues(top = padding.calculateTopPadding(), bottom = 28.dp), account,
                    HellobikeCardsState(cards = if (empty) emptyList() else listOf(card), audit = HellobikeStudentAudit(if (approved) 2 else 0, 1),
                        purchase = HellobikeCardPurchase("校园·30天月卡", listOf(HellobikePayOrder("fixture-pay", "6.90", "fixture-payment", "28")), "6.90", "28", "2801", null),
                        paymentVisible = paymentShown, options = listOf(HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "6.90"),
                            HellobikePayOption(LifePayChannel("003", "哈啰余额", false, false, "余额不足"), "6.90")), method = "001", products = if (approved)
                        listOf(product, product.copy(key = "semester", name = "校园·120天卡", price = "25.90"), product.copy(key = "year", name = "校园·365天卡", price = "68.90")) else emptyList()),
                    bike, true, {}, {})
            }
        } }
        compose.waitForIdle()
        compose.onNodeWithText("剩余 23 天").assertIsDisplayed()
        compose.onNodeWithText("到期时间 " + hellobikeCardExpiry(card.expireSecond)).assertIsDisplayed()
        val myTitle = compose.onNodeWithTag("hellobike-my-cards-title").fetchSemanticsNode().boundsInRoot.top
        val purchaseTitle = compose.onNodeWithTag("hellobike-campus-purchase-title").fetchSemanticsNode().boundsInRoot.top
        assertTrue(myTitle < purchaseTitle)
        compose.onNodeWithText("哈啰账户").assertDoesNotExist()
        fun snapshot(name: String) {
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File("../build/hellobike-campus-card/$name.png").absoluteFile.also { it.parentFile?.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("hellobike-renew-my-card").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-buy-month").assertIsDisplayed().assertIsEnabled()
        val bounds = compose.onNodeWithTag("hellobike-campus-product-month").fetchSemanticsNode().boundsInRoot
        assertTrue("Product stays compact", bounds.height < 120 * compose.activity.resources.displayMetrics.density)
        assertTrue(compose.onNodeWithTag("hellobike-buy-month").fetchSemanticsNode().boundsInRoot.left > bounds.center.x)
        snapshot("account-compact-cards-light")
        compose.onNodeWithTag("hellobike-product-rules-month").assertTextEquals(product.description).assertIsDisplayed()
        compose.onNodeWithTag("hellobike-user-rules-my-card").assertTextEquals(card.description).assertIsDisplayed()
        compose.onNodeWithTag("hellobike-card-purchase-record").assertDoesNotExist()
        compose.runOnIdle { paymentShown = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("支付方式").assertIsDisplayed()
        compose.onNodeWithText("支付宝").assertIsDisplayed()
        compose.onNodeWithText("哈啰余额").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-card-pay-submit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("hellobike-card-pay-cancel").assertIsDisplayed().assertIsEnabled()
        snapshot("account-card-payment-light")
        compose.runOnIdle { dark = true }
        snapshot("account-card-payment-dark")
        compose.runOnIdle { paymentShown = false; dark = false }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { empty = true }
        compose.onNodeWithTag("hellobike-account-page").performScrollToIndex(0)
        compose.onNodeWithText("暂无骑行卡").assertIsDisplayed()
        snapshot("account-compact-empty-approved-light")
        compose.runOnIdle { approved = false }
        compose.onNodeWithTag("hellobike-buy-month").assertDoesNotExist()
        compose.onNodeWithText("学生认证后可购买").assertIsDisplayed()
        compose.onNodeWithText("前往哈啰").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-account-page").performScrollToIndex(0)
        snapshot("account-compact-empty-unapproved-light")
        compose.runOnIdle { dark = true }
        snapshot("account-compact-empty-unapproved-dark")
    }
    @Test fun officialErrorsKeepNewlinesAndWrapFullyInDeviceAndPaymentForms() {
        val message = "官方原因\n请核对车辆1234567890：https://c3x.me/?n=1234567890\n" + "官方详细说明，请阅读后处理。".repeat(8)
        var payment by mutableStateOf(false)
        compose.setContent { AppTheme {
            Column(Modifier.width(300.dp)) {
                if (payment) PaymentMethodActions(listOf("支付宝"), 0, {}, "支付", {}, true, false,
                    error = message, preserveError = true)
                else InlineErrorGroup(message, 12.dp, preserveMessage = true) {}
            }
        } }
        fun check() {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(message, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(message, layout.layoutInput.text.text)
            assertTrue(layout.lineCount > 1)
            assertFalse(layout.hasVisualOverflow)
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
        }
        check()
        compose.runOnIdle { payment = true }
        compose.mainClock.advanceTimeBy(1000)
        check()
    }
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
