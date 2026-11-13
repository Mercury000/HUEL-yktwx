package com.mercury.yktwx

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.mercury.yktwx.ui.nav.*
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
    @Test fun recreatedLoginRestoresPhoneButRequiresFreshCaptchaAndCode() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val firstApi = Api()
        fun fresh(api: Api, handle: SavedStateHandle) = Hui798ViewModel(api, Store(null), dispatcher, { clock },
            com.mercury.yktwx.analytics.AnalyticsFixtures.from(ApplicationProvider.getApplicationContext<Application>()), handle)
        val first = fresh(firstApi, handle).also { models.put("first", it) }
        runCurrent(); first.bindPresentation("campus", false, 1)
        first.manageLogin(); first.phoneChanged("12345678901"); first.codeChanged("123456")
        runCurrent()
        val restored = handle.recreated(); models.clear()
        val api = Api()
        val next = fresh(api, restored).also { models.put("next", it) }
        runCurrent(); next.bindPresentation("campus", false, 7)
        assertTrue(next.state.value.loginVisible)
        assertEquals("12345678901", next.state.value.phone)
        assertEquals("", next.state.value.code)
        assertEquals("", next.state.value.imageCode)
        assertNull(next.state.value.captcha)
        next.resumePresentation(true, false); runCurrent()
        assertTrue(api.captchaKeys.isEmpty())
        next.resumePresentation(true, true); runCurrent()
        assertEquals(1, api.captchaKeys.size)
        assertFalse(api.captchaKeys.single() in firstApi.captchaKeys)
        assertNotNull(next.state.value.captcha)
        assertEquals(0, api.smsRequests); assertEquals(0, api.starts); assertEquals(0, api.ends)
        next.bindPresentation("another-campus", false, 8)
        assertFalse(next.state.value.loginVisible)
    }
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
        var deviceUseFailure: Exception? = null
        var deviceUseGate: CompletableDeferred<Unit>? = null
        var useReads = 0
        var homeName: String = device.name
        var userId: String? = account.userId
        var overviewDevices: List<Hui798Device> = emptyList()
        var overviewFailure = false
        var overviewGate: CompletableDeferred<Unit>? = null
        var overviewUserId: String = account.userId!!
        var overviewLoginExpired = false
        var captchaGate: CompletableDeferred<Unit>? = null
        var captchaFailure: Exception? = null
        var smsFailure: Exception? = null
        var smsRequests = 0
        val captchaKeys = mutableListOf<String>()
        val deviceQueries = mutableListOf<String>()
        var smsKey = ""
        override suspend fun captcha(key: String): ByteArray {
            captchaKeys.add(key)
            captchaGate?.let { withContext(NonCancellable) { it.await() } }
            captchaFailure?.let { throw it }
            return ByteArrayOutputStream().also { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        override suspend fun sendCode(phone: String, imageCode: String, key: String) {
            smsRequests++; smsKey = key; smsFailure?.let { throw it }
        }
        override suspend fun login(phone: String, code: String) = Hui798Account(phone, "new-token", account.userId)
        override suspend fun device(id: String, token: String): Hui798Device {
            deviceQueries += id
            return device.copy(id = id, name = homeName)
        }
        override suspend fun overview(token: String): Hui798Overview {
            val result = Hui798Overview(overviewUserId, overviewDevices)
            val loginExpired = overviewLoginExpired
            overviewGate?.let { withContext(NonCancellable) { it.await() } }
            if (loginExpired) throw Hui798Exception("old token expired", true)
            if (authFailure) throw Hui798Exception("fixture expired", true)
            if (overviewFailure) throw IOException("主页查询失败")
            return result
        }
        override suspend fun deviceUse(id: String, token: String): Hui798DeviceUse {
            useReads++
            deviceUseFailure?.let { throw it }
            val result = Hui798DeviceUse(device.copy(id = id, name = homeName), status(id, token), userId)
            deviceUseGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }
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
        return hui798ViewModelFixture(app, api, store, dispatcher) { clock }.also { models.put("model-${System.identityHashCode(it)}", it) }
    }
    private fun running() = Hui798Session(device, WaterPhase.Running, clock, true, clock, clock, sessionId = "fixture-session")

    @Test fun restoredRouteReopensThe798SessionAndValidatesDeviceAndAccountBeforeQuery() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock); overviewDevices = listOf(device) }
        val store = Store(account, listOf(running()))
        model(api, store); runCurrent()
        val handle = SavedStateHandle(); val nav = AppNavigationViewModel(handle)
        nav.bind("campus", false, 1)
        nav.showLife(AppRoute.LifeDevice(LifeProvider.Hui798, LifeFeature.Water, sessionId = running().sessionId, account = account.phone))
        val nextHandle = handle.recreated(); models.clear()
        val next = model(api, store); runCurrent()
        val nextNav = AppNavigationViewModel(nextHandle).apply { bind("campus", false, 1) }
        val route = nextNav.backStack.last() as AppRoute.LifeDevice
        assertEquals(LifeRouteRestore.Restored, restoreLifeRoute(route, false, next))
        assertEquals(running().sessionId, next.state.value.selectedSessionId)
        assertEquals(LifeRouteRestore.AccountChanged, restoreLifeRoute(route.copy(account = "other"), true, next))
        assertEquals(LifeRouteRestore.Missing, restoreLifeRoute(route.copy(sessionId = "missing"), true, next))
        val deviceRoute = route.copy(sessionId = null, deviceId = device.id)
        val queries = api.deviceQueries.size
        assertEquals(LifeRouteRestore.WaitingForNetwork, restoreLifeRoute(deviceRoute, false, next))
        assertEquals(queries, api.deviceQueries.size)
        assertEquals(LifeRouteRestore.Missing, restoreLifeRoute(deviceRoute.copy(deviceId = "invalid"), true, next))
        assertEquals(0, api.starts); assertEquals(0, api.ends); assertEquals(0, api.smsRequests)
    }
    @Test fun anotherLocalRunningDeviceDoesNotRejectStartingTheScannedIdleDevice() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val model = model(api, Store(account, listOf(running()))); runCurrent()
        api.reading = Hui798Reading(99, null)
        model.openDevice("123456789012346"); runCurrent(); model.startWater(); runCurrent()
        assertEquals(1, api.starts); assertEquals(2, model.state.value.sessions.size)
        advanceTimeBy(3000); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.session?.phase)
    }
    @Test fun failedOverviewWithoutUidDoesNotRejectStartingAServerConfirmedIdleDevice() = runTest(dispatcher) {
        val api = Api().apply { overviewFailure = true }
        val model = model(api, Store(account.copy(userId = null))); runCurrent()
        model.openDevice(device.id); runCurrent(); model.startWater(); runCurrent()
        assertEquals(1, api.starts)
        advanceTimeBy(3000); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.session?.phase)
    }
    @Test fun recoveryOfAnIdleDeviceCannotCloseAStartSentWhileRecoveryWasWaiting() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(null)); runCurrent()
        val gate = CompletableDeferred<Unit>()
        api.overviewDevices = listOf(device); api.deviceUseGate = gate
        model.connectAccount(); runCurrent()
        model.phoneChanged(account.phone); model.codeChanged("123456"); model.login(); runCurrent()
        model.openDevice(device.id); runCurrent(); model.startWater(); runCurrent()
        assertEquals(1, api.starts); assertEquals(WaterPhase.Starting, model.state.value.session?.phase)
        api.deviceUseGate = null; gate.complete(Unit); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.session?.phase)
        advanceTimeBy(3000); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.session?.phase); assertEquals(0, api.ends)
    }
    @Test fun unknownStartCanBeDeletedAndIdleStatusReleasesItsOldDeviceEntry() = runTest(dispatcher) {
        val api = Api().apply { lostStart = true }; val store = Store(account); val model = model(api, store); runCurrent()
        model.openDevice(device.id); runCurrent(); model.startWater(); runCurrent()
        val unknown = model.state.value.session!!
        assertTrue(unknown.canDismissException)
        model.dismissSession(unknown.sessionId); runCurrent()
        assertTrue(store.saved.isEmpty()); assertTrue(model.state.value.sessions.isEmpty()); assertEquals(1, api.starts)
        api.reading = Hui798Reading(99, null); model.openDevice(device.id); runCurrent()
        model.startWater(); runCurrent(); assertEquals(2, api.starts)
        api.reading = Hui798Reading(99, null); model.refreshState(); runCurrent()
        assertTrue(model.state.value.sessions.isEmpty()); assertTrue(store.saved.isEmpty())
        assertTrue(model.state.value.deviceReady); assertNull(model.state.value.session)
    }
    @Test fun scanningDuringRecoveryWaitsThenQueriesOnlyTheLatestDevice() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply { overviewGate = gate }
        val model = model(api, Store(account))
        model.openDevice(device.id); model.pageVisible(true); runCurrent()
        assertTrue(model.state.value.restoring); assertEquals(device.id, model.state.value.scannedDeviceId)
        assertTrue(api.deviceQueries.isEmpty()); assertFalse(model.state.value.loginVisible)
        val latest = "123456789012346"
        model.openDevice(latest)
        gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.restoring); assertEquals(latest, model.state.value.device?.id)
        assertEquals(listOf(latest), api.deviceQueries); assertTrue(model.state.value.deviceReady)
        assertEquals(0, api.starts); assertEquals(0, api.ends)
    }

    @Test fun leavingDuringRecoveryDoesNotQueryTheScannedDevice() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply { overviewGate = gate }
        val model = model(api, Store(account))
        model.openDevice(device.id); model.pageVisible(true); runCurrent()
        model.pageVisible(false); gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.restoring); assertTrue(api.deviceQueries.isEmpty())
        assertNull(model.state.value.device); assertEquals(0, api.starts); assertEquals(0, api.ends)
    }

    @Test fun scanningBeforeLoginRetainsTheDeviceAndOnlyQueriesAfterLogin() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(null))
        model.openDevice(device.id); model.pageVisible(true); runCurrent()
        assertFalse(model.state.value.connected); assertEquals(device.id, model.state.value.scannedDeviceId)
        assertTrue(api.deviceQueries.isEmpty()); assertFalse(model.state.value.loginVisible)
        model.connectAccount(false); runCurrent()
        model.phoneChanged(account.phone); model.codeChanged("123456"); model.login(); runCurrent()
        assertTrue(model.state.value.connected); assertEquals(device.id, model.state.value.device?.id)
        assertEquals(listOf(device.id), api.deviceQueries); assertEquals(0, api.starts); assertEquals(0, api.ends)
    }
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
    @Test fun rescanningOurRunningDeviceReturnsToItsOrderAndCompletedUseCanBeScannedAgain() = runTest(dispatcher) {
        val api = Api(); val store = Store(account); val model = model(api, store); runCurrent()
        model.pageVisible(true); model.openDevice(device.id); runCurrent()
        model.startWater(); runCurrent(); advanceTimeBy(3000); runCurrent()
        val order = model.state.value.session!!
        assertEquals(WaterPhase.Running, order.phase)
        val reads = api.reads
        model.pageVisible(false); model.openDevice(device.id); model.pageVisible(true); runCurrent()
        assertEquals(order.sessionId, model.state.value.selectedSessionId)
        assertEquals(order, model.state.value.session)
        assertFalse(model.state.value.deviceInUse); assertFalse(model.state.value.deviceReady)
        assertEquals(1, model.state.value.sessions.size); assertTrue(api.reads > reads)
        assertEquals(1, api.starts); assertNull(model.state.value.error)

        model.stopWater(); runCurrent(); advanceTimeBy(3000); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.session?.phase)
        model.openDevice(device.id); runCurrent()
        assertNull(model.state.value.session); assertTrue(model.state.value.deviceReady)
        assertFalse(model.state.value.deviceInUse)
        assertEquals(1, api.starts); assertEquals(1, api.ends)
    }
    @Test fun scanningAnotherOccupiedDeviceDoesNotSelectOurExistingOrder() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val model = model(api, Store(account, listOf(running()))); runCurrent()
        model.openSession("fixture-session")
        val otherDevice = "123456789012346"
        model.openDevice(otherDevice); runCurrent()
        assertNull(model.state.value.session)
        assertEquals(otherDevice, model.state.value.device?.id)
        assertTrue(model.state.value.deviceInUse); assertFalse(model.state.value.deviceReady)
        assertEquals(listOf(running()), model.state.value.sessions)
        model.startWater(); runCurrent()
        assertEquals(0, api.starts); assertEquals(0, api.ends)
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
    @Test fun acknowledgedStartUsesStatusWhenHomeIsUnavailableButStopStillVerifiesUser() = runTest(dispatcher) {
        val unavailable = "暂时无法确认设备状态，请刷新"
        val api = Api().apply { deviceUseFailure = Hui798Exception(unavailable) }
        val store = Store(account); val model = model(api, store); runCurrent()
        model.environment(true); model.pageVisible(true); model.openDevice(device.id); runCurrent()
        model.startWater(); runCurrent()
        assertEquals(1, api.starts); assertEquals(WaterPhase.Starting, model.state.value.session?.phase)
        advanceTimeBy(3000); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.session?.phase)
        assertEquals(clock, store.saved.single().serverTime)
        assertNull(model.state.value.error); assertEquals(0, api.useReads)
        advanceTimeBy(6000); runCurrent()
        assertNull(model.state.value.error); assertEquals(0, api.useReads)

        model.stopWater(); runCurrent()
        assertEquals(unavailable, model.state.value.error)
        assertEquals(WaterPhase.Running, model.state.value.session?.phase)
        assertEquals(1, api.useReads); assertEquals(0, api.ends)
        api.deviceUseFailure = null
        model.stopWater(); runCurrent()
        assertEquals(2, api.useReads); assertEquals(1, api.ends)
        advanceTimeBy(3000); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.session?.phase)
        assertNull(model.state.value.error); assertEquals(1, api.starts); assertEquals(1, api.ends)
        model.environment(false)
    }
    @Test fun lostStartRequiresServerOwnershipAndDoesNotReplayStartOrUseLocalTimeWindow() = runTest(dispatcher) {
        val api = Api().apply { lostStart = true; userId = null }; val model = model(api, Store(account)); runCurrent()
        model.pageVisible(true); model.openDevice(device.id); runCurrent(); model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.session?.phase); assertFalse(model.state.value.session!!.startAcknowledged)
        model.refreshState(); runCurrent()
        assertEquals(1, api.starts); assertTrue(model.state.value.confirmedSessions.isEmpty()); assertNotNull(model.state.value.error)
        api.userId = account.userId
        api.reading = Hui798Reading(1, clock - 300_000)
        model.refreshState(); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.session?.phase)
        assertEquals(clock - 300_000, model.state.value.session?.serverTime)
        assertEquals(1, api.starts); assertNull(model.state.value.error)
    }
    @Test fun laterUsersSessionCannotBeStoppedAndOurOldProgressEnds() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent(); model.openSession("fixture-session")
        api.userId = "other-user"
        model.stopWater(); runCurrent()
        assertEquals(0, api.ends); assertTrue(model.state.value.confirmedSessions.isEmpty())
        assertTrue(model.state.value.sessions.isEmpty())
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
        assertNull(store.savedAccount); assertFalse(model.state.value.connected)
        assertEquals(listOf(running()), model.state.value.sessions); assertEquals(listOf(running()), store.saved)
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
    @Test fun rejectedSmsAutomaticallyRefreshesCaptchaAndRetainsTheErrorWithoutResendingSms() = runTest(dispatcher) {
        val api = Api().apply { smsFailure = Hui798Exception("图形验证码错误") }
        val model = model(api, Store(null)); runCurrent(); model.connectAccount(); runCurrent()
        val oldKey = api.captchaKeys.single()
        model.phoneChanged(account.phone); model.imageCodeChanged("aB12"); model.sendCode(); runCurrent()
        assertEquals(2, api.captchaKeys.size); assertNotEquals(oldKey, api.captchaKeys.last())
        assertEquals(1, api.smsRequests); assertEquals(oldKey, api.smsKey)
        assertNotNull(model.state.value.captcha); assertEquals("", model.state.value.imageCode)
        assertEquals("图形验证码错误", model.state.value.loginError)
        assertFalse(model.state.value.sendingCode); assertFalse(model.state.value.captchaLoading)
        assertEquals(0, model.state.value.codeSeconds)
        api.smsFailure = null
        model.imageCodeChanged("cD34"); model.sendCode(); runCurrent()
        assertEquals(api.captchaKeys.last(), api.smsKey); assertEquals(2, api.smsRequests)
        assertEquals(60, model.state.value.codeSeconds)
        model.dismissLogin(); advanceTimeBy(60_000); runCurrent()
    }
    @Test fun failedAutomaticCaptchaReloadStopsAndCanBeRetriedManually() = runTest(dispatcher) {
        val api = Api().apply { smsFailure = Hui798Exception("图形验证码错误") }
        val model = model(api, Store(null)); runCurrent(); model.connectAccount(); runCurrent()
        api.captchaFailure = IOException("fixture offline")
        model.phoneChanged(account.phone); model.imageCodeChanged("aB12"); model.sendCode(); runCurrent()
        assertEquals(2, api.captchaKeys.size); assertEquals(1, api.smsRequests)
        assertNull(model.state.value.captcha); assertFalse(model.state.value.captchaLoading)
        assertTrue(model.state.value.loginError!!.contains("图形验证码错误"))
        model.sendCode(); runCurrent(); assertEquals(1, api.smsRequests)
        api.captchaFailure = null; model.refreshCaptcha(); runCurrent()
        assertNotNull(model.state.value.captcha); assertNull(model.state.value.loginError)
        assertEquals(3, api.captchaKeys.size)
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

    @Test fun missingUserOrNetworkPreservesKnownProgressAndAllowsServerReverification() = runTest(dispatcher) {
        for (missingUser in listOf(true, false)) {
            val api = Api().apply {
                reading = Hui798Reading(1, clock)
                if (missingUser) userId = null else statusFailure = true
            }
            val store = Store(account, listOf(running())); val model = model(api, store); runCurrent()
            assertEquals(listOf(running()), model.state.value.sessions); assertEquals(listOf(running()), store.saved)
            model.environment(true); runCurrent(); val reads = api.reads
            api.userId = account.userId; api.statusFailure = false
            advanceTimeBy(6000); runCurrent(); assertTrue(api.reads > reads)
            model.environment(false); model.environment(true); runCurrent()
            assertTrue(model.state.value.confirmedSessions.contains("fixture-session"))
            assertEquals(0, api.starts); assertEquals(0, api.ends)
            model.environment(false)
            val restarted = model(api, store); runCurrent()
            assertEquals(WaterPhase.Running, restarted.state.value.sessions.single().phase)
            assertEquals(1, store.saved.size)
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
        assertTrue(model.state.value.sessions.isEmpty())
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
        assertEquals(0, api.ends); assertTrue(model.state.value.sessions.isEmpty())
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
