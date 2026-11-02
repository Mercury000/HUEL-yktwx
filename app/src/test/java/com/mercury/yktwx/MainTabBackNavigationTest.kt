package com.mercury.yktwx

import android.content.Context
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    private lateinit var settings: ThemeSettings

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
        val payments = PaymentViewModel(compose.activity.application)
        val bills = BillViewModel(compose.activity.application)
        payments.initialize(true)
        bills.initialize(true)
        compose.setContent {
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
        compose.runOnIdle { settings.setPredictive(false) }
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

    @Test fun rootBackWorksWithPredictiveAnimationsDisabled() {
        compose.runOnIdle { settings.setPredictive(false) }
        for (tab in listOf("大厅", "账单", "我的")) {
            selectTab(tab)
            back()
            assertPaymentRoot()
        }
    }

    @Test fun secondaryPageBackReturnsToAboutBeforePayment() {
        for (predictive in listOf(true, false)) {
            compose.runOnIdle { settings.setPredictive(predictive) }
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

    @Test fun classicBarSlidesBackAfterPredictiveReturnAndStaysHiddenOnCancel() {
        selectTab("我的")
        val bar = compose.onNodeWithTag("main-bottom-navigation")
        fun barTop() = bar.fetchSemanticsNode().layoutInfo.coordinates.localToRoot(Offset.Zero).y
        val restingTop = barTop()
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        bar.assertDoesNotExist()
        compose.runOnIdle {
            val dispatcher = compose.activity.onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackCancelled()
        }
        compose.waitForIdle()
        bar.assertDoesNotExist()
        compose.onNodeWithText("主题").assertIsDisplayed()

        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                val dispatcher = compose.activity.onBackPressedDispatcher
                dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT))
                dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, .5f, BackEventCompat.EDGE_LEFT))
            }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeUntil(1_000) {
                compose.onAllNodesWithTag("main-bottom-navigation").fetchSemanticsNodes().isNotEmpty()
            }
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            val enteringTop = barTop()
            assertTrue("Classic navigation must enter from below its resting position", enteringTop > restingTop + 2f)
            compose.mainClock.advanceTimeBy(96)
            compose.waitForIdle()
            assertTrue("Classic navigation must move upward during its return", barTop() < enteringTop)
            compose.mainClock.advanceTimeBy(400)
            compose.waitForIdle()
            assertEquals("Classic navigation must return to its original position", restingTop, barTop(), 1f)
            compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun classicBarStaysComposedThroughOrdinaryPageReturn() {
        compose.runOnIdle { settings.setPredictive(false) }
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
