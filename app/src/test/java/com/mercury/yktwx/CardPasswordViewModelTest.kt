package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CardPasswordViewModelTest {
    private class MemoryVault : Vault {
        var value: Credentials? = Credentials("fake-user", "fake-password")
        var token: Session? = Session("fake-token", Long.MAX_VALUE)
        var clears = 0
        override fun credentials() = value
        override fun session() = token
        override fun saveCredentials(value: Credentials) { this.value = value }
        override fun saveSession(value: Session) { token = value }
        override fun clearSession() { token = null }
        override fun clear() { clears++; value = null; token = null }
    }
    private val vault = MemoryVault()
    private val card = CampusCard("fake-card", "校园卡", "202600000001", false, false, 0)
    private var writes = 0
    private var reads = 0
    private var logins = 0
    private var returned = 0
    private var failure: Exception? = null
    private var readGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var writeGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val service = object : CardService by CardServiceApi({ error("Unexpected network") }) {
        override suspend fun passwordConfiguration(token: String): CardPasswordConfiguration {
            reads++
            withContext(NonCancellable) { readGate.await() }
            returned++
            return CardPasswordConfiguration(listOf(card))
        }
        override suspend fun modifyCardPassword(token: String, card: CampusCard, oldPassword: String, newPassword: String, confirmation: String) {
            writes++
            withContext(NonCancellable) { writeGate.await() }
            failure?.let { throw it }
        }
    }
    private val ykt = object : YktService by YktApi({ error("Unexpected network") }) {
        override suspend fun login(credentials: Credentials): Session { logins++; return Session("new-fake-token", Long.MAX_VALUE) }
        override suspend fun payment(token: String) = PaymentBatch(listOf("00000000000000000000"), 60, PaymentAccount("fake-card", "fake-payacc", "1", "统一账户", 100))
    }
    private val repository = AccountRepository(ykt, vault, cardService = service)
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val model = CardPasswordViewModel(app, repository)
    private val payment = PaymentViewModel(app, repository)
    private val store = ViewModelStore().apply { put("password", model); put("payment", payment) }
    @After fun cleanup() { readGate.complete(Unit); writeGate.complete(Unit); store.clear(); shadowOf(Looper.getMainLooper()).idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out")
    }
    private fun open() { model.open("fake-user", false); await { model.state.value.visible && !model.state.value.loading } }
    private fun submit() = model.submit("123456", "654321", "654321")
    private fun noSuccess() = assertNull(runBlocking { withTimeoutOrNull(100) { model.passwordChanges.first() } })

    @Test fun confirmedSuccessClearsCredentialsAndReturnsToLoginWithOneToast() {
        payment.initialize(); await { !payment.state.value.restoringLogin }
        payment.onForeground(); await { payment.state.value.code != null }
        open(); submit(); await { !model.state.value.loading }
        assertFalse(model.state.value.visible)
        assertNull(vault.credentials()); assertNull(vault.session())
        assertEquals(1, vault.clears)
        val username = runBlocking { withTimeout(1000) { model.passwordChanges.first() } }
        payment.cardPasswordChanged(username)
        await { payment.state.value.needsLogin && !payment.state.value.busy }
        assertNull(payment.state.value.code); assertNull(payment.state.value.account)
        assertEquals("查询密码修改成功，请重新登录", runBlocking { withTimeout(1000) { payment.messages.first() } })
        payment.onForeground(); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, logins)
        assertEquals(1, writes)
        submit(); assertEquals(1, writes)
        payment.cardPasswordChanged(username)
        assertNull(runBlocking { withTimeoutOrNull(100) { payment.messages.first() } })
    }
    @Test fun businessFailureKeepsDialogAndOriginalMessageWithoutClearingLogin() {
        val original = "  后端原始提示\n请重新输入  "
        failure = YktException(original)
        open(); submit(); await { !model.state.value.loading }
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.submitting)
        assertEquals(original, model.state.value.error)
        assertSame(card, model.state.value.selectedCard)
        assertNotNull(vault.credentials()); assertNotNull(vault.session())
        assertEquals(0, vault.clears); assertEquals(1, writes); noSuccess()
    }
    @Test fun authenticationFailureDoesNotReplayWriteOrReloginAutomatically() {
        failure = AuthenticationRequired("official expired")
        open(); submit(); await { !model.state.value.loading }
        assertEquals("official expired", model.state.value.error)
        assertEquals(1, writes); assertEquals(0, logins)
        assertNotNull(vault.credentials()); assertNull(vault.session()); noSuccess()
    }
    @Test fun ambiguousFailureIsNotTreatedAsSuccessOrRetried() {
        failure = YktException("修改结果未确认，请先核实")
        open(); submit(); await { !model.state.value.loading }
        assertTrue(model.state.value.visible); assertNotNull(vault.credentials())
        assertEquals(1, writes); assertEquals(0, logins); noSuccess()
    }
    @Test fun invalidInputNeverCallsWriteAndDoesNotClearLogin() {
        open()
        model.submit("12345", "654321", "654321")
        assertEquals("请输入 6 位数字查询密码", model.state.value.error)
        model.submit("123456", "654321", "111111")
        assertEquals("两次输入的新密码不一致", model.state.value.error)
        assertEquals(0, writes); assertNotNull(vault.credentials()); noSuccess()
    }
    @Test fun repeatedClicksAndDismissDuringWriteCannotLoseConfirmedResult() {
        open(); writeGate = CompletableDeferred()
        submit(); await { writes == 1 }
        submit(); model.dismiss()
        assertEquals(1, writes); assertTrue(model.state.value.visible); assertTrue(model.state.value.submitting)
        writeGate.complete(Unit); await { !model.state.value.loading }
        assertEquals("fake-user", runBlocking { withTimeout(1000) { model.passwordChanges.first() } })
        assertNull(vault.credentials())
    }
    @Test fun closingPendingReadDoesNotReopenDialogWithLateResponse() {
        readGate = CompletableDeferred()
        model.open("fake-user", false); await { reads == 1 }
        model.dismiss(); readGate.complete(Unit); await { returned == 1 }
        assertFalse(model.state.value.visible); assertTrue(model.state.value.cards.isEmpty()); noSuccess()
    }
    @Test fun previewCannotModifyRealPassword() {
        model.open("preview", true); await { !model.state.value.loading }
        submit(); assertEquals(0, reads); assertEquals(0, writes)
        assertNotNull(vault.credentials()); noSuccess()
    }
    @Test fun selectedAccountMismatchNeverWritesOrClearsNewAccountCredentials() {
        open()
        vault.value = Credentials("new-user", "new-fake-password")
        submit(); await { !model.state.value.loading }
        assertEquals(0, writes); assertEquals("new-user", vault.credentials()!!.username); noSuccess()
    }
    @Test fun successForDifferentAccountCannotLogoutCurrentAccount() {
        payment.initialize(); await { !payment.state.value.restoringLogin }
        payment.cardPasswordChanged("other-user")
        assertFalse(payment.state.value.needsLogin); assertNotNull(vault.credentials())
    }
    @Test fun queuedRefreshSeesClearedCredentialsInsteadOfReloggingIn() {
        open(); writeGate = CompletableDeferred()
        submit(); await { writes == 1 }
        val queued = CoroutineScope(Dispatchers.Default).async {
            try { repository.refresh(); false } catch (_: AuthenticationRequired) { true }
        }
        writeGate.complete(Unit); await { !model.state.value.loading }
        assertTrue(runBlocking { withTimeout(1000) { queued.await() } })
        assertEquals(0, logins); assertNull(vault.credentials())
    }
    @Test fun refreshAfterConfirmedChangeCannotAutomaticallyRecoverOldCredentials() {
        open(); submit(); await { !model.state.value.loading }
        try { runBlocking { repository.refresh() }; fail("Expected authentication required") }
        catch (_: AuthenticationRequired) { }
        assertEquals(0, logins); assertNull(vault.credentials())
    }
}
