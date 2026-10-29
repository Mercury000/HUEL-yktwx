package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native Compose rendering plus entry checks for the redesigned dashboard. All data are fictitious. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        compose.waitForIdle()
        val output = File("../tools/dashboard-preview/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun dashboardScreensAndExactDormTargets() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(app)
        settings.setTheme(1)
        settings.setFloating(true)
        settings.setFloatingStyle(1)
        val payment = PaymentViewModel(app)
        val bills = BillViewModel(app)
        payment.initialize(true); bills.initialize(true)
        val fee = ViewModelProvider(compose.activity)[FeeViewModel::class.java]
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payment, bills) }
        }
        fun select(label: String) {
            compose.onNode(hasText(label) and hasClickAction()).performClick()
            compose.waitForIdle()
        }
        capture("payment-light")
        select("大厅")
        capture("hall-light")
        compose.onNodeWithText("照明充值").performClick()
        compose.waitForIdle()
        assertEquals("demo-A-Lighting", fee.state.value.selections["room"])
        compose.onNode(hasText("照明充值") and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("自定义金额（元）").assertIsDisplayed()
        compose.onNodeWithText("校区").assertDoesNotExist()
        compose.onNodeWithText("保存房间").assertDoesNotExist()
        compose.onNodeWithTag("hall-payment-target").assertIsDisplayed()
        capture("lighting-fee")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("空调充值").performClick()
        compose.waitForIdle()
        assertEquals("demo-A-AirConditioning", fee.state.value.selections["room"])
        compose.onNode(hasText("空调充值") and !hasClickAction()).assertIsDisplayed()
        compose.onNodeWithTag("hall-payment-target").assertIsDisplayed()
        compose.onNodeWithText("自定义金额（元）").assertIsDisplayed()
        capture("air-conditioning-fee")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        select("账单")
        capture("bill-light")
        compose.onNodeWithContentDescription("筛选账单").performClick()
        capture("bill-filter")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("统计").performClick()
        assertTrue(bills.state.value.statsVisible)
        capture("stats-light")
        compose.onNodeWithTag("bill-trend-plot").performTouchInput { click(center) }
        capture("stats-selection-light")
        compose.onNodeWithText("每日趋势").performTouchInput { click() }
        compose.onNodeWithTag("bill-trend-selection").assertDoesNotExist()
        compose.onNodeWithText("明细").performClick()
        compose.onNodeWithContentDescription("选择日期").performClick()
        capture("bill-date-picker")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        select("我的")
        capture("mine-light")
        compose.onNodeWithText("账户详情").performClick()
        compose.onNodeWithText("充值").performTouchInput { click(center) }
        compose.waitForIdle()
        compose.onNodeWithText("卡片充值").assertIsDisplayed()
        compose.onNodeWithText("自定义金额（元）").performTouchInput { click(center) }
        compose.onNode(hasSetTextAction() and hasText("自定义金额（元）")).assertIsFocused()
        capture("account-recharge")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("卡片充值").assertDoesNotExist()
        compose.onNodeWithText("账户").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("关于财小通").performScrollTo()
        capture("mine-footer-light")
        compose.onNodeWithContentDescription("个人信息").performScrollTo()
        compose.onNodeWithContentDescription("个人信息").performClick()
        capture("profile-light")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("主题").assertIsDisplayed()
        capture("settings-light")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.runOnIdle { settings.setTheme(2) }
        capture("mine-dark")
        select("大厅"); capture("hall-dark")
        select("账单"); capture("bill-dark")
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithTag("bill-trend-plot").performTouchInput { click(center) }
        capture("stats-selection-dark")
    }
    @Test fun restrictedMineStatusHasDistinctAccent() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(app)
        settings.setTheme(1); settings.setFloating(true); settings.setFloatingStyle(1)
        val payment = PaymentViewModel(app)
        val bills = BillViewModel(app)
        payment.initialize(true, previewLost = true); bills.initialize(true)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payment, bills) }
        }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        capture("mine-lost-light")
        compose.runOnIdle { settings.setTheme(2) }
        capture("mine-lost-dark")
    }
}
