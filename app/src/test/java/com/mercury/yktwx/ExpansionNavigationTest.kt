package com.mercury.yktwx

import androidx.activity.BackEventCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.state.BillPeriod
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.nav.AppRoute
import com.mercury.yktwx.ui.pages.billDetailRoute
import com.mercury.yktwx.ui.pages.billStatsContent
import com.mercury.yktwx.ui.themeSettingsFixture
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Exercise the production shell's source keys, click callbacks and subsequent route sync. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExpansionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @After fun cleanup() { compose.activity.viewModelStore.clear() }

    @Test fun pangguiWaterIslandExpandsAndReturnsAfterRouteSync() = island(LifeProvider.Panggui, LifeFeature.Water)
    @Test fun pangguiLaundryIslandExpandsAndReturnsAfterRouteSync() = island(LifeProvider.Panggui, LifeFeature.Laundry)
    @Test fun hui798IslandExpandsWithItsAccountIdentity() = island(LifeProvider.Hui798, LifeFeature.Water)
    @Test fun hellobikeIslandExpandsWithItsAccountIdentity() = island(LifeProvider.Hellobike, LifeFeature.Ride)

    private fun island(provider: LifeProvider, feature: LifeFeature) {
        val app = compose.activity.application
        val now = System.currentTimeMillis()
        val device = LifeDevice("water", "test-water", "测试设备", "04", "org", "org", "shop", "imei",
            "学校", "", "after", false, true, null, listOf(LifeSku("water-sku", "冷水", true)), emptyList())
        val water = WaterSession(device, "water-sku", WaterPhase.Running, orderNo = "water-order", sessionId = "water-session")
        val laundry = LaundrySession(device.copy(id = "laundry", category = "00", payment = "before"), "laundry-sku",
            "0.00", "2", LaundryPhase.Running, orderNo = "laundry-order", orderId = "laundry-id",
            startedAtMillis = now, expectedEndMillis = now + 300_000, sessionId = "laundry-session")
        val life = lifeDevicesViewModelFixture(app,
            fixtureLifeDeviceApi("test-secret", { error("No network in navigation fixtures") }),
            object : LifeDeviceStore {
                override fun account() = LifeAccount("fixture-life-owner", "fixture-token")
                override fun session() = water.takeIf { provider == LifeProvider.Panggui && feature == LifeFeature.Water }
                override fun laundrySessions() = listOf(laundry).filter { provider == LifeProvider.Panggui && feature == LifeFeature.Laundry }
                override fun saveAccount(account: LifeAccount) = Unit
                override fun saveSession(session: WaterSession) = Unit
                override fun saveLaundrySessions(sessions: List<LaundrySession>) = Unit
                override fun clearAccount() = Unit
                override fun clearSession() = Unit
            }, io = Dispatchers.Main.immediate)
        val huiSession = Hui798Session(Hui798Device("12345678", "测试用水"), WaterPhase.Running,
            now, true, now, now, sessionId = "hui-session")
        val hui = hui798ViewModelFixture(app, hui798ApiFixture(client = { error("No network in navigation fixtures") }),
            object : Hui798Store {
                override fun account() = Hui798Account("fixture-hui-owner", "fixture-token", "fixture-user")
                override fun sessions() = listOf(huiSession).filter { provider == LifeProvider.Hui798 }
                override fun saveAccount(account: Hui798Account) = Unit
                override fun saveSessions(sessions: List<Hui798Session>) = Unit
                override fun clearAccount() = Unit
            }, io = Dispatchers.Main.immediate)
        val bikeApi = FakeHellobikeService().apply {
            if (provider == LifeProvider.Hellobike) reading = HellobikeRideReading.Ride(
                fixtureBikeSession.rideGuid, fixtureBikeSession.bikeNo, 0, userGuid = fixtureBikeAccount.userGuid)
        }
        val bike = HellobikeViewModel(bikeApi,
            BikeRecords().store(sessions = listOf(fixtureBikeSession).filter { provider == LifeProvider.Hellobike }),
            FakeBikeLocation(), io = Dispatchers.Main.immediate, analytics = AnalyticsFixtures.from(app))
        fun install(model: ViewModel) = compose.activity.viewModelStore.put(
            "androidx.lifecycle.ViewModelProvider.DefaultKey:" + model.javaClass.name, model)
        install(life); install(hui)
        compose.activity.viewModelStore.put("hellobike-true", bike)
        compose.runOnIdle { bike.initialize(preview = false) }
        val settings = themeSettingsFixture(app).apply { setTheme(1); setNavTransitionStyle(2); setFloating(false) }
        val payment = paymentViewModelFixture(app).also { it.initialize(true); install(it) }
        val bills = billViewModelFixture(app).also { it.initialize(true); install(it) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payment, bills) } }
        compose.waitUntil(10_000) {
            !life.state.value.restoring && !hui.state.value.restoring && !bike.state.value.restoring &&
                compose.onAllNodesWithTag("life-status-${feature.name.lowercase()}").fetchSemanticsNodes().isNotEmpty()
        }
        frames(64)
        val navigation = ViewModelProvider(compose.activity)[AppNavigationViewModel::class.java]
        val card = compose.onNodeWithTag("payment-code-card", useUnmergedTree = true)
        fun cardLeft() = card.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).x
        val originalLeft = cardLeft()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("life-status-${feature.name.lowercase()}").performClick()
            frames(4)
            assertEquals("Expansion must keep the covered page still, instead of falling back to a slide",
                originalLeft, cardLeft(), 1f)
            val route = compose.runOnIdle { navigation.backStack.last() as AppRoute.LifeDevice }
            assertEquals(provider, route.provider)
            assertNotNull(route.account)
            if (provider == LifeProvider.Panggui) assertEquals(if (feature == LifeFeature.Water) water.skuId else laundry.skuId, route.skuId)
            frames(40)
            compose.runOnIdle {
                compose.activity.onBackPressedDispatcher.apply {
                    dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                    dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
                }
            }
            frames(2)
            assertEquals("Predictive return must retain the expansion source after route sync", originalLeft, cardLeft(), 1f)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
            frames(40)
            compose.runOnIdle { assertEquals(route, navigation.backStack.last()) }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            frames(9)
            assertEquals("Ordinary return must shrink to the island instead of using slide parallax", originalLeft, cardLeft(), 1f)
            frames(40)
            compose.runOnIdle { assertEquals(listOf(AppRoute.Main), navigation.backStack.toList()) }
            compose.onNodeWithTag("life-status-${feature.name.lowercase()}").assertIsEnabled()
            if (provider == LifeProvider.Panggui && feature == LifeFeature.Water) {
                compose.onNodeWithTag("life-status-water").performClick()
                frames(40)
                compose.runOnIdle {
                    compose.activity.onBackPressedDispatcher.apply {
                        dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                        dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
                    }
                }
                frames(2)
                compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
                frames(40)
                compose.runOnIdle { assertEquals(listOf(AppRoute.Main), navigation.backStack.toList()) }
                compose.runOnIdle { settings.setSwipeBack(false) }
                frames(2)
                compose.onNodeWithTag("life-status-water").performClick()
                frames(40)
                fun swipe() {
                    compose.onRoot().performTouchInput {
                        swipe(start = center.copy(x = width * .1f), end = center.copy(x = width * .9f), durationMillis = 400)
                    }
                    frames(40)
                }
                swipe()
                compose.runOnIdle { assertEquals("Disabled swipe must keep the expanded page", route, navigation.backStack.last()) }
                compose.runOnIdle { settings.setSwipeBack(true) }
                frames(2)
                swipe()
                compose.runOnIdle { assertEquals(listOf(AppRoute.Main), navigation.backStack.toList()) }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun frames(count: Int) = repeat(count) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    @Test fun lastRankingRowExpandsAndReturnsAfterItsSourceIncludesTheBottomInset() {
        val period = BillDateFilter(BillDateMode.Month, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        val stats = PreviewData.billStats(period, 2)
        val state = PreviewData.billState(period).copy(statsVisible = true, stats = stats)
        val routes = navBackStackOf(AppRoute.Main)
        compose.setContent {
            AppTheme(ColorSchemeMode.Dark) {
                NavDisplay(routes, Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface),
                    transition = NavTransitions.Expansion) {
                    entry<AppRoute.Main> {
                        LazyColumn(Modifier.fillMaxSize().testTag("bill-stats-list")) {
                            billStatsContent(stats, {}, {}, { routes.add(billDetailRoute(it, state)) },
                                includeTabs = false, detailRoute = { billDetailRoute(it, state) })
                        }
                    }
                    entry<AppRoute.BillDetail> {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
                    }
                }
            }
        }
        compose.onNodeWithTag("bill-stats-list").performScrollToIndex(3)
        val record = stats.ranking.last()
        val expected = billDetailRoute(record, state)
        val row = compose.onNode(hasText(record.resume) and hasClickAction())
        row.assertIsDisplayed().assertIsEnabled()
        val cardBounds = compose.onNodeWithTag("bill-stats-ranking").fetchSemanticsNode().boundsInRoot
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertEquals("The last clickable row must cover the card's bottom inset", cardBounds.bottom, rowBounds.bottom, 1f)
        val sampleX = cardBounds.center.x.toInt()
        val sampleY = (cardBounds.bottom - 2f).toInt()
        val idlePixel = compose.onRoot().captureToImage().toPixelMap()[sampleX, sampleY]
        val list = compose.onNodeWithTag("bill-stats-list")
        fun listLeft() = list.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).x
        val originalLeft = listLeft()
        compose.mainClock.autoAdvance = false
        try {
            row.performTouchInput { down(center) }
            frames(20)
            val pressedPixel = compose.onRoot().captureToImage().toPixelMap()[sampleX, sampleY]
            assertTrue("Press feedback must also shade the card's bottom inset: $idlePixel -> $pressedPixel",
                pressedPixel.red > idlePixel.red + .003f)
            row.performTouchInput { up() }
            frames(4)
            assertEquals("The ranking source must expand instead of falling back to a slide", originalLeft, listLeft(), 1f)
            compose.runOnIdle { assertEquals(expected, routes.last()) }
            frames(40)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            frames(9)
            assertEquals("Return must retain the ranking source", originalLeft, listLeft(), 1f)
            frames(40)
            compose.runOnIdle { assertEquals(listOf(AppRoute.Main), routes.toList()) }
            compose.onNode(hasText(record.resume) and hasClickAction()).assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun rankingSourceUsesTheStatisticsPeriodWhenItDiffersFromTheList() {
        val listPeriod = BillDateFilter(BillDateMode.Month, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))
        val statisticsPeriod = BillDateFilter(BillDateMode.Year, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        val state = PreviewData.billState(listPeriod).copy(statsVisible = true, stats = BillStatsState(period = statisticsPeriod))
        val record = state.items.first()
        assertEquals(BillPeriod.from(statisticsPeriod), billDetailRoute(record, state).bill.period)
        assertEquals(BillPeriod.from(listPeriod), billDetailRoute(record, state.copy(statsVisible = false)).bill.period)
    }
}
