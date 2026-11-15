package com.mercury.yktwx

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import com.mercury.yktwx.ui.themeSettingsFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DeviceRetryFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun sharedNoticeOwnsLineExpansionAndParentUsesTheSameHeightFrames() {
        var message by mutableStateOf<String?>("短提示")
        var measuredNoticeHeight = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            Column {
                Box(Modifier.width(260.dp).testTag("standalone-host")) {
                    AnimatedInlineError(message, Modifier.testTag("standalone-error"), preserveMessage = true)
                }
                ReadLayout(Modifier.width(260.dp).testTag("managed-parent")) {
                    Box(Modifier.onSizeChanged { measuredNoticeHeight = it.height }) {
                        AnimatedInlineError(message, Modifier.testTag("managed-error"), preserveMessage = true)
                    }
                }
            }
        } }
        compose.waitForIdle()
        fun height(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height
        val shortHeight = height("managed-parent")
        compose.runOnIdle { message = "第一行\n第二行\n第三行\n第四行" }
        fun frames(): List<Float> = (1..24).map {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val parentHeight = height("managed-parent")
            assertEquals("Parent must follow the shared notice without a second animation", measuredNoticeHeight.toFloat(), parentHeight, 1f)
            parentHeight
        }
        val growing = frames()
        val fullHeight = height("managed-parent")
        assertTrue("Shared notice must interpolate single-line to multiline height", growing.any { it > shortHeight + 1f && it < fullHeight - 1f })
        compose.runOnIdle { message = "短提示" }
        val shrinking = frames()
        assertTrue(shrinking.any { it > shortHeight + 1f && it < fullHeight - 1f })
        assertEquals(shortHeight, height("managed-parent"), 1f)
        compose.runOnIdle { message = null }
        repeat(24) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.onNodeWithTag("managed-error").assertDoesNotExist()
    }

    @Test fun retryReportsEvenAnImmediateFailureAndClearsOldErrorsAfterSuccess() {
        var message by mutableStateOf<String?>("设备查询失败")
        var busy by mutableStateOf(false)
        var clicks = 0
        compose.setContent { AppTheme {
            InlineRetryFeedback(message, busy, "重新检查", { clicks++ },
                Modifier.width(320.dp).testTag("retry-notice"), actionModifier = Modifier.testTag("retry-action"))
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("retry-action").performClick()
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        assertEquals(1, clicks)
        compose.onNodeWithText("正在检查").assertIsDisplayed()
        compose.onNodeWithTag("retry-action").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        compose.onNodeWithText("重新检查").assertIsDisplayed()
        compose.runOnIdle { message = null; busy = true }
        compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
        compose.onNodeWithText("设备查询失败").assertIsDisplayed()
        compose.runOnIdle { busy = false }
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.onNodeWithTag("retry-notice").assertDoesNotExist()
        compose.runOnIdle { busy = true }
        compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
        compose.onNodeWithTag("retry-notice").assertDoesNotExist()
    }

    @Test fun retryActionWidthChangesAnimateTheNoticeHeightWithoutParentLag() {
        var busy by mutableStateOf(false)
        var measuredNoticeHeight = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            ReadLayout(Modifier.width(240.dp).testTag("retry-parent")) {
                Box(Modifier.onSizeChanged { measuredNoticeHeight = it.height }) {
                    InlineRetryFeedback("暂无法读取设备状态", busy, "重试", {},
                        Modifier.testTag("retry-notice"), busyLabel = "正在重新检查")
                }
            }
        } }
        compose.waitForIdle()
        fun height() = compose.onNodeWithTag("retry-parent").fetchSemanticsNode().boundsInRoot.height
        val shortHeight = height()
        compose.runOnIdle { busy = true }
        val frames = (1..24).map {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            height().also { assertEquals(measuredNoticeHeight.toFloat(), it, 1f) }
        }
        val fullHeight = height()
        assertTrue("Fixture must wrap when the progress action grows", fullHeight > shortHeight + 1f)
        assertTrue("Action-driven wrapping must animate inside the shared notice", frames.any { it > shortHeight + 1f && it < fullHeight - 1f })
    }

    @Test fun bothWaterProvidersKeepProgressBesideRetryUntilTheDeviceReply() {
        var provider by mutableStateOf(LifeProvider.Panggui)
        var message by mutableStateOf<String?>("暂无法读取设备状态")
        var checking by mutableStateOf(false)
        var retries = 0
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent { AppTheme {
            val state = WaterDeviceUiState(provider, "用水设备", deviceId = "fixture-device", error = message, checking = checking)
            val retry = WaterDeviceAction("重新检查", !checking) { retries++; message = null; checking = true }
            WaterDevicePage(state, settings, true, retry,
                WaterDeviceAction("重新扫码", !checking) {}, retry, {}, "以平台记录为准", retryAction = retry)
        } }
        for (target in listOf(LifeProvider.Panggui, LifeProvider.Hui798)) {
            compose.runOnIdle { provider = target; message = "暂无法读取设备状态"; checking = false }
            val tag = if (target == LifeProvider.Panggui) "water" else "hui798"
            compose.onNodeWithTag("$tag-retry").performScrollTo().performClick()
            compose.onNodeWithTag(if (target == LifeProvider.Panggui) "life-device-page" else "hui798-device-page")
                .performScrollToNode(hasTestTag("$tag-retry"))
            compose.onNodeWithTag("$tag-retry").assertIsNotEnabled()
            compose.onNodeWithText("正在检查").assertIsDisplayed()
            compose.onNodeWithTag("$tag-action-progress", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("暂无法读取设备状态").assertIsDisplayed()
            compose.runOnIdle { checking = false }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("$tag-device-error").assertDoesNotExist()
        }
        assertEquals(2, retries)
    }

    @Test fun switchingWaterDeviceClearsThePreviousDevicesRetainedRetryFailure() {
        var state by mutableStateOf(WaterDeviceUiState(LifeProvider.Panggui, "用水设备", deviceId = "fixture-first",
            error = "上一设备查询失败"))
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent { AppTheme {
            val retry = WaterDeviceAction("重新检查", !state.checking) {}
            WaterDevicePage(state, settings, true, retry, retry, retry, {}, "以平台记录为准", retryAction = retry)
        } }
        compose.onNodeWithTag("water-retry").performScrollTo().assertExists()
        compose.runOnIdle { state = state.copy(deviceId = "fixture-second", error = null, checking = true) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithText("上一设备查询失败").assertDoesNotExist()
        compose.onNodeWithTag("water-device-error").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(error = "当前设备查询失败", checking = false) }
        compose.onNodeWithTag("water-retry").performScrollTo().assertExists()
        compose.runOnIdle { state = state.copy(error = null, checking = true) }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithTag("life-device-page").performScrollToNode(hasTestTag("water-retry"))
        compose.onNodeWithTag("water-retry").assertIsNotEnabled()
        compose.onNodeWithText("当前设备查询失败").assertIsDisplayed()
    }
    @Test fun primaryAndInlineRetryShareFeedbackEvenWhenBothWaterProvidersFailImmediately() {
        var provider by mutableStateOf(LifeProvider.Panggui)
        var clicks = 0
        val settings = themeSettingsFixture(compose.activity.application)
        compose.setContent { AppTheme {
            val state = WaterDeviceUiState(provider, "用水设备", deviceId = "fixture-device", error = "设备查询失败")
            val retry = WaterDeviceAction("重新检查", true) { clicks++ }
            WaterDevicePage(state, settings, true, retry, retry, retry, {}, "以平台记录为准", retryAction = retry)
        } }
        compose.mainClock.autoAdvance = false
        for (target in listOf(LifeProvider.Panggui, LifeProvider.Hui798)) {
            compose.runOnIdle { provider = target }
            compose.mainClock.advanceTimeBy(300); compose.waitForIdle()
            val tag = if (target == LifeProvider.Panggui) "water" else "hui798"
            compose.onNodeWithTag("$tag-primary-action").performClick()
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithTag("$tag-primary-action").assertIsNotEnabled()
            compose.onNodeWithTag("$tag-action-progress", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("$tag-retry").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("正在检查").assertExists()
            compose.onNodeWithTag("$tag-retry").performClick()
            compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
            compose.onNodeWithTag("$tag-primary-action").assertIsEnabled()
        }
        assertEquals(2, clicks)
    }
    @Test fun simultaneousAndInterruptedNoticesKeepOneParentAnimationOwner() {
        var first by mutableStateOf<String?>("短提示")
        var second by mutableStateOf<String?>(null)
        var firstHeight = 0
        var secondHeight = 0
        var owner: ReadLayoutMotion? = null
        compose.mainClock.autoAdvance = false
        compose.setContent { AppTheme {
            ReadLayout(Modifier.width(260.dp).testTag("two-notices-parent")) {
                owner = LocalReadLayoutMotion.current
                Column {
                    Box(Modifier.onSizeChanged { firstHeight = it.height }) { AnimatedInlineError(first, preserveMessage = true) }
                    Box(Modifier.onSizeChanged { secondHeight = it.height }) { AnimatedInlineError(second, preserveMessage = true) }
                }
            }
        } }
        compose.runOnIdle { first = "第一行\n第二行\n第三行"; second = "另一个提示\n第二行" }
        repeat(5) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        compose.runOnIdle { first = "缩短"; second = null }
        repeat(30) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            val parent = compose.onNodeWithTag("two-notices-parent").fetchSemanticsNode().boundsInRoot.height
            assertEquals((firstHeight + secondHeight).toFloat(), parent, 1f)
            assertTrue(owner!!.feedbackTransitions >= 0)
        }
        assertEquals(0, owner!!.feedbackTransitions)
        compose.onNodeWithText("缩短").assertIsDisplayed()
        compose.onNodeWithText("另一个提示\n第二行").assertDoesNotExist()
    }
}
