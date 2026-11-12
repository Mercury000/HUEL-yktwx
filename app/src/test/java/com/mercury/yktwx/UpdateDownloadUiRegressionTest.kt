package com.mercury.yktwx

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.UpdateDownloadPhase
import com.mercury.yktwx.data.UpdateDownloadState
import com.mercury.yktwx.data.UpdateRelease
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.AppUpdateDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateDownloadUiRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val release = UpdateRelease(10, "1.5.0-beta.1", "- 修复下载与更新交互", "https://github.com/release")

    @Test fun blockedBackGesturesNeverMoveTheDialogAndOptionalDismissalRecovers() {
        var state by mutableStateOf(AppUpdateUiState(release = release, visible = true))
        var dismissals = 0
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                Scaffold { AppUpdateDialog(state, onDismiss = { dismissals++; state = state.copy(visible = false) }) }
            }
        }
        val dialog = compose.onNodeWithTag("app-update-dialog")
        val dispatcher = compose.activity.onBackPressedDispatcher
        for ((required, download) in listOf(
            false to UpdateDownloadState(UpdateDownloadPhase.SelectingSource),
            false to UpdateDownloadState(UpdateDownloadPhase.Downloading, 42),
            false to UpdateDownloadState(UpdateDownloadPhase.Verifying),
            true to UpdateDownloadState(),
        )) {
            compose.runOnIdle { state = state.copy(required = required, download = download) }
            val bounds = dialog.fetchSemanticsNode().boundsInRoot
            repeat(2) { gesture ->
                compose.runOnIdle { dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 400f, 0f, BackEventCompat.EDGE_LEFT)) }
                for (progress in listOf(.5f, .9f)) {
                    compose.runOnIdle { dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 400f, progress, BackEventCompat.EDGE_LEFT)) }
                    compose.waitForIdle()
                    assertEquals("Blocked predictive back must not translate or shrink the popup", bounds,
                        dialog.fetchSemanticsNode().boundsInRoot)
                }
                compose.runOnIdle {
                    if (gesture == 0) dispatcher.dispatchOnBackCancelled() else dispatcher.onBackPressed()
                }
                dialog.assertIsDisplayed()
                assertEquals(bounds, dialog.fetchSemanticsNode().boundsInRoot)
                assertEquals(0, dismissals)
            }
        }
        compose.runOnIdle { state = state.copy(required = false, download = UpdateDownloadState()) }
        compose.runOnIdle { dispatcher.onBackPressed() }
        dialog.assertDoesNotExist()
        assertEquals(1, dismissals)
    }

    @Test fun hiddenFeedbackHasNoReservedHeightAndCollapsesAfterErrorClears() {
        var state by mutableStateOf(AppUpdateUiState(release = release, visible = true))
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) { Scaffold { AppUpdateDialog(state, onDismiss = {}) } }
        }
        val notes = compose.onNodeWithTag("app-update-notes")
        val details = compose.onNodeWithTag("app-update-details")
        val initial = notes.fetchSemanticsNode().boundsInRoot
        assertEquals(details.fetchSemanticsNode().boundsInRoot.bottom, initial.bottom, .5f)
        compose.runOnIdle { state = state.copy(download = UpdateDownloadState(UpdateDownloadPhase.Failed, error = "安装包校验失败")) }
        compose.onNodeWithText("安装包校验失败").assertIsDisplayed()
        assertTrue(notes.fetchSemanticsNode().boundsInRoot.height > initial.height)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(download = UpdateDownloadState()) }
        compose.mainClock.advanceTimeByFrame()
        assertTrue("Feedback must remain during its exit animation", notes.fetchSemanticsNode().boundsInRoot.height > initial.height)
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("安装包校验失败").assertDoesNotExist()
        assertEquals(initial.height, notes.fetchSemanticsNode().boundsInRoot.height, .5f)
    }

    @Test fun sourceFallbackIsShownAsRetryInsteadOfAnUnexplainedProgressReset() {
        val state = AppUpdateUiState(release = release, visible = true,
            download = UpdateDownloadState(UpdateDownloadPhase.Downloading, 0, attempt = 2))
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) { Scaffold { AppUpdateDialog(state, onDismiss = {}) } }
        }
        compose.onNodeWithText("重试下载 0%").assertIsDisplayed()
        compose.onNodeWithTag("update-download").assertIsNotEnabled()
    }
}
