package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.data.ReadFailureKind
import com.mercury.yktwx.data.UpdateRelease
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.nav.rememberMiuixNavigationEffects
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.*
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeDeviceStatusUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private enum class NavigationPage : NavKey { Main, Detail }
    private data class Sessions(val water: WaterSession, val laundry: LaundrySession) {
        val clock: () -> Long = { laundry.startedAtMillis!! + 46_000 }
    }
    private fun sessions(): Sessions {
        val waterDevice = LifeDevice("water", "test-water", "雅5-1冷", "04", "org", "org", "shop", "imei",
            "学校", "", "after", false, true, null, listOf(LifeSku("water-sku", "冷水", true)), emptyList())
        val water = WaterSession(waterDevice, "water-sku", WaterPhase.Running, sessionId = "water-session")
        val laundryDevice = waterDevice.copy(id = "laundry", name = "77", category = "00", payment = "before",
            skus = listOf(LifeSku("laundry-sku", "桶自洁", true, "0.00", minutes = 5)))
        val started = 1_700_000_000_000L
        return Sessions(water, LaundrySession(laundryDevice, "laundry-sku", "0.00", "2", LaundryPhase.Running,
            startedAtMillis = started, expectedEndMillis = started + 300_000, sessionId = "laundry-session"))
    }
    private fun capture(name: String, settle: Boolean = true) {
        if (settle) compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }; compose.waitForIdle()
        val file = File("../tools/life-device-ui/islands-$name.png").absoluteFile
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
        compose.onNodeWithText("5分").performScrollTo().assertIsDisplayed()
        val status = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot
        val code = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot
        val navigation = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("Islands stay below the payment code", code.bottom <= status.top)
        assertTrue("Islands clear the navigation", status.bottom <= navigation.top)
        compose.onAllNodesWithTag("payment-scan-button").assertCountEquals(1)
        val scan = compose.onNodeWithTag("payment-scan-button").fetchSemanticsNode().boundsInRoot
        assertTrue(scan.center.x > status.center.x && scan.bottom <= status.top)
        compose.onNodeWithTag("payment-scan-button").performClick()
        assertEquals(1, scans)
        compose.onNodeWithTag("life-status-water").performScrollTo().performClick()
        compose.onNodeWithTag("life-status-laundry").performScrollTo().performClick()
        assertEquals(listOf(LifeFeature.Water to sessions.water.sessionId, LifeFeature.Laundry to sessions.laundry.sessionId), opened)
        capture("payment-progress-${if (dark) "dark-large" else "light"}")
        compose.runOnIdle { water = sessions.water.copy(phase = WaterPhase.Ended, finalized = true) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${sessions.water.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("dismiss-life-session-${sessions.water.sessionId}").assertDoesNotExist()
        capture("payment-completed-${if (dark) "dark-large" else "light"}")
        compose.onNodeWithText("已完成").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
    }
    @Test fun paymentShowsBothBottomIslandsAndOneScannerInTheTopRight() = payment()
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
    @Test fun paymentIslandsAnimateWithoutMovingTheCodeAndCenterTheLastSession() {
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
        val entering = compose.onNodeWithTag("life-session-${sessions.water.sessionId}").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.advanceTimeBy(500)
        val full = compose.onNodeWithTag("life-session-${sessions.water.sessionId}").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Appearance animates horizontally", entering > 0 && entering < full)
        val codeFull = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top
        assertEquals(originalCodeTop, codeFull, 1f)
        compose.runOnIdle { water = null }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        val shrinking = compose.onNodeWithTag("life-session-${sessions.water.sessionId}").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.advanceTimeBy(500)
        assertTrue(shrinking > 0 && shrinking < full)
        compose.onNodeWithTag("life-session-${sessions.water.sessionId}").assertDoesNotExist()
        val single = compose.onNodeWithTag("life-session-${sessions.laundry.sessionId}").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot
        assertEquals("Single island is centered", viewport.center.x, single.center.x, 1f)
        assertEquals(codeFull, compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top, 1f)
        compose.runOnIdle { laundry = null }
        compose.mainClock.advanceTimeBy(48)
        compose.onNodeWithTag("payment-life-status").assertExists()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
        assertEquals(originalCodeTop, compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
    @Test fun paymentAutomaticallyHidesEndedWaterEvenWhenAfterPayIsUnfinished() {
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
        compose.onNodeWithText("已完成").assertDoesNotExist()
        compose.onNodeWithTag("life-session-${running.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("dismiss-life-session-${running.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
        assertEquals(WaterPhase.Ended, waters.single().phase)
    }
    @Test fun closedLaundryAutomaticallyLeavesTheIslandsWhileUnfinishedWaterRemainsAccessible() {
        val sessions = sessions()
        val pending = sessions.laundry.copy(phase = LaundryPhase.Paying)
        val savedClosed = pending.copy(sessionId = "previously-closed-laundry", phase = LaundryPhase.Closed)
        var laundries by mutableStateOf(listOf(savedClosed, pending))
        var water by mutableStateOf(sessions.water)
        val opened = mutableListOf<Pair<LifeFeature, String>>()
        val dismissed = mutableListOf<String>()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            LifeDeviceStatusIslands(listOf(water), laundries,
                { feature, id -> opened.add(feature to id) }, { _, id -> dismissed.add(id) }, settings)
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${savedClosed.sessionId}").assertDoesNotExist()
        compose.onNodeWithText("待支付").assertIsDisplayed()
        compose.onNodeWithTag("life-status-laundry").performScrollTo().performClick()
        compose.runOnIdle { laundries = listOf(savedClosed, pending.copy(phase = LaundryPhase.Closed)) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${pending.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("dismiss-life-session-${pending.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("life-status-water").assertIsDisplayed().performClick()
        assertEquals(listOf(LifeFeature.Laundry to pending.sessionId, LifeFeature.Water to water.sessionId), opened)
        assertTrue(dismissed.isEmpty())
        assertEquals(2, laundries.size)
        compose.runOnIdle { water = water.copy(phase = WaterPhase.Ended) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
    }

    private fun laundryTime(value: String) = LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    @Test fun completedLaundryExpiresAtNextDayThreeEvenWhenItIsTheOnlyIsland() {
        val finished = sessions().laundry.copy(phase = LaundryPhase.Ended,
            finishedAtMillis = laundryTime("2026-11-07T23:50:00"))
        val expired = finished.copy(sessionId = "old-completion", finishedAtMillis = laundryTime("2026-11-06T23:50:00"))
        val laundries = listOf(expired, finished)
        val cutoff = laundryTime("2026-11-08T03:00:00")
        var clock = cutoff - 500
        val nowMillis = { clock }
        var dismissals = 0
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            LifeDeviceStatusIslands(emptyList(), laundries, { _, _ -> }, { _, _ -> dismissals++ }, settings,
                nowMillis = nowMillis)
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${expired.sessionId}").assertDoesNotExist()
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.runOnIdle { clock = cutoff }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
        assertEquals(0, dismissals)
        assertEquals(listOf(expired, finished), laundries)
    }

    @Test fun resumingAfterThreeHidesCompletionAndKeepsRunningLaundryAndWaterAccessible() {
        val sessions = sessions()
        val finished = sessions.laundry.copy(phase = LaundryPhase.Ended,
            finishedAtMillis = laundryTime("2026-11-07T20:00:00"))
        val running = sessions.laundry.copy(sessionId = "still-running", expectedEndMillis = laundryTime("2026-11-07T21:00:00"))
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        var enabled by mutableStateOf(false)
        var clock = laundryTime("2026-11-08T02:50:00")
        val nowMillis = { clock }
        val opened = mutableListOf<String>()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true, LocalLifecycleOwner provides owner) { AppTheme {
            LifeDeviceStatusIslands(listOf(sessions.water), listOf(finished, running), { _, id -> opened.add(id) },
                { _, _ -> }, settings, enabled = enabled, nowMillis = nowMillis)
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${finished.sessionId}").assertExists()
        compose.runOnIdle {
            owner.lifecycle.currentState = Lifecycle.State.STARTED
            clock = laundryTime("2026-11-08T03:01:00")
            owner.lifecycle.currentState = Lifecycle.State.RESUMED
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${finished.sessionId}").assertDoesNotExist()
        compose.runOnIdle { enabled = true }
        compose.onNodeWithTag("life-status-water").performScrollTo().performClick()
        compose.onNodeWithTag("life-status-laundry").performScrollTo().performClick()
        assertEquals(listOf(sessions.water.sessionId, running.sessionId), opened)
    }

    @Test fun paymentKeepsCompletedLaundryUntilDismissalAndExcludesCompletedWater() {
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
        compose.onNodeWithText("洗衣中").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("已完成").assertCountEquals(1)
        compose.onNodeWithTag("life-session-${water.sessionId}").assertDoesNotExist()
        compose.onNodeWithTag("dismiss-life-session-${running.sessionId}").assertDoesNotExist()
        compose.onNode(hasTestTag("life-status-laundry") and hasAnyAncestor(hasTestTag("life-session-${finished.sessionId}")))
            .performScrollTo().performClick()
        compose.onNodeWithTag("life-device-icon-${finished.sessionId}", useUnmergedTree = true)
            .assertContentDescriptionEquals("洗衣设备")
        compose.onNodeWithTag("life-progress-${finished.sessionId}", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
        assertEquals(listOf(LifeFeature.Laundry to finished.sessionId), opened)
        compose.onNodeWithTag("payment-scan-button").performClick(); assertEquals(1, scans)
        capture("payment-multiple-orders-and-completed")
        compose.onNodeWithTag("dismiss-life-session-${finished.sessionId}").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-session-${finished.sessionId}").assertDoesNotExist()
        compose.onNodeWithText("洗衣中").performScrollTo().assertIsDisplayed()
        assertEquals(1, opened.size)
        compose.onNodeWithText("已完成").assertDoesNotExist()
        capture("payment-after-dismiss")
    }
    @Test fun expiredEstimateDoesNotFillTheRingOrShowCompletionUntilTheServerEndsTheSession() {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        var laundry by mutableStateOf(sessions.laundry)
        val clock: () -> Long = { sessions.laundry.expectedEndMillis!! + 1000 }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    laundrySessions = listOf(laundry), lifeNowMillis = clock)
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("洗衣中").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertDoesNotExist()
        compose.onNodeWithTag("life-progress-${laundry.sessionId}", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
        compose.runOnIdle { laundry = laundry.copy(phase = LaundryPhase.Ended) }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.onNodeWithTag("life-device-icon-${laundry.sessionId}", useUnmergedTree = true)
            .assertContentDescriptionEquals("洗衣设备")
        compose.onNodeWithTag("life-progress-${laundry.sessionId}", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
        capture("laundry-completed-with-original-icon")
    }

    @Test fun coveredPaymentPageAndExitingIslandsCannotOpenOrDismissSessions() {
        val sessions = sessions()
        val laundry = sessions.laundry.copy(phase = LaundryPhase.Ended)
        var laundries by mutableStateOf(listOf(laundry))
        var active by mutableStateOf(false)
        var opened = 0
        var dismissed = 0
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    laundrySessions = laundries, isActive = active,
                    onOpenLifeSession = { _, _ -> opened++ }, onDismissLifeSession = { _, _ -> dismissed++ })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-status-laundry").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("dismiss-life-session-${laundry.sessionId}").assertIsNotEnabled().performClick()
        assertEquals(0, opened); assertEquals(0, dismissed)
        compose.runOnIdle { active = true }
        compose.onNodeWithTag("life-status-laundry").assertIsEnabled()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { laundries = emptyList() }
        repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("life-status-laundry").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("dismiss-life-session-${laundry.sessionId}").assertIsNotEnabled().performClick()
        assertEquals(0, opened); assertEquals(0, dismissed)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("payment-life-status").assertDoesNotExist()
    }

    @Test fun returningFromSecondaryPageMovesTheIslandTogetherWithClassicNavigation() =
        navigationReturn(false)

    @Test fun returningFromSecondaryPageMovesTheIslandTogetherWithFloatingNavigation() =
        navigationReturn(true)

    private fun navigationReturn(floating: Boolean) {
        val sessions = sessions()
        val backStack = navBackStackOf(NavigationPage.Main)
        val settings = ThemeSettings(compose.activity.application).apply {
            setTheme(1); setFloating(floating); setFloatingStyle(1)
        }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            NavDisplay(backStack, effects = rememberMiuixNavigationEffects()) {
                entry<NavigationPage>(swipeDismiss = NavSwipeDirection.None) { page ->
                    if (page == NavigationPage.Main) MainNavigationScaffold(settings, 0, {}) { padding ->
                        PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                            laundrySessions = listOf(sessions.laundry), isActive = backStack.size == 1, lifeNowMillis = sessions.clock)
                    } else Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        val island = compose.onNodeWithTag("life-session-${sessions.laundry.sessionId}", useUnmergedTree = true)
        val navigation = compose.onNodeWithTag("main-bottom-navigation", useUnmergedTree = true)
        val original = island.fetchSemanticsNode()
        val originalWidth = original.layoutInfo.coordinates.size.width
        fun origin(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().layoutInfo.coordinates
            .localToRoot(androidx.compose.ui.geometry.Offset.Zero)
        val islandOrigin = origin(island)
        val navigationOrigin = origin(navigation)
        compose.mainClock.autoAdvance = false
        try {
            fun frames(count: Int): Float {
                repeat(count) {
                    compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                    assertEquals("The covered island stays composed", original.id, island.fetchSemanticsNode().id)
                    assertEquals("Miuix page motion keeps the island at full size", originalWidth,
                        island.fetchSemanticsNode().layoutInfo.coordinates.size.width)
                    assertEquals("Navigation and island share the page's horizontal motion",
                        origin(navigation).x - navigationOrigin.x, origin(island).x - islandOrigin.x, 1f)
                    assertEquals("Page return has no custom vertical footer motion", islandOrigin.y, origin(island).y, 1f)
                }
                return origin(navigation).x - navigationOrigin.x
            }
            compose.runOnIdle { backStack.add(NavigationPage.Detail) }
            assertTrue("The covered page moves left with Miuix parallax", frames(8) < -1f)
            compose.onNodeWithTag("life-status-laundry").assertIsNotEnabled()
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            val coveredLeft = origin(navigation).x
            compose.runOnIdle { backStack.removeLastOrNull() }
            frames(8)
            assertTrue("Returning reveals navigation and island together", origin(navigation).x > coveredLeft)
            frames(32)
            assertEquals(navigationOrigin.x, origin(navigation).x, 1f)
            assertEquals(islandOrigin.x, origin(island).x, 1f)
            compose.onNodeWithTag("life-status-laundry").assertIsEnabled()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w360dp-h891dp-xxhdpi")
    fun twoIslandsUseTheAvailableWidthAndCloseSmoothlyIntoOneCenteredIsland() {
        val sessions = sessions()
        var waters by mutableStateOf(listOf(sessions.water))
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(false) }
        var density = 1f
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            density = LocalDensity.current.density
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    waterSessions = waters, laundrySessions = listOf(sessions.laundry), lifeNowMillis = sessions.clock,
                    onDismissLifeSession = { _, _ -> waters = emptyList() })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        fun bounds(id: String) = compose.onNodeWithTag("life-session-$id").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot
        val water = bounds(sessions.water.sessionId)
        val laundry = bounds(sessions.laundry.sessionId)
        assertTrue(water.left >= viewport.left && laundry.right <= viewport.right)
        assertEquals("The pair must be centered", viewport.center.x, (water.left + laundry.right) / 2, 1f)
        assertEquals(8f * density, laundry.left - water.right, 1f)
        assertTrue("Small phones should fit both readable islands", water.width >= 144f * density)
        val codeTop = compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { waters = listOf(sessions.water.copy(phase = WaterPhase.Ended)) }
        var previous = laundry.center.x
        var moved = false
        repeat(24) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val current = bounds(sessions.laundry.sessionId).center.x
            assertTrue("Remaining island moves continuously toward the center", current <= previous + 1f)
            assertTrue("Closing must not jump at the final removal", previous - current < 25f * density)
            if (previous - current > 1f) moved = true
            previous = current
        }
        assertTrue(moved)
        compose.onNodeWithTag("life-session-${sessions.water.sessionId}").assertDoesNotExist()
        assertEquals(viewport.center.x, previous, 1f)
        assertEquals(codeTop, compose.onNodeWithTag("payment-code-card").fetchSemanticsNode().boundsInRoot.top, 1f)
        capture("compact-pair-after-close", settle = false)
    }

    @Test @Config(qualifiers = "zh-rCN-w600dp-h891dp-xxhdpi")
    fun closingTheFirstMiddleOrLastIslandKeepsTheRemainingGroupCenteredWithoutASpacingJump() {
        val sessions = sessions()
        val original = (0..2).map { sessions.laundry.copy(sessionId = "laundry-$it", phase = LaundryPhase.Ended) }
        var laundries by mutableStateOf(original)
        val settings = ThemeSettings(compose.activity.application).apply { setTheme(1); setFloating(true) }
        var density = 1f
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            density = LocalDensity.current.density
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    laundrySessions = laundries, lifeNowMillis = sessions.clock,
                    onDismissLifeSession = { _, id -> laundries = laundries.filterNot { it.sessionId == id } })
            }
        } } }
        compose.mainClock.advanceTimeBy(1000); compose.mainClock.autoAdvance = false
        val viewport = compose.onNodeWithTag("payment-life-status").fetchSemanticsNode().boundsInRoot
        fun bounds(id: String) = compose.onNodeWithTag("life-session-$id").fetchSemanticsNode().boundsInRoot
        for (closedIndex in original.indices) {
            compose.runOnIdle { laundries = original }
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            val ordered = original.sortedBy { bounds(it.sessionId).left }
            assertEquals(viewport.center.x,
                (bounds(ordered.first().sessionId).left + bounds(ordered.last().sessionId).right) / 2, 1f)
            val closed = ordered[closedIndex]
            val remaining = original.filterNot { it == closed }
            var previous = remaining.associate { it.sessionId to bounds(it.sessionId).center.x }
            compose.onNodeWithTag("dismiss-life-session-${closed.sessionId}").performClick()
            repeat(24) { frame ->
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                val current = remaining.associate { it.sessionId to bounds(it.sessionId).center.x }
                remaining.forEach { session ->
                    assertTrue("Closing ${closed.sessionId} must smoothly reposition ${session.sessionId}",
                        kotlin.math.abs(current.getValue(session.sessionId) - previous.getValue(session.sessionId)) < 25f * density)
                    if (frame >= 14) assertEquals("Removing the retained exit item must not cause a final spacing jump",
                        previous.getValue(session.sessionId), current.getValue(session.sessionId), 2f * density)
                }
                previous = current
            }
            compose.onNodeWithTag("life-session-${closed.sessionId}").assertDoesNotExist()
            val settled = remaining.sortedBy { bounds(it.sessionId).left }
            assertEquals(viewport.center.x,
                (bounds(settled.first().sessionId).left + bounds(settled.last().sessionId).right) / 2, 1f)
            assertEquals(8f * density,
                bounds(settled.last().sessionId).left - bounds(settled.first().sessionId).right, 1f)
        }
        capture("centered-group-after-close", settle = false)
    }

    @Test fun islandMaterialFollowsBottomBlurAndIosGlassAndRestoresTheOpaqueSurface() {
        val sessions = sessions()
        val settings = ThemeSettings(compose.activity.application).apply {
            setTheme(1); setFloating(false); setBlurBottom(false)
        }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            BarBlurHost(settings.blurBottomBar.value, false) {
                Box(Modifier.fillMaxSize()) {
                    BarBackdropContent(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize()) {
                            listOf(Color(0xFFFF8C90), Color(0xFF89CAA3), Color(0xFF70A9FA)).forEach { color ->
                                Box(Modifier.weight(1f).fillMaxHeight().background(color))
                            }
                        }
                    }
                    LifeDeviceStatusIslands(emptyList(), listOf(sessions.laundry), { _, _ -> }, { _, _ -> }, settings,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(), nowMillis = sessions.clock)
                }
            }
        } } }
        fun image(): Bitmap {
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            compose.runOnIdle { compose.activity.window.decorView.invalidate() }
            return compose.onNodeWithTag("life-session-${sessions.laundry.sessionId}").captureToImage()
                .asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        }
        fun changed(a: Bitmap, b: Bitmap): Float {
            assertEquals(a.width, b.width); assertEquals(a.height, b.height)
            var changed = 0
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val first = a.getPixel(x, y); val second = b.getPixel(x, y)
                if (maxOf(kotlin.math.abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)),
                        kotlin.math.abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)),
                        kotlin.math.abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))) > 10) changed++
            }
            return changed.toFloat() / (a.width * a.height)
        }
        for (style in listOf(0, 1, 2)) {
            compose.runOnIdle { settings.setFloating(style != 0); settings.setFloatingStyle(if (style == 2) 1 else 0) }
            val opaque = image()
            val bounds = compose.onNodeWithTag("life-session-${sessions.laundry.sessionId}").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { settings.setBlurBottom(true) }
            assertTrue("Bottom material must visibly change for style $style", changed(opaque, image()) > .03f)
            assertEquals(bounds, compose.onNodeWithTag("life-session-${sessions.laundry.sessionId}").fetchSemanticsNode().boundsInRoot)
            capture("material-$style")
            compose.runOnIdle { settings.setBlurBottom(false) }
            assertTrue("Disabling blur must restore the same opaque surface", changed(opaque, image()) < .01f)
        }
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
}
