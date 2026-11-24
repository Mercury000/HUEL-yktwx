package com.mercury.yktwx

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.DormRoom
import com.mercury.yktwx.data.DormMeterKind
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.HallRoomSheet
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.*
import org.junit.After
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
class HallRestorationUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @After fun cleanup() { compose.activity.viewModelStore.clear() }

    @Test fun shellKeepsTheClickedMeterTitleWhenItsCompositionIsRestored() {
        val restorer = StateRestorationTester(compose)
        val app = compose.activity.application
        val settings = themeSettingsFixture(app).apply { setTheme(1); setFloating(false) }
        val payments = paymentViewModelFixture(app).apply { initialize(true) }
        val bills = billViewModelFixture(app).apply { initialize(true) }
        restorer.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) } }
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        for (kind in listOf(DormMeterKind.Lighting, DormMeterKind.AirConditioning)) {
            val title = "${kind.label}充值"
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithTag("hall-payment-content").assertIsDisplayed()
            compose.onAllNodesWithText(title).assertCountEquals(2)
            restorer.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("hall-payment-content").assertIsDisplayed()
            compose.onAllNodesWithText(title).assertCountEquals(2)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("hall-payment-content").assertDoesNotExist()
        }
    }

    @Test fun hallEntryTemporaryTabAndAddPageSurviveRecreation() {
        val restorer = StateRestorationTester(compose)
        val settings = themeSettingsFixture(compose.activity.application)
        restorer.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            HallPage(settings, 0.dp, dorm = PreviewData.hallState(multiple = true), isActive = true)
        } } } }
        compose.onNodeWithText("切换").performClick()
        compose.onNodeWithText("临时缴费").performClick()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("切换房间 / 临时缴费").assertIsDisplayed()
        compose.onNodeWithText("郑东宿舍电费").assertIsDisplayed()
        compose.onNodeWithText("房间管理").performClick()
        compose.onNodeWithText("新增绑定").performClick()
        restorer.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("新增绑定").assertIsDisplayed()
        compose.onNodeWithText("房间管理").assertDoesNotExist()
    }

    @Test fun removalRestoresByIdentityAfterReloadAndMissingRoomClosesConfirmation() {
        val restorer = StateRestorationTester(compose)
        var state by mutableStateOf(PreviewData.hallState(multiple = true))
        var removed: DormRoom? = null
        restorer.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            HallRoomSheet(state, true, {}, {}, { removed = it }, {}, {})
        } } } }
        compose.onAllNodesWithText("解绑").onLast().performClick()
        compose.onNodeWithText("解绑房间").assertIsDisplayed()
        compose.runOnIdle { state = DormUiState(loading = true) }
        restorer.emulateSavedInstanceStateRestore()
        assertNull(removed)
        compose.runOnIdle { state = PreviewData.hallState(multiple = true) }
        compose.onNodeWithText("解绑房间").assertIsDisplayed()
        assertNull(removed)
        compose.onAllNodesWithText("解绑").onLast().performClick()
        assertSame(state.rooms.last(), removed)
        compose.onAllNodesWithText("解绑").onFirst().performClick()
        compose.runOnIdle { removed = null; state = DormUiState(loading = true) }
        restorer.emulateSavedInstanceStateRestore()
        compose.runOnIdle { state = DormUiState(loaded = true, roomsLoaded = true) }
        compose.onNodeWithText("解绑房间").assertDoesNotExist()
        assertNull(removed)
    }
}
