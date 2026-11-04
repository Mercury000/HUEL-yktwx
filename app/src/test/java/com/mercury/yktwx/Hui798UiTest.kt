package com.mercury.yktwx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.components.LifeDeviceStatusEntry
import com.mercury.yktwx.ui.pages.*
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Hui798UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val models = ViewModelStore()
    private fun model(): Hui798ViewModel {
        val store = object : Hui798Store {
            override fun account(): Hui798Account? = null
            override fun sessions(): List<Hui798Session> = emptyList()
            override fun saveAccount(account: Hui798Account) { error("No sign in in UI fixtures") }
            override fun saveSessions(sessions: List<Hui798Session>) { error("No commands in UI fixtures") }
            override fun clearAccount() {}
        }
        val api = object : Hui798Service {
            override suspend fun captcha(key: String): ByteArray {
                val bitmap = Bitmap.createBitmap(240, 80, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText("aB7K", 28f, 58f, Paint().apply { color = Color.BLACK; textSize = 52f })
                }
                return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }.toByteArray()
            }
            override suspend fun sendCode(phone: String, imageCode: String, key: String) { error("No live SMS") }
            override suspend fun login(phone: String, code: String): Hui798Account = error("No live login")
            override suspend fun device(id: String, token: String): Hui798Device = error("No live reads")
            override suspend fun status(id: String, token: String): Hui798Reading = error("No live reads")
            override suspend fun start(id: String, token: String) { error("No live starts") }
            override suspend fun end(id: String, token: String) { error("No live stops") }
        }
        return Hui798ViewModel(compose.activity.application, api, store, io = Dispatchers.Main.immediate).also { models.put("hui798", it) }
    }
    @After fun cleanup() { models.clear() }
    private fun settings(dark: Boolean = false) = ThemeSettings(compose.activity.application).apply { setTheme(if (dark) 2 else 1) }
    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        compose.waitForIdle()
        val file = File("../tools/life798-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun login(dark: Boolean) {
        val model = model()
        val settings = settings(dark)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) { Scaffold {
                val state by model.state.collectAsState()
                PaymentPage(PreviewData.paymentState(false), settings, 0.dp, {},
                    hui798LoginRequired = true, onHui798Login = model::connectAccount, scanEnabled = false)
                Hui798LoginSheet(state, model, active = true)
            } }
        } }
        compose.waitUntil { !model.state.value.restoring }
        compose.onNodeWithTag("hui798-login-sheet").assertDoesNotExist()
        compose.onNodeWithTag("hui798-login-notice").performClick()
        compose.waitUntil { model.state.value.captcha != null || model.state.value.loginError != null }
        assertNull(model.state.value.loginError, model.state.value.loginError)
        compose.onNodeWithTag("hui798-login-sheet").assertIsDisplayed()
        compose.onNodeWithContentDescription("图形验证码，点击刷新").assertIsDisplayed()
        val captcha = compose.onNodeWithTag("hui798-image-code")
        for ((index, character) in "aB7K".withIndex()) {
            captcha.performTextInput(character.toString())
            assertEquals("Sequential input retains the cursor", TextRange(index + 1),
                captcha.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        }
        assertEquals("aB7K", model.state.value.imageCode)
        captcha.performTextInputSelection(TextRange(2))
        compose.runOnIdle { model.phoneChanged("13800000000") }
        assertEquals(TextRange(2), captcha.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        captcha.performTextInput("X")
        assertEquals("aBX7K", model.state.value.imageCode)
        assertEquals(TextRange(3), captcha.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        captcha.performTextInputSelection(TextRange(2, 3))
        captcha.performTextInput("")
        assertEquals("aB7K", model.state.value.imageCode)
        assertEquals(TextRange(2), captcha.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        captcha.performTextReplacement("aB7K123456")
        assertEquals("aB7K1234", model.state.value.imageCode)
        assertEquals(TextRange(8), captcha.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("hui798-refresh-captcha").performClick()
        compose.waitUntil { model.state.value.captcha != null && !model.state.value.captchaLoading }
        assertEquals("", model.state.value.imageCode)
        assertEquals(TextRange.Zero, captcha.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        captcha.performTextInput("aB7K")
        captcha.assertTextContains("aB7K")
        compose.onNodeWithTag("hui798-login-submit").assertIsNotEnabled()
        compose.onNodeWithTag("device-scanner").assertDoesNotExist()
        capture("login-${if (dark) "dark-large" else "light"}")
        compose.runOnIdle { model.dismissLogin() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("hui798-login-sheet").assertDoesNotExist()
        assertNull(model.state.value.device)
        assertTrue(model.state.value.sessions.isEmpty())
    }
    @Test fun inlineLoginUsesSharedNativeDrawerAndAcceptsCaptchaLetters() = login(false)
    @Test @Config(fontScale = 1.3f)
    fun captchaDrawerFitsLargeTextInDarkMode() = login(true)

    private fun stages(dark: Boolean) {
        val model = model()
        val settings = settings(dark)
        val device = Hui798Device("123456789012345", "五号楼一层饮水机")
        var state by mutableStateOf(Hui798UiState(restoring = false, connectedPhone = "138****0000", device = device, deviceReady = true))
        var scans = 0
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                Hui798DevicePage(state, settings, model, active = true, onScan = { scans++ }, onBack = {}, nowMillis = { 160_000L })
            }
        } }
        compose.onNodeWithText(device.name).assertIsDisplayed()
        compose.onNodeWithTag("hui798-primary-action").assertIsEnabled().assertTextContains("开始用水")
        compose.onAllNodes(hasText("¥", substring = true)).assertCountEquals(0)
        capture("ready-${if (dark) "dark-large" else "light"}")
        val session = Hui798Session(device, WaterPhase.Running, attemptedAtMillis = 100_000, startAcknowledged = true,
            serverTime = 100_000, startedAtMillis = 100_000, sessionId = "session")
        compose.runOnIdle { state = state.copy(sessions = listOf(session), selectedSessionId = session.sessionId) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("hui798-primary-action").assertIsNotEnabled().assertTextContains("结束用水")
        compose.onNodeWithTag("hui798-elapsed").assertIsDisplayed().assertTextContains("01:00")
        compose.runOnIdle { state = state.copy(confirmedSessions = setOf(session.sessionId)) }
        compose.onNodeWithTag("hui798-primary-action").assertIsEnabled()
        capture("running-${if (dark) "dark-large" else "light"}")
        compose.runOnIdle { state = state.copy(sessions = listOf(session.copy(phase = WaterPhase.Stopping))) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("hui798-primary-action").assertIsNotEnabled().assertTextContains("正在结束…")
        compose.runOnIdle { state = state.copy(sessions = listOf(session.copy(phase = WaterPhase.Ended, endedAtMillis = 160_000))) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("用水已结束").assertIsDisplayed()
        compose.onNodeWithTag("hui798-ended-icon").assertIsDisplayed()
        compose.onAllNodes(hasText("¥", substring = true)).assertCountEquals(0)
        capture("ended-${if (dark) "dark-large" else "light"}")
        compose.onNodeWithTag("hui798-primary-action").performClick()
        assertEquals(1, scans)
    }
    @Test fun waterStagesKeepCommandsGatedByConfirmedSession() = stages(false)
    @Test @Config(fontScale = 1.3f)
    fun waterStagesFitLargeTextAndDarkMode() = stages(true)

    @Test fun hui798OnlyProgressUsesItsOwnCallbacksAndOneSharedScanner() {
        val settings = settings()
        var opened = 0; var dismissed = 0; var pangguiCallbacks = 0; var scans = 0
        var complete by mutableStateOf(false)
        var present by mutableStateOf(true)
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
            MainNavigationScaffold(settings, 0, {}) { padding ->
                PaymentPage(PreviewData.paymentState(false), settings, padding.calculateBottomPadding() + 28.dp, {},
                    lifeLoginRequired = true, onScan = { scans++ },
                    onOpenLifeSession = { _, _ -> pangguiCallbacks++ }, onDismissLifeSession = { _, _ -> pangguiCallbacks++ },
                    additionalLifeEntries = if (!present) emptyList() else listOf(LifeDeviceStatusEntry(LifeFeature.Water,
                        if (complete) "已完成" else "用水中", "五号楼饮水机", "慧生活798", "hui798-session", complete,
                        progress = if (complete) 1f else null, onOpen = { opened++ }, onDismiss = { dismissed++; present = false })))
            }
        } } }
        compose.mainClock.advanceTimeBy(1000)
        compose.onAllNodesWithTag("payment-scan-button").assertCountEquals(1)
        compose.onNodeWithTag("payment-scan-button").performClick()
        assertEquals(1, scans)
        compose.onNodeWithTag("life-status-water").performClick()
        assertEquals(1, opened)
        assertEquals(0, pangguiCallbacks)
        capture("payment-hui798-only")
        compose.runOnIdle { complete = true }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-progress-hui798-session", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(1f, 0f..1f)))
        compose.onNodeWithTag("dismiss-life-session-hui798-session").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-status-water").assertDoesNotExist()
        assertEquals(1, dismissed)
        assertEquals(0, pangguiCallbacks)
    }
}
