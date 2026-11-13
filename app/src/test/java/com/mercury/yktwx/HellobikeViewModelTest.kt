package com.mercury.yktwx

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.life.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HellobikeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private fun bikeTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.clear(); runCurrent() }
    }
    private fun model(api: FakeHellobikeService, store: HellobikeStore, location: FakeBikeLocation = FakeBikeLocation(),
        savedState: SavedStateHandle = SavedStateHandle()): HellobikeViewModel {
        api.elapsed = { 50_000 + dispatcher.scheduler.currentTime }
        return HellobikeViewModel(api, store, location, dispatcher,
            { 1_000_000 + dispatcher.scheduler.currentTime }, api.elapsed, savedState,
            analytics = AnalyticsFixtures.from(ApplicationProvider.getApplicationContext())).also {
            models.put("model-${models.keys().size}", it)
            it.initialize(preview = false)
        }
    }
    private fun TestScope.ready(model: HellobikeViewModel) {
        runCurrent(); model.environment(true); runCurrent(); model.pageVisible(true); runCurrent()
    }
    private fun TestScope.vehicle(model: HellobikeViewModel) {
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent(); assertNotNull(model.state.value.precheck)
    }
    private fun TestScope.ended(model: HellobikeViewModel) {
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.showPayment(); runCurrent(); assertEquals(3, model.state.value.payOptions.size)
    }
    private fun riding() = HellobikeRideReading.Ride(fixtureBikeSession.rideGuid, fixtureBikeSession.bikeNo, 0, userGuid = fixtureBikeAccount.userGuid)

    @Test fun uncertainAndFailedRideRecordsCanBeDeletedWithoutSendingCommands() = bikeTest {
        val api = FakeHellobikeService().apply { onRead = { throw IOException("status unavailable") } }
        for (session in listOf(fixtureBikeSession, fixtureBikeSession.copy(phase = HellobikePhase.Closing, uncertain = true),
            fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Unknown),
            fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying))) {
            val records = BikeRecords(); val store = records.store(sessions = listOf(session))
            val model = model(api, store); runCurrent(); model.environment(true); runCurrent()
            if (session == fixtureBikeSession) {
                model.openSession(session.sessionId); runCurrent()
                assertTrue(model.state.value.sessionErrors.containsKey(session.sessionId))
            }
            model.dismissSession(session.sessionId); runCurrent()
            assertTrue(store.load().sessions.isEmpty()); assertTrue(model.state.value.sessions.isEmpty())
            assertTrue(store.load().retainedSessions.isEmpty())
            model.environment(false); model.environment(true); runCurrent()
            assertTrue(model.state.value.sessions.isEmpty())
            assertEquals(0, api.closeCalls); assertEquals(0, api.createCalls); assertEquals(0, api.payCalls)
            model.environment(false)
        }
    }
    @Test fun dismissingAnUncertainRidePreventsALateRecoveryFromAddingItBack() = bikeTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeHellobikeService().apply { onRead = { withContext(NonCancellable) { gate.await(); riding() } } }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(uncertain = true)))
        val model = model(api, store); runCurrent(); model.environment(true); runCurrent()
        model.dismissSession(fixtureBikeSession.sessionId); runCurrent()
        assertTrue(store.load().sessions.isEmpty())
        gate.complete(Unit); runCurrent()
        assertTrue(model.state.value.sessions.isEmpty()); assertTrue(store.load().sessions.isEmpty())
        assertEquals(0, api.createCalls); assertEquals(0, api.closeCalls); assertEquals(0, api.payCalls)
    }

    @Test fun startCommitsItsRecordBeforeOneCreateAndRequiresAQueriedRideToShowRunning() = bikeTest {
        val records = BikeRecords(); val store = records.store(); val api = FakeHellobikeService(); val model = model(api, store)
        vehicle(model)
        api.orderReading = HellobikeOrderReading(20, 22)
        api.beforeCreate = { assertEquals(HellobikePhase.Opening, store.load().sessions.single().phase) }
        model.startRide(); model.startRide(); runCurrent()
        assertEquals(1, api.createCalls); assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
        api.orderReading = HellobikeOrderReading(20, 0)
        advanceTimeBy(1801); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase); assertEquals(1, api.createCalls)
    }
    @Test fun saveFailurePreventsCreateAndPaymentSubmission() = bikeTest {
        val records = BikeRecords(); val api = FakeHellobikeService(); val model = model(api, records.store())
        vehicle(model); records.failWrite = true
        model.startRide(); runCurrent()
        assertEquals(0, api.createCalls); assertTrue(model.state.value.sessions.isEmpty())
        models.clear()
        val paymentRecords = BikeRecords(); val paymentApi = FakeHellobikeService()
        val payment = model(paymentApi, paymentRecords.store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(payment); paymentRecords.failWrite = true
        payment.pay("003"); runCurrent(); assertEquals(0, paymentApi.payCalls)
    }
    @Test fun elapsedTimeDoesNotInventACloudExpiryOrAutomaticallyOpenTheBike() = bikeTest {
        val api = FakeHellobikeService(); val model = model(api, BikeRecords().store())
        vehicle(model); advanceTimeBy(60_001); runCurrent()
        assertEquals(0, api.createCalls)
        model.startRide(); runCurrent()
        assertEquals(1, api.createCalls); assertEquals(1, api.prepareCalls)
    }
    @Test fun createdRideIdCanDifferFromPreRideWithoutRejectingTheOfficialOrder() = bikeTest {
        val api = FakeHellobikeService().apply { createdRideGuid = "actual-created-ride" }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model); model.startRide(); runCurrent()
        assertEquals("actual-created-ride", store.load().sessions.single().rideGuid)
        advanceTimeBy(1801); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertTrue(api.queriedRideGuids.all { it == "actual-created-ride" })
        assertEquals(1, api.prepareCalls); assertEquals(1, api.createCalls)
    }
    @Test fun lostCreateKeepsAnUnidentifiedAttemptAndNeverQueriesAnInventedOrder() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = IOException("response lost") }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model); model.startRide(); runCurrent()
        val pending = store.load().sessions.single()
        assertEquals("", pending.rideGuid); assertTrue(pending.uncertain)
        assertTrue(api.queriedRideGuids.isEmpty())
        model.retryDeviceCheck(); model.startRide(); runCurrent()
        assertEquals(1, api.createCalls); assertTrue(api.queriedRideGuids.all(String::isBlank))
    }
    @Test fun unidentifiedOpeningRequiresOrderStatusSuccessBeforeReadingTheRidingPage() = bikeTest {
        val pending = fixtureBikeSession.copy(rideGuid = "", phase = HellobikePhase.Opening, uncertain = true)
        val api = FakeHellobikeService().apply { orderReading = HellobikeOrderReading(20, 23) }
        val store = BikeRecords().store(sessions = listOf(pending)); val model = model(api, store)
        ready(model); model.openSession(pending.sessionId); runCurrent()
        assertEquals("", store.load().sessions.single().rideGuid); assertTrue(api.queriedRideGuids.all(String::isBlank))
        api.reading = riding().copy(userGuid = "another-owner")
        model.refreshState(); runCurrent()
        assertEquals("", store.load().sessions.single().rideGuid); assertTrue(api.queriedRideGuids.all(String::isBlank))
        api.reading = riding().copy(userGuid = fixtureBikeAccount.userGuid)
        api.orderReading = HellobikeOrderReading(20, 0)
        model.refreshState(); runCurrent()
        assertEquals(fixtureBikeSession.rideGuid, store.load().sessions.single().rideGuid)
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(0, api.createCalls)
    }
    @Test fun serverSkipKeepsItsOrderEvenIfLocationLaterFails() = bikeTest {
        val location = FakeBikeLocation()
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                val result = super.prepare(account, bikeNo, region, position)
                location.failure = IOException("position lost after accepted server unlock")
                orderReading = HellobikeOrderReading(20, 0)
                return result.copy(skipConfirmation = true, serverUnlockStarted = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store, location)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent(); advanceTimeBy(1801); runCurrent()
        assertEquals(fixtureBikeSession.rideGuid, store.load().sessions.single().rideGuid)
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(0, api.createCalls)
        assertEquals(1, location.calls)
    }
    @Test fun clientSkipConsumesTheOfficialResponseWithoutAButtonPress() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition) =
                super.prepare(account, bikeNo, region, position).copy(skipConfirmation = true)
        }
        val model = model(api, BikeRecords().store())
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent(); advanceTimeBy(1801); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(1, api.prepareCalls); assertEquals(1, api.createCalls)
        model.startRide(); runCurrent(); assertEquals(1, api.createCalls)
    }
    @Test fun basicInfoFailureOrDelayDoesNotBlockAnOrdinaryOfficialPrecheck() = bikeTest {
        for (slow in listOf(false, true)) {
            val api = object : FakeHellobikeService() {
                override suspend fun inspectBike(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                    if (slow) awaitCancellation()
                    throw IOException("计费信息暂未返回")
                }
            }
            val store = BikeRecords().store(); val model = model(api, store)
            vehicle(model)
            assertFalse(model.state.value.loadingDevice)
            assertEquals(0, api.createCalls)
            model.startRide(); runCurrent()
            assertEquals(1, api.createCalls)
            assertEquals(HellobikePhase.Riding, store.load().sessions.single().phase)
        }
    }
    @Test fun returnedExistingRideMergesWithoutRevivingAnEndedOrderOrLosingItsPayment() = bikeTest {
        val ended = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paid,
            endedAtMillis = 900_000, amount = "2.00")
        val api = FakeHellobikeService()
        val store = BikeRecords().store(sessions = listOf(ended)); val model = model(api, store)
        vehicle(model); model.startRide(); runCurrent()
        assertEquals(listOf(ended), store.load().sessions)
        assertEquals(ended.sessionId, model.state.value.selectedSessionId)
        assertEquals(1, api.createCalls)
        assertTrue(api.queriedRideGuids.isEmpty())
    }
    @Test fun lostPreRideResponseKeepsItsDurableAttemptAndRetryOnlyDiscoversTheRide() = bikeTest {
        val store = BikeRecords().store()
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                prepareCalls++
                assertEquals(bikeNo, store.load().sessions.single().bikeNo)
                assertEquals("", store.load().sessions.single().rideGuid)
                throw IOException("pre-ride response lost")
            }
        }
        val model = model(api, store); ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        assertNull(model.state.value.precheck)
        assertTrue(model.state.value.session!!.uncertain)
        assertEquals("", store.load().sessions.single().rideGuid)
        api.reading = riding()
        model.retryDeviceCheck(); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(1, api.prepareCalls); assertEquals(0, api.createCalls)
    }
    @Test fun oldServerPreRideCompletionCannotDeleteTheNewScansRecoveryHandle() = bikeTest {
        val oldReply = CompletableDeferred<Unit>(); val newReply = CompletableDeferred<Unit>()
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                if (bikeNo == fixtureBikeSession.bikeNo) {
                    withContext(NonCancellable) { oldReply.await() }
                    return super.prepare(account, bikeNo, region, position).copy(serverUnlockStarted = true, skipConfirmation = true)
                }
                newReply.await()
                return super.prepare(account, bikeNo, region, position)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        model.openDevice("0987654321"); runCurrent()
        assertEquals(2, store.load().sessions.size)
        oldReply.complete(Unit); runCurrent()
        assertEquals(2, store.load().sessions.size)
        assertTrue(model.state.value.loadingDevice); assertNull(model.state.value.precheck)
        newReply.complete(Unit); runCurrent()
        assertEquals("0987654321", model.state.value.precheck?.bikeNo)
        assertEquals(fixtureBikeSession.rideGuid, store.load().sessions.single().rideGuid)
        assertEquals(0, api.createCalls)
    }
    @Test fun scanningAnotherBikeAndExplicitCreateReachCloudDespiteAnExistingLocalRide() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding() }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession)))
        ready(model); model.openDevice("1234567890"); runCurrent()
        assertEquals(1, api.inspectCalls); assertEquals(1, api.prepareCalls); assertNotNull(model.state.value.precheck)
        model.startRide(); runCurrent()
        assertEquals(1, api.createCalls)
    }
    @Test fun leavingDuringCreateKeepsThePendingRecordAndForegroundRecoveryOnlyQueries() = bikeTest {
        val api = FakeHellobikeService().apply { createGate = CompletableDeferred() }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model); model.startRide(); runCurrent()
        model.pageVisible(false); model.environment(false); runCurrent()
        assertEquals(HellobikePhase.Opening, store.load().sessions.single().phase); assertTrue(store.load().sessions.single().uncertain)
        api.reading = riding(); model.environment(true); runCurrent()
        assertEquals(HellobikePhase.Riding, store.load().sessions.single().phase); assertEquals(1, api.createCalls)
        assertEquals(0, api.closeCalls); assertEquals(0, api.payCalls)
    }
    @Test fun anUnclassifiedServerRejectionCannotEraseTheAttemptOrAllowAnotherCreate() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = HellobikeException("fixture rejection", rejected = true) }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model); model.startRide(); runCurrent(); model.startRide(); runCurrent()
        assertEquals(1, api.createCalls); assertEquals(HellobikePhase.Opening, store.load().sessions.single().phase)
        assertTrue(store.load().sessions.single().uncertain)
    }
    @Test fun createRejectionKeepsTheOfficialFailureAndDoesNotDiscoverAnotherOrderToOverrideIt() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = HellobikeException("你有一辆开锁中车辆", rejected = true) }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model)
        api.orderReading = HellobikeOrderReading(20, 22)
        api.beforeCreate = { api.reading = riding() }
        model.startRide(); runCurrent()
        assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
        assertTrue(model.state.value.session!!.uncertain); assertEquals("你有一辆开锁中车辆", model.state.value.error)
        assertTrue(model.state.value.confirmedSessions.isEmpty())
        model.startRide(); advanceTimeBy(1801); runCurrent()
        assertEquals("你有一辆开锁中车辆", model.state.value.error); assertEquals(1, api.createCalls)
        assertTrue(api.queriedRideGuids.isEmpty())
    }
    @Test fun restoringAnOpeningOrderQueriesTheOriginalEvenWhenRideCheckHasNoRide() = bikeTest {
        val pending = fixtureBikeSession.copy(phase = HellobikePhase.Opening, uncertain = true)
        val api = FakeHellobikeService().apply { orderReading = HellobikeOrderReading(20, 23) }
        val model = model(api, BikeRecords().store(sessions = listOf(pending)))
        ready(model); model.openSession(pending.sessionId); runCurrent()
        assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
        assertFalse(model.state.value.session!!.uncertain); assertNull(model.state.value.error)
        assertEquals(0, api.createCalls); assertEquals(0, api.prepareCalls)
        assertTrue(api.queriedRideGuids.isNotEmpty()); assertTrue(api.queriedRideGuids.all { it == pending.rideGuid })
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
    }
    @Test fun lostCreateResponseRemainsUnknownUntilAnExplicitStatusQuery() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = IOException("lost response") }
        val model = model(api, BikeRecords().store()); vehicle(model)
        api.orderReading = HellobikeOrderReading(20, 23)
        api.beforeCreate = { api.reading = riding() }
        model.startRide(); runCurrent()
        assertEquals(HellobikePhase.Opening, model.state.value.session?.phase); assertNotNull(model.state.value.error)
        assertTrue(api.queriedRideGuids.isEmpty())
        model.retryDeviceCheck(); runCurrent()
        assertNull(model.state.value.error); assertEquals(listOf(""), api.queriedRideGuids)
        model.startRide(); runCurrent(); assertEquals(1, api.createCalls)
    }
    @Test fun closeCommitsOnceAfterProofAndRideEndLeavesAnUnsettledProgressEntry() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding() }; val store = BikeRecords().store(sessions = listOf(fixtureBikeSession))
        val model = model(api, store); ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkClose(); runCurrent(); assertNotNull(model.state.value.closeProof)
        api.beforeClose = { assertEquals(HellobikePhase.Closing, store.load().sessions.single().phase) }
        model.commitClose(); model.commitClose(); runCurrent(); assertEquals(1, api.closeCalls)
        advanceTimeBy(1801); runCurrent()
        val ended = store.load().sessions.single()
        assertEquals(HellobikePhase.Ended, ended.phase); assertEquals(HellobikeSettlement.Pending, ended.settlement)
        assertFalse(ended.dismissible); assertFalse(ended.settled)
    }
    @Test fun anUnconfirmedCloseCannotReenableAnotherCloseEvenWhenServerStillSaysRiding() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding(); holdClose = true }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent(); model.checkClose(); runCurrent()
        model.commitClose(); runCurrent(); advanceTimeBy(40_001); runCurrent()
        assertEquals(HellobikePhase.Closing, store.load().sessions.single().phase); assertTrue(store.load().sessions.single().uncertain)
        val continuations = api.closeCalls
        assertTrue(continuations > 1)
        model.checkClose(); model.commitClose(); runCurrent(); assertEquals(continuations, api.closeCalls)
    }
    @Test fun theReturnedCurrentRideDrivesThePageAndTheCloudChecksItsReturn() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(rideGuid = "different-ride") }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(store.load().sessions.single().sessionId); runCurrent(); model.checkClose(); runCurrent()
        assertEquals("different-ride", store.load().sessions.single().rideGuid)
        assertNotNull(model.state.value.closeProof); assertEquals(1, api.preCloseCalls); assertFalse(model.state.value.officialRequired)
    }
    @Test fun locationAuthorizationResumesTheOriginalScanWithoutAnAutomaticCreate() = bikeTest {
        val api = FakeHellobikeService(); val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(api, BikeRecords().store(), location)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        val id = model.state.value.locationPermissionRequest!!
        assertTrue(model.claimLocationPermission(id)); assertFalse(model.claimLocationPermission(id))
        location.failure = null; model.locationPermissionReturned(id, true); runCurrent()
        assertNotNull(model.state.value.precheck); assertEquals(0, api.createCalls)
    }
    @Test fun locationPermissionResultFromAnOldScanDoesNotOverwriteTheNewVehicle() = bikeTest {
        for (nextBike in listOf(fixtureBikeSession.bikeNo, "0987654321")) {
            val api = FakeHellobikeService(); val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
            val model = model(api, BikeRecords().store(), location)
            ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
            val oldId = model.state.value.locationPermissionRequest!!
            assertTrue(model.claimLocationPermission(oldId))
            location.failure = null
            model.openDevice(nextBike); runCurrent()
            val checked = model.state.value.precheck
            assertNotNull(checked)
            assertEquals(nextBike, checked!!.bikeNo)
            model.locationPermissionReturned(oldId, false, permanentlyDenied = true); runCurrent()
            assertEquals(checked, model.state.value.precheck)
            assertNull("Old denial must not become the new vehicle error", model.state.value.error)
            assertFalse(model.state.value.locationPermissionRequired)
            assertNull(model.state.value.locationPermissionRequest)
            assertEquals(0, api.createCalls)
            model.environment(false)
        }
    }
    @Test fun cancelledRideReadCannotClearTheNextScannedVehicleCheck() = bikeTest {
        val oldRead = CompletableDeferred<Unit>()
        val newCheck = CompletableDeferred<Unit>()
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion,
                position: HellobikePosition): HellobikePrecheck {
                newCheck.await()
                return super.prepare(account, bikeNo, region, position)
            }
        }.apply { reading = riding() }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession)))
        ready(model)
        api.onRead = { withContext(NonCancellable) { oldRead.await(); riding() } }
        model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.openDevice("0987654321"); runCurrent()
        assertTrue(model.state.value.loadingDevice)
        oldRead.complete(Unit); runCurrent()
        assertTrue("A cancelled ride read must not finish the new vehicle check", model.state.value.loadingDevice)
        assertNull(model.state.value.precheck)
        newCheck.complete(Unit); runCurrent()
        assertFalse(model.state.value.loadingDevice)
        assertEquals("0987654321", model.state.value.precheck?.bikeNo)
        assertEquals(0, api.createCalls); assertEquals(0, api.closeCalls)
    }

    @Test fun rideEndingElsewhereClearsAnObsoleteLocationRequirement() = bikeTest {
        for (deniedBeforeReturn in listOf(false, true)) {
            val api = FakeHellobikeService().apply { reading = riding() }
            val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
            val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession)), location)
            ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
            model.checkClose(); runCurrent()
            val id = model.state.value.locationPermissionRequest!!
            assertTrue(model.claimLocationPermission(id))
            if (deniedBeforeReturn) model.locationPermissionReturned(id, false)
            api.reading = riding().copy(status = 2)
            model.refreshState(); runCurrent()
            if (!deniedBeforeReturn) model.locationPermissionReturned(id, false)
            assertEquals(HellobikePhase.Ended, model.state.value.session?.phase)
            assertFalse("A finished ride no longer requires return-location permission", model.state.value.locationPermissionRequired)
            assertNull(model.state.value.locationPermissionRequest)
            assertNull(model.state.value.error)
            assertEquals(0, api.closeCalls)
            model.environment(false)
        }
    }


    @Test fun deniedScanLocationRetriesAuthorizationAndSettingsReturnResumesTheSameBike() = bikeTest {
        val api = FakeHellobikeService()
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(api, BikeRecords().store(), location)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        val deniedId = model.state.value.locationPermissionRequest!!
        model.claimLocationPermission(deniedId); model.locationPermissionReturned(deniedId, false)
        assertTrue(model.state.value.locationPermissionRequired)
        assertFalse(model.state.value.locationPermissionBlocked)
        val locationCalls = location.calls
        model.retryDeviceCheck(); runCurrent()
        assertEquals(locationCalls, location.calls)
        val blockedId = model.state.value.locationPermissionRequest!!
        model.claimLocationPermission(blockedId); model.locationPermissionReturned(blockedId, false, permanentlyDenied = true)
        assertTrue(model.state.value.locationPermissionBlocked)
        model.retryDeviceCheck(); runCurrent()
        val settingsId = model.state.value.locationPermissionRequest!!
        model.claimLocationPermission(settingsId); model.locationSettingsOpening(settingsId)
        model.environment(false); model.pageVisible(false); runCurrent()
        assertEquals(settingsId, model.state.value.locationPermissionRequest)
        location.failure = null
        model.environment(true); model.pageVisible(true); runCurrent()
        model.locationPermissionReturned(settingsId, true); runCurrent()
        assertNotNull(model.state.value.precheck)
        assertFalse(model.state.value.locationPermissionRequired); assertFalse(model.state.value.locationPermissionBlocked)
        assertNull(model.state.value.error); assertEquals(0, api.createCalls)
    }

    @Test fun smsSliderKeepsTheChallengeAndLoginContinuesScanningWithoutOpening() = bikeTest {
        val api = FakeHellobikeService().apply { sms = HellobikeSmsResult(true, "challenge") }
        val location = FakeBikeLocation()
        val model = model(api, BikeRecords().store(account = null, region = null), location)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); model.connectAccount(clearPending = false)
        model.phoneChanged(fixtureBikeAccount.phone); model.sendCode(); runCurrent()
        assertNotNull(model.state.value.slider); assertTrue(model.state.value.sliderVerification); assertEquals(1, api.sliderCalls)
        assertEquals(0, location.calls); assertNull(model.state.value.locationPermissionRequest)
        api.sms = HellobikeSmsResult(); model.sliderOffsetChanged(80); model.sendCode(submitSlider = true); runCurrent()
        assertEquals(listOf(null, 80), api.smsOffsets); assertEquals("challenge", api.smsChallenges.last())
        assertFalse(model.state.value.sliderVerification); assertEquals(60, model.state.value.codeSeconds)
        model.codeChanged("123456"); model.login(); runCurrent()
        assertFalse(model.state.value.loginVisible); assertTrue(model.state.value.connected)
        assertNotNull(model.state.value.precheck); assertEquals(0, api.createCalls)
        assertTrue(api.authRegions.all { it == null })
        assertTrue(api.authClientIds.isNotEmpty()); assertTrue(api.authClientIds.all { it.isEmpty() })
    }
    @Test fun sliderLoadFailureStaysInVerificationAndCancelRejectsALateImage() = bikeTest {
        val api = FakeHellobikeService().apply {
            sms = HellobikeSmsResult(true, "challenge")
            sliderFailure = HellobikeException("图片暂时无法加载")
        }
        val model = model(api, BikeRecords().store(account = null, region = null))
        ready(model); model.connectAccount(); model.phoneChanged(fixtureBikeAccount.phone); model.sendCode(); runCurrent()
        assertTrue(model.state.value.sliderVerification); assertNotNull(model.state.value.loginError)
        model.sendCode(); model.codeChanged("123456"); model.login(); runCurrent()
        assertEquals(1, api.smsCalls); assertEquals(0, api.loginCalls)
        api.sliderFailure = null; api.sliderGate = CompletableDeferred()
        model.refreshSlider(); runCurrent(); assertTrue(model.state.value.sliderLoading)
        model.cancelSlider(); api.sliderGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.sliderVerification); assertNull(model.state.value.slider)
        assertFalse(model.state.value.sliderLoading); assertEquals(fixtureBikeAccount.phone, model.state.value.phone)
    }
    @Test fun restoredRideWithoutRegionNeedsNoLocationUntilUserReturnsTheBike() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding() }
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val store = BikeRecords().store(region = null)
        val model = model(api, store, location); ready(model)
        val recovered = store.load().sessions.single()
        assertNull(recovered.region); assertEquals(fixtureBikeSession.rideGuid, recovered.rideGuid)
        assertEquals(0, location.calls); assertNull(model.state.value.locationPermissionRequest)
        models.clear()
        val restarted = model(api, store, location); ready(restarted)
        restarted.openSession(recovered.sessionId); runCurrent()
        assertEquals(HellobikePhase.Riding, restarted.state.value.session?.phase)
        assertEquals(0, location.calls)
        restarted.checkClose(); runCurrent()
        val request = restarted.state.value.locationPermissionRequest!!
        location.failure = null; restarted.locationPermissionReturned(request, true); runCurrent()
        assertNotNull(restarted.state.value.closeProof)
        assertEquals(fixtureBikeRegion, store.load().sessions.single().region)
        assertEquals(0, api.smsCalls); assertEquals(0, api.createCalls); assertEquals(0, api.closeCalls); assertEquals(0, api.payCalls)
    }
    @Test fun logoutWaitsForCancelledReadsAndCannotPublishAnotherOwnersLateRide() = bikeTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeHellobikeService().apply { onRead = { withContext(NonCancellable) { gate.await(); riding().copy(userGuid = "different-owner") } } }
        val store = BikeRecords().store()
        val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent(); assertTrue(model.removeAccount()); runCurrent()
        assertTrue(model.state.value.accountBusy)
        gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.connected); assertFalse(model.state.value.accountBusy); assertTrue(model.state.value.sessions.isEmpty())
        assertNull(model.state.value.region); assertFalse(model.state.value.recoveryRequired)
        assertEquals(HellobikeStoredState(), store.load())
        assertEquals(0, api.createCalls)
    }
    @Test fun cancellationDuringLoginCommitKeepsMemoryAndDiskOnTheSameAccount() = bikeTest {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession))
        val api = object : FakeHellobikeService() {
            override suspend fun login(phone: String, code: String, region: HellobikeRegion?, clientId: String, loginGuid: String) =
                fixtureBikeAccount.copy(phone = phone, clientId = clientId, userGuid = "next-owner")
        }
        val model = model(api, store); ready(model); model.connectAccount(); model.phoneChanged("13100000000")
        model.codeChanged("123456"); records.onWrite = { model.dismissLogin() }
        model.login(); runCurrent(); records.onWrite = null
        assertEquals("next-owner", store.load().account?.userGuid)
        assertEquals(store.load().account?.maskedPhone, model.state.value.connectedPhone)
        assertTrue(model.state.value.sessions.isEmpty()); assertFalse(model.state.value.accountBusy)
        assertEquals(listOf(fixtureBikeSession), store.load().retainedSessions[fixtureBikeAccount.userGuid])
        assertEquals(0, api.createCalls)
    }
    @Test fun restartQueriesThePersistedPaymentOnceAndNeverLaunchesOrSubmitsItAgain() = bikeTest {
        val attempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "001", 1_000_000)
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying, paymentAttempt = attempt)
        val store = BikeRecords().store(sessions = listOf(session)); val api = FakeHellobikeService(); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        assertEquals(1, api.queryCalls); assertEquals(setOf(fixtureBikeOrder.guid), api.queriedGuids.toSet())
        assertEquals(0, api.payCalls); assertNull(model.state.value.paymentLaunch); assertFalse(store.load().sessions.single().settled)
    }
    @Test fun anSdkSuccessCallbackStartsQueriesAndCannotMarkTheRidePaidOrRetryPayment() = bikeTest {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val api = FakeHellobikeService(); val model = model(api, store); ended(model)
        api.beforePay = { assertNotNull(store.load().sessions.single().paymentAttempt) }
        model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id)); assertFalse(model.claimPayment(launch.id))
        model.paymentReturned(launch.id, "9000"); runCurrent()
        assertFalse(model.state.value.session!!.settled); assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        advanceTimeBy(9001); runCurrent(); model.pay("001"); runCurrent()
        assertEquals(RechargePhase.TimedOut, model.state.value.paymentFeedback?.phase); assertEquals(4, api.queryCalls); assertEquals(1, api.payCalls)
    }
    @Test fun backendChannelWinsAndStaleCallbacksCannotClaimAnotherPayment() = bikeTest {
        val api = FakeHellobikeService().apply { responseChannel = "1201" }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("001"); runCurrent()
        val launch = model.state.value.paymentLaunch!!
        assertEquals("52", launch.method); assertEquals("1201", model.state.value.session?.paymentAttempt?.channel)
        model.paymentReturned(launch.id + 1, "success"); assertTrue(model.state.value.paymentInFlight)
        assertTrue(model.claimPayment(launch.id)); model.paymentReturned(launch.id, "cancel"); runCurrent()
        assertFalse(model.state.value.paymentInFlight); assertFalse(model.state.value.session!!.settled)
    }
    @Test fun balanceProcessingNeedsAMatchedQueryAndThenRefreshesRideSettlement() = bikeTest {
        val api = FakeHellobikeService()
        api.queryPaid = { api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Paid); true }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("003"); runCurrent()
        assertEquals(1, api.payCalls); assertEquals(1, api.queryCalls); assertNull(model.state.value.paymentLaunch)
        assertTrue(model.state.value.session!!.settled); assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
    }
    @Test fun successfulPaymentQuerySurvivesDetailFailureButKeepsRideFeesUnconfirmed() = bikeTest {
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying,
            paymentAttempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "003", 1_000_000))
        val api = FakeHellobikeService().apply { detailFailure = IOException("fixture unavailable"); queryPaid = { true } }
        val store = BikeRecords().store(sessions = listOf(session)); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        val saved = store.load().sessions.single()
        assertEquals(1, api.queryCalls); assertEquals(setOf(fixtureBikeOrder.guid), saved.paymentAttempt?.confirmedGuids)
        assertFalse(saved.settled); assertEquals(HellobikeSettlement.Unknown, saved.settlement); assertEquals(0, api.payCalls)
    }
    @Test fun failedCurrentPaymentQueryCannotReuseAPersistedSuccessFlag() = bikeTest {
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying,
            paymentAttempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "003", 1_000_000,
                confirmedGuids = setOf(fixtureBikeOrder.guid)))
        val api = FakeHellobikeService().apply {
            detailFailure = IOException("fixture unavailable")
            queryPaid = { throw IOException("fixture query unavailable") }
        }
        val store = BikeRecords().store(sessions = listOf(session)); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        assertEquals(1, api.queryCalls); assertTrue(store.load().sessions.single().paymentAttempt!!.confirmedGuids.isEmpty())
        assertFalse(store.load().sessions.single().settled)
        assertEquals(HellobikeSettlement.Paying, store.load().sessions.single().settlement); assertEquals(0, api.payCalls)
    }
    @Test fun previewDoesNotReadSavedCredentialsOrStartLocationAndBusinessRequests() = bikeTest {
        var reads = 0
        var regionWrites = 0
        val backing = BikeRecords().store()
        val store = object : HellobikeStore by backing {
            override fun load(): HellobikeStoredState { reads++; return backing.load() }
            override fun saveRegion(region: HellobikeRegion) { regionWrites++; backing.saveRegion(region) }
        }
        val api = FakeHellobikeService(); val location = FakeBikeLocation()
        val model = HellobikeViewModel(api, store, location, dispatcher, analytics = AnalyticsFixtures.from(ApplicationProvider.getApplicationContext()))
            .also { models.put("preview", it) }
        runCurrent()
        assertEquals(0, reads)
        assertTrue(model.state.value.restoring)
        model.initialize(preview = true)
        model.initialize(preview = false) // Recomposition cannot turn a preview model into a live account model.
        runCurrent(); model.environment(true); model.openDevice(fixtureBikeSession.bikeNo); model.connectAccount(); runCurrent()
        assertEquals(0, reads); assertEquals(0, api.readCalls); assertEquals(0, location.calls)
        assertEquals(0, regionWrites); assertEquals(fixtureBikeRegion, backing.load().region)
        assertFalse(model.state.value.restoring); assertFalse(model.state.value.loginVisible); assertFalse(model.removeAccount())
    }
}
