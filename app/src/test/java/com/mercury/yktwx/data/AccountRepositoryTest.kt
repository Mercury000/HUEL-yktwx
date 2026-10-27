package com.mercury.yktwx.data

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
    private open class FakeService : YktService {
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
            return PaymentBatch(listOf("00000000000000000000"), 60, PaymentAccount("0", "0", "0", "Demo", 0))
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
        ) = BillPageData(emptyList(), current, 0)
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
    @Test fun ordinaryReadsEnterTogetherWithoutHoldingTheSessionLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = object : FakeService() {
            override suspend fun payment(token: String): PaymentBatch {
                payments++
                if (payments == 2) entered.complete(Unit)
                release.await()
                return PaymentBatch(emptyList(), 60, PaymentAccount("0", "0", "0", "Demo", 0))
            }
        }
        val repo = AccountRepository(s, MemoryVault())
        val a = async { repo.refresh() }; val b = async { repo.refresh() }
        try { withTimeout(1000) { entered.await() } }
        finally { release.complete(Unit) }
        a.await(); b.await()
        assertEquals(2, s.payments); assertEquals(0, s.logins)
    }
    @Test fun simultaneousOldTokenFailuresShareOneRenewal() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldRequests = 0
        val s = object : FakeService() {
            override suspend fun payment(token: String): PaymentBatch {
                payments++
                if (token == "old") {
                    oldRequests++
                    if (oldRequests == 2) entered.complete(Unit)
                    release.await()
                    throw AuthenticationRequired()
                }
                return PaymentBatch(emptyList(), 60, PaymentAccount("0", "0", "0", "Demo", 0))
            }
        }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val a = async { repo.refresh() }; val b = async { repo.refresh() }
        try { withTimeout(1000) { entered.await() } }
        finally { release.complete(Unit) }
        a.await(); b.await()
        assertEquals(1, s.logins); assertEquals(4, s.payments)
        assertEquals("new", v.session()?.accessToken)
    }
    @Test fun lateOldTokenRejectionCannotClearTheAlreadyRenewedSession() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = object : FakeService() {
            override suspend fun paymentAccount(token: String): PaymentAccount {
                balanceQueries++
                if (token == "old") { entered.complete(Unit); release.await(); throw AuthenticationRequired() }
                return PaymentAccount("0", "0", "0", "Demo", 1234)
            }
        }.apply { paymentFailures.add(AuthenticationRequired()) }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val slow = async { repo.paymentAccount("123") }
        try {
            withTimeout(1000) { entered.await() }
            repo.refresh()
            assertEquals("new", v.session()?.accessToken)
        } finally { release.complete(Unit) }
        assertEquals(1234L, slow.await().balanceCents)
        assertEquals(1, s.logins); assertEquals(2, s.balanceQueries)
        assertNotNull(v.credentials())
    }
    @Test fun concurrentRejectionOfNewTokenStopsWithoutAnotherLoginWave() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val renewedEntered = CompletableDeferred<Unit>()
        val renewedRelease = CompletableDeferred<Unit>()
        var oldRequests = 0
        var newRequests = 0
        val s = object : FakeService() {
            override suspend fun payment(token: String): PaymentBatch {
                payments++
                if (token == "old") {
                    oldRequests++
                    if (oldRequests == 2) entered.complete(Unit)
                    release.await()
                } else {
                    newRequests++
                    if (newRequests == 2) renewedEntered.complete(Unit)
                    renewedRelease.await()
                }
                throw AuthenticationRequired()
            }
        }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val a = async { runCatching { repo.refresh() } }; val b = async { runCatching { repo.refresh() } }
        try {
            withTimeout(1000) { entered.await() }
            release.complete(Unit)
            withTimeout(1000) { renewedEntered.await() }
        } finally { release.complete(Unit); renewedRelease.complete(Unit) }
        assertTrue(a.await().exceptionOrNull() is AuthenticationRequired)
        assertTrue(b.await().exceptionOrNull() is AuthenticationRequired)
        assertEquals(1, s.logins); assertEquals(4, s.payments); assertNull(v.credentials())
    }
    @Test fun logoutCompletesDuringSlowReadAndDiscardsItsLateResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = object : FakeService() {
            override suspend fun paymentAccount(token: String): PaymentAccount {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                return PaymentAccount("0", "0", "0", "Demo", 1234)
            }
        }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val read = async { runCatching { repo.paymentAccount("123") } }
        try { withTimeout(1000) { entered.await(); repo.logout() } }
        finally { release.complete(Unit) }
        assertTrue(read.await().exceptionOrNull() is AuthenticationRequired)
        assertNull(v.credentials()); assertEquals(0, s.logins)
    }
    @Test fun oldFailureAfterSameUsernameLoginCannotClearNewCredentialsOrRelogin() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = object : FakeService() {
            override suspend fun paymentAccount(token: String): PaymentAccount {
                entered.complete(Unit); release.await(); throw AuthenticationRequired()
            }
        }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val read = async { runCatching { repo.paymentAccount("123") } }
        try {
            withTimeout(1000) { entered.await(); repo.login("123", "changed-password") }
        } finally { release.complete(Unit) }
        assertTrue(read.await().exceptionOrNull() is YktException)
        assertEquals(1, s.logins); assertEquals("changed-password", v.credentials()?.password)
        assertEquals("new", v.session()?.accessToken)
    }
    @Test fun canceledReadCannotStartRenewalAfterItsLateAuthenticationFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = object : FakeService() {
            override suspend fun paymentAccount(token: String): PaymentAccount {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                throw AuthenticationRequired()
            }
        }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val read = launch { repo.paymentAccount("123") }
        try { withTimeout(1000) { entered.await() }; read.cancel() }
        finally { release.complete(Unit) }
        read.join()
        assertEquals(0, s.logins); assertEquals("old", v.session()?.accessToken)
    }
    @Test fun canceledRetryCannotClearTheRenewedSessionOnItsLateAuthenticationFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val s = object : FakeService() {
            override suspend fun paymentAccount(token: String): PaymentAccount {
                if (token == "new") {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                throw AuthenticationRequired()
            }
        }
        val v = MemoryVault(); val repo = AccountRepository(s, v)
        val read = launch { runCatching { repo.paymentAccount("123") } }
        try { withTimeout(1000) { entered.await() }; read.cancel() }
        finally { release.complete(Unit) }
        read.join()
        assertEquals(1, s.logins); assertEquals("new", v.session()?.accessToken)
        assertNotNull(v.credentials())
    }
    @Test fun canceledRenewalCannotClearCredentialsOnItsLateLoginRejection() = runBlocking {
        for (rejection in listOf(InvalidCredentials(), CaptchaRequired())) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val s = object : FakeService() {
                override suspend fun login(credentials: Credentials): Session {
                    logins++; entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    throw rejection
                }
            }.apply { paymentFailures.add(AuthenticationRequired()) }
            val v = MemoryVault(); val repo = AccountRepository(s, v)
            val read = launch { runCatching { repo.refresh() } }
            try { withTimeout(1000) { entered.await() }; read.cancel() }
            finally { release.complete(Unit) }
            read.join()
            assertEquals(1, s.logins); assertNotNull(v.credentials())
            assertNull(v.session())
        }
    }
    @Test fun nonReplayableOperationsRemainExclusiveAndAreNeverRetried() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEntered = false
        val s = FakeService(); val v = MemoryVault(); val repo = AccountRepository(s, v)
        val first = async { repo.withSession(retryAuthentication = false) { entered.complete(Unit); release.await() } }
        withTimeout(1000) { entered.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repo.withSession(retryAuthentication = false) { secondEntered = true }
        }
        assertFalse(secondEntered)
        release.complete(Unit); first.await(); second.await()
        assertTrue(secondEntered)
        var calls = 0
        fails(AuthenticationRequired::class.java) {
            repo.withSession(retryAuthentication = false) { calls++; throw AuthenticationRequired() }
        }
        assertEquals(1, calls); assertEquals(0, s.logins)
        assertNotNull(v.credentials()); assertNull(v.session())
    }
    @Test fun billCountQueriesRunTogetherAndPreserveTheirDirections() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val directions = mutableSetOf<Int>()
        val s = object : FakeService() {
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
                directions += type
                if (directions.size == 2) entered.complete(Unit)
                release.await()
                return type * 2
            }
        }
        val read = async { AccountRepository(s, MemoryVault()).billCounts("2026-10-01", "2026-10-31") }
        try { withTimeout(1000) { entered.await() } }
        finally { release.complete(Unit) }
        val result = read.await()
        assertEquals(2, result.income); assertEquals(4, result.expenses)
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
