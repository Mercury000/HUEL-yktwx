package com.mercury.yktwx

import com.mercury.yktwx.ui.usageConsentFixture
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.UsageConsent
import com.mercury.yktwx.ui.components.noticeParagraph
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageConsentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun displayedNoticeCoversIndependentLifeAccountsAndPreservesTheTextAndLiabilityClause() {
        assertEquals(UsageNotice.text, UsageNotice.paragraphs.joinToString("") { noticeParagraph(it).text })
        listOf("胖乖生活", "慧生活798", "账号、登录凭据、设备信息及业务数据",
            "相机画面和头像仅在本机处理", "各平台账户与业务分别管理",
            "支付信息发送至相关支付服务", "本人合法持有且有权访问的账户",
            "GitHub 及 CDN", "校验安装包完整性与应用身份", "安装由 Android 系统授权并确认")
            .forEach { assertTrue("Missing notice coverage: $it", UsageNotice.text.contains(it)) }
        assertEquals(64, UsageNotice.hash.length)
        val liability = "相关风险由使用者自行承担，开发者不因此承担任何责任。"
        val paragraph = noticeParagraph(UsageNotice.paragraphs.first { liability in it })
        assertTrue(paragraph.spanStyles.any { paragraph.text.substring(it.start, it.end) == liability })
    }

    @Test fun consentSurvivesRestartAndOnlyTheCurrentBodyHashIsAccepted() = runBlocking {
        val prefs = context.getSharedPreferences("usage-test", Context.MODE_PRIVATE)
        val first = usageConsentFixture(prefs, UsageNotice.hash)
        assertFalse(first.accepted)
        first.accept()
        assertTrue(first.accepted)
        assertEquals(UsageNotice.hash, prefs.getString("accepted_hash", null))
        assertTrue(prefs.getLong("accepted_at", 0) > 0)
        assertTrue(usageConsentFixture(prefs, UsageNotice.hash).accepted)
        val updated = usageConsentFixture(prefs, "different-body-hash")
        assertFalse(updated.accepted)
        updated.accept()
        assertFalse(usageConsentFixture(prefs, UsageNotice.hash).accepted)
        assertTrue(usageConsentFixture(prefs, "different-body-hash").accepted)
        updated.revoke()
        assertFalse(updated.accepted)
        assertFalse(usageConsentFixture(prefs, "different-body-hash").accepted)
    }

    @Test fun failedPersistenceCannotMarkTheNoticeAsAccepted() {
        val prefs = context.getSharedPreferences("failed-usage-test", Context.MODE_PRIVATE)
        val failing = object : android.content.SharedPreferences by prefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = prefs.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?) = apply { editor.putString(key, value) }
                    override fun putLong(key: String?, value: Long) = apply { editor.putLong(key, value) }
                    override fun commit() = false
                }
            }
        }
        val consent = usageConsentFixture(failing, UsageNotice.hash)
        assertThrows(IllegalStateException::class.java) { runBlocking { consent.accept() } }
        assertFalse(consent.accepted)
        assertFalse(usageConsentFixture(prefs, UsageNotice.hash).accepted)
    }

    @Test fun noticeHistoryDistinguishesUpdatesFromLogoutWithoutRetainingConsent() = runBlocking {
        val prefs = context.getSharedPreferences("usage-history-test", Context.MODE_PRIVATE)
        assertNull(usageConsentFixture(prefs, "first-body").confirmationHint)
        assertEquals("使用前需阅读并同意使用须知。", usageConsentFixture(prefs, "first-body", previousLogin = true).confirmationHint)
        val first = usageConsentFixture(prefs, "first-body")
        first.accept(); first.revoke()
        assertNull(prefs.getString("accepted_hash", null))
        assertEquals(0, prefs.getLong("accepted_at", 0))
        val returning = usageConsentFixture(prefs, "first-body")
        assertFalse(returning.accepted)
        assertNull(returning.confirmationHint)
        assertNull(first.confirmationHint)
        val changed = usageConsentFixture(prefs, "second-body")
        assertFalse(changed.accepted)
        assertEquals("使用须知已更新，请重新阅读并同意后使用。", changed.confirmationHint)
        changed.accept()
        assertNull(changed.confirmationHint)
        assertNull(usageConsentFixture(prefs, "second-body").confirmationHint)
    }
}
