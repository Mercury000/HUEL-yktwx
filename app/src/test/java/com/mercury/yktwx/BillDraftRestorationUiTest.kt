package com.mercury.yktwx

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.BillDateFilter
import com.mercury.yktwx.data.BillDateMode
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillDraftRestorationUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun filterAndDateDraftsRemainOpenAfterRestorationAndRequireConfirmation() {
        val restorer = StateRestorationTester(compose)
        val settings = themeSettingsFixture(compose.activity.application)
        var applied = 0
        var chosen: BillDateFilter? = null
        restorer.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            BillPage(BillUiState(loaded = true, amountFromCents = 1000), settings, {}, {}, {}, { chosen = it },
                { _, min, max -> assertEquals(2000L, min); assertEquals(9000L, max); applied++ }, {}, {}, {}, showBack = false)
        } } } }
        compose.onNodeWithContentDescription("筛选账单").performClick()
        compose.onNodeWithText("最低").performTextReplacement("20")
        compose.onNodeWithText("最高").performTextReplacement("90")
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("交易类型").assertIsDisplayed()
        compose.onNodeWithText("最低").assertTextContains("20")
        compose.onNodeWithText("最高").assertTextContains("90")
        assertEquals(0, applied)
        compose.onNodeWithText("应用").performClick()
        assertEquals(1, applied)
        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.onNodeWithText("日期范围").performClick()
        compose.onNodeWithText("自定义时间段").performClick()
        compose.onNodeWithText("结束").performClick()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("选择日期").assertIsDisplayed()
        compose.onNodeWithText("开始").assertExists()
        compose.onNodeWithText("结束").assertExists()
        assertNull(chosen)
        compose.onNodeWithText("确定").performClick()
        assertEquals(BillDateMode.Range, chosen?.mode)
    }
}
