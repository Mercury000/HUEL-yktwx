package com.mercury.yktwx

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
import org.json.JSONObject
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

    @Test fun settingsHaveOneManualEntryWithVersionOnTheRightAndDisablingAutomaticPersists() {
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
        compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("转回检测支付"))).performScrollToIndex(1)
        compose.onNode(isToggleable() and (hasTestTag("automatic-updates-switch") or
            hasAnyAncestor(hasTestTag("automatic-updates-switch")))).performClick()
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
        compose.onNodeWithText("发现新版本 1.2.0").assertIsDisplayed()
        compose.onNodeWithText("1.2.0").assertDoesNotExist()
        compose.onNodeWithText("前往下载").assertIsDisplayed()
        compose.onNodeWithText("稍后").assertIsDisplayed()
        capture("update-light")
        val notes = compose.onNodeWithTag("app-update-notes")
        notes.performTouchInput { swipeUp() }
        assertTrue(notes.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        compose.onNodeWithText("前往下载").assertIsDisplayed()
        compose.runOnIdle { dark = true }
        capture("update-dark")
        compose.onNodeWithText("稍后").performClick()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
    }

    @Test fun downloadUsesBackendAddressAndDismissesTheDialog() {
        val body = JSONObject().put("schemaVersion", 1).put("updateAvailable", true)
            .put("reason", "updateAvailable").put("latest", JSONObject()
                .put("versionCode", 4).put("versionName", "1.2.0").put("notes", "修复与优化")
                .put("downloadPageUrl", "https://cxt.303889.xyz/download/"))
        val release = AppUpdateApi.parse(body.toString()).release!!
        var state by mutableStateOf(AppUpdateUiState(release = release, visible = true))
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                Scaffold { AppUpdateDialog(state) { state = state.copy(visible = false) } }
            }
        }
        compose.onNodeWithText("前往下载").performClick()
        compose.runOnIdle {
            val intent = shadowOf(compose.activity).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://cxt.303889.xyz/download/", intent.data.toString())
            assertFalse(state.visible)
        }
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
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
        var expectedTop: Float? = null
        for ((label, list, imageName) in listOf(
            Triple("付款", "payment-page-list", "payment"),
            Triple("大厅", "hall-page-list", "hall"),
            Triple("账单", "bill-list", "bill"),
            Triple("我的", "my-page-list", "my"),
        )) {
            tab(label)
            compose.onNodeWithTag(list).performScrollToIndex(0)
            notice(list).assertIsDisplayed()
            compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
            val bounds = notice(list).fetchSemanticsNode().boundsInRoot
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
        notice("bill-stats-list").assertIsDisplayed()
        compose.onNodeWithText("明细").performClick()
        notice("bill-list").performClick()
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.onNodeWithText("稍后").performClick()
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
