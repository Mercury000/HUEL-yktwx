package com.mercury.yktwx

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.PaymentAccount
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.MainNavigationScaffold
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.PaymentPage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainNavigationLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    private var density = 1f
    private val payment = PaymentUiState(needsLogin = false, username = "fake-user",
        account = PaymentAccount("fake-card", "fake-payacc", "1", "统一账户", 12860),
        code = "00000000000000000000", secondsLeft = 60)

    @Before fun setup() {
        compose.activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(compose.activity)
    }

    private fun content(page: @Composable (PaddingValues) -> Unit = {
        Box(Modifier.fillMaxSize().testTag("navigation-content"))
    }) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    density = LocalDensity.current.density
                    var selected by remember { mutableIntStateOf(0) }
                    MainNavigationScaffold(settings, selected, { selected = it }, content = page)
                }
            }
        }
    }

    @Test fun coveredBalanceScrollsAboveEveryBottomBarAndStillSupportsPullRefresh() {
        var refreshes = 0
        content { padding ->
            PaymentPage(payment, settings, padding.calculateBottomPadding() + 28.dp,
                onRefresh = { refreshes++ })
        }
        val list = compose.onNode(hasScrollToIndexAction() and
            hasAnyDescendant(hasTestTag("payment-code-card")))
        val balance = compose.onNodeWithText("¥ 128.60")
        val recharge = compose.onNodeWithText("充值")
        val title = compose.onNode(hasText("付款") and !isSelectable() and !hasAnyAncestor(isSelectable()))
        for (style in listOf(-1, 0, 1)) {
            compose.runOnIdle {
                settings.setFloating(style >= 0)
                if (style >= 0) settings.setFloatingStyle(style)
            }
            list.performScrollToIndex(0)
            val barTop = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot.top
            val titleTop = title.fetchSemanticsNode().boundsInRoot.top
            assertTrue("Fixture must reproduce a balance covered by the bar (style $style)",
                balance.fetchSemanticsNode().boundsInRoot.bottom > barTop)
            assertTrue("Bottom-bar padding must contribute to scroll range",
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
            // Start in page content: the full-bleed list also extends beneath the navigation bar.
            list.performTouchInput {
                swipe(start = center.copy(y = height * .65f), end = center.copy(y = height * .2f), durationMillis = 500)
            }
            assertTrue("Upward touch must actually scroll the payment list (style $style)",
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
            assertTrue("Balance must clear the bar after scrolling",
                balance.fetchSemanticsNode().boundsInRoot.bottom <= barTop)
            assertTrue("Recharge must clear the bar after scrolling",
                recharge.fetchSemanticsNode().boundsInRoot.bottom <= barTop)
            assertEquals("Payment title stays fixed", titleTop, title.fetchSemanticsNode().boundsInRoot.top, 1f)
            list.performScrollToIndex(0)
            list.performTouchInput {
                swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
            }
            compose.waitForIdle()
            assertEquals("Pull refresh remains usable (style $style)", style + 2, refreshes)
        }
    }

    @Test fun portraitPhoneUsesBottomNavigation() {
        content()
        compose.onNodeWithTag("main-navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("main-bottom-navigation").assertExists()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w700dp-h500dp-xhdpi")
    fun landscapeUsesRailAndFloatingModesStayCentered() {
        content()
        assertRail(expanded = false)
        for (style in 0..1) {
            compose.runOnIdle { settings.setFloating(true); settings.setFloatingStyle(style) }
            compose.onNodeWithTag("main-navigation-rail").assertDoesNotExist()
            val bar = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot
            val tabs = compose.onAllNodes(isSelectable()).fetchSemanticsNodes().map { it.boundsInRoot }
            assertEquals(4, tabs.size)
            val left = tabs.minOf { it.left }
            val right = tabs.maxOf { it.right }
            assertTrue("Floating pill must stay compact on wide windows", right - left <= 400f * density)
            assertEquals("Floating pill must be centered", bar.center.x, (left + right) / 2f, 2f)
        }
        compose.runOnIdle { settings.setFloating(false) }
        assertRail(expanded = false)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w700dp-h1000dp-xhdpi")
    fun mediumPortraitKeepsBottomNavigation() {
        content()
        compose.onNodeWithTag("main-navigation-rail").assertDoesNotExist()
        compose.onNodeWithTag("main-bottom-navigation").assertExists()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w900dp-h1100dp-xhdpi")
    fun widePortraitUsesCollapsedRail() {
        content()
        assertRail(expanded = false)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
    fun largeWindowUsesExpandedRail() {
        content()
        assertRail(expanded = true)
    }

    private fun assertRail(expanded: Boolean) {
        compose.onNodeWithTag("main-bottom-navigation").assertDoesNotExist()
        val rail = compose.onNodeWithTag("main-navigation-rail").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("navigation-content").fetchSemanticsNode().boundsInRoot
        assertEquals("Content must start beside the rail", rail.right, page.left, 1f)
        assertEquals("Rail must fill page height", page.height, rail.height, 1f)
        assertEquals("Only large windows expand the rail", expanded, rail.width / density >= 200f)
        val tabs = compose.onAllNodes(isSelectable()).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(4, tabs.size)
        assertTrue("Rail tabs must be stacked vertically", tabs.zipWithNext().all { (before, after) -> before.bottom <= after.top })
        compose.onNode(hasText("账单") and isSelectable()).performClick().assertIsSelected()
    }
}
