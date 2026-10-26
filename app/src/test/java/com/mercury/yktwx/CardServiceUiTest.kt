package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.*
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardServiceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
    }
    private fun screenshot(name: String) {
        val output = File("../tools/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun page(mode: ColorSchemeMode) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode) { HallPage(settings, 0.dp, dorm = DormUiState(loaded = true)) }
            }
        }
        compose.onNodeWithText("我的宿舍").assertIsDisplayed()
    }
    @Test fun hallKeepsLivingFeesAndOthersRechargeWithOwnCardManagementOnMyPage() {
        page(ColorSchemeMode.Light)
        compose.onNodeWithText("生活缴费").assertExists()
        compose.onNodeWithText("代人充值").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("挂失 / 解挂").assertDoesNotExist()
        compose.onNodeWithText("修改查询密码").assertDoesNotExist()
        screenshot("hall-services-light")
    }
    @Test fun hallServicesAlsoRenderInDarkTheme() {
        page(ColorSchemeMode.Dark)
        compose.onNodeWithText("我的宿舍").assertIsDisplayed()
        screenshot("hall-services-dark")
    }
    @Test fun hallPullGestureRefreshesDorm() {
        var refreshed = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    HallPage(settings, 0.dp, dorm = DormUiState(loaded = true), onRefreshDorm = { refreshed++ })
                }
            }
        }
        compose.onNode(hasScrollToIndexAction()).performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
        }
        compose.waitForIdle()
        assertTrue("Pull gesture must refresh dorm readings", refreshed > 0)
    }
    @Test fun backgroundDormLoadKeepsOtherServicesScrollable() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) { HallPage(settings, 0.dp, dorm = DormUiState(loading = true)) }
            }
        }
        compose.onNodeWithText("代人充值").performScrollTo().assertIsDisplayed()
    }
    @Test fun stateConfirmationIsCompactAndOnlyUnlostAsksForPassword() {
        val notLost = CampusCard("demo-card", "校园卡", "202600000001", false, false, 0)
        val lost = CampusCard("demo-card", "校园卡", "202600000001", true, false, 0)
        val current = mutableStateOf(CardServiceUiState(visible = true, cards = listOf(notLost), selectedCard = notLost))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { CardServiceDialog(current.value, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}) } }
            }
        }
        compose.onNodeWithText("确认挂失").assertExists()
        compose.onNodeWithText("确定").assertIsEnabled()
        compose.onNodeWithText("校园卡查询密码").assertDoesNotExist()
        compose.onNodeWithText("刷新卡状态").assertDoesNotExist()
        compose.onNodeWithText("根据当前卡状态操作：未挂失可挂失，已挂失可解挂。").assertDoesNotExist()
        screenshot("campus-card-state-lost-dialog")
        compose.runOnIdle { current.value = current.value.copy(cards = listOf(lost), selectedCard = lost) }
        compose.onNodeWithText("确认解挂").assertExists()
        compose.onNodeWithText("确定").assertIsNotEnabled()
        compose.onNodeWithText("校园卡查询密码").assertExists()
        screenshot("campus-card-state-unlost-dialog")
    }
    @Test fun stateFailureStaysInsideTheDialogAsInlineOrFieldError() {
        val notLost = CampusCard("demo-card", "校园卡", "202600000001", false, false, 0)
        val lost = CampusCard("demo-card", "校园卡", "202600000001", true, false, 0)
        val current = mutableStateOf(CardServiceUiState(visible = true, cards = listOf(notLost),
            selectedCard = notLost, error = "校园卡状态读取失败"))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { CardServiceDialog(current.value, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}) } }
            }
        }
        compose.onNodeWithText("校园卡状态读取失败").assertIsDisplayed()
        compose.onNodeWithText("确认挂失").assertExists()
        screenshot("campus-card-inline-error-dialog")
        compose.runOnIdle { current.value = current.value.copy(cards = listOf(lost),
            selectedCard = lost, error = "查询密码错误") }
        compose.onNodeWithText("查询密码错误").assertIsDisplayed()
        compose.onNodeWithText("校园卡查询密码").assertExists()
        compose.onNodeWithText("确认解挂").assertExists()
        screenshot("campus-card-field-error-dialog")
    }
    @Test fun recipientConfirmationShowsCompleteValuesWithoutQueryFields() {
        val recipient = RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户", 0)
        val current = mutableStateOf(CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge, recipients = listOf(recipient)))
        var selected: RechargeRecipient? = null
        var backs = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { CardServiceDialog(current.value, {}, {}, {},
                    { backs++; current.value = current.value.copy(recipients = emptyList(), selectedRecipient = null) }, {}, { _, _, _ -> },
                    { selected = it; current.value = current.value.copy(selectedRecipient = it) }, {}) } }
            }
        }
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.onNodeWithText("202600000002").assertExists()
        compose.onNodeWithText("示例同学").assertExists()
        screenshot("campus-card-recipient-unselected-dialog")
        compose.onNodeWithText("示例同学").performClick()
        compose.onNodeWithText("统一账户 · recipient-target").assertExists()
        compose.onNodeWithText("学工号").assertDoesNotExist()
        compose.onNodeWithText("证件号").assertDoesNotExist()
        compose.onNodeWithText("验证码").assertDoesNotExist()
        assertSame(recipient, selected)
        compose.onNodeWithText("下一步").assertIsEnabled()
        screenshot("campus-card-recipient-dialog")
        compose.onNodeWithText("上一步").performClick()
        assertEquals(1, backs)
        compose.onNodeWithText("验证码").assertExists()
        compose.onNodeWithText("下一步").assertDoesNotExist()
    }
    @Test fun recipientSelectionChangesGreyToPrimaryWithMatchingTextContrast() {
        val recipient = RechargeRecipient("demo-card", "demo-target", "示例同学", "202600000002", "统一账户", 0)
        val current = mutableStateOf(CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge, recipients = listOf(recipient)))
        var grey = Color.Unspecified
        var primary = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    grey = MiuixTheme.colorScheme.surfaceContainerHigh
                    primary = MiuixTheme.colorScheme.primary
                    Scaffold { CardServiceDialog(current.value, {}, {}, {}, {}, {}, { _, _, _ -> },
                        { current.value = current.value.copy(selectedRecipient = it) }, {}) }
                }
            }
        }
        fun background() = compose.onNode(hasText("示例同学") and hasClickAction()).captureToImage()
            .asAndroidBitmap().let { bitmap -> bitmap.getPixel(bitmap.width / 2, 8) }
        assertEquals(grey.toArgb(), background())
        compose.onNodeWithText("示例同学").performClick()
        assertEquals(primary.toArgb(), background())
        compose.onNodeWithText("已选择").assertExists()
    }
    @Test fun tappingSelectedRecipientClearsHighlightAndDisablesNext() {
        val model = CardServiceViewModel(compose.activity.application)
        val store = androidx.lifecycle.ViewModelStore().apply { put("card", model) }
        try {
            compose.setContent {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    AppTheme {
                        val state by model.state.collectAsState()
                        Scaffold { CardServiceDialog(state, model::dismiss, model::selectCard, model::changeCard,
                            model::refreshCaptcha, model::clearRecipient, model::queryRecipient, model::selectRecipient, model::continueRecharge) }
                    }
                }
            }
            compose.runOnIdle { model.open(CampusCardAction.OthersRecharge, "preview", true) }
            compose.waitUntil(5_000) { !model.state.value.loading }
            compose.runOnIdle { model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "") }
            compose.waitUntil(5_000) { model.state.value.recipients.isNotEmpty() }
            compose.onNodeWithText("下一步").assertIsNotEnabled()
            compose.onNodeWithText("示例同学").performClick()
            compose.onNodeWithText("已选择").assertExists()
            compose.onNodeWithText("下一步").assertIsEnabled()
            compose.onNodeWithText("示例同学").performClick()
            compose.onNodeWithText("已选择").assertDoesNotExist()
            compose.onNodeWithText("下一步").assertIsNotEnabled()
            compose.onNodeWithText("示例同学").assertExists()
            screenshot("campus-card-recipient-deselected-dialog")
            compose.onNodeWithText("示例同学").performClick()
            compose.onNodeWithText("下一步").assertIsEnabled()
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }
    @Test fun recipientBottomSheetKeepsActionAboveInjectedNavigationBar() {
        compose.activity.enableEdgeToEdge()
        val recipient = RechargeRecipient("demo-card", "demo-target", "示例同学", "202600000002", "统一账户", 0)
        val state = CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge,
            recipients = listOf(recipient), selectedRecipient = recipient)
        var view: android.view.View? = null
        var observedBottomInset = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    view = LocalView.current
                    observedBottomInset = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                    Scaffold { CardServiceDialog(state, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}) }
                }
            }
        }
        compose.waitForIdle()
        val insetPixels = 72
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(view!!, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, insetPixels))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
        }
        compose.waitUntil(5_000) { observedBottomInset == insetPixels }
        val bounds = compose.onNodeWithText("下一步").fetchSemanticsNode().boundsInRoot
        val height = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Sheet action overlaps system navigation inset", bounds.bottom <= height - insetPixels)
        screenshot("campus-card-recipient-navigation-safe")
    }
    @Test fun pendingCardReadDoesNotShowAnIntermediatePopup() {
        val card = CampusCard("demo", "校园卡", "202600000001", false, false, 0)
        val current = mutableStateOf(CardServiceUiState(loading = true, visible = false))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { CardServiceDialog(current.value, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}) } }
            }
        }
        compose.onNodeWithText("挂失 / 解挂").assertDoesNotExist()
        compose.onNodeWithText("确认挂失").assertDoesNotExist()
        compose.runOnIdle { current.value = current.value.copy(visible = true, loading = false, cards = listOf(card), selectedCard = card) }
        compose.onNodeWithText("确认挂失").assertExists()
        compose.onNodeWithText("挂失 / 解挂").assertDoesNotExist()
    }
    @Test fun serviceIconHasOneClickTargetAndLoadingBlocksRepeatedEntry() {
        var calls = 0
        val loading = mutableStateOf(false)
        compose.setContent {
            AppTheme { CampusCardServicesCard(loading.value, { calls++; loading.value = true },
                CampusCardAction.ManageState.takeIf { loading.value }) }
        }
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
        compose.onAllNodes(hasClickAction(), useUnmergedTree = true).assertCountEquals(3)
        compose.onNodeWithContentDescription("挂失 / 解挂").performClick()
        assertEquals(1, calls)
        compose.onNodeWithContentDescription("挂失 / 解挂").assertIsNotEnabled()
        compose.onNodeWithText("挂失 / 解挂").performClick()
        assertEquals(1, calls)
    }
    @Test fun servicePressIndicationStaysInsideCircleForBothGrids() {
        val ownCardGrid = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    if (ownCardGrid.value) CampusCardServicesCard(false, {})
                    else HallPage(settings, 0.dp, dorm = DormUiState(loaded = true))
                }
            }
        }
        for (label in listOf("校园网费", "挂失 / 解挂")) {
            if (label == "挂失 / 解挂") compose.runOnIdle { ownCardGrid.value = true }
            val node = compose.onNodeWithContentDescription(label)
            node.assertHasClickAction()
            val before = node.captureToImage().asAndroidBitmap()
            var density = 1f
            compose.runOnIdle { density = compose.activity.resources.displayMetrics.density }
            val centerX = before.width / 2f
            val centerY = 32f * density // 8 dp top padding + 24 dp icon radius.
            val radius = 24f * density
            compose.mainClock.autoAdvance = false
            node.performTouchInput { down(Offset(centerX, centerY)) }
            compose.mainClock.advanceTimeBy(300)
            val pressed = node.captureToImage().asAndroidBitmap()
            var changed = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                if (before.getPixel(x, y) != pressed.getPixel(x, y)) {
                    changed++
                    assertTrue("$label press highlight escaped its circular icon at ($x, $y)",
                        hypot(x + 0.5f - centerX, y + 0.5f - centerY) <= radius + 2f)
                }
            }
            assertTrue("$label has no visible press feedback", changed > 0)
            screenshot(if (label == "校园网费") "life-fees-circular-press" else "campus-card-circular-press")
            node.performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
        }
    }
    @Test fun successfulStateDoesNotRequireAnExtraCompletionClick() {
        val card = CampusCard("demo", "校园卡", "202600000001", false, false, 0)
        val current = mutableStateOf(CardServiceUiState(visible = true, cards = listOf(card), selectedCard = card))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { CardServiceDialog(current.value, {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {}) } }
            }
        }
        compose.onNodeWithText("确认挂失").assertExists()
        compose.runOnIdle { current.value = current.value.copy(visible = false, selectedCard = null, completed = true) }
        compose.waitForIdle()
        compose.onNodeWithText("确认挂失").assertDoesNotExist()
        compose.onNodeWithText("操作成功").assertDoesNotExist()
        compose.onNodeWithText("完成").assertDoesNotExist()
    }
    @Test fun captchaImageIsClickableBesideInputAndPreviousClearsCodeButKeepsRecipientInput() {
        val image = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(120, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.LTGRAY) }
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val recipient = RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户", 0)
        val current = mutableStateOf(CardServiceUiState(visible = true, action = CampusCardAction.OthersRecharge,
            captcha = RecipientCaptcha("initial-key", image)))
        var refreshes = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { CardServiceDialog(current.value, {}, {}, {}, {
                    refreshes++
                    current.value = current.value.copy(captcha = RecipientCaptcha("fresh-key-$refreshes", image), recipients = emptyList(), selectedRecipient = null)
                }, {}, { _, _, _ -> current.value = current.value.copy(recipients = listOf(recipient), captcha = null) }, {}, {}) } }
            }
        }
        compose.onNodeWithText("刷新验证码").assertDoesNotExist()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields.onFirst().performTextInput("202600000002")
        fields.onLast().performTextInput("abcde")
        val picBounds = compose.onNodeWithContentDescription("图形验证码").fetchSemanticsNode().boundsInRoot
        val inputBounds = fields.onLast().fetchSemanticsNode().boundsInRoot
        assertTrue(picBounds.right < inputBounds.left)
        compose.onNodeWithContentDescription("图形验证码").performClick()
        assertEquals(1, refreshes)
        fields.onLast().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        fields.onLast().performTextInput("fresh")
        screenshot("campus-card-query-dialog")
        compose.onNodeWithText("查询").performClick()
        compose.onNodeWithText("验证码").assertDoesNotExist()
        compose.onNodeWithText("上一步").performClick()
        assertEquals(2, refreshes)
        compose.onAllNodes(hasSetTextAction()).onFirst().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("202600000002")))
        compose.onAllNodes(hasSetTextAction()).onLast().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }
    @Test fun rechargeSheetIncludesRecipientBeforeReusingAmountAndWechatButton() {
        val recipient = RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户", 0)
        val config = RechargeConfiguration("demo", recipient.targetId, "统一账户", 0, listOf(1000, 5000, 10000), 1, 50000, true, false, recipient)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { RechargeDialog(RechargeUiState(visible = true, config = config, preview = true), {}, {}, {}) } }
            }
        }
        compose.onNodeWithText("代人充值").assertExists()
        compose.onNodeWithText("收款人：" + recipient.name).assertExists()
        compose.onNodeWithText("¥ 10").performClick()
        compose.onNodeWithText("微信充值 ¥ 10.00").assertIsNotEnabled()
        screenshot("campus-card-others-recharge-dialog")
    }
}
