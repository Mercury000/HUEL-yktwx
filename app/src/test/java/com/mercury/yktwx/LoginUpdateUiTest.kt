package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.UsageConsent
import com.mercury.yktwx.ui.YktApp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val update = UpdateCheckResult(UpdateCheckReason.UpdateAvailable,
        UpdateRelease(5, "1.3.0", "优化付款码展示体验\n改进页面加载与更新提示", "https://cxt.303889.xyz/download/"))

    private fun <T : ViewModel> inject(type: Class<T>, model: T) {
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + type.name, model)
    }

    private fun store(): UpdateCheckStore = UpdateCheckStore(
        compose.activity.getSharedPreferences("login-update-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }, "v4")

    private fun consent(): UsageConsent {
        compose.activity.getSharedPreferences("usage_consent", Context.MODE_PRIVATE).edit().clear().commit()
        return UsageConsent(compose.activity)
    }

    private fun settings(): ThemeSettings {
        compose.activity.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return ThemeSettings(compose.activity).apply { setTheme(1) }
    }

    private fun content(model: AppUpdateViewModel, consent: UsageConsent, settings: ThemeSettings,
        service: YktService = FakeAccountService(), inspection: State<Boolean> = mutableStateOf(false)): PaymentViewModel {
        val app = compose.activity.application
        val vault = object : Vault {
            private var credentials: Credentials? = null
            private var session: Session? = null
            override fun credentials() = credentials
            override fun session() = session
            override fun saveCredentials(value: Credentials) { credentials = value }
            override fun saveSession(value: Session) { session = value }
            override fun clearSession() { session = null }
            override fun clear() { credentials = null; session = null }
        }
        val repository = AccountRepository(service, vault)
        val payments = PaymentViewModel(app, repository).apply { initialize() }
        val bills = BillViewModel(app, repository)
        inject(AppUpdateViewModel::class.java, model)
        inject(MyViewModel::class.java, MyViewModel(app, repository))
        inject(HallViewModel::class.java, HallViewModel(app, repository))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides inspection.value) {
                YktApp(settings, payments, bills, consent)
            }
        }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payments.state.value.restoringLogin }
        compose.waitForIdle()
        return payments
    }

    private fun capture(name: String) {
        val file = File("../tools/app-updates/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun consentGatesCachedNoticeAndCheckingWhileLoginFieldsSurviveItsDismissal() {
        val store = store().apply { cacheRelease(update.release) }
        val consent = consent()
        val settings = settings()
        var checks = 0
        val model = AppUpdateViewModel(compose.activity.application, store, { checks++; update })
        val payments = content(model, consent, settings)
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        assertEquals(0, checks)
        compose.onNodeWithText("上滑并阅读").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").assertIsEnabled()
        assertEquals("Reading alone must not check for updates", 0, checks)
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.onNodeWithText("同意并继续").performClick()
        compose.waitUntil(5_000) { consent.accepted && checks == 1 }
        compose.waitForIdle()
        assertTrue("No successful login is needed", payments.state.value.needsLogin)
        compose.onNodeWithTag("app-update-notice").assertIsDisplayed()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        val notice = compose.onNodeWithTag("app-update-notice").fetchSemanticsNode().boundsInRoot
        val initialGap = notice.top - compose.onNodeWithText("河南财经政法大学").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(notice.top >= compose.onNodeWithText("河南财经政法大学").fetchSemanticsNode().boundsInRoot.bottom)
        assertTrue(notice.bottom <= compose.onNodeWithText("学号 / 工号").fetchSemanticsNode().boundsInRoot.top)
        capture("login-update-light")
        compose.runOnIdle { settings.setTheme(2) }
        capture("login-update-dark")
        compose.runOnIdle { settings.setTheme(1) }

        compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
        compose.onNodeWithText("密码").performTextInput("fictional-password")
        compose.onNodeWithTag("app-update-notice").performClick()
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.onNodeWithText("忽略").performClick()
        compose.onNodeWithTag("app-update-notice").assertIsDisplayed()
        compose.onNodeWithTag("dismiss-update-notice").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.onNodeWithText("学号 / 工号").assertTextContains("202600000001")
        compose.onNodeWithText("密码").assertTextContains("fictional-password")
        val hiddenGap = compose.onNodeWithText("学号 / 工号").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithText("河南财经政法大学").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("Hidden notice must leave no extra space", initialGap, hiddenGap, 1f)
        assertFalse("Dismissal is shared and persisted", store.canNotify(update.release!!.versionCode, System.currentTimeMillis()))
        assertEquals(1, checks)
    }

    @Test fun failedLoginDoesNotCancelThePendingUpdateOrLoseRetryInformation() {
        val consent = consent().apply { runBlocking { accept() } }
        val gate = CompletableDeferred<UpdateCheckResult>()
        var checks = 0
        val model = AppUpdateViewModel(compose.activity.application, store(), { checks++; gate.await() })
        val service = object : YktService by FakeAccountService() {
            override suspend fun login(credentials: Credentials): Session = throw NetworkFailure("登录服务暂时不可用")
        }
        val payments = content(model, consent, settings(), service)
        assertEquals(1, checks)
        compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
        compose.onNodeWithText("密码").performTextInput("fictional-password")
        compose.onNodeWithText("登录").performClick()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); payments.state.value.error != null }
        compose.onNodeWithText("登录服务暂时不可用").assertIsDisplayed()
        compose.runOnIdle { gate.complete(update) }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-notice").assertIsDisplayed()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.onNodeWithText("学号 / 工号").assertTextContains("202600000001")
        compose.onNodeWithText("密码").assertTextContains("fictional-password")
        compose.onNodeWithText("登录服务暂时不可用").assertIsDisplayed()
        compose.onNodeWithTag("app-update-notice").performClick()
        compose.onNodeWithText("下载安装").performClick()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.download.phase == com.mercury.yktwx.data.UpdateDownloadPhase.Failed }
        compose.onNodeWithText("更新清单无效").assertIsDisplayed()
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.onNodeWithText("忽略").performClick()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        assertTrue(payments.state.value.needsLogin)
        assertEquals(1, checks)
    }

    @Test fun revokingConsentHidesTheNoticeAndRejectsAnUncancellableLateResult() {
        val store = store()
        val consent = consent().apply { runBlocking { accept() } }
        val gate = CompletableDeferred<UpdateCheckResult>()
        var checks = 0
        val model = AppUpdateViewModel(compose.activity.application, store, {
            checks++; withContext(NonCancellable) { gate.await() }
        })
        content(model, consent, settings())
        assertEquals(1, checks)
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        assertFalse(consent.accepted)
        compose.runOnIdle { gate.complete(update) }
        compose.waitForIdle()
        assertNull(model.state.value.release)
        assertNull(store.cachedRelease())
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        compose.onNodeWithText("上滑并阅读").assertIsNotEnabled()
    }

    @Test fun loginKeepsCheckingWithRemindersDisabledAndInspectionStillBlocksChecks() {
        val store = store().apply { cacheRelease(update.release) }
        val consent = consent().apply { runBlocking { accept() } }
        settings().setAutomaticUpdates(false)
        val restoredSettings = ThemeSettings(compose.activity)
        val inspection = mutableStateOf(false)
        var checks = 0
        val model = AppUpdateViewModel(compose.activity.application, store, { checks++; update })
        content(model, consent, restoredSettings, inspection = inspection)
        assertFalse(restoredSettings.automaticUpdates.value)
        assertEquals(1, checks)
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.runOnIdle { inspection.value = true; restoredSettings.setAutomaticUpdates(true) }
        compose.waitForIdle()
        assertEquals(1, checks)
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.runOnIdle { inspection.value = false }
        compose.waitForIdle()
        assertEquals(1, checks)
        compose.onNodeWithTag("app-update-notice").assertIsDisplayed()
        compose.runOnIdle { restoredSettings.setAutomaticUpdates(false) }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
    }

    @Test fun enteringMainKeepsThePendingCheckAndManualCheckingStillOpensDetailsAfterDismissal() {
        val store = store().apply { cacheRelease(update.release) }
        val consent = consent().apply { runBlocking { accept() } }
        val gate = CompletableDeferred<UpdateCheckResult>()
        var checks = 0
        val model = AppUpdateViewModel(compose.activity.application, store, {
            checks++; if (checks == 1) gate.await() else update
        })
        val payments = content(model, consent, settings())
        compose.onNodeWithTag("app-update-notice").performClick()
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        compose.runOnIdle { payments.login("202600000001", "fictional-password") }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payments.state.value.needsLogin }
        compose.waitForIdle()
        compose.onNodeWithText("付款码").assertIsDisplayed()
        compose.onNodeWithTag("app-update-dialog").assertDoesNotExist()
        assertEquals("Route changes must not restart the check", 1, checks)
        compose.runOnIdle { gate.complete(update) }
        compose.waitForIdle()
        compose.onNode(hasTestTag("app-update-notice") and hasAnyAncestor(hasTestTag("payment-page-list"))).assertIsDisplayed()
        compose.onNode(hasTestTag("dismiss-update-notice") and hasAnyAncestor(hasTestTag("payment-page-list"))).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-notice").assertDoesNotExist()
        compose.runOnIdle { model.checkManually() }
        compose.waitForIdle()
        compose.onNodeWithTag("app-update-dialog").assertIsDisplayed()
        assertEquals(2, checks)
        assertFalse(store.canNotify(update.release!!.versionCode, System.currentTimeMillis()))
    }

    @Test fun confirmedForcedUpdateReplacesLoginAndBusinessPagesAndSurvivesBackAndSwitchChanges() {
        val consent = consent().apply { runBlocking { accept() } }
        val settings = settings()
        val gate = CompletableDeferred<UpdateCheckResult>()
        val model = AppUpdateViewModel(compose.activity.application, store(), { gate.await() })
        val payments = content(model, consent, settings)
        compose.onNodeWithText("学号 / 工号").assertIsDisplayed()
        compose.runOnIdle { gate.complete(update.copy(release = update.release!!.copy(versionCode = 10, maxForcedVersionCode = 9))) }
        compose.waitForIdle()
        compose.onNodeWithText("需要更新后继续使用").assertIsDisplayed()
        compose.onNodeWithText("学号 / 工号").assertDoesNotExist()
        compose.onNodeWithText("跳过此版本").assertDoesNotExist()
        compose.onNodeWithText("忽略").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed(); settings.setAutomaticUpdates(false) }
        compose.waitForIdle()
        compose.onNodeWithText("需要更新后继续使用").assertIsDisplayed()
        capture("root-forced-login")
        compose.runOnIdle { payments.login("202600000001", "fictional-password") }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payments.state.value.needsLogin }
        compose.waitForIdle()
        compose.onNodeWithText("付款码").assertDoesNotExist()
        compose.onNodeWithText("需要更新后继续使用").assertIsDisplayed()
        assertTrue(compose.activity.viewModelStore.keys().none {
            compose.activity.viewModelStore[it] is LifeDevicesViewModel || compose.activity.viewModelStore[it] is Hui798ViewModel
        })
        capture("root-forced-authenticated")
    }
}
