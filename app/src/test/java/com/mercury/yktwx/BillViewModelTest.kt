package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class BillViewModelTest {
    private val vault = MemoryVault()
    private val service = FakeService()
    private val model = BillViewModel(
        ApplicationProvider.getApplicationContext<Application>(), AccountRepository(service, vault),
    )
    private val store = ViewModelStore().apply { put("bills", model) }

    @After fun cleanup() {
        vault.credentialsGate?.countDown()
        service.requestGate.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun refreshIsBusyBeforeCredentialCheckAndIgnoresRepeatedPulls() {
        vault.credentialsGate = CountDownLatch(1)
        model.refresh()
        assertTrue(model.state.value.refreshing)
        assertFalse(model.state.value.loading)
        model.refresh()
        vault.credentialsGate!!.countDown()
        await { service.requests == 1 }
        assertTrue(model.state.value.refreshing)
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
        assertEquals(1, service.requests)
        assertTrue(model.state.value.loaded)
    }

    @Test fun emptyBillListStaysRefreshingUntilRequestCompletes() {
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.loaded }
        assertTrue(model.state.value.items.isEmpty())

        service.requestGate = CompletableDeferred()
        model.refresh()
        assertTrue(model.state.value.refreshing)
        assertFalse(model.state.value.loading)
        await { service.requests == 2 }
        assertTrue(model.state.value.refreshing)
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
    }

    @Test fun existingBillsStayVisibleThroughoutCredentialCheckAndReload() {
        loadExistingBills()
        vault.credentialsGate = CountDownLatch(1)
        service.requestGate = CompletableDeferred()
        model.refresh()
        assertTrue(model.state.value.refreshing)
        assertEquals(listOf(record), model.state.value.items)
        vault.credentialsGate!!.countDown()
        await { service.requests == 2 }
        assertTrue(model.state.value.refreshing)
        assertEquals(listOf(record), model.state.value.items)
        service.requestGate.complete(Unit)
        await { !model.state.value.refreshing }
    }

    @Test fun missingCredentialsEndsRefreshAndRequiresLogin() {
        vault.savedCredentials = null
        model.refresh()
        assertTrue(model.state.value.refreshing)
        await { !model.state.value.refreshing }
        assertTrue(model.state.value.needsLogin)
        assertFalse(model.state.value.loaded)
        assertEquals(0, service.requests)
    }

    @Test fun failedRequestEndsRefreshWithoutDiscardingExistingBills() {
        loadExistingBills()
        service.failure = NetworkFailure()
        model.refresh()
        await { !model.state.value.refreshing }
        assertFalse(model.state.value.loading)
        assertFalse(model.state.value.needsLogin)
        assertNotNull(model.state.value.error)
        assertEquals(listOf(record), model.state.value.items)
    }

    @Test fun credentialReadFailureDoesNotLeaveRefreshStuck() {
        vault.readFailure = IllegalStateException("fake vault failure")
        model.refresh()
        await { !model.state.value.refreshing }
        assertNotNull(model.state.value.error)
        assertEquals(0, service.requests)
    }

    @Test fun changingMonthCancelsPendingRefresh() {
        model.initialize()
        await { vault.reads > 0 }
        vault.credentialsGate = CountDownLatch(1)
        model.refresh()
        assertTrue(model.state.value.refreshing)
        val nextMonth = java.time.YearMonth.from(model.state.value.dateFilter.start).minusMonths(1)
        model.selectDate(BillDateFilter.month(nextMonth))
        assertFalse(model.state.value.refreshing)
        assertTrue(model.state.value.loading)
        vault.credentialsGate!!.countDown()
        service.requestGate.complete(Unit)
        await { model.state.value.loaded }
        assertEquals(nextMonth, java.time.YearMonth.from(model.state.value.dateFilter.start))
        assertEquals(listOf(nextMonth.atDay(1).toString()), service.requestMonths)
    }

    @Test fun selectingCustomRangeUsesExactFromAndToDates() {
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.loaded }
        service.requests = 0
        service.requestRanges.clear()

        model.selectDate(BillDateFilter.range(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 10, 2)))

        await { model.state.value.loaded && service.requests == 1 }
        assertEquals(listOf("2026-09-03" to "2026-10-02"), service.requestRanges)
        assertEquals(BillDateMode.Range, model.state.value.dateFilter.mode)
    }
    @Test fun detailsAndStatsShareDateAndChangingStatsDateDoesNotLoadHiddenList() {
        service.requestGate.complete(Unit)
        model.initialize()
        await { vault.reads > 0 }
        model.refresh()
        await { model.state.value.loaded }
        model.applyFilters("4", 100L, 2000L)
        await { model.state.value.loaded }
        model.openStats()
        await { model.state.value.stats.loaded }
        val listCalls = service.requests
        val range = BillDateFilter.range(LocalDate.of(2026, 8, 12), LocalDate.of(2026, 9, 7))
        model.selectDate(range)
        await { model.state.value.stats.loaded }
        assertTrue(model.state.value.statsVisible)
        assertEquals(range, model.state.value.dateFilter)
        assertEquals(range, model.state.value.stats.period)
        assertEquals(listCalls, service.requests)
        assertEquals("4", model.state.value.typeId)
        model.showDetails()
        await { model.state.value.loaded }
        assertFalse(model.state.value.statsVisible)
        assertEquals(range.timeFrom to range.timeTo, service.requestRanges.last())
        assertEquals("4", model.state.value.typeId)
    }
    @Test fun myMonthlyShortcutReturnsToUnfilteredCurrentMonthDetails() {
        service.requestGate.complete(Unit)
        model.refresh(); await { model.state.value.loaded }
        model.applyFilters("4", 100L, 5000L); await { model.state.value.loaded }
        model.search("示例商户"); await { model.state.value.loaded }
        model.openStats(); await { model.state.value.stats.loaded }
        model.showCurrentMonth(); await { model.state.value.loaded }
        assertFalse(model.state.value.statsVisible)
        assertEquals(BillDateFilter.currentMonth(), model.state.value.dateFilter)
        assertNull(model.state.value.typeId)
        assertNull(model.state.value.amountFromCents)
        assertNull(model.state.value.amountToCents)
        assertEquals("", model.state.value.info)
    }

    private fun loadExistingBills() {
        service.items = listOf(record)
        service.requestGate.complete(Unit)
        model.refresh()
        await { model.state.value.loaded }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for bill state")
    }

    private class MemoryVault : Vault {
        var savedCredentials: Credentials? = Credentials("test-user", "fake-password")
        @Volatile var credentialsGate: CountDownLatch? = null
        @Volatile var reads = 0
        var readFailure: Exception? = null
        override fun credentials(): Credentials? {
            reads++
            check(credentialsGate?.await(5, TimeUnit.SECONDS) != false)
            readFailure?.let { throw it }
            return savedCredentials
        }
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) { savedCredentials = value }
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() { savedCredentials = null }
    }

    private class FakeService : YktService {
        override suspend fun paymentAccount(token: String) = PaymentAccount("0", "0", "0", "Demo", 0)
        @Volatile var requests = 0
        var requestGate = CompletableDeferred<Unit>()
        var failure: YktException? = null
        var items = emptyList<BillRecord>()
        val requestMonths = mutableListOf<String>()
        val requestRanges = mutableListOf<Pair<String, String>>()
        override suspend fun bills(
            token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
            typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?,
        ): BillPageData {
            requestMonths += timeFrom
            requestRanges += timeFrom to timeTo
            requests++
            requestGate.await()
            failure?.let { throw it }
            return BillPageData(items, current, if (items.isEmpty()) 0 else 1)
        }
        override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(0, 0)
        override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int =
            if (type == 1) items.count { it.income } else items.count { !it.income }
        override suspend fun billTypes(token: String) = listOf(BillType("4", "二维码支付"))
        override suspend fun login(credentials: Credentials): Session = error("Unexpected login")
        override suspend fun payment(token: String): PaymentBatch = error("Unexpected payment")
        override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> = emptyList()
        override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> =
            emptyList()
        override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> = emptyList()
    }

    private companion object {
        val record = BillRecord(
            orderId = "TEST-01", resume = "测试账单", payName = "扫码支付",
            turnoverType = "二维码支付", icon = "qrCode-payment", typeId = "4", typeFrom = "2",
            amountCents = 800, balanceCents = 8236, feeCents = 0,
            effectTime = "2026-10-20 12:05:00", bookTime = "2026-10-20 12:06:00",
            fromAccount = "校园卡", position = "TEST-POS", remark = "测试", merchant = "测试餐厅",
            serialNumber = "TEST-SERIAL", labelName = "",
        )
    }
}
