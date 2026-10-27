package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.pages.HallFeeDialog
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.hallLabel
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

/** Actual native Compose screenshots, backed only by fictitious preview data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HallPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        val output = File("../tools/hall-implementation-preview/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun settings(): ThemeSettings {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return ThemeSettings(app).apply { setTheme(1); setFloating(true); setFloatingStyle(1) }
    }
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
    @Test fun threeCardsAndTemporaryCrossProjectPaymentRemainInHall() {
        val app = compose.activity.application
        val settings = settings()
        val payments = PaymentViewModel(app).apply { initialize(true) }
        val bills = BillViewModel(app).apply { initialize(true) }
        val fee = ViewModelProvider(compose.activity)[FeeViewModel::class.java]
        val hall = ViewModelProvider(compose.activity)[HallViewModel::class.java]
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) } }
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        val official = hall.state.value.selectedRoom!!.binding
        compose.onNodeWithText("水电费").assertIsDisplayed()
        compose.onNodeWithText("校园网").assertIsDisplayed()
        compose.onNodeWithText("代人充值").assertIsDisplayed()
        compose.onNodeWithText("生活缴费").assertDoesNotExist()
        capture("01-hall-light")
        compose.onNodeWithText("照明充值").performClick()
        assertEquals("demo-A-Lighting", fee.state.value.selections["room"])
        compose.onNodeWithText("校区").assertDoesNotExist()
        compose.onNodeWithTag("hall-payment-target").assertIsDisplayed()
        compose.onNodeWithTag("hall-payment-target").assertIsSelected()
        compose.onNodeWithText("充值金额").assertIsDisplayed()
        compose.onNodeWithText("支付方式").assertIsDisplayed()
        capture("02-lighting-recharge"); back()
        compose.onNodeWithText("空调充值").performClick()
        assertEquals("demo-A-AirConditioning", fee.state.value.selections["room"])
        capture("03-air-recharge"); back()
        compose.onNodeWithText("切换").performClick()
        capture("04-saved-rooms")
        compose.onNodeWithText("临时缴费").performClick()
        compose.onNodeWithText("文北宿舍电费").assertIsDisplayed()
        compose.onNodeWithText("商业街水电费").assertIsDisplayed()
        capture("05-projects")
        compose.onNodeWithText("文北宿舍电费").performClick()
        compose.onNodeWithText("校区").assertDoesNotExist()
        compose.onNodeWithText("楼栋").assertIsDisplayed()
        assertNull(fee.state.value.currentRoom)
        capture("06-wenbei-building-first")
        fun choose(label: String, item: String) {
            compose.onNodeWithText(label).performClick()
            compose.onAllNodesWithText(item).onLast().performClick()
            compose.waitForIdle()
        }
        choose("楼栋", "示例B栋空调")
        choose("楼层", "示例楼层")
        choose("房间", "示例B栋-房间B空调")
        assertEquals("demo-building-B-AirConditioning", fee.state.value.selections["building"])
        assertEquals("demo-floor-AirConditioning", fee.state.value.selections["floor"])
        assertEquals("demo-B-AirConditioning", fee.state.value.selections["room"])
        compose.onNode(isToggleable()).assertIsOff()
        capture("07-wenbei-selected")
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("hall-payment-target").assertIsDisplayed()
        assertNull(fee.state.value.roomBinding)
        assertTrue(official.sameTarget(hall.state.value.selectedRoom!!.binding))
        capture("08-wenbei-temporary-recharge"); back()
        compose.onNodeWithTag("network-recharge").performClick()
        compose.onNodeWithText("校园网充值").assertIsDisplayed()
        compose.onNodeWithText("当前登录账户").assertDoesNotExist()
        capture("09-network-recharge"); back()
        compose.onNodeWithText("切换").performClick()
        capture("10-manage-rooms")
        compose.onNodeWithText("新增绑定").performClick()
        compose.onNodeWithText("商业街水电费").assertDoesNotExist()
        compose.onNodeWithText("家属区水电费").assertIsDisplayed()
        capture("19-bind-projects")
        compose.onNodeWithText("家属区水电费").performClick()
        compose.onNodeWithTag("fee-meter-type").assertDoesNotExist()
        choose("楼栋", "示例A栋")
        choose("楼层", "示例楼层")
        choose("房间", "房间A")
        val familyRoom = fee.state.value.currentRoom!!
        compose.onNode(hasText("水费") and hasClickAction()).performClick()
        assertEquals(FeeProject.FamilyWater, fee.state.value.config?.project)
        assertEquals(familyRoom.names, fee.state.value.currentRoom!!.names)
        compose.onNodeWithTag("fee-meter-type").assertIsDisplayed()
        capture("20-family-water-selector"); back()
        compose.onNode(hasText("账单") and hasClickAction()).performClick()
        capture("17-bill-list-light")
        compose.onNodeWithText("统计").performClick()
        capture("18-bill-stats-light")
        compose.onNode(hasText("大厅") and hasClickAction()).performClick()
        compose.onNodeWithText("水电费").assertIsDisplayed()
        compose.runOnIdle { settings.setTheme(2) }
        capture("11-hall-dark")
    }
    @Test fun emptyMultipleAndWaterStatesKeepOneUtilityCard() {
        val app = compose.activity.application
        val settings = settings()
        val model = HallViewModel(app)
        val multiple = model.previewState(multiple = true)
        var state by mutableStateOf(multiple)
        var dark by mutableStateOf(false)
        var paidProject: FeeProject? = null
        var paidTarget: FeeRoomBinding? = null
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    Scaffold {
                        HallPage(settings, 0.dp, dorm = state, isActive = true, onOpenDormFee = { project, target ->
                            paidProject = project; paidTarget = target
                        }, onSelectRoom = { index ->
                            state = DormUiState(multiple.rooms, loaded = true, selectedIndex = index, network = multiple.network)
                        })
                    }
                }
            }
        }
        compose.onNodeWithText(multiple.rooms[1].hallLabel).assertDoesNotExist()
        compose.onNodeWithText("切换").performClick()
        capture("12-multiple-project-rooms")
        compose.onNodeWithText(multiple.rooms[1].hallLabel).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithText(multiple.rooms[1].hallLabel).assertIsDisplayed()
        compose.onNodeWithText(multiple.rooms[0].hallLabel).assertDoesNotExist()
        compose.onAllNodesWithText("水电费").assertCountEquals(1)
        capture("13-switched-wenbei")
        compose.runOnIdle { state = DormUiState(loaded = true, network = multiple.network) }
        capture("14-empty-hall")
        val binding = FeeRoomBinding.from(listOf(FeeLevel("building", 1, "楼栋"), FeeLevel("room", 2, "房间")),
            mapOf("building" to "sample-family", "room" to "sample-water"), mapOf("building" to "示例A栋", "room" to "房间A"))!!
        val water = DormRoom(FeeProject.FamilyWater, binding, listOf(DormMeter(DormMeterKind.Other, binding,
            FeeQuery(thirdParty = "{}", display = listOf("剩余水量(吨)" to "28.60")))), 0)
        compose.runOnIdle { state = DormUiState(listOf(water), loaded = true, network = multiple.network) }
        compose.onNodeWithText("28.60").assertIsDisplayed()
        compose.onNodeWithText("累计用量", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("utility-single-recharge").assertIsDisplayed()
        compose.onNodeWithText("度").assertDoesNotExist()
        capture("15-water-hall")
        compose.runOnIdle { dark = true }
        capture("16-water-dark")
        val electricBinding = FeeRoomBinding.from(listOf(FeeLevel("building", 1, "楼栋"), FeeLevel("room", 2, "房间")),
            mapOf("building" to "sample-family", "room" to "sample-electric"), mapOf("building" to "示例A栋", "room" to "房间A"))!!
        val electric = DormRoom(FeeProject.FamilyElectric, electricBinding, listOf(DormMeter(DormMeterKind.Other, electricBinding,
            FeeQuery(thirdParty = "{}", display = listOf("剩余电量(度)" to "86.4")))), 0)
        val discovered = DormRoom(FeeProject.FamilyElectric, electricBinding, listOf(
            DormMeter(DormMeterKind.Other, electricBinding, electric.meters.single().query, project = FeeProject.FamilyElectric),
            DormMeter(DormMeterKind.Other, binding, water.meters.single().query, project = FeeProject.FamilyWater)), 0)
        compose.runOnIdle { dark = false; state = DormUiState(mergeFamilyRooms(listOf(discovered)), loaded = true, network = multiple.network) }
        compose.onNodeWithText("电费充值").assertIsDisplayed()
        compose.onNodeWithText("水费充值").assertIsDisplayed()
        compose.onAllNodesWithText("电费").assertCountEquals(1)
        compose.onNodeWithText("28.60").assertIsDisplayed()
        compose.onNodeWithText("累计用量", substring = true).assertDoesNotExist()
        compose.onNodeWithText("元").assertDoesNotExist()
        compose.onNodeWithText("水费充值").performClick()
        assertEquals(FeeProject.FamilyWater, paidProject)
        assertSame(binding, paidTarget)
        compose.onNodeWithText("电费充值").performClick()
        assertEquals(FeeProject.FamilyElectric, paidProject)
        assertSame(electricBinding, paidTarget)
        compose.onAllNodesWithText("水电费").assertCountEquals(1)
        capture("21-family-combined")
    }

    @Test fun paymentMethodsStayBesideAmountAndReuseRecipientSelectionStyle() {
        val config = FeeConfiguration(FeeProject.Zhengdong, RechargeConfiguration(FeeProject.Zhengdong.id, "", listOf(1000, 5000, 10000), 1, 200000, true, false),
            roomBindingEnabled = true)
        val pending = PendingRecharge("preview-user", "{}", FeeProject.Zhengdong.id, 1000, "PREVIEW-ORDER")
        var selectedMethod: FeeMethod? = null
        var state by mutableStateOf(FeeUiState(config = config, preview = true, stage = FeeStage.Methods,
            query = FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "86.4 度")),
            order = FeeOrder(pending, listOf(
                FeeMethod("preview-card", "ACCOUNTTSM", "一卡通", false, 0),
                FeeMethod("preview-wechat", "TSM", "微信", false, 0),
            ))))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    HallFeeDialog(state = state, visible = true, project = FeeProject.Zhengdong,
                        fixedTarget = null, onDismiss = {}, onSelect = { _, _ -> }, onRetry = {},
                        onCreate = { _, _ -> }, onMethod = { selectedMethod = it }, onPayAccount = { _, _ -> },
                        onCancelPayment = {}, onFinish = {}, onContinuePayment = {},
                        onRoomAction = {}, onConfirmRoomAction = {}, onDismissRoomDialog = {},
                        onConsumeRoomNotice = {})
                } }
            }
        }
        compose.onNodeWithText("充值金额").assertIsDisplayed()
        compose.onNodeWithText("支付方式").assertIsDisplayed()
        compose.onNodeWithText("一卡通").assertIsDisplayed()
        compose.onNodeWithText("微信").assertIsDisplayed()
        capture("22-payment-method")
        val available = state.order!!.methods.last()
        compose.runOnIdle { state = FeeUiState(config = config, preview = true, stage = FeeStage.Methods,
            error = "所选支付方式不可用，请重新选择", order = FeeOrder(pending, listOf(available))) }
        compose.onNodeWithText("所选支付方式不可用，请重新选择").assertIsDisplayed()
        compose.onNodeWithText("微信").assertIsDisplayed()
        assertNull(selectedMethod)
        compose.onNodeWithText("确定支付").assertIsEnabled().performClick()
        assertSame(available, selectedMethod)
        val second = FeeMethod("preview-wechat-second", "TSM", "另一渠道", false, 0)
        compose.runOnIdle { state = FeeUiState(config = config, preview = true, stage = FeeStage.Methods,
            order = FeeOrder(pending, listOf(available, second))) }
        compose.onNodeWithText("另一渠道").performClick()
        compose.onNodeWithText("确定支付").performClick()
        assertSame(second, selectedMethod)
    }

    @Test fun cardPasswordUsesCenteredDialogAndClearsSubmittedPassword() {
        val method = FeeMethod("preview-card", "ACCOUNTTSM", "一卡通", false, 0)
        val preparation = FeeAccountPreparation("PREVIEW-ORDER", method.id,
            listOf(FeeAccount("preview-account", "统一账户", 8600)), "preview-uuid", "9876543210")
        var state by mutableStateOf(FeeUiState(stage = FeeStage.Password,
            order = FeeOrder(PendingRecharge("preview-user", "{}", FeeProject.Zhengdong.id,
                1000, "PREVIEW-ORDER"), listOf(method)), preparation = preparation))
        var submitted: Pair<String, String>? = null
        var canceled = 0
        var dismissed = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    HallFeeDialog(state = state, visible = true, project = FeeProject.Zhengdong,
                        fixedTarget = null, onDismiss = { dismissed++ }, onSelect = { _, _ -> }, onRetry = {},
                        onCreate = { _, _ -> }, onMethod = {}, onPayAccount = { type, password ->
                            submitted = type to password
                            state = FeeUiState(stage = FeeStage.Password, order = state.order,
                                preparation = preparation, error = "账户密码错误")
                        }, onCancelPayment = { canceled++; state = FeeUiState() },
                        onFinish = {}, onContinuePayment = {}, onRoomAction = {},
                        onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {})
                } }
            }
        }
        compose.onNodeWithText("一卡通支付").assertIsDisplayed()
        compose.onNodeWithText("充值金额").assertDoesNotExist()
        compose.onNodeWithTag("hall-payment-target").assertDoesNotExist()
        val button = compose.onNodeWithText("确定支付")
        button.assertIsNotEnabled()
        val center = button.fetchSemanticsNode().boundsInRoot.center.x
        assertEquals(center, compose.onNodeWithText("¥ 10.00").fetchSemanticsNode().boundsInRoot.center.x, 1f)
        assertEquals(center, compose.onNodeWithText("可用余额 ¥ 86.00").fetchSemanticsNode().boundsInRoot.center.x, 1f)
        capture("23-card-password-centered")
        val input = compose.onNode(SemanticsMatcher.keyIsDefined(
            androidx.compose.ui.semantics.SemanticsProperties.Password))
        input.performTextInput("123456")
        button.performClick()
        assertEquals("preview-account" to "123456", submitted)
        compose.onNodeWithText("账户密码错误").assertIsDisplayed()
        input.assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString("")))
        button.assertIsNotEnabled()
        back()
        assertEquals(1, canceled)
        assertEquals(0, dismissed)
        compose.onNodeWithText("一卡通支付").assertDoesNotExist()
    }

    @Test fun failedBindingKeepsSelectionAndUnknownBindingPreventsResubmission() {
        val levels = listOf(FeeLevel("building", 1, "楼栋"), FeeLevel("room", 2, "房间"))
        val target = FeeRoomBinding.from(levels, mapOf("building" to "sample-building", "room" to "sample-room"),
            mapOf("building" to "示例A栋", "room" to "房间A照明"))!!
        val config = FeeConfiguration(FeeProject.Zhengdong, RechargeConfiguration(FeeProject.Zhengdong.id, "", listOf(1000), 1, 200000, true, false),
            roomBindingEnabled = true)
        fun queryState(known: Boolean = true, busy: Boolean = false, error: String? = null,
            notice: String? = null, dialog: FeeRoomDialog? = null) = FeeUiState(config = config,
            levels = levels, selections = target.selections, names = target.names,
            options = levels.associate { it.number to listOf(FeeOption(target.selections.getValue(it.key), target.names.getValue(it.key))) },
            query = FeeQuery(thirdParty = "{}"), roomBindingKnown = known, roomBusy = busy,
            error = error, roomNotice = notice, roomDialog = dialog)
        var state by mutableStateOf(queryState())
        var saveOnly by mutableStateOf(false)
        var requests = 0
        var consumed = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold {
                    HallFeeDialog(state = state, visible = true, project = FeeProject.Zhengdong,
                        fixedTarget = null, saveOnly = saveOnly, onDismiss = {}, onSelect = { _, _ -> }, onRetry = {},
                        onCreate = { _, _ -> }, onMethod = {}, onPayAccount = { _, _ -> }, onCancelPayment = {},
                        onFinish = {}, onContinuePayment = {}, onRoomAction = {
                            requests++; state = queryState(dialog = FeeRoomDialog(FeeRoomAction.Save, target, null))
                        }, onConfirmRoomAction = { state = queryState(busy = true) },
                        onDismissRoomDialog = {}, onConsumeRoomNotice = { consumed++ })
                } }
            }
        }
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("绑定").performClick()
        compose.runOnIdle { state = queryState(error = "绑定未更新") }
        compose.onNodeWithText("绑定未更新").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsDisplayed()
        compose.onNodeWithText("充值金额").assertDoesNotExist()
        compose.runOnIdle { state = queryState(notice = "已保存为我的房间") }
        compose.onNodeWithText("下一步").assertIsDisplayed()
        assertEquals(0, consumed)
        compose.runOnIdle { saveOnly = true; state = queryState(known = false, error = "绑定结果无法确认") }
        compose.onNodeWithText("绑定房间").assertIsNotEnabled()
        assertEquals(1, requests)
    }
}
