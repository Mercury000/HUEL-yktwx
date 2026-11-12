package com.mercury.yktwx

import com.mercury.yktwx.data.feeApiFixture
import com.mercury.yktwx.data.accountRepositoryFixture
import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnifiedNetworkReadTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private var failReads = false
    private var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private val reads = ConcurrentHashMap<String, AtomicInteger>()
    private val releases = mutableListOf<CountDownLatch>()
    private suspend fun read(key: String) {
        reads.getOrPut(key) { AtomicInteger() }.incrementAndGet()
        gate.await()
        if (failReads) throw NetworkFailure("服务暂时无法访问")
    }
    private fun count(key: String) = reads[key]?.get() ?: 0
    private val vault = object : Vault {
        override fun credentials() = Credentials("fake-owner", "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private val service = object : YktService by FakeAccountService() {
        override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
            read("payment")
            return PaymentBatch(listOf("00000000000000000000"), 60, PaymentAccount("card", "pay", "1", "示例", 100))
        }
        override suspend fun paymentAccount(token: String): PaymentAccount {
            read("account"); return PaymentAccount("card", "pay", "1", "示例", 100)
        }
        override suspend fun paymentCard(token: String, account: String): CampusCard {
            read("card"); return CampusCard(account, "校园卡", "fake-owner", false, false, 0)
        }
        override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
            read("summary"); return BillSummary(100, 200)
        }
        override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
            read("count-$type"); return type
        }
    }
    private val profiles = object : ProfileService {
        override suspend fun profile(token: String): PersonalProfile {
            read("profile"); return PersonalProfile("示例", "fake-owner", "", "", null)
        }
        override suspend fun avatar(path: String): ByteArray? = null
        override suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity? {
            read("identity"); return HomeCardIdentity("示例", sno, "院系", "专业")
        }
    }
    private val fees = object : FeeService by feeApiFixture({ error("No real request") }) {
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            read("fee-${project.id}")
            return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false))
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
            selections: Map<String, String>, detail: Boolean): FeeQuery {
            read("network"); return FeeQuery(display = listOf("用户套餐" to "示例"))
        }
    }
    private fun repository(cache: DisplayCache? = null) =
        accountRepositoryFixture(service, vault, profileService = profiles, feeService = fees, displayCache = cache)
    private class Page(val update: (Boolean, Boolean, Long) -> Unit, val refresh: () -> Unit,
        val recover: (Long) -> Unit, val loading: () -> Boolean, val failure: () -> ReadFailureKind?,
        val key: String)
    private fun page(kind: String, repo: AccountRepository = repository()): Page = when (kind) {
        "my" -> {
            val model = myViewModelFixture(app, repo)
            store.put(kind, model); model.accountChanged("fake-owner")
            Page(model::setPageVisible, model::refresh, model::recoverRead,
                { model.state.value.loading },
                { readFailureKind(model.state.value.profileErrorKind, model.state.value.homeIdentityErrorKind,
                    model.state.value.avatarErrorKind, model.state.value.summaryErrorKind,
                    model.state.value.countsErrorKind) },
                "profile")
        }
        "hall" -> {
            val model = hallViewModelFixture(app, repo)
            store.put(kind, model); model.accountChanged("fake-owner")
            Page(model::setPageVisible, model::refresh, model::recoverRead,
                { model.state.value.loading || model.state.value.managing },
                { readFailureKind(model.state.value.errorKind, model.state.value.networkErrorKind,
                    model.state.value.projectErrorKinds.values.firstOrNull(),
                    model.state.value.rooms.asSequence().flatMap { it.meters.asSequence() }
                        .map { it.errorKind }.firstOrNull { it == ReadFailureKind.Network }) },
                "fee-412")
        }
        else -> {
            val model = paymentViewModelFixture(app, repo)
            store.put(kind, model)
            model.setReadContext(false, false, false, false, true, 0)
            model.initialize(); await { !model.state.value.restoringLogin }; model.onForeground()
            val account = kind == "account"
            Page({ visible, online, id -> model.setReadContext(visible && !account, false, visible && account,
                visible, online, id) },
                { if (account) model.refreshAccount(manual = true) else model.refresh(manual = true) },
                model::recoverRead,
                { model.state.value.busy || model.state.value.accountLoading || model.state.value.cardStatusLoading },
                { if (account) readFailureKind(model.state.value.accountErrorKind, model.state.value.cardStatusErrorKind)
                  else model.state.value.errorKind },
                if (account) "account" else "payment")
        }
    }
    private fun await(message: String = "read state", condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for $message")
    }
    @After fun cleanup() {
        gate.complete(Unit); releases.forEach { it.countDown() }
        store.clear(); shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun allPagesBlockOfflineManualAndAutomaticReadsAndActivateOnlyWhenVisible() {
        for (kind in listOf("my", "hall", "payment", "account")) {
            reads.clear()
            val page = page(kind)
            page.update(true, false, 0); page.refresh(); page.recover(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(kind, 0, count(page.key)); assertFalse(kind, page.loading())
            page.update(false, true, 1); page.refresh(); page.recover(1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(kind, 0, count(page.key))
            page.update(true, true, 1)
            await { count(page.key) == 1 && !page.loading() }
            page.recover(1)
            assertEquals(kind, 1, count(page.key))
            store.clear()
        }
    }

    @Test fun reconnectMergesOldRequestsThenRecoversTheirNetworkFailureOnlyOnce() {
        for (kind in listOf("my", "hall", "payment", "account")) {
            reads.clear(); failReads = true; gate = CompletableDeferred()
            val page = page(kind)
            page.update(true, true, 0)
            await("$kind first read") { count(page.key) == 1 }
            page.update(true, false, 0); page.update(true, true, 1); page.recover(1)
            assertEquals(kind, 1, count(page.key))
            gate.complete(Unit)
            await("$kind first failure") { !page.loading() && page.failure() == ReadFailureKind.Network }
            page.recover(1)
            await("$kind recovery read") { count(page.key) == 2 && !page.loading() }
            page.recover(1)
            assertEquals(kind, 2, count(page.key))
            failReads = false
            page.update(true, false, 1); page.update(true, true, 2)
            await("$kind reconnect read") { count(page.key) == 3 && !page.loading() && page.failure() == null }
            assertEquals(kind, 3, count(page.key))
            assertNull(kind, page.failure())
            store.clear()
        }
    }

    @Test fun slowLocalPreparationAcrossReconnectConsumesOnlyTheActualNewAttempt() {
        for (kind in listOf("my", "hall")) {
            reads.clear(); failReads = true
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1).also { releases += it }
            val cache = DisplayCache(folder.newFolder(kind), { bytes, _ -> bytes }, { bytes, _ ->
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); bytes })
            if (kind == "my") cache.write("fake-owner", DisplayKeys.Profile,
                PersonalProfile("示例", "fake-owner", "", "", null), DisplayCodecs.Profile)
            else cache.write("fake-owner", DisplayKeys.Network, FeeQuery(display = listOf("用户套餐" to "示例")), DisplayCodecs.Query)
            val repo = repository(cache)
            runBlocking { repo.username() }
            val page = page(kind, repo)
            page.update(true, true, 0)
            await { entered.count == 0L && page.loading() }
            page.update(true, false, 0); page.update(true, true, 1)
            release.countDown()
            await { count(page.key) == 1 && !page.loading() && page.failure() == ReadFailureKind.Network }
            page.recover(1)
            assertEquals(kind, 1, count(page.key))
            store.clear()
        }
    }

    @Test fun codeExpiresOfflineAndUnderAnOverlayWithoutAnotherRequestAndRefreshesOnReturn() {
        for (offline in listOf(true, false)) {
            reads.clear()
            val model = paymentViewModelFixture(app, repository())
            store.put("payment", model)
            model.initialize(); await { !model.state.value.restoringLogin }
            model.onForeground(); await { model.state.value.code != null }
            if (offline) model.setReadContext(true, false, false, true, false, 0)
            else model.setReadContext(false, true, false, false, true, 0)
            shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS)
            assertNull(model.state.value.code); assertEquals(0L, model.state.value.secondsLeft)
            assertEquals(1, count("payment")); assertFalse(model.state.value.busy)
            model.setReadContext(true, false, false, true, true, 1)
            await { count("payment") == 2 && model.state.value.code != null }
            store.clear()
        }
    }

    @Test fun motionBalanceChecksRespectOfflineAndStopTheirQueuedRetries() {
        val model = paymentViewModelFixture(app, repository())
        store.put("payment", model)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground(); await { model.state.value.code != null }
        model.setPaymentMotionActive(true)
        model.setReadContext(true, false, false, true, false, 0)
        model.paymentTurnStarted(); model.paymentTurnReturned()
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(0, count("account"))

        model.setReadContext(true, false, false, true, true, 1)
        failReads = true; gate = CompletableDeferred()
        model.paymentTurnStarted(); model.paymentTurnReturned()
        await { count("account") == 1 }
        model.setReadContext(true, false, false, true, false, 1)
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idleFor(2, TimeUnit.SECONDS)
        assertEquals(1, count("account"))
        assertNull(model.paymentSuccess.value)
    }

    @Test fun profileInvalidationWhileCoveredReadsOnReturnEvenWithinTheCacheWindow() {
        val model = myViewModelFixture(app, repository())
        store.put("my", model); model.accountChanged("fake-owner")
        model.setPageVisible(true)
        await { count("profile") == 1 && !model.state.value.loading }
        model.setPageVisible(false)
        model.invalidateProfile(); model.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, count("profile"))
        model.setPageVisible(true)
        await { count("profile") == 2 && !model.state.value.loading }
    }
}
