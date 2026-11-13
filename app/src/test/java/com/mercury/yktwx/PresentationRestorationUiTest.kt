package com.mercury.yktwx

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresentationRestorationUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun updateNotesScrollWaitsForRestoredDetailsWithoutDownloading() {
        val restorer = StateRestorationTester(compose)
        val release = UpdateRelease(10, "1.5.0", (1..60).joinToString("\n\n") { "第 $it 项更新内容" }, "https://github.com/release")
        var recreated = false
        lateinit var state: MutableState<AppUpdateUiState>
        var downloads = 0
        restorer.setContent { AppTheme { Scaffold {
            val live = remember { mutableStateOf(AppUpdateUiState(release = release, visible = !recreated)) }
            SideEffect { state = live }
            AppUpdateDialog(live.value, onDownload = { downloads++ }, onDismiss = {})
        } } }
        fun scroll() = compose.onNodeWithTag("app-update-notes").fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNodeWithTag("app-update-notes").performTouchInput { swipeUp() }
        val before = scroll()
        assertTrue(before > 0f)
        recreated = true
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("app-update-notes").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(visible = true) }
        assertEquals(before, scroll(), .001f)
        assertEquals(0, downloads)
    }

    @Test fun expandedThemeChoiceRestoresWithoutWritingASetting() {
        val restorer = StateRestorationTester(compose)
        var writes = 0
        restorer.setContent { AppTheme { Scaffold {
            RestorableDropdownPreference("显示模式", listOf("自动", "浅色", "深色"), 0, { writes++ })
        } } }
        compose.onNodeWithText("显示模式").performClick()
        compose.onNodeWithText("深色").assertIsDisplayed()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("深色").assertIsDisplayed()
        assertEquals(0, writes)
        compose.onNodeWithText("深色").performClick()
        assertEquals(1, writes)
    }

    @Test fun chartRestoresOriginalDateAndExpandedCategoriesAndHidesMissingDate() {
        val restorer = StateRestorationTester(compose)
        val date = LocalDate.of(2026, 11, 13)
        var points by mutableStateOf(listOf(BillDailyPoint(date, 1234)))
        restorer.setContent { AppTheme { Column {
            DailyBillChart(points, BillDateMode.Month)
            CategoryDonutChart((1..7).map { BillCategory("分类$it", it * 100L) }, 2)
        } } }
        compose.onNodeWithText("查看全部 7 项").performClick()
        compose.onNodeWithTag("bill-trend-plot").performTouchInput { click(center) }
        compose.onNodeWithTag("bill-trend-selection").assertTextContains("12.34", substring = true)
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bill-trend-selection").assertTextContains("12.34", substring = true)
        compose.onNodeWithText("收起分类").assertIsDisplayed()
        compose.onNodeWithText("分类7").assertIsDisplayed()
        compose.runOnIdle { points = listOf(BillDailyPoint(date.plusDays(1), 4567)) }
        compose.onNodeWithTag("bill-trend-selection").assertDoesNotExist()
        compose.onNodeWithTag("bill-trend-plot").assertContentDescriptionEquals("收支趋势")
    }

    @Test fun lifeLogoutConfirmationWaitsForStoreAndRejectsReplacementOwner() {
        val restorer = StateRestorationTester(compose)
        val settings = themeSettingsFixture(compose.activity.application)
        val original = ManagedLifeAccount(LifeProvider.Panggui,
            LifeAccountConnection(status = LifeAccountStatus.Verified, phone = "原账户"), owner = "original")
        var account by mutableStateOf(original)
        var removed = 0
        restorer.setContent { AppTheme { Scaffold {
            AppSettingsContent(Modifier, PaddingValues(0.dp), settings, AppUpdateUiState(), {}, {},
                listOf(account), true, {}, {}, {}, { removed++ })
        } } }
        compose.onNodeWithTag("panggui-account-row").performClick()
        compose.onNodeWithTag("life-account-exit").assertIsDisplayed()
        compose.runOnIdle { account = original.copy(restoring = true, owner = null) }
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("life-account-exit").assertDoesNotExist()
        assertEquals(0, removed)
        compose.runOnIdle { account = original }
        compose.onNodeWithTag("life-account-exit").assertIsDisplayed()
        compose.runOnIdle { account = original.copy(owner = "replacement") }
        compose.onNodeWithTag("life-account-exit").assertDoesNotExist()
        compose.runOnIdle { account = original }
        compose.onNodeWithTag("life-account-exit").assertDoesNotExist()
        assertEquals(0, removed)
    }
}
