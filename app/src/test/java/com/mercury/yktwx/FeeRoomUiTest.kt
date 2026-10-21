package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.FeePage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeeRoomUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    private val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("room", 2, "房间"))
    private val options = listOf(FeeOption("demo-light&示例照明", "示例照明"), FeeOption("demo-air&示例空调", "示例空调"))
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
    }
    private fun target(index: Int) = FeeRoomBinding.from(levels,
        mapOf("campus" to "demo-campus", "room" to options[index].id),
        mapOf("campus" to "示例校区", "room" to options[index].name))!!
    private fun state(bound: Boolean = false, enabled: Boolean = true, dialog: FeeRoomDialog? = null) = FeeUiState(
        config = FeeConfiguration(FeeProject.Wenbei, "示例宿舍电费",
            RechargeConfiguration("409", "", "", 0, listOf(1000, 5000, 10000), 1, 100000, true, false), enabled),
        levels = levels, options = mapOf(2 to options), selections = target(0).selections, names = target(0).names,
        query = FeeQuery(thirdParty = "{}", display = listOf("当前剩余电量" to "100 度")),
        hiddenLevels = setOf("campus"), roomBinding = if (bound) target(0) else null, roomDialog = dialog,
    )
    private fun content(state: () -> FeeUiState, onAction: () -> Unit = {}, onConfirm: () -> Unit = {}, onDismiss: () -> Unit = {}) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    FeePage(state(), settings, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, {}, {},
                        onRoomAction = onAction, onConfirmRoomAction = onConfirm, onDismissRoomDialog = onDismiss)
                }
            }
        }
    }
    private fun screenshot(name: String) {
        val output = File("../tools/$name.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun topRightUsesIconsAndSwitchesWithoutAddingRoomCard() {
        val current = mutableStateOf(state())
        var clicks = 0
        content({ current.value }, onAction = { clicks++ })
        compose.onNodeWithContentDescription("保存房间").assertIsEnabled().performClick()
        assertEquals(1, clicks)
        compose.onNodeWithText("保存").assertDoesNotExist()
        compose.onNodeWithText("我的房间").assertDoesNotExist()
        screenshot("fee-room-save")
        compose.runOnIdle { current.value = state(bound = true) }
        compose.onNodeWithContentDescription("解绑房间").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("保存房间").assertDoesNotExist()
        compose.onNodeWithText("解绑").assertDoesNotExist()
        assertEquals(2, clicks)
        screenshot("fee-room-unbind")
    }
    @Test fun unsupportedProjectHasNoManagementIcon() {
        content({ state(enabled = false) })
        compose.onNodeWithContentDescription("保存房间").assertDoesNotExist()
        compose.onNodeWithContentDescription("解绑房间").assertDoesNotExist()
    }
    @Test fun incompleteSelectionDisablesSaveIcon() {
        val complete = state()
        content({ FeeUiState(config = complete.config, levels = levels, options = complete.options) })
        compose.onNodeWithContentDescription("保存房间").assertIsNotEnabled()
    }
    @Test fun unboundSaveDialogOffersExplicitSaveOrCancel() {
        var confirmed = false
        var canceled = false
        content({ state(dialog = FeeRoomDialog(FeeRoomAction.Save, target(0), null)) },
            onConfirm = { confirmed = true }, onDismiss = { canceled = true })
        compose.onNodeWithText("保存房间").assertExists()
        compose.onNodeWithText("保存「示例照明」，下次自动选择").assertExists()
        compose.onNodeWithText("下次自动选择此房间").assertDoesNotExist()
        screenshot("fee-room-save-dialog")
        compose.onNodeWithText("取消").performClick()
        assertTrue(canceled)
        compose.onNodeWithText("保存").performClick()
        assertTrue(confirmed)
    }
    @Test fun fullMeterNameUsesCompactSummaryWithoutRepeatingLocation() {
        val room = FeeRoomBinding.parse("campus:demo-campus#\$#示例大学;building:demo-building#\$#示例A栋;floor:demo-floor#\$#示例楼层;room:demo-room#\$#示例A栋-房间A照明")!!
        content({ state(dialog = FeeRoomDialog(FeeRoomAction.Save, room, null)) })
        compose.onNodeWithText("保存「示例A栋-房间A照明」，下次自动选择").assertExists()
        compose.onNodeWithText("示例大学 · 示例A栋 · 示例楼层 · 示例A栋-房间A照明").assertDoesNotExist()
        screenshot("fee-room-save-dialog-full-name")
    }
    @Test fun replacementDialogMakesOldAndNewTargetsExplicit() {
        content({ state(dialog = FeeRoomDialog(FeeRoomAction.Save, target(1), target(0))) })
        compose.onNodeWithText("更换房间").assertExists()
        compose.onNodeWithText("将「示例照明」更换为「示例空调」").assertExists()
        compose.onNodeWithText("原房间：示例照明").assertDoesNotExist()
        compose.onNodeWithText("新房间：示例空调").assertDoesNotExist()
        compose.onNodeWithText("更换").assertExists()
        screenshot("fee-room-replace-dialog")
    }
    @Test fun unbindRequiresConfirmation() {
        var confirmed = false
        content({ state(bound = true, dialog = FeeRoomDialog(FeeRoomAction.Unbind, target(0), target(0))) },
            onConfirm = { confirmed = true })
        compose.onNodeWithText("解绑房间").assertExists()
        compose.onNodeWithText("解绑「示例照明」").assertExists()
        screenshot("fee-room-unbind-dialog")
        assertFalse(confirmed)
        compose.onNodeWithText("解绑").performClick()
        assertTrue(confirmed)
    }
}
