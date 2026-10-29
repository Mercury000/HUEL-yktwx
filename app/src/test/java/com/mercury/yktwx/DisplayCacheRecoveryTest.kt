package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayCacheRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val gate = CompletableDeferred<Unit>()
    private val cache by lazy { DisplayCache(folder.root, { b, _ -> b }, { b, _ -> b }) }
    private class MemoryVault : Vault {
        var owner: String? = "fake-owner"
        var savedSession: Session? = Session("fake-token", Long.MAX_VALUE)
        override fun credentials() = owner?.let { Credentials(it, "fake-password") }
        override fun session() = savedSession
        override fun saveCredentials(value: Credentials) { owner = value.username }
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() { owner = null }
    }
    private val vault = MemoryVault()
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for cache recovery")
    }
    @After fun cleanup() { gate.complete(Unit); store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun accountRestorationPreparesAllDisplayTabsWithoutRequestingTheirNetworkData() {
        var requests = 0
        val service = object : YktService by FakeAccountService() {
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                requests++; throw AssertionError("Prewarming must not read bills")
            }
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                requests++; throw AssertionError("Prewarming must not read profile")
            }
            override suspend fun avatar(path: String): ByteArray? = throw AssertionError("Prewarming must not download avatar")
        }
        val fees = object : FeeService by FeeApi() {
            override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
                requests++; throw AssertionError("Prewarming must not read hall")
            }
        }
        val repository = AccountRepository(service, vault, profileService = profiles, feeService = fees, displayCache = cache)
        val hall = PreviewData.hallState()
        val bill = PreviewData.billState()
        val my = PreviewData.myState()
        runBlocking {
            repository.username()
            repository.saveDisplay("fake-owner", DisplayKeys.Profile, my.profile!!, DisplayCodecs.Profile)
            repository.saveDisplay("fake-owner", DisplayKeys.summary(bill.dateFilter.timeFrom, bill.dateFilter.timeTo), bill.summary!!, DisplayCodecs.Summary)
            repository.saveDisplay("fake-owner", DisplayKeys.bills(bill.dateFilter, null, null, null, ""), BillPageData(bill.items, 1, 2), DisplayCodecs.Page)
            hall.rooms.flatMap { it.bindings }.forEach {
                repository.saveDisplay("fake-owner", DisplayKeys.room(it.project), it, DisplayCodecs.Room)
            }
            repository.saveDisplay("fake-owner", DisplayKeys.Network, hall.network!!, DisplayCodecs.Query)
        }
        val bills = BillViewModel(app, repository).also { store.put("bill", it); it.initialize(); it.accountChanged("fake-owner") }
        val mine = MyViewModel(app, repository).also { store.put("my", it); it.accountChanged("fake-owner") }
        val dorm = HallViewModel(app, repository).also { store.put("hall", it); it.accountChanged("fake-owner") }
        await { bills.state.value.displayRestored && mine.state.value.displayRestored && dorm.state.value.displayRestored }
        assertEquals(0, requests)
        assertFalse(bills.state.value.loaded); assertFalse(bills.state.value.loading)
        assertFalse(mine.state.value.loaded); assertFalse(mine.state.value.loading)
        assertFalse(dorm.state.value.loaded); assertFalse(dorm.state.value.loading)
        bills.accountChanged(null); mine.accountChanged(""); dorm.accountChanged("")
        assertTrue(bills.state.value.items.isEmpty()); assertNull(mine.state.value.profile); assertTrue(dorm.state.value.rooms.isEmpty())
    }

    @Test fun displayCacheReadDoesNotWaitForSlowTokenRenewal() {
        vault.savedSession = null
        var logins = 0
        val service = object : YktService by FakeAccountService() {
            override suspend fun login(credentials: Credentials): Session {
                logins++; gate.await(); throw NetworkFailure()
            }
        }
        val repository = AccountRepository(service, vault, displayCache = cache)
        val account = PaymentAccount("card", "pay", "1", "示例账户", 100)
        runBlocking { repository.username(); repository.saveDisplay("fake-owner", DisplayKeys.Account, account, DisplayCodecs.Account) }
        val model = PaymentViewModel(app, repository)
        store.put("payment", model); model.onForeground(); model.initialize()
        await { logins == 1 }
        val cached = runBlocking { kotlinx.coroutines.withTimeout(1000) {
            repository.cachedDisplay("fake-owner", DisplayKeys.Account, DisplayCodecs.Account)
        } }
        assertEquals(100L, cached!!.balanceCents)
    }

    @Test fun logoutRevokesADisplayReadAlreadyDecodingOnDisk() = runBlocking {
        val decoding = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val slowCache = DisplayCache(folder.root, { bytes, _ -> bytes }, { bytes, _ ->
            decoding.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
            bytes
        })
        val repository = AccountRepository(FakeAccountService(), vault, displayCache = slowCache)
        repository.username()
        repository.saveDisplay("fake-owner", DisplayKeys.Account, PaymentAccount("a", "p", "1", "old", 10), DisplayCodecs.Account)
        val read = async(Dispatchers.Default) { repository.cachedDisplay("fake-owner", DisplayKeys.Account, DisplayCodecs.Account) }
        try {
            assertTrue(decoding.await(5, TimeUnit.SECONDS))
            val logout = async(Dispatchers.Default) { repository.logout() }
            withTimeout(1000) { while (repository.ownsAccount("fake-owner")) yield() }
            finish.countDown()
            assertNull(read.await())
            logout.await()
        } finally { finish.countDown() }
    }

    @Test fun billRestartRestoresFirstPageButWaitsForLiveValidationBeforeAppending() {
        val period = BillDateFilter.currentMonth()
        val old = PreviewData.billState().items.first().copy(orderId = "old")
        val fresh = old.copy(orderId = "new")
        val historical = old.copy(orderId = "historical")
        var pagesRead = 0
        val service = object : YktService by FakeAccountService() {
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                pagesRead++; gate.await()
                return BillPageData(if (current == 1) listOf(fresh, old) else listOf(historical), current, 2)
            }
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary = throw NetworkFailure()
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int = throw NetworkFailure()
            override suspend fun billTypes(token: String) = emptyList<BillType>()
        }
        val repository = AccountRepository(service, vault, displayCache = cache)
        runBlocking {
            repository.username()
            repository.saveDisplay("fake-owner", DisplayKeys.bills(period, null, null, null, ""), BillPageData(listOf(old), 1, 2), DisplayCodecs.Page)
            repository.saveDisplay("fake-owner", DisplayKeys.summary(period.timeFrom, period.timeTo), BillSummary(1, 2), DisplayCodecs.Summary)
        }
        val model = BillViewModel(app, repository)
        store.put("bill", model)
        model.initialize(); model.accountChanged("fake-owner"); model.onActivate()
        await { model.state.value.items.isNotEmpty() && pagesRead == 1 }
        assertEquals(listOf("old"), model.state.value.items.map { it.orderId })
        assertFalse(model.state.value.paginationReady)
        assertEquals(BillListUpdate.Restore, model.state.value.listUpdate)
        model.loadMore(); assertEquals(1, pagesRead)
        gate.complete(Unit)
        await { !model.state.value.loading && !model.state.value.refreshing && model.state.value.paginationReady }
        assertEquals(setOf("new"), model.state.value.animatedOrderIds)
        assertEquals(2L, model.state.value.summary!!.expensesCents)
        model.loadMore(); await { model.state.value.currentPage == 2 }
        assertEquals(BillListUpdate.Append, model.state.value.listUpdate)
        assertEquals(setOf("historical"), model.state.value.animatedOrderIds)
        val persisted = runBlocking { repository.cachedDisplay("fake-owner", DisplayKeys.bills(period, null, null, null, ""), DisplayCodecs.Page) }!!
        assertEquals(1, persisted.current)
        assertEquals(listOf("new", "old"), persisted.items.map { it.orderId })
    }

    @Test fun cachedBalanceDoesNotBecomeAPaymentCodeOrAnUnverifiedSuccessBaseline() {
        val account = PaymentAccount("card", "pay", "1", "示例账户", 18650)
        val service = object : YktService by FakeAccountService() {
            override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
                gate.await(); throw NetworkFailure()
            }
        }
        val repository = AccountRepository(service, vault, displayCache = cache)
        runBlocking { repository.username(); repository.saveDisplay("fake-owner", DisplayKeys.Account, account, DisplayCodecs.Account) }
        val model = PaymentViewModel(app, repository)
        store.put("payment", model)
        model.onForeground(); model.initialize()
        await { model.state.value.busy && model.state.value.account != null }
        assertTrue(model.state.value.accountFromCache)
        assertNull(model.state.value.code)
        gate.complete(Unit); await { !model.state.value.busy }
        assertEquals(18650L, model.state.value.account!!.balanceCents)
        assertTrue(model.state.value.accountFromCache)
        assertEquals(PaymentCodeProblem.StatusUnverified, model.state.value.problem)
        assertNull(model.paymentSuccess.value)
    }

    @Test fun failedHallReadKeepsCachedReadingsAndRestartHasNoUpdatingLabels() {
        val ready = PreviewData.hallState()
        val fees = object : FeeService by FeeApi() {
            override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
                gate.await(); throw NetworkFailure()
            }
        }
        val repository = AccountRepository(FakeAccountService(), vault, feeService = fees, displayCache = cache)
        runBlocking {
            repository.username()
            for (room in ready.rooms.flatMap { it.bindings }) repository.saveDisplay("fake-owner", DisplayKeys.room(room.project), room, DisplayCodecs.Room)
            repository.saveDisplay("fake-owner", DisplayKeys.Network, ready.network!!, DisplayCodecs.Query)
        }
        val model = HallViewModel(app, repository)
        store.put("hall", model); model.accountChanged("fake-owner"); model.onActivate()
        await { model.state.value.rooms.isNotEmpty() }
        val readings = model.state.value.rooms.flatMap { it.meters }.map { it.query!!.display }
        gate.complete(Unit); await { !model.state.value.loading }
        assertEquals(readings, model.state.value.rooms.flatMap { it.meters }.map { it.query!!.display })
        assertNotNull(model.state.value.error)
        assertFalse(model.state.value.rooms.flatMap { it.meters }.any { it.updating })
    }

    @Test fun logoutClearsSavedDataAndRevokesUnownedWrites() {
        val repository = AccountRepository(FakeAccountService(), vault, displayCache = cache)
        runBlocking {
            repository.username()
            repository.saveDisplay("fake-owner", DisplayKeys.Account, PaymentAccount("a", "p", "1", "old", 10), DisplayCodecs.Account)
            repository.logout()
            repository.saveDisplay("fake-owner", DisplayKeys.Account, PaymentAccount("a", "p", "1", "late", 20), DisplayCodecs.Account)
            assertNull(repository.cachedDisplay("fake-owner", DisplayKeys.Account, DisplayCodecs.Account))
            repository.login("fake-owner", "fake-password")
            assertNull(repository.cachedDisplay("fake-owner", DisplayKeys.Account, DisplayCodecs.Account))
        }
    }

    @Test fun profileRefreshKeepsEquivalentAvatarBytesAndRetainsThemAfterAnImageReadFailure() {
        var image: ByteArray? = byteArrayOf(1, 2, 3)
        var sno = "student-a"
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例同学", sno, "", "", "/minio/avatar.png")
            override suspend fun avatar(path: String) = image?.copyOf()
        }
        val service = object : YktService by FakeAccountService() {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(1, 2)
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = 1
        }
        val model = MyViewModel(app, AccountRepository(service, vault, profileService = profiles))
        store.put("my", model); model.accountChanged("fake-owner"); model.refresh()
        await { model.state.value.loaded && !model.state.value.loading }
        val original = model.state.value.avatar
        assertNotNull(original)
        model.refresh(); await { !model.state.value.loading }
        assertSame(original, model.state.value.avatar)
        image = null
        model.refresh(); await { !model.state.value.loading }
        assertSame(original, model.state.value.avatar)
        assertNotNull(model.state.value.avatarError)
        // Even an unchanged image URL cannot carry the old student's image to a new profile.
        sno = "student-b"
        model.refresh(); await { !model.state.value.loading }
        assertNull(model.state.value.avatar)
    }

    @Test fun restartAfterStudentChangeDoesNotRestorePreviousStudentAvatarAtTheSameUrl() {
        var sno = "student-a"
        var image: ByteArray? = byteArrayOf(1, 2, 3)
        var holdProfile = false
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                if (holdProfile) gate.await()
                return PersonalProfile("示例同学", sno, "", "", "/minio/shared.png")
            }
            override suspend fun avatar(path: String) = image?.copyOf()
        }
        val repository = AccountRepository(FakeAccountService(), vault, profileService = profiles, displayCache = cache)
        val first = MyViewModel(app, repository)
        store.put("first", first); first.accountChanged("fake-owner"); first.refresh()
        await { first.state.value.loaded && !first.state.value.loading }
        assertNotNull(first.state.value.avatar)
        sno = "student-b"
        image = null
        first.refresh(); await { !first.state.value.loading }
        assertNull(first.state.value.avatar)
        store.clear()
        holdProfile = true
        val restartedRepository = AccountRepository(FakeAccountService(), vault, profileService = profiles, displayCache = cache)
        runBlocking { restartedRepository.username() }
        val restarted = MyViewModel(app, restartedRepository)
        store.put("restarted", restarted); restarted.accountChanged("fake-owner"); restarted.refresh()
        await { restarted.state.value.profile != null }
        assertEquals("student-b", restarted.state.value.profile!!.sno)
        assertNull(restarted.state.value.avatar)
    }
}
