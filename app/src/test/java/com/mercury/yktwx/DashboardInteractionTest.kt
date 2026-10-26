package com.mercury.yktwx

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.BillDateSelector
import com.mercury.yktwx.ui.components.BillListMinimumDate
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.pages.MyPage
import com.mercury.yktwx.ui.pages.PersonalProfilePage
import com.mercury.yktwx.ui.pages.DormFeeDialog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    private val verticalList = hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @Before fun setup() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(app)
    }

    private fun content(page: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) { page() }
            }
        }
    }

    private fun pull() {
        compose.onNode(verticalList).performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
        }
        compose.waitForIdle()
    }

    @Test fun datePressAndClickStayInsideTextAndChevron() {
        content {
            Scaffold {
                Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(16.dp)) {
                    BillDateSelector(BillDateFilter.currentMonth(), BillListMinimumDate, {})
                }
            }
        }
        val target = compose.onNodeWithContentDescription("选择日期")
        val bounds = target.fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        assertTrue("Date target must not occupy the whole row", bounds.width < rootBounds.width * .5f)
        root.performTouchInput { click(Offset(rootBounds.width * .75f, bounds.center.y)) }
        compose.onNodeWithText("选择日期").assertDoesNotExist()

        val before = root.captureToImage().asAndroidBitmap()
        compose.mainClock.autoAdvance = false
        target.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(300)
        val pressed = root.captureToImage().asAndroidBitmap()
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before.getPixel(x, y) != pressed.getPixel(x, y)) {
                changed++
                assertTrue("Date press escaped the text/chevron bounds at ($x, $y)",
                    x >= bounds.left - 2 && x <= bounds.right + 2 && y >= bounds.top - 2 && y <= bounds.bottom + 2)
            }
        }
        assertTrue("Date selector must show press feedback", changed > 0)
        target.performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        target.performClick()
        compose.onNodeWithText("选择日期").assertIsDisplayed()
        compose.onNodeWithText("上一时间段").assertDoesNotExist()
        compose.onNodeWithText("下一时间段").assertDoesNotExist()
    }

    @Test fun mineScrollsDuringBackgroundReadAndPullsAgainAfterCompletion() {
        val loading = mutableStateOf(true)
        var refreshes = 0
        content {
            MyPage(MyUiState(loading = loading.value), PaymentUiState(), settings, 100.dp,
                isActive = true, onActivate = {}, onRefresh = { refreshes++; loading.value = true },
                onProfile = {}, onAccount = {}, onBills = {}, onRecharge = {}, rechargeLoading = false,
                onCardAction = {}, cardBusy = false, onSettings = {}, onAbout = {})
        }
        val list = compose.onNode(verticalList)
        list.performTouchInput { swipeUp() }
        assertTrue("Background read must allow a real scroll gesture", list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        compose.onNodeWithText("关于财小通").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { loading.value = false }
        list.performScrollToIndex(0)
        pull()
        assertEquals(1, refreshes)
        compose.runOnIdle { loading.value = false }
        compose.waitForIdle()
        pull()
        assertEquals(2, refreshes)
    }

    @Test fun billsScrollDuringBackgroundReadAndPullDetailsAndStats() {
        val model = BillViewModel(compose.activity.application).apply { initialize(true) }
        val state = mutableStateOf(model.state.value.copy(loading = true))
        var refreshes = 0
        content {
            BillPage(state.value, settings, {}, {}, { refreshes++ }, {}, {}, { _, _, _ -> }, {},
                onOpenStats = {}, onOpenDetail = {}, showBack = false)
        }
        val list = compose.onNode(verticalList)
        list.performTouchInput { swipeUp() }
        assertTrue("Background bill read must not consume all scroll", list.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        compose.runOnIdle { state.value = state.value.copy(loading = false) }
        list.performScrollToIndex(0)
        pull()
        assertEquals(1, refreshes)
        compose.runOnIdle { state.value = state.value.copy(statsVisible = true) }
        pull()
        assertEquals(2, refreshes)
    }

    @Test fun personalInformationSupportsPullRefreshWithOfficialFields() {
        var refreshes = 0
        content {
            PersonalProfilePage(MyUiState(profile = PersonalProfile("示例同学", "202600000001", "示例部门", "示例专业", null)),
                settings, {}, { refreshes++ })
        }
        compose.onNodeWithText("202600000001").assertIsDisplayed()
        compose.onNodeWithText("部门").assertIsDisplayed()
        compose.onNodeWithText("专业").assertDoesNotExist()
        compose.onNodeWithText("示例专业").assertDoesNotExist()
        pull()
        assertEquals(1, refreshes)
    }

    @Test fun searchUsesMiuixGreyAndReleasesFocusOnOutsideTouchScrollAndSubmit() {
        val model = BillViewModel(compose.activity.application).apply { initialize(true) }
        val initial = model.state.value
        var submitted: String? = null
        var expectedBackground = 0
        content {
            expectedBackground = MiuixTheme.colorScheme.secondaryContainer.toArgb()
            BillPage(initial, settings, {}, {}, {}, {}, {}, { _, _, _ -> }, { submitted = it },
                onOpenStats = {}, onOpenDetail = {}, showBack = false)
        }
        val search = compose.onNodeWithTag("bill-search")
        val bitmap = search.captureToImage().asAndroidBitmap()
        assertEquals(expectedBackground, bitmap.getPixel(bitmap.width / 2, 5))
        search.performTouchInput { click(center) }
        search.assertIsFocused()
        val bounds = search.fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(bounds.center.x, bounds.bottom + 15f)) }
        search.assertIsNotFocused()
        search.performClick().assertIsFocused()
        compose.onNodeWithText("账单").performTouchInput { click(center) }
        search.assertIsNotFocused()
        search.performClick().performTextInput("餐厅")
        search.performImeAction()
        assertEquals("餐厅", submitted)
        search.assertIsNotFocused()
        search.performClick().assertIsFocused()
        compose.onNode(verticalList).performTouchInput { swipeUp() }
        search.assertIsNotFocused()
    }

    @Test fun dormMeterStartsSelectedAndAmountConfirmationRequiresSelection() {
        val initial = FeeUiState(config = FeeConfiguration(FeeProject.Zhengdong, "郑东电费",
            RechargeConfiguration("410", "", "", 0, listOf(1000), 1, 10000, true, false)),
            levels = listOf(FeeLevel("room", 1, "房间")), selections = mapOf("room" to "demo-light"),
            names = mapOf("room" to "示例A栋房间A照明"), query = FeeQuery(thirdParty = "{}"))
        var submitted: Long? = null
        content {
            Scaffold {
                DormFeeDialog(initial, true, "照明缴费", {}, { submitted = it }, {}, { _, _ -> }, {}, {}, {})
            }
        }
        val target = compose.onNodeWithTag("dorm-payment-target")
        target.assertIsSelected()
        compose.onNodeWithText("示例A栋房间A").assertIsDisplayed()
        compose.onNodeWithText("郑东电费 · 照明").assertIsDisplayed()
        compose.onNodeWithText("¥ 10").performClick()
        val submit = compose.onNodeWithText("缴费 ¥ 10.00")
        submit.assertIsEnabled()
        target.performClick().assertIsNotSelected()
        submit.assertIsNotEnabled()
        assertNull(submitted)
        target.performClick().assertIsSelected()
        submit.assertIsEnabled().performClick()
        assertEquals(1000L, submitted)
    }
}
