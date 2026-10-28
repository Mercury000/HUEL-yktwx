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
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.MyPage
import com.mercury.yktwx.ui.pages.PersonalProfilePage
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
                onCardAction = {}, manageLoading = false, passwordLoading = false,
                onSettings = {}, onAbout = {})
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

    @Test fun cardManagementOnlyDisablesTheActionThatIsLoading() {
        var manageLoading by mutableStateOf(false)
        var passwordLoading by mutableStateOf(false)
        var rechargeLoading by mutableStateOf(false)
        val actions = mutableListOf<CampusCardAction>()
        content {
            MyPage(MyUiState(loaded = true), PaymentUiState(), settings, 0.dp,
                isActive = false, onActivate = {}, onRefresh = {},
                onProfile = {}, onAccount = {}, onBills = {}, onRecharge = {}, rechargeLoading = rechargeLoading,
                onCardAction = { actions += it }, manageLoading = manageLoading,
                passwordLoading = passwordLoading, onSettings = {}, onAbout = {})
        }
        val manage = compose.onNode(hasText("挂失 / 解挂") and hasClickAction())
        val password = compose.onNode(hasText("修改查询密码") and hasClickAction())
        manage.assertIsEnabled()
        password.assertIsEnabled()
        compose.runOnIdle { manageLoading = true }
        manage.assertDoesNotExist()
        password.assertIsEnabled().performClick()
        assertEquals(listOf(CampusCardAction.ChangePassword), actions)
        compose.runOnIdle { manageLoading = false; passwordLoading = true }
        manage.assertIsEnabled().performClick()
        password.assertDoesNotExist()
        assertEquals(listOf(CampusCardAction.ChangePassword, CampusCardAction.ManageState), actions)
        compose.runOnIdle { passwordLoading = false; rechargeLoading = true }
        manage.assertIsEnabled()
        password.assertIsEnabled()
    }

    @Test fun hallOnlyDisablesTheServiceEntryThatIsLoading() {
        val hall = com.mercury.yktwx.preview.PreviewData.hallState()
        val room = hall.rooms.single()
        val lighting = room.meters.first { it.kind == DormMeterKind.Lighting }
        var feeOpening by mutableStateOf<FeeProject?>(null)
        var feeOpeningTarget by mutableStateOf<FeeRoomBinding?>(null)
        var othersRechargeLoading by mutableStateOf(false)
        val feeActions = mutableListOf<FeeProject>()
        val cardActions = mutableListOf<CampusCardAction>()
        content {
            HallPage(settings, 0.dp, dorm = hall, feeOpening = feeOpening,
                feeOpeningTarget = feeOpeningTarget, othersRechargeLoading = othersRechargeLoading,
                onOpenFee = { feeActions += it }, onOpenCardService = { cardActions += it })
        }
        val lightingAction = hasText("照明充值") and hasClickAction()
        val airAction = hasText("空调充值") and hasClickAction()
        val networkAction = hasTestTag("network-recharge")
        val othersAction = hasText("代人充值") and hasClickAction()

        compose.onNode(lightingAction).assertIsEnabled()
        compose.onNode(airAction).assertIsEnabled()
        compose.onNode(networkAction).assertIsEnabled()
        compose.onNode(othersAction).assertIsEnabled()

        compose.runOnIdle {
            feeOpening = room.project
            feeOpeningTarget = lighting.room
        }
        compose.onNodeWithText("照明充值").assertIsNotEnabled()
        compose.onNode(airAction).assertIsEnabled().performClick()
        compose.onNode(networkAction).assertIsEnabled().performClick()
        compose.onNode(othersAction).assertIsEnabled().performClick()
        assertEquals(listOf(room.project, FeeProject.Network), feeActions)
        assertEquals(listOf(CampusCardAction.OthersRecharge), cardActions)

        compose.runOnIdle {
            feeOpening = FeeProject.Network
            feeOpeningTarget = null
        }
        compose.onNode(lightingAction).assertIsEnabled()
        compose.onNode(airAction).assertIsEnabled()
        compose.onNode(networkAction).assertIsNotEnabled()
        compose.onNode(othersAction).assertIsEnabled()

        compose.runOnIdle {
            feeOpening = null
            othersRechargeLoading = true
        }
        compose.onNode(lightingAction).assertIsEnabled()
        compose.onNode(airAction).assertIsEnabled()
        compose.onNode(networkAction).assertIsEnabled()
        compose.onNode(othersAction).assertDoesNotExist()
    }

    @Test fun billsShowSkeletonBeforeActivationAndOnlyShowEmptyAfterLoadingCompletes() {
        var state by mutableStateOf(BillUiState())
        var active by mutableStateOf(false)
        var activations = 0
        content {
            BillPage(state, settings, {}, { activations++ }, {}, {}, {}, { _, _, _ -> }, {},
                onOpenStats = {}, onOpenDetail = {}, isActive = active, showBack = false)
        }
        fun assertInitialSkeleton() {
            compose.onNodeWithContentDescription("正在加载账单").assertIsDisplayed()
            compose.onNodeWithText("当前时间段还没有账单").assertDoesNotExist()
            compose.onAllNodesWithText("—").assertCountEquals(0)
        }
        // The adjacent tab is composed before activation, and the credential check is async.
        assertInitialSkeleton()
        assertEquals(0, activations)
        compose.runOnIdle { active = true }
        assertInitialSkeleton()
        assertEquals(1, activations)
        compose.runOnIdle { state = state.copy(loading = true) }
        assertInitialSkeleton()
        // The confirmed list can be empty while unrelated metrics are still loading.
        compose.runOnIdle { state = state.copy(listLoaded = true, summaryLoaded = true, summary = BillSummary(0, 0)) }
        compose.onNodeWithContentDescription("正在加载账单").assertDoesNotExist()
        compose.onNodeWithText("当前时间段还没有账单").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(loading = false, loaded = true) }

        // Errors and expired credentials must replace placeholders, not leave a perpetual load.
        compose.runOnIdle { state = BillUiState(error = "账单加载失败，请稍后重试") }
        compose.onNodeWithContentDescription("正在加载账单").assertDoesNotExist()
        compose.onNodeWithText("当前时间段还没有账单").assertDoesNotExist()
        compose.onNodeWithText("账单加载失败，请稍后重试").assertIsDisplayed()
        compose.runOnIdle { state = BillUiState(needsLogin = true) }
        compose.onNodeWithContentDescription("正在加载账单").assertDoesNotExist()
        compose.onNodeWithText("当前时间段还没有账单").assertDoesNotExist()
        compose.onNodeWithText("登录已失效，请重新登录。").assertIsDisplayed()
    }

    @Test fun billOverlaysCloseWithPageAndReopenWithAppliedFilters() {
        var active by mutableStateOf(true)
        var applied = 0
        var dates = 0
        content {
            BillPage(BillUiState(loaded = true, amountFromCents = 1000), settings,
                {}, {}, {}, {}, { dates++ }, { _, from, _ -> assertEquals(1000L, from); applied++ }, {},
                onOpenStats = {}, onOpenDetail = {}, isActive = active, showBack = false)
        }
        compose.onNodeWithContentDescription("筛选账单").performClick()
        compose.onNodeWithText("最低").assertTextContains("10").performTextReplacement("20")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { active = false }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("应用").assertIsNotEnabled()
        compose.runOnIdle { active = true }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("交易类型").assertDoesNotExist()
        compose.onNodeWithContentDescription("筛选账单").performClick()
        compose.onNodeWithText("最低").assertTextContains("10")
        compose.onNodeWithText("应用").performClick()
        assertEquals(1, applied)

        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.onNodeWithText("选择日期").assertIsDisplayed()
        compose.onNodeWithText("日期范围").performClick()
        compose.onNodeWithText("自定义时间段").performClick()
        compose.onNodeWithText("开始").assertExists()
        compose.runOnIdle { active = false }
        compose.onNodeWithText("选择日期").assertDoesNotExist()
        compose.runOnIdle { active = true }
        compose.onNodeWithText("选择日期").assertDoesNotExist()
        assertEquals(0, dates)
        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.onNodeWithText("按月").assertExists()
        compose.onNodeWithText("开始").assertDoesNotExist()
        compose.onNodeWithText("确定").performClick()
        assertEquals(1, dates)
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

}
