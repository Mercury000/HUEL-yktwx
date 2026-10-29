package com.mercury.yktwx

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.PaymentCodePresentationEffect
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExperienceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun content(page: @Composable () -> Unit) {
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { page() } } }
    }
    @Composable private fun bill(state: BillUiState, onSearch: (String) -> Unit = {}, onFilter: (String?, Long?, Long?) -> Unit = { _, _, _ -> }) {
        BillPage(state, ThemeSettings(compose.activity.application), {}, {}, {}, {}, {}, onFilter, onSearch, {}, {})
    }
    private fun capture(name: String) {
        val file = File("../tools/experience/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun queryLoadingDoesNotChangeOnePixelOrTheBoundsOfTheOverview() {
        var state by mutableStateOf(PreviewData.billState())
        content { bill(state) }
        val summary = compose.onNodeWithTag("bill-summary")
        val initialBounds = summary.fetchSemanticsNode().boundsInRoot
        val initial = summary.captureToImage().asAndroidBitmap()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(info = "餐厅", items = emptyList(), loaded = false, listAvailable = false,
            listLoaded = false, loading = true, listLoading = true) }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertEquals(initialBounds, summary.fetchSemanticsNode().boundsInRoot)
            assertTrue("Search must not repaint or replace the overview", initial.sameAs(summary.captureToImage().asAndroidBitmap()))
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun searchHasAnExplicitActionAndActiveConditionsCanBeRemovedIndividually() {
        var state by mutableStateOf(PreviewData.billState().copy(typeId = "4", amountFromCents = 100))
        var query = ""
        content { bill(state, onSearch = { query = it; state = state.copy(info = it) }, onFilter = { type, from, to ->
            state = state.copy(typeId = type, amountFromCents = from, amountToCents = to)
        }) }
        val field = compose.onNodeWithTag("bill-search")
        field.performTextInput("餐厅")
        compose.onNodeWithContentDescription("提交搜索").performClick()
        assertEquals("餐厅", query)
        compose.onNodeWithContentDescription("移除条件：≥ ¥ 1").performClick()
        assertNull(state.amountFromCents)
        assertEquals("4", state.typeId)
        compose.onNodeWithContentDescription("移除条件：搜索：餐厅").performClick()
        assertEquals("", query)
        capture("bill-conditions")
    }

    @Test fun detailAndStatisticsKeepSeparateReadingPositions() {
        val base = PreviewData.billState()
        var state by mutableStateOf(base.copy(items = (0 until 45).map { index ->
            val date = LocalDate.now().minusDays(index.toLong()).toString() + " 12:00:00"
            base.items.first().copy(orderId = "bill-$index", effectTime = date, bookTime = date)
        }))
        content { bill(state) }
        val details = compose.onNodeWithTag("bill-list")
        details.performScrollToIndex(12)
        val offset = details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset > 0)
        compose.runOnIdle { state = state.copy(statsVisible = true) }
        compose.onNodeWithTag("bill-stats-list").performScrollToIndex(4)
        compose.runOnIdle { state = state.copy(statsVisible = false) }
        assertEquals(offset, details.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 1f)
    }

    @Test fun unfinishedAmountIsQuietUntilFocusLeavesAndOnlyAValidAmountCanSubmit() {
        var amount by mutableStateOf("")
        var submitted = 0L
        val config = RechargeConfiguration("fee", "target", emptyList(), 1000, 50000, true, false)
        content {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                PaymentAmountForm(config, amount, { amount = it }, listOf("一卡通"), 0, {}, false, true, null, { submitted = it })
            }
        }
        compose.onNode(hasSetTextAction() and hasText("自定义金额（元）")).assertExists()
        compose.onNodeWithText("最低 ¥ 10 · 单次最高 ¥ 500").assertExists()
        capture("amount-empty")
        val field = compose.onNode(hasSetTextAction())
        field.performClick().performTextInput("10.")
        compose.onNodeWithTag("read-notice").assertDoesNotExist()
        compose.onNodeWithText("请输入有效金额，最多两位小数").assertDoesNotExist()
        compose.onNodeWithText("充值").assertIsNotEnabled()
        field.performImeAction()
        compose.onNodeWithText("请输入有效金额，最多两位小数").assertExists()
        field.performClick().performTextReplacement("10.50")
        compose.onNodeWithText("请输入有效金额，最多两位小数").assertDoesNotExist()
        field.performImeAction()
        compose.onNodeWithText("充值 ¥ 10.50").performClick()
        assertEquals(1050L, submitted)
        capture("amount-valid")
    }

    @Test fun filterDotIsNotSilentlyAppliedAsAnEmptyBound() {
        var applied = false
        content { bill(PreviewData.billState(), onFilter = { _, _, _ -> applied = true }) }
        compose.onNodeWithContentDescription("筛选账单").performClick()
        compose.onNode(hasText("最低") and hasSetTextAction()).performTextInput(".")
        compose.onNodeWithText("应用").performClick()
        compose.onNodeWithText("请输入有效金额").assertExists()
        assertFalse(applied)
        capture("filter-validation")
    }

    @Test fun trendPointHasExactValuesAndAllCategoriesAreAvailable() {
        val date = LocalDate.of(2026, 10, 1)
        content { Column { DailyBillChart(listOf(BillDailyPoint(date, 100), BillDailyPoint(date.plusDays(1), 200)), BillDateMode.Month)
            CategoryDonutChart((1..8).map { BillCategory("分类$it", it * 100L) }, 2) } }
        compose.onNodeWithTag("bill-trend-plot").performTouchInput { click(centerRight) }
        compose.onNodeWithText("2026-10-02 · ¥ 2.00").assertExists()
        compose.onNodeWithText("分类8").assertDoesNotExist()
        compose.onNodeWithText("查看全部 8 项").performClick()
        compose.onNodeWithText("分类8").assertExists()
        compose.onNodeWithText("收起分类").performClick()
        compose.onNodeWithText("分类8").assertDoesNotExist()
    }

    @Test fun loginNetworkFailureKeepsPasswordForRetryAndSuccessfulLoginClearsIt() {
        var state by mutableStateOf(PaymentUiState(needsLogin = true))
        val submissions = mutableListOf<String>()
        content { LoginPage(state) { _, password -> submissions += password; state = state.copy(busy = true) } }
        compose.onNode(hasText("学号 / 工号") and hasSetTextAction()).performTextInput("202600000001")
        val password = compose.onNode(hasText("密码") and hasSetTextAction())
        password.performTextInput("fake-password")
        compose.onNodeWithText("登录").performClick()
        compose.runOnIdle { state = state.copy(busy = false, error = "网络连接失败") }
        compose.onNodeWithText("登录").assertIsEnabled().performClick()
        assertEquals(listOf("fake-password", "fake-password"), submissions)
        compose.runOnIdle { state = state.copy(busy = false, needsLogin = false, error = null) }
        compose.onNodeWithText("登录").assertIsNotEnabled()
    }

    @Test fun validCodeCanBeEnlargedAndDisappearsImmediatelyWhenItBecomesInvalid() {
        var state by mutableStateOf(PreviewData.paymentState(false))
        content { top.yukonga.miuix.kmp.basic.Scaffold { PaymentPage(state, ThemeSettings(compose.activity.application), 0.dp, {}) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("付款二维码").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("付款二维码").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("放大的付款二维码").fetchSemanticsNodes().isNotEmpty() }
        capture("payment-enlarged")
        compose.runOnIdle { state = state.copy(code = null, error = "网络连接失败") }
        compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
    }

    @Test fun codeNumberVisibilityUsesCurrentCodeAndKeepsTheCardInPlace() {
        var state by mutableStateOf(PreviewData.paymentState(false))
        var active by mutableStateOf(true)
        content { top.yukonga.miuix.kmp.basic.Scaffold { PaymentPage(state, ThemeSettings(compose.activity.application), 0.dp, {}, isActive = active) } }
        val code = requireNotNull(state.code)
        val card = compose.onNodeWithTag("payment-code-card")
        val bounds = card.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText(code.chunked(4).joinToString(" ")).assertDoesNotExist()
        val toggle = compose.onNodeWithContentDescription("显示完整付款码号")
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(toggle.fetchSemanticsNode().touchBoundsInRoot.height >= 48 * density - 1)
        toggle.performClick()
        compose.onNodeWithText(code.chunked(4).joinToString(" ")).assertExists()
        assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
        capture("payment-number-visible")
        val replacement = "98765432109876543210"
        compose.runOnIdle { state = state.copy(code = replacement) }
        compose.onNodeWithText(replacement.chunked(4).joinToString(" ")).assertExists()
        compose.onNodeWithText(code.chunked(4).joinToString(" ")).assertDoesNotExist()
        compose.onNodeWithContentDescription("隐藏完整付款码号").performClick()
        compose.onNodeWithText(replacement.chunked(4).joinToString(" ")).assertDoesNotExist()
        assertEquals(bounds, card.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("显示完整付款码号").performClick()
        compose.runOnIdle { active = false }
        compose.onNodeWithText(replacement.chunked(4).joinToString(" ")).assertDoesNotExist()
        compose.runOnIdle { active = true; state = state.copy(code = null) }
        compose.onNodeWithContentDescription("显示完整付款码号").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi")
    fun narrowLargeFontAmountHeadingAndFullCodeStayWithinTheirRowsInBothThemes() {
        var dark by mutableStateOf(false)
        val config = RechargeConfiguration("fee", "target", emptyList(), 1000, 50000, true, false)
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.4f)) {
                AppTheme(if (dark) top.yukonga.miuix.kmp.theme.ColorSchemeMode.Dark else top.yukonga.miuix.kmp.theme.ColorSchemeMode.Light) {
                    Column(Modifier.fillMaxSize().then(Modifier.background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface))
                        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PaymentAmountForm(config, "", {}, listOf("一卡通"), 0, {}, false, true, null, {})
                        PaymentCodeNumber("12345678901234567890", true)
                    }
                }
            }
        }
        val title = compose.onNodeWithText("充值金额").fetchSemanticsNode().boundsInRoot
        val limits = compose.onNodeWithTag("payment-amount-limits").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("¥ 10–500").assertExists()
        assertEquals(title.center.y, limits.center.y, 3f)
        assertTrue(limits.left >= title.right)
        compose.onNode(hasSetTextAction() and hasText("自定义金额（元）")).assertExists()
        compose.onNodeWithContentDescription("显示完整付款码号").performClick()
        val number = compose.onNodeWithText("1234 5678 9012 3456 7890").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("payment-code-number").fetchSemanticsNode().boundsInRoot
        assertTrue(number.left >= row.left && number.right <= row.right)
        capture("amount-code-narrow-large-light")
        compose.runOnIdle { dark = true }
        capture("amount-code-narrow-large-dark")
    }

    @Test fun paymentWindowBrightnessAndScreenAwakeAreScopedAndRestoreOnLeaving() {
        var enabled by mutableStateOf(true)
        val window = compose.activity.window
        window.attributes = window.attributes.apply { screenBrightness = .2f }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content { PaymentCodePresentationEffect(enabled) }
        assertTrue(window.attributes.screenBrightness >= .65f)
        assertTrue(window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        compose.runOnIdle { enabled = false }
        compose.waitForIdle()
        assertEquals(.2f, window.attributes.screenBrightness, .001f)
        assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test fun compactActionsHaveAFullTouchTargetWithoutEnlargingTheirVisualHeight() {
        var clicks = 0
        content { Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            DashboardLink("切换", { clicks++ })
            CompactButton("充值", { clicks++ })
        } }
        val density = compose.activity.resources.displayMetrics.density
        val nodes = listOf(compose.onNode(hasText("切换") and hasClickAction()), compose.onNode(hasText("充值") and hasClickAction()))
        for (node in nodes) {
            val target = node.fetchSemanticsNode()
            assertTrue(target.boundsInRoot.height < 48 * density)
            assertTrue("A compact action still needs 48dp of touch height", target.touchBoundsInRoot.height >= 48 * density - 1)
            node.performTouchInput { click(center.copy(y = -4 * density)) }
        }
        assertEquals(2, clicks)
    }
}
