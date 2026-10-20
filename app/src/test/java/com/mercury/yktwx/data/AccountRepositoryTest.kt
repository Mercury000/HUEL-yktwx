package com.mercury.yktwx.data

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountRepositoryTest {
    private class MemoryVault : Vault {
        var savedCredentials: Credentials? = Credentials("123", "fake-password")
        var savedSession: Session? = Session("old", Long.MAX_VALUE)
        override fun credentials() = savedCredentials
        override fun session() = savedSession
        override fun saveCredentials(value: Credentials) { savedCredentials = value }
        override fun saveSession(value: Session) { savedSession = value }
        override fun clearSession() { savedSession = null }
        override fun clear() { savedCredentials = null; savedSession = null }
    }
    private class FakeService : YktService {
        var logins = 0
        var payments = 0
        var loginFailure: YktException? = null
        var paymentFailures = mutableListOf<YktException>()
        override suspend fun login(credentials: Credentials): Session {
            logins++; delay(5)
            loginFailure?.let { throw it }
            return Session("new", Long.MAX_VALUE)
        }
        override suspend fun payment(token: String): PaymentBatch {
            payments++
            if (paymentFailures.isNotEmpty()) throw paymentFailures.removeAt(0)
            return PaymentBatch(listOf("00000000000000000000"), 60, 0, PaymentAccount("0", "0", "0", "Demo", 0))
        }
    }
    private suspend fun fails(type: Class<out YktException>, action: suspend () -> Unit) {
        try { action(); fail("Expected error") } catch (e: YktException) { assertTrue(type.isInstance(e)) }
    }
    @Test fun freshTokenDoesNotLogin() = runBlocking {
        val s = FakeService(); AccountRepository(s, MemoryVault()).refresh()
        assertEquals(0, s.logins); assertEquals(1, s.payments)
    }
    @Test fun expiredTokenLogsInOnce() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.savedSession = Session("expired", 0)
        AccountRepository(s, v).refresh(); assertEquals(1, s.logins); assertEquals("new", v.session()?.accessToken)
    }
    @Test fun rejectedTokenGetsOneRetry() = runBlocking {
        val s = FakeService(); s.paymentFailures.add(AuthenticationRequired())
        AccountRepository(s, MemoryVault()).refresh(); assertEquals(1, s.logins); assertEquals(2, s.payments)
    }
    @Test fun rejectedNewTokenClearsLoginWithoutLoop() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); s.paymentFailures.addAll(listOf(AuthenticationRequired(), AuthenticationRequired()))
        fails(AuthenticationRequired::class.java) { AccountRepository(s, v).refresh() }
        assertEquals(1, s.logins); assertEquals(2, s.payments); assertNull(v.credentials())
    }
    @Test fun expiredAndRejectedTokenNeverLogsInTwice() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.savedSession = null; s.paymentFailures.add(AuthenticationRequired())
        fails(AuthenticationRequired::class.java) { AccountRepository(s, v).refresh() }
        assertEquals(1, s.logins); assertNull(v.credentials())
    }
    @Test fun invalidPasswordClearsStoredCredentials() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.savedSession = null; s.loginFailure = InvalidCredentials()
        fails(InvalidCredentials::class.java) { AccountRepository(s, v).refresh() }
        assertEquals(1, s.logins); assertNull(v.credentials())
    }
    @Test fun captchaStopsAndClearsCredentials() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.savedSession = null; s.loginFailure = CaptchaRequired()
        fails(CaptchaRequired::class.java) { AccountRepository(s, v).refresh() }
        assertEquals(1, s.logins); assertNull(v.credentials())
    }
    @Test fun networkFailureKeepsCredentials() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.savedSession = null; s.loginFailure = NetworkFailure()
        fails(NetworkFailure::class.java) { AccountRepository(s, v).refresh() }
        assertNotNull(v.credentials()); assertEquals(1, s.logins)
    }
    @Test fun paymentNetworkFailureDoesNotRelogin() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); s.paymentFailures.add(NetworkFailure())
        fails(NetworkFailure::class.java) { AccountRepository(s, v).refresh() }
        assertEquals(0, s.logins); assertNotNull(v.credentials())
    }
    @Test fun concurrentExpiredRequestsShareSingleLogin() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.savedSession = null; val repo = AccountRepository(s, v)
        val a = async { repo.refresh() }; val b = async { repo.refresh() }; a.await(); b.await()
        assertEquals(1, s.logins); assertEquals(2, s.payments)
    }
    @Test fun manualLoginTrimsUsernameAndStoresAfterSuccess() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.clear()
        AccountRepository(s, v).login(" 123 ", "fake-password")
        assertEquals("123", v.credentials()?.username); assertEquals("new", v.session()?.accessToken)
    }
    @Test fun noCredentialsNeverMakesNetworkRequest() = runBlocking {
        val s = FakeService(); val v = MemoryVault(); v.clear()
        fails(AuthenticationRequired::class.java) { AccountRepository(s, v).refresh() }
        assertEquals(0, s.logins); assertEquals(0, s.payments)
    }
    @Test fun logoutClearsEverything() = runBlocking {
        val v = MemoryVault(); AccountRepository(FakeService(), v).logout()
        assertNull(v.credentials()); assertNull(v.session())
    }
}
