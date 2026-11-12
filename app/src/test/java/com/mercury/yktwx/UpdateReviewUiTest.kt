package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.UpdateDownloadPhase
import com.mercury.yktwx.data.UpdateDownloadState
import com.mercury.yktwx.data.UpdateRelease
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.AppUpdateDialog
import com.mercury.yktwx.ui.components.UpdateSettingsCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val release = UpdateRelease(100104, "1.5.0", """
        ## 应用内更新

        - 支持 **正式版 / 测试版** 更新渠道。
        - 下载失败会自动尝试下一来源。
        - 安装前校验完整性、包名、版本和签名。

        ### 使用体验

        1. 自动发现更新时显示横幅。
        2. 点按横幅可查看更新详情。
        3. 手动检查不受跳过记录影响。

        > 测试版可以切回正式版订阅，等待较新的正式版。

        [查看发布说明](https://github.com/${com.mercury.yktwx.data.AppUpdateApi.REPOSITORY}/releases)
    """.trimIndent(), "https://github.com/release", publishedAt = Instant.parse("2026-11-11T00:30:00Z"))

    private fun capture(name: String) {
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        val file = File("../tools/app-updates/review/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun dialogStatesKeepActionsInPlaceAndShowExplicitRecovery() {
        var state by mutableStateOf(AppUpdateUiState(release = release, visible = true))
        var dark by mutableStateOf(false)
        compose.setContent {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                Scaffold(topBar = { TopAppBar(title = "应用设置") }) { padding ->
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(padding)) {
                        SmallTitle("应用更新")
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            UpdateSettingsCard(AppUpdateUiState(), true, {}, {})
                        }
                    }
                    AppUpdateDialog(state, onDismiss = {})
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { dark = theme == "dark"; state = AppUpdateUiState(release = release, visible = true) }
            compose.onNodeWithText("跳过此版本").assertIsDisplayed()
            compose.onNodeWithText("忽略").assertIsDisplayed()
            compose.onNodeWithText("下载安装").assertIsDisplayed()
            capture("optional-$theme")
            val action = compose.onNodeWithTag("update-download").fetchSemanticsNode().boundsInRoot
            for ((name, download, label) in listOf(
                Triple("downloading", UpdateDownloadState(UpdateDownloadPhase.Downloading, 42), "下载中 42%"),
                Triple("unknown-size", UpdateDownloadState(UpdateDownloadPhase.Downloading), "下载中"),
                Triple("verifying", UpdateDownloadState(UpdateDownloadPhase.Verifying), "正在校验"),
                Triple("authorization", UpdateDownloadState(UpdateDownloadPhase.NeedsAuthorization, 100), "允许安装"),
                Triple("ready", UpdateDownloadState(UpdateDownloadPhase.Ready, 100), "安装更新"),
                Triple("installing", UpdateDownloadState(UpdateDownloadPhase.Installing, 100), "安装更新"),
                Triple("download-error", UpdateDownloadState(UpdateDownloadPhase.Failed, error = "所有下载来源均失败，请重试"), "重试下载"),
            )) {
                compose.runOnIdle { state = state.copy(download = download) }
                compose.onNodeWithText(label).assertIsDisplayed()
                if (download.busy) {
                    compose.onNodeWithTag("update-download").assertIsNotEnabled()
                    compose.onNodeWithText("忽略").assertIsNotEnabled()
                    assertEquals(action, compose.onNodeWithTag("update-download").fetchSemanticsNode().boundsInRoot)
                }
                assertEquals(action, compose.onNodeWithTag("update-download").fetchSemanticsNode().boundsInRoot)
                capture("$name-$theme")
            }
            compose.runOnIdle { state = state.copy(required = true, download = UpdateDownloadState()) }
            compose.onNodeWithText("需要更新后继续使用").assertIsDisplayed()
            compose.onNodeWithText("忽略").assertDoesNotExist()
            compose.onNodeWithText("跳过此版本").assertDoesNotExist()
            capture("forced-$theme")
            compose.runOnIdle { state = state.copy(download = UpdateDownloadState(UpdateDownloadPhase.Failed,
                error = "安装包签名与当前应用不一致")) }
            compose.onNodeWithText("安装包签名与当前应用不一致").assertExists()
            capture("forced-error-$theme")
        }
    }

    @Test fun manualCheckingSlotAndRedDotRemainVisibleAcrossBothThemes() {
        var state by mutableStateOf(AppUpdateUiState())
        var channel by mutableStateOf("stable")
        var dark by mutableStateOf(false)
        compose.setContent {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                Scaffold(topBar = { TopAppBar(title = "应用设置") }) { padding ->
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(padding)) {
                        SmallTitle("应用更新")
                        Column(Modifier.padding(horizontal = 16.dp)) {
                            UpdateSettingsCard(state, true, {}, {}, channel) { channel = it }
                        }
                    }
                }
            }
        }
        for (theme in listOf("light", "dark")) {
            compose.runOnIdle { dark = theme == "dark"; state = AppUpdateUiState(); channel = "stable" }
            capture("settings-stable-$theme")
            val bounds = compose.onNodeWithTag("check-updates").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { state = state.copy(checking = true) }
            compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo,
                androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
            assertEquals(bounds, compose.onNodeWithTag("check-updates").fetchSemanticsNode().boundsInRoot)
            capture("settings-checking-$theme")
            compose.runOnIdle { state = state.copy(checking = false, release = release) }
            compose.onNodeWithTag("update-red-dot", useUnmergedTree = true).assertIsDisplayed()
            capture("settings-red-dot-$theme")
            compose.onNodeWithTag("update-channel-switch").performClick()
            compose.runOnIdle { assertEquals("beta", channel) }
            capture("settings-beta-$theme")
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h568dp-port-xhdpi")
    fun compactDialogHasScrollableNotesAndVisibleActions() {
        var state by mutableStateOf(AppUpdateUiState(release = release.copy(notes =
            (1..50).joinToString("\n") { "- 第 $it 项更新：改进检查、下载与安装体验。" }), visible = true))
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                Scaffold { AppUpdateDialog(state, onDismiss = {}) }
            }
        }
        compose.onNodeWithText("下载安装").assertIsDisplayed()
        compose.onNodeWithText("忽略").assertIsDisplayed()
        compose.onNodeWithText("跳过此版本").assertIsDisplayed()
        capture("compact-long-notes")
        compose.onNodeWithTag("app-update-notes").performTouchInput { swipeUp() }
        compose.onNodeWithText("下载安装").assertIsDisplayed()
        capture("compact-long-notes-scrolled")
        compose.runOnIdle { state = state.copy(download = UpdateDownloadState(UpdateDownloadPhase.Downloading, 42)) }
        compose.onNodeWithText("下载中 42%").assertIsDisplayed()
        capture("compact-downloading")
    }

    @Test fun largeFontsKeepBothSecondaryActionsAndProgressVisible() {
        var state by mutableStateOf(AppUpdateUiState(release = release.copy(versionName = "1.5.0-beta.100104", channel = "beta"), visible = true))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme(ColorSchemeMode.Light) { Scaffold { AppUpdateDialog(state, onDismiss = {}) } }
            }
        }
        compose.onNodeWithText("跳过此版本").assertIsDisplayed()
        compose.onNodeWithText("忽略").assertIsDisplayed()
        compose.onNodeWithText("下载安装").assertIsDisplayed()
        compose.onNodeWithText("测试版").assertIsDisplayed()
        capture("large-font-beta")
        compose.runOnIdle { state = state.copy(download = UpdateDownloadState(UpdateDownloadPhase.Downloading, 99)) }
        compose.onNodeWithText("下载中 99%").assertIsDisplayed()
        capture("large-font-downloading")
    }
}
