package com.mercury.yktwx

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Hui798ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val clock = 1_700_000_000_000L
    private val device = Hui798Device("123456789012345", "用水设备")
    private val account = Hui798Account("13800000000", "fixture-token", "fixture-user")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private class Store(var savedAccount: Hui798Account?, var saved: List<Hui798Session> = emptyList()) : Hui798Store {
        override fun account() = savedAccount
        override fun saveAccount(account: Hui798Account) { if (savedAccount != null && savedAccount!!.phone != account.phone) saved = emptyList(); savedAccount = account }
        override fun clearAccount() { savedAccount = null }
        override fun sessions() = saved
        override fun saveSessions(sessions: List<Hui798Session>) { saved = sessions }
    }
    private inner class Api : Hui798Service {
        var reading = Hui798Reading(99, null)
        var starts = 0; var ends = 0; var reads = 0
        var lostStart = false; var lostEnd = false; var holdEnd = false
        var authFailure = false
        var statusFailure = false
        var homeName: String = device.name
        var userId: String? = account.userId
        var overviewDevices: List<Hui798Device> = emptyList()
        var overviewFailure = false
        var overviewGate: CompletableDeferred<Unit>? = null
        var overviewUserId: String = account.userId!!
        var overviewLoginExpired = false
        var captchaGate: CompletableDeferred<Unit>? = null
        val captchaKeys = mutableListOf<String>()
        var smsKey = ""
        override suspend fun captcha(key: String): ByteArray {
            captchaKeys.add(key)
            captchaGate?.let { withContext(NonCancellable) { it.await() } }
            return ByteArrayOutputStream().also { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        override suspend fun sendCode(phone: String, imageCode: String, key: String) { smsKey = key }
        override suspend fun login(phone: String, code: String) = Hui798Account(phone, "new-token", account.userId)
        override suspend fun device(id: String, token: String) = device.copy(id = id, name = homeName)
        override suspend fun overview(token: String): Hui798Overview {
            val result = Hui798Overview(overviewUserId, overviewDevices)
            val loginExpired = overviewLoginExpired
            overviewGate?.let { withContext(NonCancellable) { it.await() } }
            if (loginExpired) throw Hui798Exception("old token expired", true)
            if (authFailure) throw Hui798Exception("fixture expired", true)
            if (overviewFailure) throw IOException("主页查询失败")
            return result
        }
        override suspend fun deviceUse(id: String, token: String) = Hui798DeviceUse(device.copy(id = id, name = homeName), status(id, token), userId)
        override suspend fun status(id: String, token: String): Hui798Reading {
            reads++
            if (authFailure) throw Hui798Exception("fixture expired", true)
            if (statusFailure) throw IOException("设备状态查询失败")
            return reading
        }
        override suspend fun start(id: String, token: String) { starts++; reading = Hui798Reading(1, clock); if (lostStart) throw IOException("fixture lost response") }
        override suspend fun end(id: String, token: String) { ends++; if (!holdEnd) reading = Hui798Reading(99, null); if (lostEnd) throw IOException("fixture lost response") }
    }
    private fun model(api: Api, store: Store): Hui798ViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return Hui798ViewModel(app, api, store, dispatcher) { clock }.also { models.put("model-${System.identityHashCode(it)}", it) }
    }
    private fun running() = Hui798Session(device, WaterPhase.Running, clock, true, clock, clock, sessionId = "fixture-session")
    @Test fun emptyHomeNameDoesNotPreventStatusQueryOrEnableStartBeforeConfirmation() = runTest(dispatcher) {
        val api = Api().apply { homeName = ""; statusFailure = true }
        val model = model(api, Store(account)); runCurrent()
        model.openDevice(device.id); runCurrent()
        assertEquals(device.id, model.state.value.scannedDeviceId)
        assertEquals(device.copy(name = ""), model.state.value.device)
        assertEquals(1, api.reads)
        assertFalse(model.state.value.deviceReady)
        assertNotNull(model.state.value.error)
        model.startWater(); runCurrent(); assertEquals(0, api.starts)
        api.statusFailure = false
        model.refreshDevice(); runCurrent()
        assertTrue(model.state.value.deviceReady)
        assertNull(model.state.value.error)
        assertEquals(2, api.reads)
        assertEquals(0, api.starts)
    }
    @Test fun occupiedDeviceIsAConfirmedStateAndRefreshCanMakeItReady() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val model = model(api, Store(account)); runCurrent()
        model.openDevice(device.id); runCurrent()
        assertTrue(model.state.value.deviceInUse)
        assertFalse(model.state.value.deviceReady)
        assertNull(model.state.value.error)
        model.startWater(); runCurrent(); assertEquals(0, api.starts)
        api.reading = Hui798Reading(99, null)
        model.refreshDevice(); runCurrent()
        assertFalse(model.state.value.deviceInUse)
        assertTrue(model.state.value.deviceReady)
        assertNull(model.state.value.error)
        assertEquals(0, api.starts)
    }
    @Test fun deviceOccupiedBetweenQueryAndStartDoesNotSendStartOrReportUnknownState() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(account)); runCurrent()
        model.openDevice(device.id); runCurrent()
        api.reading = Hui798Reading(1, clock)
        model.startWater(); runCurrent()
        assertTrue(model.state.value.deviceInUse)
        assertFalse(model.state.value.deviceReady)
        assertNull(model.state.value.error)
        assertNull(model.state.value.session)
        assertEquals(0, api.starts)
    }
    @Test fun duplicatedStartAndStopWaitForDeviceConfirmationRatherThanCommandAcceptance() = runTest(dispatcher) {
        val api = Api(); val store = Store(account); val model = model(api, store); runCurrent()
        model.pageVisible(true); model.openDevice(device.id); runCurrent()
        model.startWater(); model.startWater(); runCurrent()
        assertEquals(1, api.starts); assertEquals(WaterPhase.Starting, model.state.value.session?.phase)
        val beforeStartConfirmation = api.reads
        advanceTimeBy(2999); runCurrent(); assertEquals(beforeStartConfirmation, api.reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(beforeStartConfirmation + 1, api.reads); assertEquals(WaterPhase.Running, model.state.value.session?.phase)
        api.holdEnd = true
        model.stopWater(); model.stopWater(); runCurrent()
        assertEquals(1, api.ends); assertEquals(WaterPhase.Stopping, model.state.value.session?.phase)
        assertNull(model.state.value.session?.endedAtMillis)
        val beforeEndConfirmation = api.reads
        advanceTimeBy(2999); runCurrent(); assertEquals(beforeEndConfirmation, api.reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(beforeEndConfirmation + 1, api.reads); assertEquals(WaterPhase.Stopping, model.state.value.session?.phase)
        api.reading = Hui798Reading(99, null)
        advanceTimeBy(2999); runCurrent(); assertEquals(beforeEndConfirmation + 1, api.reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(beforeEndConfirmation + 2, api.reads)
        assertEquals(WaterPhase.Ended, model.state.value.session?.phase); assertEquals(clock, store.saved.single().endedAtMillis)
    }
    @Test fun lostEndResponseIsRecoveredByReadingWithoutRepeatingTheCommand() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock); lostEnd = true }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent(); model.openSession("fixture-session")
        model.stopWater(); runCurrent(); assertEquals(WaterPhase.Stopping, model.state.value.session?.phase)
        model.refreshState(); runCurrent()
        assertEquals(1, api.ends); assertEquals(WaterPhase.Ended, model.state.value.session?.phase)
    }
    @Test fun lostStartDoesNotAdoptAnUnidentifiedRunOrReplayStartOnRetry() = runTest(dispatcher) {
        val api = Api().apply { lostStart = true }; val model = model(api, Store(account)); runCurrent()
        model.pageVisible(true); model.openDevice(device.id); runCurrent(); model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.session?.phase); assertFalse(model.state.value.session!!.startAcknowledged)
        model.refreshState(); runCurrent()
        assertEquals(1, api.starts); assertTrue(model.state.value.confirmedSessions.isEmpty()); assertNotNull(model.state.value.error)
    }
    @Test fun laterUsersSessionCannotBeStoppedAndOurOldProgressEnds() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent(); model.openSession("fixture-session")
        api.userId = "other-user"
        model.stopWater(); runCurrent()
        assertEquals(0, api.ends); assertTrue(model.state.value.confirmedSessions.isEmpty())
        assertEquals(WaterPhase.Ended, model.state.value.sessions.single().phase)
    }
    @Test fun foregroundPollingUpdatesStoredProgressAndCompletionCanBeRemoved() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }; val store = Store(account, listOf(running()))
        val model = model(api, store); runCurrent(); model.environment(true); runCurrent()
        assertEquals(setOf("fixture-session"), model.state.value.confirmedSessions)
        val firstRead = api.reads
        advanceTimeBy(5999); runCurrent(); assertEquals(firstRead, api.reads)
        advanceTimeBy(1); runCurrent(); assertEquals(firstRead + 1, api.reads)
        model.environment(false); val reads = api.reads; advanceTimeBy(12_000); runCurrent(); assertEquals(reads, api.reads)
        model.environment(true); runCurrent(); assertEquals(reads + 1, api.reads)
        api.reading = Hui798Reading(99, null)
        advanceTimeBy(5999); runCurrent(); assertEquals(reads + 1, api.reads)
        assertEquals(WaterPhase.Running, model.state.value.sessions.single().phase)
        advanceTimeBy(1); runCurrent(); assertEquals(reads + 2, api.reads)
        assertEquals(WaterPhase.Ended, model.state.value.sessions.single().phase)
        model.dismissSession("fixture-session"); runCurrent(); assertTrue(store.saved.isEmpty()); assertTrue(model.state.value.sessions.isEmpty())
    }
    @Test fun pendingEndPollsEveryThreeSecondsAfterLostResponseWithoutRepeatingEnd() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock); lostEnd = true; holdEnd = true }
        val model = model(api, Store(account, listOf(running())))
        runCurrent(); model.environment(true); runCurrent(); model.openSession("fixture-session")
        model.stopWater(); runCurrent()
        val reads = api.reads
        assertEquals(1, api.ends); assertEquals(WaterPhase.Stopping, model.state.value.session?.phase)
        advanceTimeBy(2999); runCurrent(); assertEquals(reads, api.reads)
        advanceTimeBy(1); runCurrent(); assertEquals(reads + 1, api.reads)
        api.reading = Hui798Reading(99, null)
        advanceTimeBy(2999); runCurrent(); assertEquals(reads + 1, api.reads)
        advanceTimeBy(1); runCurrent(); assertEquals(reads + 2, api.reads)
        assertEquals(1, api.ends); assertEquals(WaterPhase.Ended, model.state.value.session?.phase)
        model.environment(false)
    }
    @Test fun endConfirmationKeepsThirtySecondWaitLimitThenContinuesForegroundPolling() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock); holdEnd = true }
        val model = model(api, Store(account, listOf(running())))
        runCurrent(); model.environment(true); runCurrent(); model.openSession("fixture-session")
        model.stopWater(); runCurrent()
        val reads = api.reads
        advanceTimeBy(29_999); runCurrent()
        assertTrue(model.state.value.busy); assertEquals(reads + 9, api.reads)
        advanceTimeBy(1); runCurrent()
        assertFalse(model.state.value.busy); assertEquals(reads + 10, api.reads)
        assertEquals(WaterPhase.Stopping, model.state.value.session?.phase); assertNotNull(model.state.value.error)
        api.reading = Hui798Reading(99, null)
        advanceTimeBy(2999); runCurrent(); assertEquals(reads + 10, api.reads)
        advanceTimeBy(1); runCurrent(); assertEquals(reads + 11, api.reads)
        assertEquals(1, api.ends); assertEquals(WaterPhase.Ended, model.state.value.session?.phase)
        model.environment(false)
    }
    @Test fun expiryLeavesProgressOwnedByTheDisconnectedPlatform() = runTest(dispatcher) {
        val api = Api().apply { authFailure = true }; val store = Store(account, listOf(running())); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        assertNull(store.savedAccount); assertFalse(model.state.value.connected); assertTrue(model.state.value.sessions.isEmpty())
        assertTrue(store.saved.isEmpty())
        assertFalse(model.state.value.loginVisible)
    }
    @Test fun refreshedCaptchaUsesOnlyItsMatchingKeyEvenWhenTheOldImageArrivesLate() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val api = Api().apply { captchaGate = gate }; val model = model(api, Store(null)); runCurrent()
        model.connectAccount(); runCurrent(); val oldKey = api.captchaKeys.single()
        api.captchaGate = null; model.refreshCaptcha(); runCurrent()
        assertNotNull(model.state.value.captcha); assertNotEquals(oldKey, api.captchaKeys.last())
        gate.complete(Unit); runCurrent()
        model.phoneChanged(account.phone); model.imageCodeChanged("aB12"); model.sendCode(); runCurrent()
        assertEquals(api.captchaKeys.last(), api.smsKey); assertEquals(60, model.state.value.codeSeconds)
        model.dismissLogin(); advanceTimeBy(60_000); runCurrent()
    }
    @Test fun inlineLoginDoesNotStartOrOpenADeviceUntilExplicitlyRequested() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(null)); runCurrent(); model.connectAccount(); runCurrent()
        model.phoneChanged(account.phone); model.codeChanged("123456"); model.login(); runCurrent()
        assertTrue(model.state.value.connected); assertFalse(model.state.value.loginVisible)
        assertNull(model.state.value.device); assertEquals(0, api.starts); assertEquals(0, api.reads)
    }

    @Test fun restartDiscoversThisAccountsRunningDeviceWithoutALocalOrder() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock); overviewDevices = listOf(device) }
        val store = Store(account.copy(userId = null)); val model = model(api, store); runCurrent()
        val restored = model.state.value.sessions.single()
        assertEquals(device, restored.device); assertEquals(WaterPhase.Running, restored.phase)
        assertEquals(clock, restored.serverTime); assertEquals(account.userId, store.savedAccount?.userId)
        assertEquals(0, api.starts); assertEquals(0, api.ends)
    }

    @Test fun restartExcludesEndedAndOtherUsersDevices() = runTest(dispatcher) {
        for ((reading, userId) in listOf(Hui798Reading(99, null) to account.userId,
            Hui798Reading(1, clock) to "other-user")) {
            val api = Api().apply { this.reading = reading; this.userId = userId; overviewDevices = listOf(device) }
            val store = Store(account, listOf(running(), running().copy(phase = WaterPhase.Ended, sessionId = "completed")))
            val model = model(api, store); runCurrent()
            assertTrue(model.state.value.sessions.isEmpty()); assertTrue(store.saved.isEmpty())
            assertEquals(0, api.starts); assertEquals(0, api.ends)
        }
    }

    @Test fun missingUserOrNetworkDropsTheCacheAndNeverRetriesOldProgress() = runTest(dispatcher) {
        for (missingUser in listOf(true, false)) {
            val api = Api().apply {
                reading = Hui798Reading(1, clock)
                if (missingUser) userId = null else statusFailure = true
            }
            val store = Store(account, listOf(running())); val model = model(api, store); runCurrent()
            assertTrue(model.state.value.sessions.isEmpty()); assertTrue(store.saved.isEmpty())
            model.environment(true); runCurrent(); val reads = api.reads
            api.userId = account.userId; api.statusFailure = false
            advanceTimeBy(30_000); runCurrent(); assertEquals(reads, api.reads)
            model.environment(false); model.environment(true); runCurrent()
            assertEquals(reads, api.reads)
            assertEquals(0, api.starts); assertEquals(0, api.ends)
            model.environment(false)
            val restarted = model(api, store); runCurrent()
            assertTrue(restarted.state.value.sessions.isEmpty()); assertTrue(store.saved.isEmpty())
            assertEquals(reads, api.reads)
        }
    }

    @Test fun foregroundReturnRechecksUserAndNetworkFailurePreservesThisAppSessionsProgress() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent()
        model.environment(true); runCurrent(); model.pageVisible(false); model.environment(false)
        assertEquals(listOf(running()), model.state.value.sessions)
        api.statusFailure = true; model.environment(true); runCurrent()
        assertEquals(listOf(running()), model.state.value.sessions); assertTrue(model.state.value.confirmedSessions.isEmpty())
        api.statusFailure = false; api.userId = "other-user"
        advanceTimeBy(6000); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.sessions.single().phase)
        assertEquals(0, api.ends); model.environment(false)
    }
    @Test fun accountIdentityMismatchDropsTheOldCacheWithoutAdoptingADevice() = runTest(dispatcher) {
        val api = Api().apply { overviewUserId = "different-user"; overviewDevices = listOf(device); reading = Hui798Reading(1, clock) }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent()
        assertTrue(model.state.value.sessions.isEmpty()); assertTrue(store.saved.isEmpty())
        assertNull(store.savedAccount)
        assertNotNull(model.state.value.error); assertEquals(0, api.starts); assertEquals(0, api.ends)
    }
    @Test fun changedMarkerCannotStopTheLaterRunEvenWhenTheUserIsTheSame() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val model = model(api, Store(account, listOf(running()))); runCurrent(); model.openSession("fixture-session")
        api.reading = Hui798Reading(1, clock + 10_000)
        model.stopWater(); runCurrent()
        assertEquals(0, api.ends); assertEquals(WaterPhase.Ended, model.state.value.sessions.single().phase)
    }
    @Test fun lateRecoveryResponseFromPreviousAccountCannotRestoreItsDevice() = runTest(dispatcher) {
        for (expiredResponse in listOf(false, true)) {
            val api = Api().apply { overviewFailure = true; reading = Hui798Reading(1, clock) }
            val store = Store(account, listOf(running())); val model = model(api, store); runCurrent()
            val gate = CompletableDeferred<Unit>()
            api.overviewFailure = false; api.overviewDevices = listOf(device); api.overviewGate = gate
            api.overviewLoginExpired = expiredResponse
            model.connectAccount(); runCurrent(); model.phoneChanged(account.phone); model.codeChanged("123456"); model.login(); runCurrent()
            api.overviewGate = null; api.overviewDevices = emptyList(); api.overviewLoginExpired = false
            model.connectAccount(); runCurrent(); model.phoneChanged("13900000000"); model.codeChanged("123456"); model.login(); runCurrent()
            assertEquals("13900000000", store.savedAccount?.phone)
            assertNull(model.state.value.error)
            gate.complete(Unit); runCurrent()
            assertTrue(model.state.value.sessions.isEmpty()); assertTrue(store.saved.isEmpty())
            assertEquals("13900000000", store.savedAccount?.phone)
            assertEquals(0, api.starts); assertEquals(0, api.ends); model.environment(false)
        }
    }
}
