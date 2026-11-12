package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.*
import com.mercury.yktwx.ui.components.*
import org.junit.Assert.*
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
class ReadLayoutMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val ready = PreviewData.hallState()

    private fun content(block: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold { block() } } }
        }
        frames(50)
    }

    private fun frames(count: Int = 1) {
        repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun textLayout(text: String): TextLayoutResult {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single()
    }

    private fun assertContinuous(before: Float, after: Float, samples: List<Float>) {
        val low = minOf(before, after); val high = maxOf(before, after)
        assertTrue("Fixture must change height: $before -> $after: $samples", high - low > 1f)
        assertTrue("Height must pass through intermediate values: $samples", samples.any { it > low + 1 && it < high - 1 })
        assertTrue("Height must not overshoot: $samples", samples.all { it >= low - 1 && it <= high + 1 })
    }

    @Test fun singleWaterMeterReplacesTwoSkeletonsAtFinalWidthAndMovesFollowingCardContinuously() {
        val settings = themeSettingsFixture(compose.activity.application)
        val current = mutableStateOf(DormUiState(network = ready.network, roomsLoaded = false))
        var paid: FeeRoomBinding? = null
        content { HallPage(settings, 0.dp, dorm = current.value, onOpenDormFee = { _, room -> paid = room }) }
        val before = bounds("hall-utility-card")
        val following = bounds("hall-network-card").top
        val binding = ready.rooms.first().binding
        val explanation = "计量说明".repeat(20)
        val meter = DormMeter(DormMeterKind.Other, binding,
            FeeQuery(display = listOf("剩余水量" to "12 吨", "说明" to explanation)), project = FeeProject.FamilyWater)
        val room = DormRoom(FeeProject.FamilyWater, binding, listOf(meter), 0)
        compose.runOnIdle { current.value = DormUiState(listOf(room), loaded = true, network = ready.network) }
        val heights = mutableListOf<Float>()
        val widths = mutableListOf<Float>()
        repeat(24) {
            frames()
            heights += bounds("hall-utility-card").height
            val buttons = compose.onAllNodesWithTag("utility-single-recharge").fetchSemanticsNodes()
            // During height expansion the button can sit below the animated clip.
            // Read its measured width, not the currently visible intersection.
            if (buttons.isNotEmpty()) compose.onNodeWithTag("utility-single-recharge").getUnclippedBoundsInRoot().let {
                widths += (it.right - it.left).value
            }
        }
        val after = bounds("hall-utility-card")
        assertContinuous(before.height, after.height, heights)
        assertTrue("The incoming panel must not grow sideways and rewrap: $widths", widths.all { it == widths.first() })
        val cardBounds = compose.onNodeWithTag("hall-utility-card").getUnclippedBoundsInRoot()
        assertTrue("The only meter should occupy the full row", widths.last() > (cardBounds.right - cardBounds.left).value * .7f)
        assertEquals(after.height - before.height, bounds("hall-network-card").top - following, 1f)
        assertFalse(textLayout("说明 $explanation").hasVisualOverflow)
        compose.onNodeWithTag("utility-single-recharge").assertIsEnabled().performClick()
        assertTrue(binding.sameTarget(paid))
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi", fontScale = 1.5f)
    fun unboundValueLabelsWrapInsteadOfBeingClipped() {
        val settings = themeSettingsFixture(compose.activity.application)
        content { HallPage(settings, 0.dp, dorm = DormUiState(loaded = true)) }
        val layout = textLayout("尚未添加")
        assertTrue("Large text must be allowed to wrap", layout.lineCount > 1)
        assertFalse("Wrapped value labels must not be clipped", layout.hasVisualOverflow)
        val card = bounds("hall-utility-card")
        val label = compose.onNodeWithText("尚未添加").fetchSemanticsNode().boundsInRoot
        assertTrue("Label must stay inside the utility card", label.bottom <= card.bottom + .5f)
    }

    @Test fun twoMeterButtonsAlignWhenOnlyOneDescriptionWraps() {
        val settings = themeSettingsFixture(compose.activity.application)
        val room = ready.rooms.first()
        val long = "附加说明".repeat(25)
        val light = room.meters.first()
        val meters = listOf(DormMeter(light.kind, light.room,
            FeeQuery(display = listOf("剩余电量" to "86.4 度", "说明" to long))), room.meters.last())
        content { HallPage(settings, 0.dp, dorm = DormUiState(
            listOf(DormRoom(room.project, room.binding, meters, 0)), loaded = true, network = ready.network)) }
        val lighting = compose.onNode(hasText("照明充值") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val air = compose.onNode(hasText("空调充值") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(lighting.top, air.top, 1f)
        assertEquals(lighting.bottom, air.bottom, 1f)
        assertTrue(textLayout("说明 $long").lineCount > 1)
        assertFalse(textLayout("说明 $long").hasVisualOverflow)
    }

    @Test fun longNumericMeterReadingsFitOneLineAndUnitsRemainBesideThem() {
        val settings = themeSettingsFixture(compose.activity.application)
        val room = ready.rooms.first()
        val number = "123456789.1234"
        val label = "剩余可用电量及本期结算余额"
        val meters = room.meters.map { meter -> DormMeter(meter.kind, meter.room,
            FeeQuery(display = listOf(label to "$number 度"))) }
        content { HallPage(settings, 0.dp, dorm = DormUiState(
            listOf(DormRoom(room.project, room.binding, meters, 0)), loaded = true, network = ready.network)) }
        val readings = compose.onAllNodes(hasText(number) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, readings.size)
        readings.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertEquals("Signs and decimals must remain together", 1, layouts.single().lineCount)
            assertFalse("Every digit must remain visible", layouts.single().hasVisualOverflow)
        }
        assertEquals(2, compose.onAllNodesWithText("度", useUnmergedTree = true).fetchSemanticsNodes().size)
        val lighting = compose.onNode(hasText("照明充值") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val air = compose.onNode(hasText("空调充值") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(lighting.top, air.top, 1f)
    }

    @Test fun singleLineWaterReadingStaysNearItsButtonBesideMultilineElectricity() {
        val settings = themeSettingsFixture(compose.activity.application)
        val binding = ready.rooms.first().binding
        val number = "-30136.69"
        val meters = listOf(
            DormMeter(DormMeterKind.Other, binding, FeeQuery(display = listOf(
                "信息" to "电表结算信息较长时仍然完整显示并保持自然换行")), project = FeeProject.FamilyElectric),
            DormMeter(DormMeterKind.Other, binding, FeeQuery(display = listOf(
                "剩余用量" to "$number 度")), project = FeeProject.FamilyWater))
        content { HallPage(settings, 0.dp, dorm = DormUiState(listOf(
            DormRoom(FeeProject.FamilyElectric, binding, meters, 0)), loaded = true, network = ready.network)) }
        val numericNode = compose.onNode(hasText(number) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true).fetchSemanticsNode()
        val unitNode = compose.onNodeWithText("度", useUnmergedTree = true).fetchSemanticsNode()
        val water = compose.onNode(hasText("水费充值") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val electric = compose.onNode(hasText("电费充值") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val bitmap = compose.onNodeWithTag("hall-utility-card").captureToImage().asAndroidBitmap()
        val file = java.io.File("../tools/read-motion-audit/water-electricity-refined.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(1, textLayout(number).lineCount)
        assertFalse(textLayout(number).hasVisualOverflow)
        assertEquals(water.top, electric.top, 1f)
        val group = bounds("utility-readings-FamilyWater-Other")
        assertEquals("The reading group and action keep one standard gap", 18f, water.top - group.bottom, 1f)
        val numericLayout = textLayout(number)
        val unitLayout = textLayout("度")
        assertEquals(numericNode.boundsInRoot.top + numericLayout.lastBaseline,
            unitNode.boundsInRoot.top + unitLayout.lastBaseline, 1f)
    }

    @Test fun selectedServerRoomNameWrapsAndFieldHeightChangesContinuously() {
        val name = "服务端提供的完整楼栋及房间名称".repeat(4)
        val selected = mutableIntStateOf(0)
        content {
            ReadLayout(Modifier.fillMaxWidth().padding(20.dp).testTag("server-room-field")) {
                FeeLocationDropdown("房间", listOf(name), selected.intValue, true) {}
            }
        }
        val before = bounds("server-room-field").height
        compose.runOnIdle { selected.intValue = 1 }
        val heights = (0 until 24).map { frames(); bounds("server-room-field").height }
        assertContinuous(before, bounds("server-room-field").height, heights)
        assertTrue(textLayout(name).lineCount > 1)
        assertFalse(textLayout(name).hasVisualOverflow)
    }

    @Test fun skeletonPaintWaitsThenFadesAndFastSubsequentReadsDoNotFlash() {
        val pending = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            Box(Modifier.size(40.dp).background(Color.White).testTag("skeleton-paint-test")) {
                ReadContent(pending.value, pending = { it }) { reading ->
                    Box(Modifier.size(40.dp).background(if (reading) Color.Red else Color.Blue))
                }
            }
        } }
        fun pixel(): Int = compose.onNodeWithTag("skeleton-paint-test").captureToImage().asAndroidBitmap().let {
            it.getPixel(it.width / 2, it.height / 2)
        }
        frames(10)
        assertEquals("Brief reads must not flash skeleton paint", android.graphics.Color.WHITE, pixel())
        val samples = (0 until 18).map { frames(); android.graphics.Color.green(pixel()) }
        assertTrue("Skeleton paint must fade in through intermediate opacity", samples.any { it in 1..254 })
        assertEquals(0, samples.last())
        compose.runOnIdle { pending.value = false }
        val blue = (0 until 15).map { frames(); android.graphics.Color.blue(pixel()) }
        assertTrue("Skeleton replacement must also fade", blue.any { it in 1..254 })
        assertEquals(android.graphics.Color.BLUE, pixel())
        compose.runOnIdle { pending.value = true }
        frames(12)
        assertEquals("A new brief read restarts the paint delay", android.graphics.Color.WHITE, pixel())
        compose.runOnIdle { pending.value = false }
        frames(15)
        frames(15)
        assertEquals(android.graphics.Color.BLUE, pixel())
    }

    @Test fun electricRechargeFormStaysMountedWhileQueryLoadsAndRefreshes() {
        val room = ready.rooms.first()
        val rules = RechargeConfiguration("electric", "demo", listOf(1000, 5000, 10000), 1, 50000, true, false)
        val config = FeeConfiguration(room.project, rules)
        val levels = room.binding.selections.keys.mapIndexed { index, key -> FeeLevel(key, index + 1, key) }
        fun state(query: FeeQuery?, loading: Boolean = false, selecting: Boolean = false) = FeeUiState(
            config = config, levels = levels, selections = room.binding.selections, names = room.binding.names,
            query = query, loading = loading, selecting = selecting)
        val query = FeeQuery(display = listOf("剩余用量" to "86.4"), thirdParty = "{}")
        val current = mutableStateOf(state(query))
        content { HallFeeDialog(current.value, true, room.project, room.binding,
            onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> }, onMethod = {},
            onPayAccount = { _, _ -> }, onCancelPayment = {}, onFinish = {}, onContinuePayment = {},
            onRoomAction = {}, onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {}) }
        val input = compose.onNodeWithTag("payment-amount-input")
        val identity = input.fetchSemanticsNode().id
        compose.runOnIdle { current.value = state(query) }
        frames(24)
        val before = input.captureToImage().asAndroidBitmap()
        for (next in listOf(state(query), state(query, loading = true), state(null, loading = true),
            state(null, selecting = true), state(query))) {
            compose.runOnIdle { current.value = next }
            repeat(24) {
                frames()
                compose.onNodeWithContentDescription("正在读取充值信息").assertDoesNotExist()
                assertEquals("Query progress must not recreate amount controls", identity, input.fetchSemanticsNode().id)
            }
        }
        assertTrue("Unchanged form must return to identical paint", before.sameAs(input.captureToImage().asAndroidBitmap()))
        compose.onNode(hasText("¥ 50") and hasClickAction()).assertIsEnabled().performClick()
        frames(20)
        compose.onNode(hasText("充值 ¥ 50.00") and hasClickAction()).assertIsEnabled()
    }

    @Test fun fixedRoomHeadingDoesNotFadeWhenItsQueryCompletes() {
        val room = ready.rooms.first()
        val config = FeeConfiguration(room.project,
            RechargeConfiguration("electric", "demo", listOf(1000, 5000, 10000), 1, 50000, true, false))
        val levels = room.binding.selections.keys.mapIndexed { index, key -> FeeLevel(key, index + 1, key) }
        fun state(query: FeeQuery?, loading: Boolean = false) = FeeUiState(config = config,
            levels = levels, selections = room.binding.selections, names = room.binding.names,
            query = query, loading = loading)
        val current = mutableStateOf(state(null, loading = true))
        content { HallFeeDialog(current.value, true, room.project, room.binding,
            onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> }, onMethod = {},
            onPayAccount = { _, _ -> }, onCancelPayment = {}, onFinish = {}, onContinuePayment = {},
            onRoomAction = {}, onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {}) }
        val heading = compose.onNode(hasText(room.binding.dialogLabel) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
        val identity = heading.fetchSemanticsNode().id
        val baseline = heading.captureToImage().asAndroidBitmap()
        compose.runOnIdle { current.value = state(FeeQuery(display = listOf("剩余电量" to "86.4 度"), thirdParty = "{}")) }
        repeat(24) {
            frames()
            assertEquals("Query completion must not recreate the room heading", identity, heading.fetchSemanticsNode().id)
            assertTrue("An unchanged room heading must keep identical paint", baseline.sameAs(heading.captureToImage().asAndroidBitmap()))
        }
    }

    @Test fun waterElectricDrawerKeepsRoomAndAmountControlsDuringOpeningQueryCompletion() {
        val room = ready.rooms.first()
        val config = FeeConfiguration(room.project,
            RechargeConfiguration("electric", "demo", listOf(1000, 5000, 10000), 1, 50000, true, false))
        val levels = room.binding.selections.keys.mapIndexed { index, key -> FeeLevel(key, index + 1, key) }
        val current = mutableStateOf(FeeUiState(loading = true))
        val visible = mutableStateOf(false)
        content { HallFeeDialog(current.value, visible.value, room.project, room.binding,
            onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> }, onMethod = {},
            onPayAccount = { _, _ -> }, onCancelPayment = {}, onFinish = {}, onContinuePayment = {},
            onRoomAction = {}, onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {}) }
        compose.runOnIdle { visible.value = true }
        var headingId: Int? = null
        var inputId: Int? = null
        val tops = mutableListOf<Float>()
        repeat(40) { frame ->
            if (frame == 6) compose.runOnIdle { current.value = FeeUiState(config = config, levels = levels, loading = true) }
            if (frame == 9) compose.runOnIdle { current.value = FeeUiState(config = config, levels = levels,
                selections = room.binding.selections, names = room.binding.names,
                query = FeeQuery(display = listOf("剩余电量" to "86.4 度"), thirdParty = "{}")) }
            frames()
            if (frame < 9) compose.onNodeWithTag("payment-amount-input").assertDoesNotExist()
            val headings = compose.onAllNodes(hasText(room.binding.dialogLabel) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("Opening must never duplicate the room heading", headings.size <= 1)
            headings.singleOrNull()?.let {
                headingId?.let { previous -> assertEquals("Room lookup must not recreate the heading", previous, it.id) }
                headingId = it.id
            }
            val inputs = compose.onAllNodesWithTag("payment-amount-input").fetchSemanticsNodes()
            inputs.singleOrNull()?.let {
                inputId?.let { previous -> assertEquals("The verified query must reuse existing amount controls", previous, it.id) }
                inputId = it.id
            }
            compose.onAllNodesWithTag("hall-payment-content").fetchSemanticsNodes().singleOrNull()?.let {
                if (it.boundsInRoot.height > 0) tops += it.boundsInRoot.top
            }
        }
        assertNotNull(headingId)
        assertNotNull(inputId)
        assertTrue("The opening must move upwards without jumping back: $tops",
            tops.zipWithNext().all { (before, after) -> after <= before + 1f })
        compose.onNodeWithText("剩余电量  86.4 度", useUnmergedTree = true).assertExists()
    }

    @Test fun sameRoomRefreshKeepsSummaryPaintUntilSettledAndFailureClearsOldReadings() {
        val room = ready.rooms.first()
        val config = FeeConfiguration(room.project,
            RechargeConfiguration("electric", "demo", listOf(1000, 5000, 10000), 1, 50000, true, false))
        val levels = room.binding.selections.keys.mapIndexed { index, key -> FeeLevel(key, index + 1, key) }
        val query = FeeQuery(display = listOf("剩余电量" to "86.4 度"), thirdParty = "{}")
        fun state(query: FeeQuery?, loading: Boolean = false, error: String? = null) = FeeUiState(config = config,
            levels = levels, selections = room.binding.selections, names = room.binding.names,
            query = query, loading = loading, error = error)
        val current = mutableStateOf(state(query))
        content { HallFeeDialog(current.value, true, room.project, room.binding,
            onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> }, onMethod = {},
            onPayAccount = { _, _ -> }, onCancelPayment = {}, onFinish = {}, onContinuePayment = {},
            onRoomAction = {}, onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {}) }
        val card = compose.onNodeWithTag("hall-payment-target")
        val baseline = card.captureToImage().asAndroidBitmap()
        for (next in listOf(state(null, loading = true), state(query))) {
            compose.runOnIdle { current.value = next }
            repeat(24) {
                frames()
                assertTrue("A same-target refresh must not blank or fade the summary", baseline.sameAs(card.captureToImage().asAndroidBitmap()))
            }
        }
        compose.runOnIdle { current.value = state(null, error = "读取失败") }
        frames(24)
        compose.onNodeWithText("剩余电量  86.4 度", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(room.binding.dialogLabel, useUnmergedTree = true).assertExists()
        val failed = card.captureToImage().asAndroidBitmap()
        compose.runOnIdle { current.value = state(null, loading = true) }
        frames(28)
        assertFalse("Retry must not treat a failed read as a successful empty result",
            failed.sameAs(card.captureToImage().asAndroidBitmap()))
        compose.onNodeWithText("剩余电量  86.4 度", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun profileNameExpandsFromSkeletonAndUnchangedRefreshKeepsHeight() {
        val settings = themeSettingsFixture(compose.activity.application)
        val current = mutableStateOf(MyUiState())
        content { PersonalProfilePage(current.value, settings, {}, {}) }
        val before = bounds("profile-details-card").height
        val name = "示例姓名".repeat(18)
        val profile = PersonalProfile(name, "202600000001", "示例部门", "示例专业", null)
        compose.runOnIdle { current.value = MyUiState(profile = profile, loaded = true) }
        val heights = (0 until 24).map { frames(); bounds("profile-details-card").height }
        val after = bounds("profile-details-card").height
        assertContinuous(before, after, heights)
        assertFalse(textLayout(name).hasVisualOverflow)
        compose.runOnIdle { current.value = MyUiState(profile = profile, loaded = true, loading = true, displayRestored = true) }
        repeat(24) { frames(); assertEquals(after, bounds("profile-details-card").height, 1f) }
    }

    @Test fun cachedUtilityPanelsStayMountedAndPaintUnchangedThroughoutPartialRefresh() {
        val settings = themeSettingsFixture(compose.activity.application)
        val room = ready.rooms.first()
        val current = mutableStateOf(DormUiState(listOf(room), loaded = true,
            network = ready.network, displayRestored = true))
        val active = mutableStateOf(true)
        var activations = 0
        content {
            LaunchedEffect(active.value) {
                if (active.value) {
                    activations++
                    current.value = DormUiState(listOf(room), loaded = true, loading = true,
                        network = ready.network, displayRestored = true)
                }
            }
            HallPage(settings, 0.dp, dorm = current.value, isActive = active.value)
        }
        val panels = listOf("Lighting", "AirConditioning").map {
            compose.onNodeWithTag("utility-panel-Zhengdong-$it")
        }
        val baselines = panels.map { it.captureToImage().asAndroidBitmap() }
        val identities = panels.map { it.fetchSemanticsNode().id }
        compose.runOnIdle { active.value = false }
        frames(2)
        compose.runOnIdle { active.value = true }
        frames(2)
        assertEquals(2, activations)
        val partial = DormRoom(room.project, room.binding, room.meters.map { meter ->
            DormMeter(meter.kind, meter.room, meter.query, project = room.project, updating = true)
        }, 1, pendingMeters = room.meters.map { DormMeterSlot(it.kind, room.project) })
        val refreshed = DormRoom(room.project, room.binding, partial.meters.reversed(), 2)
        for (snapshot in listOf(room, partial, refreshed)) {
            compose.runOnIdle { current.value = DormUiState(listOf(snapshot), loading = snapshot !== refreshed,
                loaded = true, network = ready.network, displayRestored = true) }
            repeat(24) {
                frames()
                panels.forEachIndexed { index, panel ->
                    assertEquals("Cached panel was recreated during refresh", identities[index], panel.fetchSemanticsNode().id)
                    assertTrue("Unchanged cached readings must not fade during refresh",
                        baselines[index].sameAs(panel.captureToImage().asAndroidBitmap()))
                }
            }
        }
    }

    @Test fun serverAmountsExpandToThreeRowsAndLatestPresetRemainsSelectable() {
        val current = mutableStateOf(RechargeUiState(visible = true, loading = true))
        var submitted = 0L
        content { RechargeDialog(current.value, {}, { submitted = it }, {}) }
        val before = bounds("sheet-read-layout").height
        val config = RechargeConfiguration("demo", "demo", (1L..7L).map { it * 1000 }, 1, 50000, true, false)
        compose.runOnIdle { current.value = RechargeUiState(visible = true, config = config) }
        val heights = (0 until 32).map { frames(); bounds("sheet-read-layout").height }
        val after = bounds("sheet-read-layout").height
        assertContinuous(before, after, heights)
        val first = compose.onNode(hasText("¥ 10") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val middle = compose.onNode(hasText("¥ 40") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val last = compose.onNode(hasText("¥ 70") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue(first.top < middle.top && middle.top < last.top)
        compose.onNode(hasText("¥ 70") and hasClickAction()).assertIsEnabled().performClick()
        frames(20)
        compose.onNode(hasText("充值 ¥ 70.00") and hasClickAction()).assertIsEnabled().performClick()
        assertEquals(7000L, submitted)
    }

    @Test fun serverAmountsCanShrinkBackToOneRowWithoutKeepingObsoleteChoices() {
        fun config(amounts: List<Long>) = RechargeConfiguration("demo", "demo", amounts, 1, 50000, false, false)
        val current = mutableStateOf(RechargeUiState(visible = true, config = config((1L..6L).map { it * 1000 })))
        content { RechargeDialog(current.value, {}, {}, {}) }
        val before = bounds("sheet-read-layout").height
        compose.runOnIdle { current.value = RechargeUiState(visible = true, config = config(listOf(2000))) }
        val heights = (0 until 32).map { frames(); bounds("sheet-read-layout").height }
        val after = bounds("sheet-read-layout").height
        assertContinuous(before, after, heights)
        compose.onNode(hasText("¥ 20") and hasClickAction()).assertIsEnabled()
        compose.onNode(hasText("¥ 60") and hasClickAction()).assertDoesNotExist()
    }
}
