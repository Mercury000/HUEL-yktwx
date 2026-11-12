package com.mercury.yktwx

import kotlinx.serialization.json.*
import com.mercury.yktwx.data.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import com.mercury.yktwx.data.UpdateRelease
import com.mercury.yktwx.data.AppUpdateApi
import com.mercury.yktwx.data.UpdateCheckStore
import com.mercury.yktwx.data.UpdateCheckResult
import com.mercury.yktwx.data.UpdateCheckReason
import com.mercury.yktwx.ui.AppUpdateEffect
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.components.AppUpdateDialog
import com.mercury.yktwx.ui.components.UpdateSettingsCard
import com.mercury.yktwx.ui.components.InlineUpdateNotice
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.mercury.yktwx.ui.pages.AboutPage
import org.robolectric.shadows.ShadowToast
import org.robolectric.Shadows.shadowOf
import kotlinx.coroutines.CompletableDeferred
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
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun releasePleaseNotesShowVersionOnceAndKeepMarkdownContent() {
        val release = UpdateRelease(10, "1.5.0", """
            ## [1.5.0](https://github.com/Mercury000/HUEL-yktwx/compare/v1.4.1...v1.5.0) (2026-11-12)

            ### Features

            * **update:** 完善应用内更新并接入双渠道自动发版 ([710e02c](https://github.com/Mercury000/HUEL-yktwx/commit/710e02c0a3d5970848310bf2ffabe05fcdf6cd7b))

            ### Bug Fixes

            * **ui:** 统一入场动画状态并避免切页重复播放 ([1adce9b](https://github.com/Mercury000/HUEL-yktwx/commit/1adce9bd613d8495da9a08fdb5a618a05c5733d8))
        """.trimIndent(), "https://github.com/release", publishedAt = java.time.Instant.parse("2026-11-12T10:30:00Z"))
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                Scaffold { AppUpdateDialog(AppUpdateUiState(release = release, visible = true), onDismiss = {}) }
            }
        }
        compose.onAllNodesWithText("1.5.0").assertCountEquals(1)
        compose.onNodeWithText("正式版").assertExists()
        compose.onNodeWithText("发布时间：", substring = true).assertExists()
        compose.onNodeWithText("1.5.0 (2026-11-12)").assertDoesNotExist()
        compose.onNodeWithText("Features").assertExists()
        compose.onNodeWithText("Bug Fixes").assertExists()
        compose.onNodeWithText("• update: 完善应用内更新并接入双渠道自动发版 (710e02c)").assertExists()
        compose.onNodeWithText("• ui: 统一入场动画状态并避免切页重复播放 (1adce9b)").assertExists()
        compose.onNodeWithText("下载安装").assertIsDisplayed()
    }
    @Test fun channelSwitchUpdatesThePersistedPreference() {
        val settings = settings()
        settings.setUpdateChannel("stable")
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                Scaffold {
                    UpdateSettingsCard(AppUpdateUiState(), false, {}, {}, settings.updateChannel.value, settings::setUpdateChannel)
                }
            }
        }
        compose.onNodeWithTag("update-channel-switch").performClick()
        compose.runOnIdle {
            assertEquals("beta", settings.updateChannel.value)
            assertEquals("beta", ThemeSettings(compose.activity).updateChannel.value)
        }
    }
    private fun settings(): ThemeSettings {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return ThemeSettings(app)
    }
    private fun capture(name: String) {
        val file = File("../tools/app-updates/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun settingsExplainReminderOnlyControlAndPersistItWithOneManualEntry() {
        val settings = settings()
        val payments = PaymentViewModel(compose.activity.application).apply { initialize(true) }
        val bills = BillViewModel(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        compose.onNode(hasText("我的") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText("应用设置").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        capture("settings-entry")
        compose.onNodeWithTag("settings-page-list").performScrollToIndex(5)
        compose.onNodeWithText("普通更新提醒").assertExists()
        compose.onNodeWithText("关闭后仍会检查版本，强制更新和手动检查不受影响").assertExists()
        compose.onNode(isToggleable() and (hasTestTag("automatic-updates-switch") or
            hasAnyAncestor(hasTestTag("automatic-updates-switch")))).performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertFalse(ThemeSettings(compose.activity).automaticUpdates.value) }
        compose.onNodeWithTag("check-updates").performScrollTo()
        compose.onAllNodesWithTag("check-updates").assertCountEquals(1)
        val title = compose.onNodeWithText("检查更新", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val version = compose.onNodeWithText(BuildConfig.VERSION_NAME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Version belongs to the right side of the same row", version.left > title.right &&
            version.top < title.bottom && version.bottom > title.top)
        compose.onNodeWithTag("check-updates").performClick()
        compose.runOnIdle { assertEquals("界面预览不检查更新", ShadowToast.getTextOfLatestToast()) }
        capture("settings-light")
    }

    @Test fun aboutPageShowsVersionOnceWithoutADuplicateCheckEntry() {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    AboutPage(PaddingValues(), false, {})
                }
            }
        }
        compose.onAllNodesWithText("${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})").assertCountEquals(1)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        compose.onNodeWithTag("check-updates").assertDoesNotExist()
        compose.onNodeWithText("检查更新").assertDoesNotExist()
        capture("about-light")
    }

    @Test fun manualCheckShowsLoadingAndCanRetryAfterFailure() {
        var checks = 0
        val gate = CompletableDeferred<UpdateCheckResult>()
        val model = AppUpdateViewModel(compose.activity.application, UpdateCheckStore(
            compose.activity.getSharedPreferences("update-ui-test", Context.MODE_PRIVATE), "v3"), {
            checks++
            if (checks == 1) gate.await() else UpdateCheckResult(UpdateCheckReason.CurrentOrNewer)
        })
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                val state by model.state.collectAsState()
                AppUpdateEffect(model, false, true)
                Scaffold { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                        UpdateSettingsCard(state, false, {}, model::checkManually)
                    }
                }
            }
        }
        val titleBounds = compose.onNodeWithText("检查更新", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("check-updates").performClick()
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        assertEquals(titleBounds, compose.onNodeWithText("检查更新", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        compose.onNodeWithTag("check-updates").performTouchInput { click(center) }
        assertEquals("Checking must disable another request", 1, checks)
        compose.runOnIdle { gate.completeExceptionally(java.io.IOException("offline")) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("网络连接失败，请稍后重试", ShadowToast.getTextOfLatestToast()) }
        compose.onNodeWithText("检查更新").assertIsDisplayed()
        assertEquals(titleBounds, compose.onNodeWithText("检查更新", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("check-updates").performClick()
        assertEquals(2, checks)
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("已是最新版本", ShadowToast.getTextOfLatestToast()) }
        compose.onNodeWithText("已是最新版本").assertDoesNotExist()
    }

    @Test fun longReleaseNotesScrollWhileDownloadAndLaterButtonsRemainVisible() {
        val release = UpdateRelease(4, "1.2.0", (1..80).joinToString("\n") { "• 第 $it 项更新：优化页面显示与操作体验" },
            "https://cxt.303889.xyz/download.html")
        var state by mutableStateOf(AppUpdateUiState(release = release, visible = true))
        var dark by mutableStateOf(false)
        compose.setContent {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                Scaffold { AppUpdateDialog(state) { state = state.copy(visible = false) } }
            }
        }
        compose.onNodeWithText("更新详情").assertIsDisplayed()
        compose.onNodeWithText("1.2.0").assertIsDisplayed()
        compose.onNodeWithText("下载安装").assertIsDisplayed()
        compose.onNodeWithText("忽略").assertIsDisplayed()
        capture("update-light")
        val notes = compose.onNodeWithTag("app-update-notes")
        notes.performTouchInput { swipeUp() }
        assertTrue(notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        compose.onNodeWithText("下载安装").assertIsDisplayed()
        compose.runOnIdle { dark = true }
        capture("update-dark")
        compose.onNodeWithText("忽略").performClick()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
    }

    @Test fun downloadActionKeepsTheDialogOpenAndErrorsAreInline() {
        val release = UpdateRelease(10, "1.5.0", "修复与优化", "https://github.com/release")
        var downloads = 0
        var state by mutableStateOf(AppUpdateUiState(release = release, visible = true))
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                Scaffold { AppUpdateDialog(state, onDownload = { downloads++ }) { state = state.copy(visible = false) } }
            }
        }
        compose.onNodeWithText("下载安装").performClick()
        assertEquals(1, downloads)
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(download = com.mercury.yktwx.data.UpdateDownloadState(
            com.mercury.yktwx.data.UpdateDownloadPhase.Failed, error = "安装包校验失败")) }
        compose.onNodeWithText("安装包校验失败").assertIsDisplayed()
        compose.onNodeWithText("重试下载").assertIsDisplayed()
        compose.onNodeWithText("跳过此版本").assertIsDisplayed()
    }

    @Test fun forcedRootBlocksBusinessPagesAndHasNoDismissActions() {
        val settings = settings()
        val model = AppUpdateViewModel(compose.activity.application, UpdateCheckStore(
            compose.activity.getSharedPreferences("forced-root-test", Context.MODE_PRIVATE), "v9"), {
            UpdateCheckResult(UpdateCheckReason.UpdateAvailable,
                UpdateRelease(10, "1.5.0", "## 必须更新\n\n- 修复问题", "https://github.com/release", maxForcedVersionCode = 9))
        })
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + AppUpdateViewModel::class.java.name, model)
        val payments = PaymentViewModel(compose.activity.application).apply { initialize(true) }
        val bills = BillViewModel(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        compose.runOnIdle { model.configure(true, true, true) }
        compose.waitForIdle()
        compose.onNodeWithText("需要更新后继续使用").assertIsDisplayed()
        compose.onNodeWithText("付款码").assertDoesNotExist()
        compose.onNodeWithText("学号 / 工号").assertDoesNotExist()
        compose.onNodeWithText("忽略").assertDoesNotExist()
        compose.onNodeWithText("跳过此版本").assertDoesNotExist()
        compose.runOnIdle { model.dismiss(); model.presentIfAllowed(false) }
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        capture("forced-update-light")
    }
    @Test fun inlineNoticesArePassiveSharedAcrossPagesAndOpenDetailsOnlyWhenTapped() {
        val settings = settings()
        val prefs = compose.activity.getSharedPreferences("inline-update-ui-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val model = AppUpdateViewModel(compose.activity.application, UpdateCheckStore(prefs, "v4"), {
            UpdateCheckResult(UpdateCheckReason.UpdateAvailable,
                UpdateRelease(5, "1.3.0", "优化付款码展示体验\n改进页面加载与更新提示", "https://cxt.303889.xyz/download/"))
        })
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + AppUpdateViewModel::class.java.name, model)
        val payments = PaymentViewModel(compose.activity.application).apply { initialize(true) }
        val bills = BillViewModel(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        // Business pages use fixtures; explicitly run the injected, fake update service after preview setup.
        compose.runOnIdle { model.configure(true, true, true) }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.onNodeWithText("付款码").assertIsDisplayed()

        fun tab(label: String) {
            compose.onNode(hasText(label) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        fun notice(list: String) = compose.onNode(hasTestTag("app-update-notice") and hasAnyAncestor(hasTestTag(list)))
        // The bill notice lives in the shared scroll header, not inside either list.
        fun noticeParent(list: String) = if (list.startsWith("bill-")) "bill-scroll-surface" else list
        var expectedTop: Float? = null
        for ((label, list, imageName) in listOf(
            Triple("付款", "payment-page-list", "payment"),
            Triple("大厅", "hall-page-list", "hall"),
            Triple("账单", "bill-list", "bill"),
            Triple("我的", "my-page-list", "my"),
        )) {
            tab(label)
            compose.onNodeWithTag(list).performScrollToIndex(0)
            notice(noticeParent(list)).assertIsDisplayed()
            compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
            val bounds = notice(noticeParent(list)).fetchSemanticsNode().boundsInRoot
            if (expectedTop == null) expectedTop = bounds.top
            else assertEquals("All four tabs place the notice at the same top position", expectedTop!!, bounds.top, 1f)
            capture("inline-$imageName-light")
            compose.runOnIdle { settings.setTheme(2) }
            capture("inline-$imageName-dark")
            compose.runOnIdle { settings.setTheme(1) }
        }
        // Switching the bill mode keeps the shared notice above its content.
        tab("账单")
        compose.onNodeWithText("统计").performClick()
        notice("bill-scroll-surface").assertIsDisplayed()
        compose.onNodeWithText("明细").performClick()
        notice("bill-scroll-surface").performClick()
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.onNodeWithText("忽略").performClick()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()

        tab("我的")
        compose.onNodeWithTag("my-page-list").performScrollToIndex(4)
        compose.onNodeWithText("应用设置").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasTestTag("app-update-notice") and hasAnyAncestor(hasTestTag("settings-page-list"))).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("关于财小通").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNode(hasTestTag("app-update-notice") and hasAnyAncestor(hasTestTag("about-page-list"))).assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("my-page-list").performScrollToIndex(0)
        compose.onNode(hasTestTag("dismiss-update-notice") and hasAnyAncestor(hasTestTag("my-page-list"))).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(model.state.value.noticeVisible)
            assertFalse("Closing a notice must not open details", model.state.value.visible)
        }
        compose.onAllNodesWithTag("app-update-notice").assertCountEquals(0)
        for (label in listOf("付款", "大厅", "账单", "我的")) {
            tab(label)
            compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
            compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        }
    }

    @Test fun compactNoticeKeepsTheCloseTargetVisibleWithLongVersionsAndLargeText() {
        val release = UpdateRelease(5, "1.3.0-preview.20261102", "修复与优化", "https://cxt.303889.xyz/download/")
        val state = AppUpdateUiState(release = release, noticeVisible = true)
        var dark by mutableStateOf(false)
        var opens = 0
        var dismissals = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    Scaffold { padding ->
                        Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 24.dp)) {
                            InlineUpdateNotice(state, { opens++ }, { dismissals++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("dismiss-update-notice").assertIsDisplayed().performTouchInput { click(center) }
        assertEquals(1, dismissals)
        assertEquals("Close must not trigger the parent action", 0, opens)
        compose.onNodeWithTag("app-update-notice").performTouchInput { click(center) }
        assertEquals(1, opens)
        val close = compose.onNodeWithTag("dismiss-update-notice").fetchSemanticsNode().boundsInRoot
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(close.width >= 44 * density && close.height >= 44 * density)
        capture("inline-large-light")
        compose.runOnIdle { dark = true }
        capture("inline-large-dark")
    }
}
