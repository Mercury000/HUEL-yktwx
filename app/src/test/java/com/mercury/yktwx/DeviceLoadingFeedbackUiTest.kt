package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.fixtureLifeDeviceApi
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.LifeDevicePage
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceLoadingFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val models = ViewModelStore()
    @After fun cleanup() { models.clear() }

    private fun model(): LifeDevicesViewModel {
        val store = object : LifeDeviceStore {
            override fun account(): LifeAccount? = null
            override fun session(): WaterSession? = null
            override fun saveAccount(account: LifeAccount) { error("No live accounts") }
            override fun saveSession(session: WaterSession) { error("No device commands") }
            override fun clearAccount() {}
            override fun clearSession() {}
        }
        return lifeDevicesViewModelFixture(compose.activity.application,
            fixtureLifeDeviceApi("fixture-secret", { error("No live requests") }), store)
            .also { models.put("loading-feedback", it) }
    }

    private fun device(shower: Boolean = false) = LifeDevice("fixture-device", "fixture-serial", "测试设备",
        if (shower) "08" else "04", "org", "org", "shop", "imei", "学校", "", "after", false, true,
        null, listOf(LifeSku("program", "测试程序", true, "2.50")), emptyList())

    private fun assertSpinners(tag: String, count: Int) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true).assertCountEquals(count)
        if (count == 1) compose.onNodeWithTag("$tag-action-progress", useUnmergedTree = true).assertIsDisplayed()
        else compose.onNodeWithTag("$tag-action-progress", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }; compose.waitForIdle()
        val file = File("../build/device-loading-feedback/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun pangguiWaterAndShowerKeepOneButtonSpinnerThroughQueryCommandsAndWaiting() {
        val model = model()
        val settings = themeSettingsFixture(compose.activity.application)
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, connectedPhone = "fixture-account",
            feature = LifeFeature.Water, scannedSerial = "fixture-serial", loadingDevice = true))
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            LifeDevicePage(state, settings, model, true, {}, nowMillis = { 100_000L })
        } } }
        assertSpinners("water", 1)
        for (shower in listOf(false, true)) {
            val device = device(shower)
            val session = WaterSession(device, "program", WaterPhase.Starting)
            compose.runOnIdle { state = state.copy(device = device, selectedSkuId = "program", loadingDevice = false,
                water = session, waterBusy = true, error = null) }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            compose.onNodeWithText("正在启动设备").assertIsDisplayed()
            assertSpinners("water", 1)
            if (shower) capture("shower-starting")
            compose.runOnIdle { state = state.copy(waterBusy = false) }
            assertSpinners("water", 1)
            compose.runOnIdle { state = state.copy(error = "设备响应较慢，请重试") }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            assertSpinners("water", 0)
            compose.onNodeWithTag("water-primary-action").assertIsEnabled().assertTextContains("重试")
            compose.runOnIdle { state = state.copy(water = session.copy(phase = WaterPhase.Running, startedAtMillis = 90_000), error = null) }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            assertSpinners("water", 0)
            compose.onNodeWithTag("water-primary-action").assertIsEnabled()
            compose.runOnIdle { state = state.copy(waterBusy = true) }
            assertSpinners("water", 1)
            compose.runOnIdle { state = state.copy(water = state.water!!.copy(phase = WaterPhase.Stopping)) }
            compose.onNodeWithText("正在结束设备").assertIsDisplayed()
            assertSpinners("water", 1)
            compose.runOnIdle { state = state.copy(waterBusy = false) }
            assertSpinners("water", 1)
            compose.runOnIdle { state = state.copy(water = state.water!!.copy(phase = WaterPhase.Ended, finalized = false)) }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            assertSpinners("water", 1)
            compose.runOnIdle { state = state.copy(water = state.water!!.copy(finalized = true)) }
            assertSpinners("water", 0)
        }
        compose.runOnIdle { state = state.copy(water = null, restoring = true) }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        assertSpinners("water", 1)
        val unsupported = WaterSession(device().copy(supportsFinish = false), "program", WaterPhase.Running)
        compose.runOnIdle { state = state.copy(restoring = false, water = unsupported) }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        assertSpinners("water", 0)
        compose.onNodeWithTag("water-primary-action").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(waterBusy = true) }
        assertSpinners("water", 1)
        compose.onNodeWithTag("water-primary-action").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(waterBusy = false) }
        assertSpinners("water", 0)
        compose.onNodeWithTag("water-primary-action").assertDoesNotExist()
    }

    @Test fun laundryQueryAndAllOrderPhasesKeepProgressInTheButton() {
        val model = model()
        val settings = themeSettingsFixture(compose.activity.application)
        var state by mutableStateOf(LifeDevicesUiState(restoring = false, connectedPhone = "fixture-account",
            feature = LifeFeature.Laundry, scannedSerial = "fixture-laundry", loadingDevice = true))
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme { Scaffold {
            LifeDevicePage(state, settings, model, true, {}, nowMillis = { 100_000L })
        } } } }
        compose.onNodeWithTag("laundry-device-check").performScrollTo()
        assertSpinners("laundry", 1)
        val device = device()
        for (phase in LaundryPhase.entries) {
            val session = LaundrySession(device, "program", "2.50", "13", phase,
                createSent = true, orderNo = "fixture-order", orderId = "fixture-order-id", paid = true)
            compose.runOnIdle { state = state.copy(loadingDevice = false, device = device,
                selectedSkuId = "program", laundry = session, laundryBusy = true) }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            compose.onNodeWithTag("laundry-progress").performScrollTo()
            assertSpinners("laundry", 1)
            if (phase == LaundryPhase.Starting) capture("laundry-starting")
            compose.runOnIdle { state = state.copy(laundryBusy = false) }
            compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
            assertSpinners("laundry", 0)
        }
    }
}
