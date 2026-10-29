package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
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
class HallViewModelTest {
    private val fees = FakeFees()
    private val vault = object : Vault {
        override fun credentials() = Credentials("fake-owner", "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private val model = HallViewModel(ApplicationProvider.getApplicationContext<Application>(),
        AccountRepository(YktApi(), vault, feeService = fees))
    private val store = ViewModelStore().apply { put("hall", model) }
    @Before fun clearDisplayPreference() {
        ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("hall_display", Application.MODE_PRIVATE)
            .edit().clear().commit()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for hall state")
    }
    private fun open() {
        model.accountChanged("fake-owner")
        model.refresh()
        await { model.state.value.loaded && !model.state.value.loading }
    }
    @After fun cleanup() {
        fees.networkGate?.complete(Unit)
        fees.projectGate?.complete(Unit)
        fees.detailGate?.complete(Unit)
        fees.pickerGate?.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun slowProjectDoesNotBlockPublishingTheNetworkCard() {
        fees.projectGate = CompletableDeferred()
        model.accountChanged("fake-owner"); model.refresh()
        await { fees.blockedProjectEntered && fees.networkEntered &&
            fees.configReads[FeeProject.Wenbei] == 1 && fees.configReads[FeeProject.Commercial] == 1 &&
            model.state.value.networkLoaded }
        assertTrue(model.state.value.loading)
        assertNotNull(model.state.value.network)
        assertFalse(model.state.value.roomsLoaded)
        fees.projectGate!!.complete(Unit)
        await { !model.state.value.loading }
        assertTrue(model.state.value.loaded)
        assertNotNull(model.state.value.network)
        assertTrue(fees.writes.isEmpty())
    }

    @Test fun failedConfigurationRefreshKeepsKnownReadingsAndNetwork() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        open()
        val room = model.state.value.rooms.single()
        val query = room.meters.single().query
        val network = model.state.value.network
        fees.failConfigurations = true
        model.refresh()
        await { !model.state.value.loading }
        assertSame(query, model.state.value.rooms.single().meters.single().query)
        assertSame(network, model.state.value.network)
        assertNotNull(model.state.value.error)
        assertNotNull(model.state.value.networkError)
        assertTrue(model.state.value.roomsAvailable)
        assertTrue(fees.writes.isEmpty())
    }

    @Test fun failedMeterRefreshKeepsOnlyTheSameMetersLastReading() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        open()
        val query = model.state.value.rooms.single().meters.single().query
        fees.failDetails = true
        model.refresh()
        await { !model.state.value.loading }
        val meter = model.state.value.rooms.single().meters.single()
        assertSame(query, meter.query)
        assertNotNull(meter.error)
        // A newly selected binding cannot inherit the old room's reading.
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "B")
        model.refresh()
        await { !model.state.value.loading }
        assertNull(model.state.value.rooms.single().meters.single().query)
        assertTrue(fees.writes.isEmpty())
    }

    @Test fun firstOfflineReadDoesNotClaimEmptyBindingsWereSuccessfullyRead() {
        fees.failConfigurations = true
        open()
        assertFalse(model.state.value.roomsAvailable)
        assertTrue(model.state.value.rooms.isEmpty())
        assertNull(model.state.value.network)
        assertNotNull(model.state.value.error)
        fees.failConfigurations = false
        model.refresh()
        await { !model.state.value.loading }
        assertTrue(model.state.value.roomsAvailable)
        assertNull(model.state.value.error)
    }

    @Test fun reconnectingPublishesTheRoomWithoutTheOldFailureWhileNetworkIsStillReading() {
        fees.failConfigurations = true
        open()
        assertNotNull(model.state.value.error)
        fees.failConfigurations = false
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.networkGate = CompletableDeferred()
        fees.networkEntered = false
        model.refresh()
        await { fees.networkEntered && model.state.value.rooms.singleOrNull()?.meters?.singleOrNull()?.query != null }
        assertTrue(model.state.value.loading)
        assertNull("A recovered room must not carry last attempt's page error", model.state.value.error)
        assertNull("The old network error must not reappear beside the recovered room", model.state.value.networkError)
        fees.networkGate!!.complete(Unit)
        await { !model.state.value.loading }
        assertNull(model.state.value.error)
        assertNull(model.state.value.networkError)
    }

    @Test fun bindingInvalidatedDuringRefreshIsReadAgainBeforeBeingConsideredFresh() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.networkGate = CompletableDeferred()
        model.accountChanged("fake-owner"); model.refresh()
        await { fees.networkEntered }
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "B")
        model.invalidate(); model.refresh()
        fees.networkGate!!.complete(Unit)
        await { !model.state.value.loading && fees.configReads.getValue(FeeProject.Zhengdong) == 2 }
        assertEquals("B", model.state.value.rooms.single().binding.names["room"])
        assertTrue(fees.writes.isEmpty())
    }

    @Test fun slowNetworkDoesNotBlockBoundRoomAndAccountChangeClearsThePartialResult() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.networkGate = CompletableDeferred()
        model.accountChanged("fake-owner"); model.refresh()
        await { fees.networkEntered && model.state.value.rooms.singleOrNull()?.meters?.singleOrNull()?.query != null }
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.networkLoaded)
        assertNotNull(model.state.value.rooms.single().meters.single().query)
        model.accountChanged("")
        fees.networkGate!!.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(model.state.value.rooms.isEmpty())
        assertFalse(model.state.value.loaded)
        assertFalse(model.state.value.loading)
    }
    @Test fun refreshingKeepsSiblingReadingUntilItsNewReadCompletes() {
        fees.paired = true
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A照明")
        open()
        assertEquals(2, model.state.value.selectedRoom!!.meters.size)
        val oldAir = model.state.value.selectedRoom!!.meters.single { it.kind == DormMeterKind.AirConditioning }
        fees.pickerGate = CompletableDeferred()
        model.refresh()
        await { model.state.value.selectedRoom!!.meters.any { it.updating } }
        val pending = model.state.value.selectedRoom!!.meters.single { it.kind == DormMeterKind.AirConditioning }
        assertSame(oldAir.query, pending.query)
        assertTrue(pending.updating)
        assertTrue(model.state.value.loading)
        fees.pickerGate!!.complete(Unit)
        await { !model.state.value.loading }
        assertTrue(model.state.value.selectedRoom!!.meters.none { it.updating })
        assertEquals(2, model.state.value.selectedRoom!!.meters.size)
    }
    @Test fun switchingRoomsDuringSlowNetworkSurvivesTheFinalPublication() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        fees.networkGate = CompletableDeferred()
        model.accountChanged("fake-owner"); model.refresh()
        await { model.state.value.roomsLoaded }
        assertTrue(model.state.value.loading)
        model.selectRoom(1)
        assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
        fees.networkGate!!.complete(Unit)
        await { !model.state.value.loading }
        assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
    }

    @Test fun failedBoundMeterRefreshKeepsVerifiedSiblingUntilRecoveryButClearsItForANewRoom() {
        fees.paired = true
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A照明")
        open()
        val previous = model.state.value.selectedRoom!!
        assertEquals(2, previous.meters.size)
        fees.failDetails = true
        model.refresh()
        await { !model.state.value.loading }
        val failed = model.state.value.selectedRoom!!
        assertEquals(2, failed.meters.size)
        previous.meters.forEach { old ->
            val retained = failed.meters.single { it.kind == old.kind }
            assertSame(old.query, retained.query)
            assertNotNull(retained.error)
            assertFalse(retained.updating)
        }
        assertEquals(previous.updatedAtMillis, failed.updatedAtMillis)
        fees.failDetails = false
        model.refresh()
        await { !model.state.value.loading }
        assertEquals(2, model.state.value.selectedRoom!!.meters.size)
        assertTrue(model.state.value.selectedRoom!!.meters.all { it.error == null })
        fees.failDetails = true
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "B照明")
        model.refresh()
        await { !model.state.value.loading }
        assertEquals(1, model.state.value.selectedRoom!!.meters.size)
        assertNull(model.state.value.selectedRoom!!.meters.single().query)
    }

    @Test fun fasterWenbeiReadCannotChooseTheRoomBeforeZhengdongBindingIsKnown() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        fees.projectGate = CompletableDeferred()
        fees.detailGate = CompletableDeferred()
        model.accountChanged("fake-owner"); model.refresh()
        await { fees.detailsEntered.contains(FeeProject.Wenbei) && model.state.value.networkLoaded }
        assertTrue(model.state.value.rooms.isEmpty())
        fees.projectGate!!.complete(Unit)
        await { model.state.value.rooms.size == 2 && fees.detailsEntered.contains(FeeProject.Zhengdong) }
        assertEquals(FeeProject.Zhengdong, model.state.value.selectedRoom!!.project)
        assertTrue(model.state.value.selectedRoom!!.meters.isEmpty())
        assertTrue(model.state.value.selectedRoom!!.pendingMeters.isNotEmpty())
        fees.detailGate!!.complete(Unit)
        await { !model.state.value.loading }
        assertEquals(FeeProject.Zhengdong, model.state.value.selectedRoom!!.project)
        assertEquals(listOf(FeeProject.Zhengdong, FeeProject.Wenbei), model.state.value.rooms.map { it.project })
    }

    @Test fun lastSelectionSurvivesAccountRestorationAndWinsOverTheFasterRoom() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        open()
        model.selectRoom(1)
        assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
        val preferences = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("hall_display", Application.MODE_PRIVATE)
        assertTrue(preferences.all.keys.single().matches(Regex("[a-f0-9]{64}\\.selection")))
        assertTrue((preferences.all.values.single() as Set<*>).all { it.toString().matches(Regex("[a-f0-9]{64}")) })
        model.accountChanged(""); model.accountChanged("fake-owner")
        fees.detailGate = CompletableDeferred()
        fees.detailGateProject = FeeProject.Wenbei
        model.refresh()
        await { model.state.value.rooms.size == 2 && model.state.value.rooms.first().meters.isNotEmpty() }
        assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
        assertTrue(model.state.value.selectedRoom!!.meters.isEmpty())
        fees.detailGate!!.complete(Unit)
        await { !model.state.value.loading }
        model.selectRoom(0)
        assertEquals(FeeProject.Zhengdong, model.state.value.selectedRoom!!.project)
        model.onActivate()
        assertEquals(FeeProject.Zhengdong, model.state.value.selectedRoom!!.project)
        model.refresh()
        await { !model.state.value.loading }
        assertEquals(FeeProject.Zhengdong, model.state.value.selectedRoom!!.project)
        assertTrue(fees.writes.isEmpty())
    }

    @Test fun missingLastSelectionFallsBackToZhengdongThenWenbeiThenFamily() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        fees.saved[FeeProject.FamilyElectric] = fees.room(FeeProject.FamilyElectric, "A电表")
        fees.saved[FeeProject.FamilyWater] = fees.room(FeeProject.FamilyWater, "A水表")
        open()
        assertEquals(listOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric),
            model.state.value.rooms.map { it.project })
        model.selectRoom(1)
        fees.saved[FeeProject.Wenbei] = null
        model.accountChanged(""); open()
        assertEquals(FeeProject.Zhengdong, model.state.value.selectedRoom!!.project)
        fees.saved[FeeProject.Zhengdong] = null
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "A")
        model.accountChanged(""); open()
        assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
        fees.saved[FeeProject.Wenbei] = null
        model.accountChanged(""); open()
        assertEquals(FeeProject.FamilyElectric, model.state.value.selectedRoom!!.project)
    }

    @Test fun lastSelectedRoomIsRestoredByANewViewModel() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        open()
        model.selectRoom(1)
        val restored = HallViewModel(ApplicationProvider.getApplicationContext<Application>(),
            AccountRepository(YktApi(), vault, feeService = fees))
        val restoredStore = ViewModelStore().apply { put("hall", restored) }
        try {
            restored.accountChanged("fake-owner")
            restored.onActivate()
            await { restored.state.value.loaded && !restored.state.value.loading }
            assertEquals(FeeProject.Wenbei, restored.state.value.selectedRoom!!.project)
            restored.selectRoom(-1)
            restored.selectRoom(100)
            assertEquals(FeeProject.Wenbei, restored.state.value.selectedRoom!!.project)
            assertTrue(fees.writes.isEmpty())
        } finally { restoredStore.clear() }
    }

    @Test fun lastSelectionBelongsToEachAccount() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        open()
        model.selectRoom(1)
        val otherVault = object : Vault by vault {
            override fun credentials() = Credentials("another-owner", "fake-password")
        }
        val other = HallViewModel(ApplicationProvider.getApplicationContext<Application>(),
            AccountRepository(YktApi(), otherVault, feeService = fees))
        val otherStore = ViewModelStore().apply { put("hall", other) }
        try {
            other.accountChanged("another-owner"); other.refresh()
            await { other.state.value.loaded && !other.state.value.loading }
            assertEquals(FeeProject.Zhengdong, other.state.value.selectedRoom!!.project)
            other.selectRoom(0)
            other.accountChanged(""); other.accountChanged("another-owner"); other.refresh()
            await { other.state.value.loaded && !other.state.value.loading }
            assertEquals(FeeProject.Zhengdong, other.state.value.selectedRoom!!.project)
            model.accountChanged(""); open()
            assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
            assertTrue(fees.writes.isEmpty())
        } finally { otherStore.clear() }
    }

    @Test fun rememberedFamilyRoomStillMatchesTheRemainingOfficialMeter() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.FamilyElectric] = fees.room(FeeProject.FamilyElectric, "A电表")
        fees.saved[FeeProject.FamilyWater] = fees.room(FeeProject.FamilyWater, "A水表")
        open()
        model.selectRoom(1)
        fees.saved[FeeProject.FamilyElectric] = null
        model.accountChanged(""); open()
        assertEquals(FeeProject.FamilyWater, model.state.value.selectedRoom!!.project)
        assertEquals(listOf(FeeProject.FamilyWater), model.state.value.selectedRoom!!.bindings.map { it.project })
        assertTrue(fees.writes.isEmpty())
    }

    @Test fun unbindingOneRoomKeepsTheSelectedOtherRoomAndRefreshesExactlyOnce() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.saved[FeeProject.Wenbei] = fees.room(FeeProject.Wenbei, "B")
        open()
        val first = model.state.value.rooms.first()
        model.selectRoom(1)
        model.remove(first)
        await { !model.state.value.managing && !model.state.value.loading && model.state.value.rooms.size == 1 }
        assertEquals(FeeProject.Wenbei, model.state.value.selectedRoom!!.project)
        assertEquals(listOf(FeeProject.Zhengdong), fees.writes)
        assertEquals(2, fees.configReads.getValue(FeeProject.Network))
        assertNull(model.state.value.error)
        model.remove(first)
        assertEquals(1, fees.writes.size)
    }

    @Test fun partialFamilyUnbindKeepsFailureAndReconcilesActualRemainingBinding() {
        fees.saved[FeeProject.FamilyElectric] = fees.room(FeeProject.FamilyElectric, "A电表")
        fees.saved[FeeProject.FamilyWater] = fees.room(FeeProject.FamilyWater, "A水表")
        open()
        val family = model.state.value.rooms.single()
        fees.failWrite = FeeProject.FamilyWater
        model.remove(family)
        await { !model.state.value.managing && !model.state.value.loading && fees.writes.size == 2 }
        assertNull(fees.saved[FeeProject.FamilyElectric])
        assertNotNull(fees.saved[FeeProject.FamilyWater])
        assertEquals(listOf(FeeProject.FamilyWater), model.state.value.rooms.single().bindings.map { it.project })
        assertEquals("模拟解绑拒绝", model.state.value.error)
        assertEquals(listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater), fees.writes)
        fees.networkGate = CompletableDeferred()
        fees.networkEntered = false
        model.refresh()
        await { fees.networkEntered }
        assertNull("A new explicit refresh must clear last operation's error", model.state.value.error)
        fees.networkGate!!.complete(Unit)
        await { !model.state.value.loading }
        assertNull(model.state.value.error)
        assertEquals(2, fees.writes.size)
    }

    @Test fun changedOfficialBindingIsNeverUnboundUsingAnOldRoomCard() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        open()
        val old = model.state.value.rooms.single()
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "B")
        model.remove(old)
        await { !model.state.value.managing && !model.state.value.loading }
        assertTrue(fees.writes.isEmpty())
        assertEquals("B", model.state.value.rooms.single().binding.names["room"])
        assertEquals("绑定已变化，请刷新后核实", model.state.value.error)
    }

    @Test fun accountChangeDiscardsQueuedRefreshAndOldErrors() {
        fees.saved[FeeProject.Zhengdong] = fees.room(FeeProject.Zhengdong, "A")
        fees.networkGate = CompletableDeferred()
        model.accountChanged("fake-owner"); model.refresh()
        // Parallel projects can enter in either order; cancel only after both relevant reads begin.
        await { fees.networkEntered && fees.configReads[FeeProject.Zhengdong] == 1 }
        model.invalidate()
        model.accountChanged("")
        fees.networkGate!!.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(model.state.value.loaded)
        assertFalse(model.state.value.loading)
        assertTrue(model.state.value.rooms.isEmpty())
        assertEquals(1, fees.configReads.getValue(FeeProject.Zhengdong))
    }

    private class FakeFees : FeeService by FeeApi({ error("No live request") }) {
        val saved = mutableMapOf<FeeProject, FeeRoomBinding?>()
        val configReads = java.util.concurrent.ConcurrentHashMap<FeeProject, Int>()
        val writes = mutableListOf<FeeProject>()
        var failWrite: FeeProject? = null
        var failConfigurations = false
        var failDetails = false
        var networkGate: CompletableDeferred<Unit>? = null
        @Volatile var networkEntered = false
        var paired = false
        var pickerGate: CompletableDeferred<Unit>? = null
        var projectGate: CompletableDeferred<Unit>? = null
        @Volatile var blockedProjectEntered = false
        var detailGate: CompletableDeferred<Unit>? = null
        var detailGateProject = FeeProject.Zhengdong
        val detailsEntered = java.util.concurrent.ConcurrentHashMap.newKeySet<FeeProject>()
        private val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("room", 2, "房间"))
        fun room(project: FeeProject, name: String) = FeeRoomBinding.from(levels,
            mapOf("campus" to "fake-campus", "room" to "${project.id}-$name"),
            mapOf("campus" to "示例校区", "room" to name))!!
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            configReads.merge(project, 1, Int::plus)
            if (failConfigurations) throw NetworkFailure()
            if (project == FeeProject.Zhengdong && projectGate != null) {
                blockedProjectEntered = true; projectGate!!.await()
            }
            return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false),
                !project.network && project != FeeProject.Commercial, saved[project])
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
            selections: Map<String, String>, detail: Boolean): FeeQuery {
            if (project.network) {
                networkEntered = true
                networkGate?.await()
                return FeeQuery(thirdParty = "{}", display = listOf("用户套餐" to "示例套餐"))
            }
            if (detail) {
                if (failDetails) throw NetworkFailure()
                detailsEntered += project
                if (project == detailGateProject) detailGate?.await()
                return FeeQuery(thirdParty = "{}", display = listOf(
                    if (project == FeeProject.FamilyWater) "剩余水量(吨)" to "28.60" else "剩余电量(度)" to "86.4"))
            }
            if (paired && project == FeeProject.Zhengdong) pickerGate?.await()
            val names = if (project == FeeProject.FamilyElectric) listOf("A电表", "B电表")
                else if (project == FeeProject.FamilyWater) listOf("A水表", "B水表")
                else if (paired && project == FeeProject.Zhengdong) listOf("A照明", "A空调") else listOf("A", "B")
            return FeeQuery(levels, if (level == 0) listOf(FeeOption("fake-campus", "示例校区"))
                else names.map { FeeOption("${project.id}-$it", it) })
        }
        override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) {
            writes += project
            if (project == failWrite) throw YktException("模拟解绑拒绝")
            saved[project] = room
        }
    }
}
