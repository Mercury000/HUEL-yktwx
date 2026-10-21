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
import com.mercury.yktwx.ui.pages.RechargeDialog
import com.mercury.yktwx.data.RechargeConfiguration
import com.mercury.yktwx.data.PendingRecharge
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
        compose.onNodeWithText("登录并获取付款码").assertIsNotEnabled()
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

    @Test fun billEntryOpensBillPage() {
        content()
        compose.onNodeWithText("查看交易记录与统计").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("账单统计").assertExists()
        compose.onNodeWithContentDescription("刷新账单").assertDoesNotExist()
        compose.onNodeWithContentDescription("筛选账单")
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("筛选账单").assertExists()
        compose.onNodeWithText("应用").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("付款条形码").assertExists()
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
    @Test fun rechargeDialogShowsConfiguredAmountsAndExactCustomInput() {
        var submitted: Long? = null
        val state = RechargeUiState(visible = true, config = RechargeConfiguration("fake-fee", "fake-account", "统一账户", 0, listOf(1000, 5000, 10000), 1, 50000, true, false))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    RechargeDialog(state, {}, { submitted = it }, {})
                }
            }
        }
        compose.onNodeWithText("¥ 10").assertExists().performClick()
        compose.onNodeWithText("¥ 50").assertExists()
        compose.onNodeWithText("¥ 100").assertExists()
        compose.onNodeWithText("微信充值 ¥ 10.00").performClick()
        assertEquals(1000L, submitted)
        compose.onNodeWithText("自定义金额（元）").performTextReplacement("12.34")
        compose.onNodeWithText("微信充值 ¥ 12.34").performClick()
        assertEquals(1234L, submitted)
        compose.onNodeWithText("自定义金额（元）").performTextReplacement("12.345")
        compose.onNodeWithText("微信充值").assertIsNotEnabled()
    }
    @Test fun rechargeSuccessShowsOnlyServerConfirmedState() {
        val pending = PendingRecharge("123456", "fake-account", "401", 1000, 0, "fake-order", "81")
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    RechargeDialog(RechargeUiState(visible = true, pending = pending, phase = RechargePhase.Paid), {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("充值成功").assertExists()
        compose.onNodeWithText("¥ 10.00").assertExists()
        compose.onNodeWithText("完成").assertExists()
    }
}
