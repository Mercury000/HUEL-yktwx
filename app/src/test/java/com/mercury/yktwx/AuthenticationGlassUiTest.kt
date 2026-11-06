package com.mercury.yktwx

import android.graphics.Bitmap
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.UsageConsent
import com.mercury.yktwx.ui.YktApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthenticationGlassUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun settle() {
        repeat(2) { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
    }

    private fun assertBarPixels(name: String, entering: Boolean = false): Set<String> {
        // Check the first capture, including while the page is entering.
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File("../tools/consent-review/$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        val checked = mutableSetOf<String>()
        val centers = listOf("付款", "大厅", "账单", "我的").mapNotNull { label ->
            val nodes = compose.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
            if (entering && nodes.isEmpty()) return@mapNotNull null
            val node = nodes.single()
            val coordinates = node.layoutInfo.coordinates
            val position = coordinates.positionInRoot()
            // Ignore only tabs that are legitimately outside the window during
            // the slide. Once a tab fits, its own icon and label must be drawn.
            if (entering && (position.x < 0 || position.x + coordinates.size.width > bitmap.width)) return@mapNotNull null
            val bounds = node.boundsInRoot
            var ink = 0
            for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(bitmap.height)) {
                for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(bitmap.width)) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)
                    if (maxOf(r, g, b) - minOf(r, g, b) > 50 || maxOf(r, g, b) < 100) ink++
                }
            }
            assertTrue("$name: $label must contain rendered icon/label pixels", ink > 50)
            checked += label
            bounds.center.x
        }
        assertTrue(centers.zipWithNext().all { (a, b) -> a < b })
        return checked
    }

    private fun assertWholeBar(name: String) {
        settle()
        assertEquals(setOf("付款", "大厅", "账单", "我的"), assertBarPixels(name))
    }

    private fun assertEnteringBar(name: String, loginFinished: () -> Boolean) {
        val checked = mutableSetOf<String>()
        var pendingFrame = 0
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            checked += assertBarPixels("$name-pending-${pendingFrame++}", entering = true)
            loginFinished()
        }
        repeat(32) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            checked += assertBarPixels("$name-frame-$frame", entering = true)
        }
        assertEquals(setOf("付款", "大厅", "账单", "我的"), checked)
    }

    @Test fun repeatedLogoutReadAgreeAndLoginKeepsAllGlassTabsDrawn() = loginCycles(readNotice = true)

    @Test fun loginWithoutOpeningNoticeKeepsAllGlassTabsDrawn() = loginCycles(readNotice = false)

    private fun loginCycles(readNotice: Boolean) {
        val app = compose.activity.application
        var credentials: Credentials? = Credentials("202600000001", "fictional-password")
        var session: Session? = Session("fake-token", Long.MAX_VALUE)
        val vault = object : Vault {
            override fun credentials() = credentials
            override fun session() = session
            override fun saveCredentials(value: Credentials) { credentials = value }
            override fun saveSession(value: Session) { session = value }
            override fun clearSession() { session = null }
            override fun clear() { credentials = null; session = null }
        }
        var logins = 0
        val service = object : YktService by YktApi({ error("Unexpected HTTP request") }) {
            override suspend fun login(credentials: Credentials): Session { logins++; return Session("fake-token", Long.MAX_VALUE) }
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                val account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 6496)
                onAccount(account)
                return PaymentBatch(listOf("12345678901234567890"), 60, account)
            }
        }
        val payments = PaymentViewModel(app, AccountRepository(service, vault)).apply { initialize(true) }
        val bills = BillViewModel(app).apply { initialize(true) }
        val settings = ThemeSettings(app).apply { setFloating(true); setFloatingStyle(1); setTheme(1) }
        val consent = UsageConsent(app).apply { runBlocking { accept() } }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            YktApp(settings, payments, bills, consent)
        } }
        assertWholeBar("glass-before-logout")
        repeat(3) { turn ->
            tab("我的").performClick()
            compose.onNodeWithText("应用设置").performScrollTo().performClick()
            compose.onNodeWithTag("settings-page-list").performScrollToNode(hasText("退出校园卡登录"))
            compose.onNodeWithText("退出校园卡登录").performClick()
            compose.onNodeWithText("确定").performClick()
            compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payments.state.value.busy }
            assertFalse(consent.accepted)
            assertFalse(UsageConsent(app).accepted)
            assertNull(consent.confirmationHint)
            assertNull(UsageConsent(app).confirmationHint)
            assertNull(credentials)
            assertNull(session)
            compose.onNodeWithText("同意并继续").assertDoesNotExist()
            compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
            compose.onNodeWithText("密码").performTextInput("fictional-password")
            compose.onNodeWithText("登录").assertIsNotEnabled()
            if (readNotice) {
                compose.onNodeWithTag("usage-consent-checkbox").performClick()
                compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
                compose.onNodeWithText("同意并继续").performClick()
            } else {
                compose.runOnIdle { runBlocking { consent.accept() } }
            }
            compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); consent.accepted }
            // The sheet covers the form until its exit finishes; a real user
            // cannot tap the login button through that modal surface.
            settle()
            assertEquals(turn, logins)
            compose.mainClock.autoAdvance = false
            compose.onNodeWithText("登录").assertIsEnabled().performClick()
            try {
                assertEnteringBar("glass-${if (readNotice) "notice" else "control"}-$turn-entry") { !payments.state.value.needsLogin }
            } finally { compose.mainClock.autoAdvance = true }
            assertWholeBar("glass-relogin-$turn")
            tab("大厅").performClick()
            assertWholeBar("glass-relogin-$turn-hall")
            tab("付款").performClick()
            settle()
            compose.onNodeWithContentDescription("付款二维码").performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("放大的付款二维码").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("放大的付款二维码").assertExists()
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            settle()
            compose.onNodeWithContentDescription("放大的付款二维码").assertDoesNotExist()
            assertWholeBar("glass-relogin-$turn-overlay")
            compose.runOnIdle { settings.setFloatingStyle(0) }
            settle()
            compose.runOnIdle { settings.setFloatingStyle(1) }
            assertWholeBar("glass-relogin-$turn-style")
            compose.runOnIdle { settings.setFloating(false) }
            settle()
            compose.runOnIdle { settings.setFloating(true) }
            assertWholeBar("glass-relogin-$turn-floating")
        }
        assertEquals(3, logins)
        compose.activity.viewModelStore.clear()
    }
}
