package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
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
import com.mercury.yktwx.data.PaymentAccount
import com.mercury.yktwx.ui.pages.PaymentPage
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.pages.LoginPage
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
    private fun content(preview: Boolean = true): PaymentViewModel {
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
        return model
    }
    private fun awaitCode(description: String) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
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
    @Test fun loginFailureStaysInlineAboveTheLoginButton() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { LoginPage(PaymentUiState(needsLogin = true, error = "用户名或密码错误"), { _, _ -> }) }
            }
        }
        compose.onNodeWithText("用户名或密码错误").assertIsDisplayed()
        compose.onNodeWithText("登录").assertExists()
        compose.onNodeWithText("登录校园一卡通").assertExists()
    }
    @Test fun paymentPreviewShowsCodesWithoutRefreshButton() {
        content()
        compose.onNodeWithContentDescription("刷新付款码").assertDoesNotExist()
        awaitCode("付款条形码")
        awaitCode("付款二维码")
        compose.onNodeWithContentDescription("付款条形码").assertExists()
        compose.onNodeWithContentDescription("付款二维码").assertExists()
    }
    @Test fun ownRechargeOverlayDoesNotReplaceTheBackgroundCodeWithAnUnavailableMessage() {
        val model = content()
        val displayed = model.state.value
        compose.onNode(hasText("充值") and !hasTestTag("network-recharge")).performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("充值金额").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(displayed, model.state.value)
        compose.onNodeWithText("付款码暂不可用，请下拉刷新").assertDoesNotExist()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        assertEquals(displayed.code, model.state.value.code)
        compose.onNodeWithText("充值金额").assertDoesNotExist()
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
                        preview = true, items = listOf(record), currentPage = 1, pages = 1, loaded = true,
                        summary = BillSummary(20000, 20540),
                        types = listOf(BillType("4", "二维码支付")),
                    ),
                    settings = settings,
                    onBack = {}, onRefresh = {}, onLoadMore = {},
                    onDateSelected = {}, onFiltersApplied = { _, _, _ -> },
                    onSearch = {}, onOpenStats = {}, onOpenDetail = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("第一餐厅-扫码支付").assertExists()
        compose.onNodeWithContentDescription("筛选账单").assertExists()
        compose.onNodeWithContentDescription("选择日期").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("日期范围").assertExists()
        compose.onNodeWithText("按月").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("按年").onLast().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确定").assertExists()
    }

    @Test fun hallIsSecondTabAndOwnsServicesAcrossBottomBarStyles() {
        content()
        for (style in 0..2) {
            compose.runOnIdle {
                settings.setFloating(style != 0)
                settings.setFloatingStyle(if (style == 2) 1 else 0)
            }
            compose.waitForIdle()
            val labels = listOf("付款", "大厅", "账单", "我的")
            val centers = labels.map { label ->
                compose.onNode((hasText(label) or hasContentDescription(label)) and hasClickAction()).fetchSemanticsNode().boundsInRoot.center.x
            }
            assertTrue(centers.zipWithNext().all { (left, right) -> left < right })
            compose.onNode((hasText("大厅") or hasContentDescription("大厅")) and hasClickAction()).performClick()
            compose.onNodeWithText("校园网").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("水电费").assertExists()
            compose.onNodeWithText("代人充值").assertExists()
            compose.onNodeWithText("生活缴费").assertDoesNotExist()
            compose.onNodeWithContentDescription("付款条形码").assertIsNotDisplayed()
            compose.onAllNodes(hasContentDescription("付款条形码") and hasAnyAncestor(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText("水电费"))))
                .assertCountEquals(0)
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File("../tools/hall-tab-style-$style.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNode((hasText("付款") or hasContentDescription("付款")) and hasClickAction()).performClick()
            awaitCode("付款条形码")
            compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
            compose.onAllNodes((hasText("水电费") or hasText("校园网")) and
                hasAnyAncestor(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange) and hasAnyDescendant(hasText("付款码")))).assertCountEquals(0)
        }
    }

    @Test fun billTabOpensBillsAndPaymentTabReturnsToCodes() {
        content()
        // Adjacent pager pages can already exist: verify visibility, not just existence.
        awaitCode("付款条形码")
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        compose.onNodeWithText("统计").assertIsDisplayed()
        compose.onNode(hasText("付款") and hasClickAction()).performClick()
        awaitCode("付款条形码")
        compose.onNodeWithContentDescription("付款条形码").assertIsDisplayed()
    }
    @Test fun aboutSettingsThemeNavigateAndPersist() {
        content()
        compose.onNode(hasText("我的") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("应用设置").performScrollTo()
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
    @Test fun paymentMotionDetectionSwitchIsInSettingsOffByDefaultAndPersists() {
        content()
        compose.onNode(hasText("我的") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("应用设置").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("转回检测支付").assertIsDisplayed()
        fun switch() = compose.onNode(isToggleable() and
            (hasTestTag("payment-motion-detection-switch") or
                hasAnyAncestor(hasTestTag("payment-motion-detection-switch"))))
        switch().assertIsOff().performClick()
        switch().assertIsOn()
        assertTrue(settings.paymentMotionDetection.value)
        assertTrue(ThemeSettings(compose.activity).paymentMotionDetection.value)
        val output = File("../build/payment-motion-settings.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        switch().performClick().assertIsOff()
        assertFalse(settings.paymentMotionDetection.value)
        assertFalse(ThemeSettings(compose.activity).paymentMotionDetection.value)
    }

    @Test fun campusCardEntriesOpenStateDialogAndReuseRechargeSheet() {
        content()
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("校园卡管理").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("挂失 / 解挂").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("确认挂失").assertExists()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.activity.onBackPressedDispatcher.onBackPressed()
        compose.waitForIdle()
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        compose.onNodeWithText("代人充值").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("查询").assertExists()
        compose.onNode(hasSetTextAction() and hasText("学工号")).performTextInput("202600000002")
        compose.onNodeWithText("查询").performClick()
        compose.waitForIdle()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("充值金额").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("下一步").assertDoesNotExist()
        compose.onNodeWithText("示例同学").assertExists()
        compose.onNodeWithText("已选择").assertExists()
        compose.onNodeWithText("¥ 10").performClick()
        compose.onNodeWithText("充值 ¥ 10.00").assertIsNotEnabled()
    }

    @Test fun firstReadFailureReplacesUnknownAccountAndRecoversByPulling() {
        var clicked = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { PaymentPage(PaymentUiState(needsLogin = false, username = "123", error = "付款码暂不可用"),
                    settings, 0.dp, { clicked = true }, onRecharge = { fail("Unknown account must not render a fabricated card") }) }
            }
        }
        compose.onNodeWithText("充值").assertDoesNotExist()
        compose.onNodeWithText("暂时无法加载，请下拉刷新").assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue(clicked)
    }

    @Test fun codeFailureOrRefreshDoesNotHideKnownBalance() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { PaymentPage(PaymentUiState(needsLogin = false, busy = true, username = "123",
                    account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 6496)), settings, 0.dp, {}) }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("充值"))
        compose.onNodeWithText("¥ 64.96").assertIsDisplayed()
        compose.onNodeWithText("充值").assertIsEnabled()
        compose.onAllNodesWithContentDescription("付款条形码").assertCountEquals(0)
    }

}
