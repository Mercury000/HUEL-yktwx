package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
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
class PaymentViewModelTest {
    private val vault = MemoryVault()
    private var account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 6496)
    private var codeAvailable = false
    private var payments = 0
    private var logins = 0
    private val service = object : YktService by YktApi({ error("Unexpected network request") }) {
        override suspend fun login(credentials: Credentials): Session {
            logins++
            return Session("fake-token", Long.MAX_VALUE)
        }
        override suspend fun payment(token: String): PaymentBatch {
            payments++
            if (!codeAvailable) throw PaymentCodeUnavailable(account, "持卡人已挂失！")
            return PaymentBatch(listOf("00000000000000000000"), 60, 0, account)
        }
    }
    private val model = PaymentViewModel(ApplicationProvider.getApplicationContext<Application>(), AccountRepository(service, vault))
    private val store = ViewModelStore().apply { put("payment", model) }

    @After fun cleanup() { store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun firstEntryShowsBalanceEvenWhenBarcodeIsUnavailable() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.account != null && !model.state.value.busy }
        assertFalse(model.state.value.needsLogin)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        assertEquals("持卡人已挂失！", model.state.value.error)
        assertNull(model.state.value.code)
        assertEquals(0, logins)
    }

    @Test fun acceptedLoginDoesNotBecomeFailureJustBecauseBarcodeWasRefused() {
        vault.clear()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        model.login(" 123 ", "fake-password")
        await { !model.state.value.busy && model.state.value.account != null }
        assertFalse(model.state.value.needsLogin)
        assertEquals("123", model.state.value.username)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        assertEquals(1, logins)
        assertNotNull(vault.credentials())
    }

    @Test fun newAccountBalanceIsPreservedWhileOldBarcodeIsCleared() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        codeAvailable = true
        model.onForeground()
        await { model.state.value.code != null }
        codeAvailable = false
        account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 1234)
        model.refresh()
        await { !model.state.value.busy }
        assertEquals(1234L, model.state.value.account!!.balanceCents)
        assertNull(model.state.value.code)
        assertNull(model.state.value.batch)
        assertEquals("持卡人已挂失！", model.state.value.error)
        assertEquals(2, payments)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for payment state")
    }
    private class MemoryVault : Vault {
        private var value: Credentials? = Credentials("123", "fake-password")
        private var token: Session? = Session("fake-token", Long.MAX_VALUE)
        override fun credentials() = value
        override fun session() = token
        override fun saveCredentials(value: Credentials) { this.value = value }
        override fun saveSession(value: Session) { token = value }
        override fun clearSession() { token = null }
        override fun clear() { value = null; token = null }
    }
}
