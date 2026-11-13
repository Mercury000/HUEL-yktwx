package com.mercury.yktwx

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
    private fun model(api: FakeHellobikeService, store: HellobikeStore, location: FakeBikeLocation = FakeBikeLocation()): HellobikeViewModel {
        api.elapsed = { 50_000 + dispatcher.scheduler.currentTime }
        return HellobikeViewModel(api, store, location, dispatcher,
            { 1_000_000 + dispatcher.scheduler.currentTime }, api.elapsed,
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

    @Test fun startCommitsItsRecordBeforeOneCreateAndRequiresAQueriedRideToShowRunning() = bikeTest {
        val records = BikeRecords(); val store = records.store(); val api = FakeHellobikeService(); val model = model(api, store)
        vehicle(model)
        api.beforeCreate = { assertEquals(HellobikePhase.Opening, store.load().sessions.single().phase) }
        model.startRide(); model.startRide(); runCurrent()
        assertEquals(1, api.createCalls); assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
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
    @Test fun expiredPrecheckDoesNotOpenOrRefreshByItself() = bikeTest {
        val api = FakeHellobikeService(); val model = model(api, BikeRecords().store())
        vehicle(model); advanceTimeBy(60_001); runCurrent()
        model.startRide(); runCurrent()
        assertEquals(0, api.createCalls); assertNull(model.state.value.precheck); assertEquals(1, api.prepareCalls)
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
    @Test fun acceptedOpeningOrderContinuesAfterCreateRejectionWithoutErrorOrResubmission() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = HellobikeException("你有一辆开锁中车辆", rejected = true) }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model)
        api.orderReading = HellobikeOrderReading(20, 22)
        model.startRide(); runCurrent()
        assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
        assertFalse(model.state.value.session!!.uncertain); assertNull(model.state.value.error)
        assertTrue(model.state.value.confirmedSessions.isEmpty())
        model.startRide(); advanceTimeBy(1801); runCurrent()
        assertNull(model.state.value.error); assertEquals(1, api.createCalls)
        assertTrue(api.queriedRideGuids.all { it == fixtureBikeSession.rideGuid })
        api.orderReading = HellobikeOrderReading(20, 0)
        advanceTimeBy(1801); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertNull(model.state.value.error); assertEquals(1, api.createCalls)
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
    @Test fun lostCreateResponseUsesExactOrderProgressAndNeverSendsASecondCreate() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = IOException("lost response") }
        val model = model(api, BikeRecords().store()); vehicle(model)
        api.orderReading = HellobikeOrderReading(20, 23)
        model.startRide(); runCurrent()
        assertEquals(HellobikePhase.Opening, model.state.value.session?.phase); assertNull(model.state.value.error)
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
        model.commitClose(); runCurrent(); advanceTimeBy(21_601); runCurrent()
        assertEquals(HellobikePhase.Closing, store.load().sessions.single().phase); assertTrue(store.load().sessions.single().uncertain)
        model.checkClose(); model.commitClose(); runCurrent(); assertEquals(1, api.closeCalls)
    }
    @Test fun differentOrderResponsesNeverReplaceThePendingOrderOrAuthorizeClose() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(rideGuid = "different-ride") }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent(); model.checkClose(); runCurrent()
        assertEquals(fixtureBikeSession.rideGuid, store.load().sessions.single().rideGuid)
        assertNull(model.state.value.closeProof); assertEquals(0, api.closeCalls); assertTrue(model.state.value.officialRequired)
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
        val model = model(api, BikeRecords().store())
        runCurrent(); model.environment(true); runCurrent(); assertTrue(model.removeAccount()); runCurrent()
        assertTrue(model.state.value.accountBusy)
        gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.connected); assertFalse(model.state.value.accountBusy); assertTrue(model.state.value.sessions.isEmpty())
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
    @Test fun restartQueriesAPersistedPaymentFourTimesAndNeverLaunchesOrSubmitsItAgain() = bikeTest {
        val attempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "001", 1_000_000)
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying, paymentAttempt = attempt)
        val store = BikeRecords().store(sessions = listOf(session)); val api = FakeHellobikeService(); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent(); advanceTimeBy(9001); runCurrent()
        assertEquals(4, api.queryCalls); assertEquals(setOf(fixtureBikeOrder.guid), api.queriedGuids.toSet())
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
