package com.mercury.yktwx

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainTabBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration by lazy { StateRestorationTester(compose) }
    private lateinit var settings: ThemeSettings

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
        val payments = PaymentViewModel(compose.activity.application)
        val bills = BillViewModel(compose.activity.application)
        payments.initialize(true)
        bills.initialize(true)
        restoration.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                YktApp(settings, payments, bills)
            }
        }
        compose.waitForIdle()
    }

    private fun selectTab(label: String) {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
        compose.waitForIdle()
    }

    private fun back(commitGesture: Boolean = false) {
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            if (commitGesture) {
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
            }
            dispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun assertPaymentRoot() {
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
        compose.runOnIdle {
            // Payment still delegates back to the Activity rather than consuming it.
            assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
            assertFalse(compose.activity.isFinishing)
        }
    }

    @Test fun hallBackGestureReturnsToPayment() {
        selectTab("大厅")
        compose.onNodeWithText("水电费").assertIsDisplayed()
        back(commitGesture = true)
        assertPaymentRoot()
    }

    @Test fun feeSheetBackReturnsToHallBeforePayment() {
        selectTab("大厅")
        compose.onNodeWithTag("network-recharge").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("校园网充值").assertIsDisplayed()
        back()
        compose.onNodeWithText("水电费").assertIsDisplayed()
        compose.onNode(hasText("大厅") and hasClickAction()).assertIsSelected()
        back()
        assertPaymentRoot()
    }

    @Test fun billBackGestureReturnsToPayment() {
        selectTab("账单")
        compose.onNodeWithText("统计").assertIsDisplayed()
        back(commitGesture = true)
        assertPaymentRoot()
    }

    @Test fun aboutBackGestureReturnsToPayment() {
        selectTab("我的")
        compose.onNodeWithText("应用设置").assertIsDisplayed()
        back(commitGesture = true)
        assertPaymentRoot()
    }

    @Test fun cancelledBackGestureKeepsCurrentTab() {
        for (tab in listOf("大厅", "账单", "我的")) {
            selectTab(tab)
            compose.runOnIdle {
                val dispatcher = compose.activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, 0.5f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
            compose.onNode(hasText(tab) and hasClickAction()).assertIsSelected()
            compose.onNodeWithContentDescription("付款条形码").assertIsNotDisplayed()
        }
    }

    @Test fun rootBackWorksWithSwipeBackDisabled() {
        for (tab in listOf("大厅", "账单", "我的")) {
            selectTab(tab)
            back()
            assertPaymentRoot()
        }
    }

    @Test fun secondaryPageBackReturnsToAboutBeforePayment() {
        for (swipeBack in listOf(true, false)) {
            compose.runOnIdle { settings.setSwipeBack(swipeBack) }
            selectTab("我的")
            compose.onNodeWithText("应用设置").performScrollTo().performClick()
            compose.onNodeWithText("主题").assertIsDisplayed()
            back()
            compose.onNodeWithText("应用设置").assertIsDisplayed()
            compose.onNodeWithContentDescription("付款条形码").assertIsNotDisplayed()
            back()
            assertPaymentRoot()
        }
    }

    @Test fun predictiveCancelRestoresSettingsAndCommitReturnsToTheSameTab() {
        selectTab("我的")
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        val page = compose.onNodeWithTag("settings-page-list")
        fun pageLeft() = page.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).x
        val restingLeft = pageLeft()
        val pageWidth = page.fetchSemanticsNode().layoutInfo.coordinates.size.width
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertEquals("Miuix predictive back follows the full page width", pageWidth * .5f,
            pageLeft() - restingLeft, 1f)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Cancelling restores the original page position", restingLeft, pageLeft(), 1f)
        compose.onNodeWithText("主题").assertIsDisplayed()
        back(commitGesture = true)
        compose.onNodeWithText("主题").assertDoesNotExist()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("main-bottom-navigation").assertIsDisplayed()
    }

    @Test fun nestedPagesReturnOneAtATimeBeforeReturningToPayment() {
        selectTab("我的")
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.swipe_back)).assertExists()
        back(commitGesture = true)
        compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
        back()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
        back()
        assertPaymentRoot()
    }

    @Test fun rapidMainTabChangesSettleOnTheLastSelectedPage() {
        compose.mainClock.autoAdvance = false
        for (label in listOf("我的", "大厅", "账单")) {
            compose.onNode(hasText(label) and hasClickAction()).performClick()
            compose.mainClock.advanceTimeBy(80)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNode(hasText("账单") and isSelectable()).assertIsSelected()
        compose.onNodeWithTag("bill-content-viewport").assertIsDisplayed()
        back()
        assertPaymentRoot()
    }

    @Test fun enablingSwipeBackReturnsToThePreviousPage() {
        compose.runOnIdle { settings.setSwipeBack(true) }
        selectTab("我的")
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onRoot().performTouchInput {
            swipe(start = center.copy(x = width * .1f), end = center.copy(x = width * .9f), durationMillis = 400)
        }
        compose.waitForIdle()
        compose.onNodeWithText("主题").assertDoesNotExist()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
    }

    @Test fun transitionStyleSelectionKeepsTheThemePageAndAppliesToItsReturn() {
        selectTab("我的")
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        val title = compose.activity.getString(R.string.nav_transition_style)
        val list = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(title)))
        val originalId = list.fetchSemanticsNode().id
        compose.onNodeWithText(title).performScrollTo().performClick()
        compose.onNodeWithText("AOSP").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, settings.navTransitionStyle.value) }
        assertEquals("Changing style must retain the theme page", originalId, list.fetchSemanticsNode().id)
        compose.onNodeWithText("AOSP").assertIsDisplayed()
        back()
        compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
        back()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
    }

    @Test fun aospPredictiveReturnUsesACardAndRestoresItOnCancel() {
        compose.runOnIdle { settings.setNavTransitionStyle(1) }
        selectTab("我的")
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        val page = compose.onNodeWithTag("settings-page-list")
        fun drawnWidth(): Float {
            val coordinates = page.fetchSemanticsNode().layoutInfo.coordinates
            return coordinates.localToRoot(Offset(coordinates.size.width.toFloat(), 0f)).x -
                coordinates.localToRoot(Offset.Zero).x
        }
        val originalWidth = drawnWidth()
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                val dispatcher = compose.activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            assertTrue("AOSP predictive back shrinks the page into a card", drawnWidth() < originalWidth * .95f)
            assertTrue("The card stays within the demo scale range", drawnWidth() >= originalWidth * .9f - 1f)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled() }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
        assertEquals("Cancelling restores the page to full width", originalWidth, drawnWidth(), 1f)
        compose.onNodeWithText("主题").assertIsDisplayed()
        back(commitGesture = true)
        compose.onNodeWithText("主题").assertDoesNotExist()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
    }

    @Test fun savedStateRestoresTheNestedNavigationStack() {
        selectTab("我的")
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(compose.activity.getString(R.string.swipe_back)).assertIsDisplayed()
        back()
        compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
        back()
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
    }

    @Test fun classicBarStaysComposedThroughOrdinaryPageReturn() {
        selectTab("我的")
        val bar = compose.onNodeWithTag("main-bottom-navigation")
        val originalId = bar.fetchSemanticsNode().id
        fun barLeft() = bar.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).x
        val restingLeft = barLeft()
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        assertEquals("Ordinary back keeps navigation with the covered page", originalId, bar.fetchSemanticsNode().id)
        val coveredLeft = barLeft()
        assertTrue("Classic navigation must move with the covered main page", coveredLeft < restingLeft - 1f)
        compose.mainClock.autoAdvance = false
        try {
            back()
            compose.mainClock.advanceTimeBy(144)
            compose.waitForIdle()
            assertTrue("Ordinary return must animate the existing bar with the main page", barLeft() > coveredLeft)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        bar.assertIsDisplayed()
        assertEquals("Ordinary return must preserve the navigation instance", originalId, bar.fetchSemanticsNode().id)
        assertEquals("Ordinary return must restore the original position", restingLeft, barLeft(), 1f)
        compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
    }
}
