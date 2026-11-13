package com.mercury.yktwx

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.state.*
import com.mercury.yktwx.ui.nav.AppRoute
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** A real Bundle/Parcel round trip, with a new handle, repository and ViewModel each time. */
internal fun SavedStateHandle.recreated(): SavedStateHandle {
    val parcel = Parcel.obtain()
    return try {
        parcel.writeBundle(savedStateProvider().saveState())
        val bytes = parcel.marshall()
        parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
        SavedStateHandle.createHandle(parcel.readBundle(SavedStateHandle::class.java.classLoader), null)
    } finally { parcel.recycle() }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StateRestorationTest {
    private val models = ViewModelStore()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val period = BillDateFilter.day(LocalDate.of(2026, 10, 20))
    private val reference = BillReference("saved-order", BillPeriod.from(period))
    private val requests = CopyOnWriteArrayList<Int>()
    private var gate: CompletableDeferred<Unit>? = null
    @After fun cleanup() { gate?.complete(Unit); models.clear(); shadowOf(Looper.getMainLooper()).idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("State did not settle", condition())
    }
    private fun navigation(handle: SavedStateHandle) = AppNavigationViewModel(handle).also { models.put("nav-${models.keys().size}", it) }

    @Test fun allRouteParametersAndTabSurviveANewModelButReloginResetsTheirScope() {
        val handle = SavedStateHandle(); val before = navigation(handle)
        before.bind("owner", false, 31); before.tab = 3
        val routes = listOf(AppRoute.Profile, AppRoute.Account, AppRoute.Settings, AppRoute.Theme, AppRoute.About,
            AppRoute.References, AppRoute.BillDetail(reference), AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride,
                sessionId = "ride-id", account = "bike-owner"))
        routes.forEach(before::open)
        val scope = before.scope
        val recreated = handle.recreated(); models.clear()
        val after = navigation(recreated)
        after.bind("owner", false, 1) // Generations restart with the process.
        assertEquals(listOf(AppRoute.Main) + routes, after.backStack.toList()); assertEquals(3, after.tab)
        assertEquals(scope, after.scope)
        assertTrue(after.needsLifeRestore(routes.last() as AppRoute.LifeDevice))
        after.bind("owner", false, 2)
        assertEquals(listOf(AppRoute.Main), after.backStack.toList()); assertEquals(0, after.tab)
        assertNotEquals(scope, after.scope)
    }

    @Test fun waitingForIdentityPreservesSnapshotWhileAnotherOwnerAndPreviewCannotConsumeIt() {
        val handle = SavedStateHandle(); val before = navigation(handle)
        before.bind("owner", false, 1); before.open(AppRoute.BillDetail(reference))
        val waitingHandle = handle.recreated(); navigation(waitingHandle)
        val stillWaiting = waitingHandle.recreated(); models.clear()
        val restored = navigation(stillWaiting); restored.bind("owner", false, 88)
        assertEquals(AppRoute.BillDetail(reference), restored.backStack.last())
        val different = navigation(handle.recreated()); different.bind("other", false, 1)
        assertEquals(listOf(AppRoute.Main), different.backStack.toList())
        val preview = navigation(handle.recreated()); preview.bind("owner", true, 1)
        assertEquals(listOf(AppRoute.Main), preview.backStack.toList())
    }

    @Serializable private data class Draft(val text: String)
    @Test fun malformedOrOversizedStateFallsBackWithoutRestoringAnyPartialRoute() {
        val corrupt = SavedStateHandle(mapOf("navigation" to Bundle().apply {
            putInt("version", 1); putString("owner", "owner"); putString("value", "{broken")
        }))
        val nav = navigation(corrupt); nav.bind("owner", false, 1)
        assertEquals(listOf(AppRoute.Main), nav.backStack.toList())
        val handle = SavedStateHandle()
        val saved = RestorableState(handle, "draft", Draft.serializer()) { Draft("x".repeat(70_000)) }
        saved.bind("owner")
        val next = RestorableState(handle.recreated(), "draft", Draft.serializer()) { Draft("") }
        assertNull(next.bind("owner"))
    }

    private fun billModel(handle: SavedStateHandle, owner: String = "owner", missing: Boolean = false): BillViewModel {
        val vault = object : Vault {
            override fun credentials() = Credentials(owner, "unused-secret")
            override fun session() = Session("unused-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val record = BillRecord("saved-order", "Read again", "", "", "", "4", "2", 100, 1000, 0,
            "2026-10-20 12:00:00", "2026-10-20 12:00:00", "", "", "", "", "", "")
        val service = object : YktService by yktApiFixture({ error("Unexpected request") }) {
            override suspend fun bills(token: String, current: Int, size: Int, timeFrom: String, timeTo: String,
                typeId: String?, amountFromCents: Long?, amountToCents: Long?, info: String?): BillPageData {
                assertEquals(period.timeFrom, timeFrom); assertEquals(period.timeTo, timeTo)
                requests.add(current)
                gate?.let { withContext(NonCancellable) { it.await() } }
                return BillPageData(listOf(if (current == 2 && !missing) record else record.copy(orderId = "other-$current")), current, 2)
            }
        }
        val repository = accountRepositoryFixture(service, vault)
        return BillViewModel(repository, analytics = AnalyticsFixtures.from(app), initialEnvironment = ReadEnvironment.Inactive,
            savedState = handle).also {
            models.put("bill-${models.keys().size}", it); it.initialize()
            await { repository.sessionState.value.restored && !it.state.value.restoringDisplay }
        }
    }

    @Test fun billQueryAndStatisticsRestoreBeforeReadingAndDetailRequeriesItsExactIdAcrossPages() {
        val handle = SavedStateHandle(); val before = billModel(handle)
        before.selectDate(period); before.applyFilters("4", 100, 900); before.search("食堂")
        before.openStats(); before.selectStatsType(1)
        val encoded = handle.recreated(); models.clear()
        val after = billModel(encoded)
        assertEquals(period, after.state.value.dateFilter); assertEquals("4", after.state.value.typeId)
        assertEquals(100L, after.state.value.amountFromCents); assertEquals(900L, after.state.value.amountToCents)
        assertEquals("食堂", after.state.value.info); assertTrue(after.state.value.statsVisible)
        assertEquals(1, after.state.value.stats.type); assertTrue(requests.isEmpty())
        after.restoreDetail(reference, active = true, online = false)
        assertTrue(requests.isEmpty()); assertNull(after.state.value.detail)
        after.restoreDetail(reference, active = true, online = true)
        await { after.state.value.detail != null && !after.state.value.detailLoading }
        assertEquals(listOf(1, 2), requests.toList()); assertEquals("saved-order", after.state.value.detail?.stableId)
        assertEquals("Read again", after.state.value.detail?.resume)
        after.restoreDetail(reference, active = true, online = true)
        assertEquals(2, requests.size)
        val other = billModel(handle.recreated(), owner = "other")
        assertEquals("", other.state.value.info); assertFalse(other.state.value.statsVisible)
    }

    @Test fun missingDetailTerminatesAndLateResponsesCannotReappearAfterAnAccountChange() {
        val missing = billModel(SavedStateHandle(), missing = true)
        missing.restoreDetail(reference, true, true)
        await { missing.state.value.detailMissing && !missing.state.value.detailLoading }
        assertEquals(listOf(1, 2), requests.toList())
        missing.restoreDetail(reference, true, true); assertEquals(2, requests.size)
        requests.clear(); gate = CompletableDeferred()
        val model = billModel(SavedStateHandle())
        model.restoreDetail(reference, true, true); await { requests.isNotEmpty() }
        model.accountChanged("other", renewed = true)
        gate!!.complete(Unit); await { !model.state.value.detailLoading }
        assertNull(model.state.value.detail); assertFalse(model.state.value.detailMissing)
        val before = requests.size
        model.restoreDetail(reference, true, true)
        await { model.state.value.detailError != null && !model.state.value.detailLoading }
        assertEquals("A mismatched repository account must not receive the restored query", before, requests.size)
    }
}
