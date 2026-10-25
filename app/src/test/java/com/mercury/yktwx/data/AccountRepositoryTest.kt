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
        var balanceQueries = 0
        var balanceFailures = mutableListOf<YktException>()
        override suspend fun paymentAccount(token: String): PaymentAccount {
            balanceQueries++
            if (balanceFailures.isNotEmpty()) throw balanceFailures.removeAt(0)
            return PaymentAccount("0", "0", "0", "Demo", 1234)
        }
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
        override suspend fun bills(
            token: String,
            current: Int,
            size: Int,
            timeFrom: String,
            timeTo: String,
            typeId: String?,
            amountFromCents: Long?,
            amountToCents: Long?,
            info: String?,
        ) = BillPageData(emptyList(), 0, current, 0, size)
        override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(0, 0)
        override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int = 0
        override suspend fun billTypes(token: String): List<BillType> = emptyList()
        override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> = emptyList()
        override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> = emptyList()
        override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> = emptyList()
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
    @Test fun balanceQueryUsesSessionWithoutRequestingPaymentCodes() = runBlocking {
        val s = FakeService(); val v = MemoryVault()
        assertEquals(1234L, AccountRepository(s, v).paymentAccount("123").balanceCents)
        assertEquals(1, s.balanceQueries); assertEquals(0, s.payments); assertEquals(0, s.logins)
    }
    @Test fun balanceQueryRejectsAnotherLoginBeforeAnyRequest() = runBlocking {
        val s = FakeService()
        fails(YktException::class.java) { AccountRepository(s, MemoryVault()).paymentAccount("other") }
        assertEquals(0, s.balanceQueries); assertEquals(0, s.payments)
    }
    @Test fun rejectedBalanceTokenRelogsOnceWithoutFetchingCodes() = runBlocking {
        val s = FakeService(); s.balanceFailures.add(AuthenticationRequired())
        AccountRepository(s, MemoryVault()).paymentAccount("123")
        assertEquals(2, s.balanceQueries); assertEquals(1, s.logins); assertEquals(0, s.payments)
    }
    @Test fun logoutClearsEverything() = runBlocking {
        val v = MemoryVault(); AccountRepository(FakeService(), v).logout()
        assertNull(v.credentials()); assertNull(v.session())
    }
}
