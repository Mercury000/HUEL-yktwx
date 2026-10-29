package com.mercury.yktwx

import android.graphics.Bitmap
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.FeeLocationDropdown
import com.mercury.yktwx.ui.components.FeeLocationDropdownPlaceholder
import com.mercury.yktwx.ui.pages.HallFeeDialog
import org.junit.Assert.*
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
class FeeSelectorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var contentView: View? = null
    private var observedImeBottom = 0
    private val levels = listOf(FeeLevel("building", 1, "楼栋"), FeeLevel("floor", 2, "楼层"), FeeLevel("room", 3, "房间"))
    private fun state(project: FeeProject, kind: String = "", complete: Boolean = true): FeeUiState {
        val names = mapOf("building" to "示例A栋", "floor" to "示例楼层", "room" to "示例A栋-房间A$kind")
        val selections = mapOf("building" to "building-${project.id}", "floor" to "floor-${project.id}", "room" to "room-${project.id}-$kind")
        return FeeUiState(
            config = FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(1000), 1, 200000, true, false), roomBindingEnabled = true),
            levels = levels, names = if (complete) names else names - "room",
            selections = if (complete) selections else selections - "room",
            options = levels.associate { level -> level.number to if (level.key == "room" && project == FeeProject.Zhengdong)
                listOf(FeeOption("room-${project.id}-照明", "示例A栋-房间A照明"), FeeOption("room-${project.id}-空调", "示例A栋-房间A空调"))
                else listOf(FeeOption(selections.getValue(level.key), names.getValue(level.key))) },
            query = if (complete) FeeQuery(thirdParty = "{}", display = listOf("剩余用量" to "86.4")) else null,
        )
    }
    private fun content(state: () -> FeeUiState, fixed: Boolean = false, saveOnly: Boolean = false,
        dark: () -> Boolean = { false }, onSelect: (FeeLevel, FeeOption) -> Unit = { _, _ -> },
        onProject: (FeeProject) -> Unit = {}, onCreate: (Long, String) -> Unit = { _, _ -> },
        bindingProjects: Set<FeeProject> = setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)) {
        compose.setContent {
            contentView = LocalView.current
            observedImeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (dark()) ColorSchemeMode.Dark else ColorSchemeMode.Light) { Scaffold {
                    val current = state()
                    HallFeeDialog(current, true, current.config!!.project, if (fixed) current.currentRoom else null,
                        saveOnly = saveOnly, onDismiss = {}, onSelect = onSelect, onRetry = {}, onCreate = onCreate,
                        onMethod = {}, onPayAccount = { _, _ -> }, onCancelPayment = {}, onFinish = {},
                        onContinuePayment = {}, onRoomAction = {}, onConfirmRoomAction = {},
                        onDismissRoomDialog = {}, onConsumeRoomNotice = {}, onProject = onProject,
                        bindingProjects = bindingProjects)
                } }
            }
        }
    }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        val output = File("../tools/fee-selector-preview/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun lightweightLocationPlaceholderMatchesDropdownHeightAtDifferentFontScales() {
        var fontScale by mutableFloatStateOf(1f)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalInspectionMode provides true,
                LocalDensity provides Density(density.density, fontScale)) {
                AppTheme { Scaffold {
                    Column {
                        Column(Modifier.fillMaxWidth().testTag("location-field")) {
                            FeeLocationDropdown("楼栋", emptyList(), 0, false) {}
                        }
                        Column(Modifier.fillMaxWidth().testTag("location-placeholder")) {
                            FeeLocationDropdownPlaceholder()
                        }
                    }
                } }
            }
        }
        for (scale in listOf(1f, 1.3f, 1.8f)) {
            compose.runOnIdle { fontScale = scale }
            val field = compose.onNodeWithTag("location-field").getUnclippedBoundsInRoot()
            val placeholder = compose.onNodeWithTag("location-placeholder").getUnclippedBoundsInRoot()
            assertEquals("Location field height at font scale $scale",
                (field.bottom - field.top).value, (placeholder.bottom - placeholder.top).value, .001f)
        }
    }

    @Test fun everyFixedCardEntryKeepsItsMeterAndOnlyAsksForAmount() {
        var current by mutableStateOf(state(FeeProject.FamilyElectric))
        var dark by mutableStateOf(false)
        var submitted: Pair<Long, String>? = null
        content({ current }, fixed = true, dark = { dark }, onCreate = { cents, method -> submitted = cents to method })
        listOf(FeeProject.FamilyElectric to "", FeeProject.FamilyWater to "",
            FeeProject.Zhengdong to "照明", FeeProject.Zhengdong to "空调").forEach { (project, kind) ->
            compose.runOnIdle { current = state(project, kind); submitted = null }
            compose.onNodeWithTag("fee-meter-type").assertDoesNotExist()
            compose.onNodeWithText("楼栋").assertDoesNotExist()
            compose.onNodeWithText("返回选房").assertDoesNotExist()
            compose.onNodeWithTag("hall-payment-target").assertIsSelected()
            compose.onNodeWithText("充值金额").assertIsDisplayed()
            compose.onNodeWithText("¥ 10").performClick()
            compose.onNodeWithText("充值 ¥ 10.00").assertIsEnabled().performClick()
            assertEquals(1000L to "ACCOUNTTSM", submitted)
            capture("fixed-${project.id}-${kind.ifEmpty { "family" }}")
            compose.onNodeWithTag("hall-payment-target").performClick()
            compose.onNodeWithText("充值 ¥ 10.00").assertIsNotEnabled()
        }
        compose.runOnIdle { dark = true; current = state(FeeProject.FamilyWater) }
        capture("fixed-water-dark")
    }
    @Test fun amountRulesExplainDisabledPaymentWithoutKeyboardDone() {
        val rules = RechargeConfiguration("412", "", listOf(1000), 1000, 50000, true, true)
        val current = FeeUiState(config = FeeConfiguration(FeeProject.Network, rules), query = FeeQuery(thirdParty = "{}"))
        var submitted: Long? = null
        content({ current }, onCreate = { cents, _ -> submitted = cents })
        val input = compose.onNode(hasSetTextAction())
        val formatError = "请输入有效的整数金额"
        compose.onNodeWithText("自定义金额（元，仅限整数）").assertExists()
        compose.onNodeWithText(formatError).assertDoesNotExist()
        input.performClick()
        compose.runOnIdle {
            val keyboardHeight = (280 * compose.activity.resources.displayMetrics.density).toInt()
            ViewCompat.dispatchApplyWindowInsets(contentView!!, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, keyboardHeight))
                .setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        compose.waitUntil(5_000) { observedImeBottom > 0 }
        val anchoredBottom = compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().bottom
        compose.mainClock.autoAdvance = false
        input.performTextReplacement("9")
        repeat(32) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("重试").assertDoesNotExist()
            val content = compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot()
            val button = compose.onNodeWithText("充值").getUnclippedBoundsInRoot()
            assertEquals(anchoredBottom.value, content.bottom.value, 1f)
            assertTrue("Amount action is clipped during error entry", button.bottom.value <= content.bottom.value + 1f)
        }
        compose.mainClock.autoAdvance = true
        for ((amount, message) in listOf("9" to "最低充值 ¥ 10", "501" to "单次最高 ¥ 500", "0" to formatError)) {
            input.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
            input.performTextReplacement(amount)
            compose.onNodeWithText(message).assertDoesNotExist()
            input.performImeAction()
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onNodeWithText("充值").assertIsNotEnabled()
            compose.onNodeWithText("请输入有效金额，最多两位小数").assertDoesNotExist()
            compose.onNodeWithText("重试").assertDoesNotExist()
        }
        input.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        input.performTextReplacement("10")
        input.performTextReplacement("10.50")
        input.assertTextContains("10")
        compose.onNodeWithText("充值 ¥ 10.00").assertIsEnabled()
        input.performTextReplacement("10.001")
        input.assertTextContains("10")
        input.performImeAction()
        input.performTextReplacement("")
        compose.onNodeWithText(formatError).assertDoesNotExist()
        input.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        input.performTextReplacement("501")
        input.performImeAction()
        compose.onNodeWithText("单次最高 ¥ 500").assertIsDisplayed()
        val hint = compose.onNodeWithText("单次最高 ¥ 500").fetchSemanticsNode().boundsInRoot
        assertTrue(hint.top > compose.onNodeWithText("支付方式").fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(hint.bottom < compose.onNodeWithText("充值").fetchSemanticsNode().boundsInRoot.top)
        capture("payment-amount-limit")
        val bottom = compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().bottom
        compose.mainClock.autoAdvance = false
        input.performTextReplacement("500")
        repeat(32) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.onNodeWithText("重试").assertDoesNotExist()
            assertEquals(bottom.value, compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().bottom.value, 1f)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("单次最高 ¥ 500").assertDoesNotExist()
        compose.onNodeWithText("充值 ¥ 500.00").assertIsEnabled()
        input.performImeAction()
        compose.onNodeWithText(formatError).assertDoesNotExist()
        compose.onNodeWithText("¥ 10").performClick()
        compose.onNodeWithText("最低充值 ¥ 10").assertDoesNotExist()
        compose.onNodeWithText("仅支持整数金额").assertDoesNotExist()
        compose.onNodeWithText("充值 ¥ 10.00").assertIsEnabled().performClick()
        assertEquals(1000L, submitted)
    }

    @Test fun familyAndStudentMeterChoicesFollowRoomAndDisappearOnAmountStep() {
        var current by mutableStateOf(state(FeeProject.Zhengdong, "照明"))
        var selectedOption: FeeOption? = null
        var selectedProject: FeeProject? = null
        content({ current }, onSelect = { _, option -> selectedOption = option; current = state(FeeProject.Zhengdong, "空调") },
            onProject = { selectedProject = it; current = state(it) })
        fun assertBelowRoom() {
            val selector = compose.onNodeWithTag("fee-meter-type").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(selector.top > compose.onNodeWithText("房间").fetchSemanticsNode().boundsInRoot.bottom)
            val building = compose.onNodeWithText("楼栋").fetchSemanticsNode().boundsInRoot
            val floor = compose.onNodeWithText("楼层").fetchSemanticsNode().boundsInRoot
            assertTrue(floor.top > building.bottom)
            assertEquals(building.left, floor.left, 1f)
        }
        assertBelowRoom()
        compose.onNode(hasText("空调") and hasClickAction()).performClick()
        assertEquals("room-410-空调", selectedOption?.id)
        capture("student-target")
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("fee-meter-type").assertDoesNotExist()
        compose.onNodeWithText("返回选房").assertDoesNotExist()
        compose.runOnIdle { current = state(FeeProject.FamilyElectric) }
        assertBelowRoom()
        compose.onNode(hasText("水费") and hasClickAction()).performClick()
        assertEquals(FeeProject.FamilyWater, selectedProject)
        assertBelowRoom()
        capture("family-target")
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("fee-meter-type").assertDoesNotExist()
        compose.onNodeWithText("充值金额").assertIsDisplayed()
        capture("family-temporary-amount")
    }
    @Test fun bindingOnlyShowsTypeAfterRoomAndOnlyForAvailableProjects() {
        var current by mutableStateOf(state(FeeProject.FamilyElectric, complete = false))
        content({ current }, saveOnly = true)
        compose.onNodeWithTag("fee-meter-type").assertDoesNotExist()
        compose.onNodeWithText("绑定房间").assertIsNotEnabled()
        compose.runOnIdle { current = state(FeeProject.FamilyElectric) }
        compose.onNodeWithTag("fee-meter-type").assertIsDisplayed()
        compose.onNodeWithText("绑定房间").assertIsEnabled()
    }
    @Test fun singleBindableFamilyServiceHasNoAlternativeType() {
        content({ state(FeeProject.FamilyWater) }, saveOnly = true, bindingProjects = setOf(FeeProject.FamilyWater))
        compose.onNodeWithTag("fee-meter-type").assertDoesNotExist()
        compose.onNodeWithText("绑定房间").assertIsEnabled()
    }
    @Test fun familyTargetUsesStudentFieldLayoutInBothThemes() {
        var dark by mutableStateOf(false)
        content({ state(FeeProject.FamilyElectric) }, dark = { dark })
        compose.onNodeWithText("楼栋").assertIsDisplayed()
        compose.onNodeWithText("楼层").assertIsDisplayed()
        compose.onNodeWithText("房间").assertIsDisplayed()
        capture("family-target-light")
        compose.runOnIdle { dark = true }
        capture("family-target-dark")
    }
    @Test fun bindingAndTemporaryLoadingNeverShowThePaymentForm() {
        var visible by mutableStateOf(true)
        var saveOnly by mutableStateOf(true)
        var current by mutableStateOf(FeeUiState(loading = true))
        var bindingRequests = 0
        var paymentRequests = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    HallFeeDialog(current, visible, FeeProject.FamilyElectric, null, saveOnly = saveOnly,
                        onDismiss = {}, onSelect = { _, _ -> }, onRetry = {},
                        onCreate = { _, _ -> paymentRequests++ }, onMethod = {}, onPayAccount = { _, _ -> },
                        onCancelPayment = {}, onFinish = {}, onContinuePayment = {},
                        onRoomAction = { bindingRequests++ }, onConfirmRoomAction = {},
                        onDismissRoomDialog = {}, onConsumeRoomNotice = {},
                        bindingProjects = setOf(FeeProject.FamilyElectric))
                } }
            }
        }
        fun assertSelecting() {
            compose.onNodeWithText("充值金额").assertDoesNotExist()
            compose.onNodeWithText("支付方式").assertDoesNotExist()
            compose.onNodeWithContentDescription("正在读取充值信息").assertDoesNotExist()
        }
        compose.onNodeWithText("新增绑定").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在读取房间选项").assertIsDisplayed()
        assertSelecting()
        capture("binding-loading")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { current = state(FeeProject.FamilyElectric) }
        var fieldLeft: Float? = null
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertSelecting()
            // The root popup receives state on a subsequent frame. From its first rendered
            // field onward, content must replace the skeleton immediately and stay in place.
            compose.onAllNodesWithText("楼栋").fetchSemanticsNodes().singleOrNull()?.let { field ->
                compose.onNodeWithContentDescription("正在读取房间选项").assertDoesNotExist()
                val left = field.boundsInRoot.left
                fieldLeft?.let { assertEquals("Loading completion must update fields in place", it, left, 0f) }
                fieldLeft = left
            }
        }
        assertNotNull("Loaded fields must appear", fieldLeft)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("楼栋").assertIsDisplayed()
        compose.onNodeWithText("绑定房间").assertIsEnabled().performClick()
        assertEquals(1, bindingRequests)
        assertEquals(0, paymentRequests)
        capture("binding-ready")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { visible = false; current = FeeUiState() }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("楼栋").assertExists()
        compose.onNodeWithText("绑定房间").assertIsNotEnabled()
        assertSelecting()
        compose.runOnIdle { visible = true; current = FeeUiState(loading = true) }
        repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithText("楼栋").assertDoesNotExist()
        compose.onNodeWithContentDescription("正在读取房间选项").assertExists()
        assertSelecting()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { saveOnly = false; current = FeeUiState(loading = true) }
        compose.onNodeWithContentDescription("正在读取房间选项").assertIsDisplayed()
        assertSelecting()
        compose.mainClock.advanceTimeBy(1500)
        val loadingHeight = compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        compose.runOnIdle { current = state(FeeProject.FamilyElectric, complete = false) }
        compose.mainClock.advanceTimeBy(1500)
        val initialFormHeight = compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertEquals("Temporary payment skeleton must reserve the room binding row",
            loadingHeight.value, initialFormHeight.value, 1f)
        compose.runOnIdle { current = state(FeeProject.FamilyElectric) }
        assertSelecting()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("充值金额").assertIsDisplayed()
        compose.onNodeWithText("支付方式").assertIsDisplayed()
        assertEquals(0, paymentRequests)
    }
    @Test fun paymentSheetKeepsTargetAndFormHeightWhileDetailsLoad() {
        fun ready(project: FeeProject): FeeUiState {
            val original = state(project)
            val config = FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(1000, 5000, 10000), 1, 200000, true, false))
            return if (project.network) FeeUiState(config = config,
                query = FeeQuery(thirdParty = "{}", display = listOf("用户套餐" to "学生宽带套餐", "用户状态" to "正常")))
            else FeeUiState(config = config, levels = original.levels, selections = original.selections,
                names = original.names, query = original.query)
        }
        var project by mutableStateOf(FeeProject.FamilyWater)
        var visible by mutableStateOf(true)
        var target by mutableStateOf(ready(project).currentRoom)
        var current by mutableStateOf(FeeUiState(loading = true))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) { Scaffold {
                    HallFeeDialog(current, visible, project, target, onDismiss = {}, onSelect = { _, _ -> },
                        onRetry = {}, onCreate = { _, _ -> }, onMethod = {}, onPayAccount = { _, _ -> },
                        onCancelPayment = {}, onFinish = {}, onContinuePayment = {}, onRoomAction = {},
                        onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {})
                } }
            }
        }
        for (service in listOf(FeeProject.FamilyWater, FeeProject.Network)) {
            val loaded = ready(service)
            compose.runOnIdle { project = service; target = loaded.currentRoom; current = FeeUiState(loading = true) }
            compose.mainClock.advanceTimeBy(1500)
            val targetBounds = compose.onNodeWithTag("hall-payment-target").assertIsSelected().fetchSemanticsNode().boundsInRoot
            val sheetBounds = compose.onNodeWithTag("hall-payment-content").fetchSemanticsNode().boundsInRoot
            capture("payment-loading-${service.id}")
            val stages = listOf(FeeUiState(config = loaded.config, loading = true, levels = loaded.levels),
                FeeUiState(config = loaded.config, loading = true, levels = loaded.levels,
                    selections = loaded.selections, names = loaded.names), loaded)
            stages.forEach { next ->
                compose.runOnIdle { current = next }
                compose.mainClock.advanceTimeBy(1500)
                val actualTarget = compose.onNodeWithTag("hall-payment-target").fetchSemanticsNode().boundsInRoot
                val actualSheet = compose.onNodeWithTag("hall-payment-content").fetchSemanticsNode().boundsInRoot
                assertEquals("${service.id} target height", targetBounds.height, actualTarget.height, 1f)
                assertEquals("${service.id} sheet height", sheetBounds.height, actualSheet.height, 1f)
                assertEquals("${service.id} sheet top", sheetBounds.top, actualSheet.top, 1f)
            }
            compose.onNodeWithText("¥ 10").assertIsDisplayed().performClick()
            compose.onNodeWithText("充值 ¥ 10.00").assertIsEnabled()
            capture("payment-ready-${service.id}")
            compose.onNodeWithTag("hall-payment-target").performClick().assertIsNotSelected()
            assertEquals(targetBounds.height, compose.onNodeWithTag("hall-payment-target").fetchSemanticsNode().boundsInRoot.height, 1f)
            val formHeight = compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().let { it.bottom - it.top }
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { visible = false; current = FeeUiState(); target = null }
            repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithText("充值 ¥ 10.00").assertExists().assertIsNotEnabled()
            compose.onNodeWithTag("hall-payment-target").assertIsNotSelected().assertIsNotEnabled()
            val summary = if (service.network) "学生宽带套餐" else "剩余用量  86.4"
            compose.onNodeWithText(summary, useUnmergedTree = true).assertExists()
            assertEquals(formHeight.value,
                compose.onNodeWithTag("hall-payment-content").getUnclippedBoundsInRoot().let { (it.bottom - it.top).value }, .001f)
            compose.runOnIdle { visible = true; target = loaded.currentRoom; current = FeeUiState(loading = true) }
            repeat(4) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.onNodeWithText("充值 ¥ 10.00").assertDoesNotExist()
            compose.onNodeWithContentDescription("正在读取充值信息").assertExists()
            compose.onNodeWithTag("hall-payment-target").assertIsSelected()
            compose.mainClock.autoAdvance = true
        }
    }
}
