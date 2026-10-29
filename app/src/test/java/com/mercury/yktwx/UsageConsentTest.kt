package com.mercury.yktwx

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

    @Test fun displayedNoticePreservesEveryApprovedCharacterAndEmphasizesTheLiabilityClause() {
        val approved = "本应用是非官方开源项目，与河南财经政法大学及相关一卡通服务提供方不存在隶属、合作或授权关系。本项目出于学习与研究目的开发。请仅使用本人合法持有且有权访问的一卡通账户，并遵守学校及相关服务提供方的规定与服务协议。使用本应用不改变你与相关服务提供方之间的权利义务。本应用通过官方服务接口提供功能，不能替代官方服务渠道。使用过程中可能出现认证失败、账号异常或使用受限、数据显示不准确、支付操作失败等情况。请自行评估使用风险；余额、账单及支付结果以官方记录为准。出现异常时，请通过官方渠道核实，避免在支付结果未明确时重复操作。相关风险由使用者自行承担，开发者不因此承担任何责任。登录及一卡通业务数据直接发送至官方服务，开发者不通过自建服务器中转或收集这些数据。使用微信支付时，必要的支付信息会发送至相关支付服务。登录凭据与本地资料缓存采用 Android Keystore 管理的密钥加密保存；自定义头像和界面设置保存在应用私有目录，自定义头像不会上传。官方接口、认证方式及服务规则可能发生变化，导致部分或全部功能不可用。本项目不保证持续可用，也不承诺及时通知、更新、修复或提供技术支持；项目可能暂停或停止维护。"
        assertEquals(approved, UsageNotice.text)
        assertEquals(approved, UsageNotice.paragraphs.joinToString("") { noticeParagraph(it).text })
        val liability = "相关风险由使用者自行承担，开发者不因此承担任何责任。"
        val paragraph = noticeParagraph(UsageNotice.paragraphs[2])
        assertTrue(paragraph.spanStyles.any { paragraph.text.substring(it.start, it.end) == liability })
    }

    @Test fun consentSurvivesRestartAndOnlyTheCurrentBodyHashIsAccepted() = runBlocking {
        val prefs = context.getSharedPreferences("usage-test", Context.MODE_PRIVATE)
        val first = UsageConsent(prefs, UsageNotice.hash)
        assertFalse(first.accepted)
        first.accept()
        assertTrue(first.accepted)
        assertEquals(UsageNotice.hash, prefs.getString("accepted_hash", null))
        assertTrue(prefs.getLong("accepted_at", 0) > 0)
        assertTrue(UsageConsent(prefs, UsageNotice.hash).accepted)
        val updated = UsageConsent(prefs, "different-body-hash")
        assertFalse(updated.accepted)
        updated.accept()
        assertFalse(UsageConsent(prefs, UsageNotice.hash).accepted)
        assertTrue(UsageConsent(prefs, "different-body-hash").accepted)
        updated.revoke()
        assertFalse(updated.accepted)
        assertFalse(UsageConsent(prefs, "different-body-hash").accepted)
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
        val consent = UsageConsent(failing, UsageNotice.hash)
        assertThrows(IllegalStateException::class.java) { runBlocking { consent.accept() } }
        assertFalse(consent.accepted)
        assertFalse(UsageConsent(prefs, UsageNotice.hash).accepted)
    }

    @Test fun noticeHistoryDistinguishesUpdatesFromLogoutWithoutRetainingConsent() = runBlocking {
        val prefs = context.getSharedPreferences("usage-history-test", Context.MODE_PRIVATE)
        assertNull(UsageConsent(prefs, "first-body").confirmationHint)
        assertEquals("登录前需阅读并同意使用须知。", UsageConsent(prefs, "first-body", previousLogin = true).confirmationHint)
        val first = UsageConsent(prefs, "first-body")
        first.accept(); first.revoke()
        assertNull(prefs.getString("accepted_hash", null))
        assertEquals(0, prefs.getLong("accepted_at", 0))
        val returning = UsageConsent(prefs, "first-body")
        assertFalse(returning.accepted)
        assertNull(returning.confirmationHint)
        assertNull(first.confirmationHint)
        val changed = UsageConsent(prefs, "second-body")
        assertFalse(changed.accepted)
        assertEquals("使用须知已更新，请重新阅读并同意后登录。", changed.confirmationHint)
        changed.accept()
        assertNull(changed.confirmationHint)
        assertNull(UsageConsent(prefs, "second-body").confirmationHint)
    }
}
