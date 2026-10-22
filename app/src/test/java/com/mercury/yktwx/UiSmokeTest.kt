package com.mercury.yktwx

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.BillRecord
import com.mercury.yktwx.data.BillSummary
import com.mercury.yktwx.data.BillType
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.pages.BillPage
import org.junit.Assert.*
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
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
    }
    private fun content(preview: Boolean = true) {
        val model = PaymentViewModel(compose.activity.application)
        val bills = BillViewModel(compose.activity.application)
        model.initialize(preview)
        bills.initialize(preview)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                YktApp(settings, model, bills)
            }
        }
        compose.waitForIdle()
    }
    @Test fun firstLaunchShowsLoginAndSystemPasswordField() {
        content(false)
        compose.onNodeWithText("登录校园一卡通").assertIsDisplayed()
        compose.onNodeWithText("学号 / 工号").assertExists()
        compose.onNodeWithText("密码").assertExists()
        compose.onNodeWithContentDescription("显示密码").assertExists().performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithText("打开官网处理验证码 / 账户问题").assertDoesNotExist()
        compose.onNodeWithText("登录").assertIsNotEnabled()
    }
    @Test fun paymentPreviewShowsCodesWithoutRefreshButton() {
        content()
        compose.onNodeWithContentDescription("刷新付款码").assertDoesNotExist()
        compose.onNodeWithContentDescription("付款条形码").assertExists()
        compose.onNodeWithContentDescription("付款二维码").assertExists()
    }
    @Test fun billPageRendersPreview() {
        val record = BillRecord(
            orderId = "TEST-01", resume = "第一餐厅-扫码支付", payName = "扫码支付",
            turnoverType = "二维码支付", icon = "qrCode-payment", typeId = "4", typeFrom = "2",
            amountCents = 800, balanceCents = 8236, feeCents = 0,
            effectTime = "2026-10-20 12:05:00", bookTime = "2026-10-20 12:06:00",
            fromAccount = "校园卡", position = "POS-01", remark = "测试", merchant = "第一餐厅",
            serialNumber = "20260001", labelName = "",
        )
        compose.setContent {
            AppTheme {
                BillPage(
                    state = BillUiState(
                        preview = true, items = listOf(record), total = 1,
                        currentPage = 1, pages = 1, loaded = true,
                        summary = BillSummary(20000, 20540),
                        types = listOf(BillType("4", "二维码支付", "qrCode-payment")),
                    ),
                    settings = settings,
                    onBack = {}, onActivate = {}, onRefresh = {}, onLoadMore = {},
                    onMonthSelected = {}, onFiltersApplied = { _, _, _ -> },
                    onSearch = {}, onOpenStats = {}, onOpenDetail = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("第一餐厅-扫码支付").assertExists()
        compose.onNodeWithContentDescription("筛选账单").assertExists()
    }

    @Test fun billTabOpensBillsAndPaymentTabReturnsToCodes() {
        content()
        // Adjacent pager pages can already exist: verify visibility, not just existence.
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("账单统计").assertIsDisplayed()
        compose.onNode(hasText("付款") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
    }
    @Test fun aboutSettingsThemeNavigateAndPersist() {
        content()
        compose.onNode(hasText("关于") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("设置").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("主题").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("预测性返回动画").assertExists()
        compose.onNodeWithText("顶栏模糊").assertExists()
        compose.runOnIdle { settings.setTheme(2); settings.setAccent(1); settings.setFloating(true); settings.setFloatingStyle(1) }
        compose.waitForIdle()
        compose.onNodeWithText("主题颜色").assertExists()
        val restored = ThemeSettings(compose.activity)
        assertEquals(2, restored.themeMode.value)
        assertEquals(1, restored.accentMode.value)
        assertTrue(restored.floatingBottomBar.value)
        compose.onAllNodesWithContentDescription("返回").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("主题").assertExists()
    }
    @Test fun campusCardEntriesOpenStateDialogAndReuseRechargeSheet() {
        content()
        compose.onNode(hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText("付款码")))
            .performScrollToNode(hasText("校园卡服务"))
        compose.onNodeWithContentDescription("挂失 / 解挂").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认挂失").assertExists()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("代人充值").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("查询").assertExists()
        compose.onNode(hasSetTextAction() and hasText("学工号")).performTextInput("202600000002")
        compose.onNodeWithText("查询").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("下一步").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.onNodeWithText("示例同学").performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("收款人：示例同学").assertExists()
        compose.onNodeWithText("¥ 10").performClick()
        compose.onNodeWithText("微信充值 ¥ 10.00").assertIsNotEnabled()
    }

}
