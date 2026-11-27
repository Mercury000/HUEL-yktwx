package com.mercury.yktwx

import androidx.lifecycle.SavedStateHandle
import com.mercury.yktwx.ui.nav.*
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

    @Test fun noOrderNeverEntersTheRidingPageOrChangesTheAccountStatus() = bikeTest {
        var ridingCalls = 0
        val api = object : FakeHellobikeService() {
            override suspend fun ridingInfo(account: HellobikeAccount): HellobikeRideReading {
                ridingCalls++
                throw HellobikeException("无骑行中订单", rejected = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model)
        assertEquals(0, ridingCalls)
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        assertNull(model.accountConnection.value.error); assertNull(model.state.value.error)
        model.checkAccount(force = true); runCurrent()
        assertEquals(0, ridingCalls); assertNull(model.accountConnection.value.error)
        assertNotNull(store.load().account); assertTrue(model.state.value.sessions.isEmpty())
    }

    @Test fun lateRidingPageFailureCannotOverwriteACompletedExpiryCheck() = bikeTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : FakeHellobikeService() {
            override suspend fun ridingInfo(account: HellobikeAccount): HellobikeRideReading {
                gate.await()
                throw HellobikeException("无骑行中订单", rejected = true)
            }
        }.apply { reading = riding() }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model)
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        gate.complete(Unit); runCurrent()
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        assertNull(model.accountConnection.value.error); assertNotNull(store.load().account)
    }

    @Test fun accountProbeFailureDoesNotPublishRideOrNetworkErrorsAsAccountFailures() = bikeTest {
        var failed = true
        val api = object : FakeHellobikeService() {
            override suspend fun checkAccount(account: HellobikeAccount) {
                if (failed) throw IOException("probe unavailable")
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model)
        assertEquals(LifeAccountStatus.Unverified, model.accountConnection.value.status)
        assertNull(model.accountConnection.value.error); assertNotNull(store.load().account)
        failed = false
        model.checkAccount(force = true); runCurrent()
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        assertNull(model.accountConnection.value.error)
    }

    @Test fun explicitAccountExpiryStillClearsCredentialsAndCannotBecomeARecoverySuccess() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun checkAccount(account: HellobikeAccount) {
                throw HellobikeException("官方登录已过期", loginExpired = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model)
        assertEquals(LifeAccountStatus.Expired, model.accountConnection.value.status)
        assertEquals("官方登录已过期", model.accountConnection.value.error)
        assertNull(store.load().account); assertFalse(model.state.value.connected)
    }

    @Test fun officialSkipConfirmReturnSubmitsAfterTheReturnTapWithoutASecondConfirmation() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun preClose(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition,
                region: HellobikeRegion, poll: Boolean, commit: Boolean): HellobikeCloseReading =
                super.preClose(account, session, position, region, poll, commit).copy(skipConfirmation = !commit)
        }.apply { reading = riding() }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkClose(); runCurrent()
        assertEquals(1, api.preCloseCalls); assertEquals(1, api.closeCalls)
        assertNull(model.state.value.closeProof); assertEquals(HellobikePhase.Ended, store.load().sessions.single().phase)
    }
    @Test fun temporaryLockUsesTheConfirmedEligibilityWithoutExtraRideOrLocationChecks() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(temporaryLockSupported = true) }
        val location = FakeBikeLocation(); val store = BikeRecords().store(sessions = listOf(fixtureBikeSession))
        val model = model(api, store, location); ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        val reads = api.readCalls
        model.checkTemporaryLock(); runCurrent()
        assertEquals(reads, api.readCalls); assertEquals(1, api.temporaryVerifyCalls)
        val positions = location.calls
        location.failure = IOException("no new location after confirmation")
        model.commitTemporaryLock(); runCurrent()
        assertEquals(listOf(false), api.temporaryCommands); assertEquals(1, api.temporaryVerifyCalls)
        assertEquals(reads, api.readCalls); assertEquals(positions, location.calls)
    }
    @Test fun paymentRefreshesChangedBillBeforeRequiringAnotherConfirmation() = bikeTest {
        val api = FakeHellobikeService()
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended,
            settlement = HellobikeSettlement.Pending)))
        val model = model(api, store); ended(model)
        api.detail = fixtureBikeDetail.copy(amount = "3.00", orders = listOf(fixtureBikeOrder.copy(amount = "3.00")))
        model.pay("001"); runCurrent()
        assertEquals(0, api.payCalls); assertNull(model.state.value.paymentLaunch)
        assertEquals("3.00", model.state.value.payDetail?.amount)
        assertEquals("费用已更新，请确认后付款", model.state.value.paymentNotice)
        model.pay("001"); runCurrent()
        assertEquals(1, api.payCalls); assertNotNull(model.state.value.paymentLaunch)
    }

    @Test fun serverUnlockWithoutAnIdChecksOrderStatusBeforeEnteringTheOfficialRidingPage() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                reading = riding().copy(rideGuid = "actual-server-order")
                return super.prepare(account, bikeNo, region, position).copy(orderGuid = "", skipConfirmation = true, serverUnlockStarted = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        assertEquals("actual-server-order", model.state.value.session?.rideGuid)
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals("actual-server-order", store.load().sessions.single().rideGuid)
        assertNull(model.state.value.precheck); assertNull(model.state.value.error)
        assertEquals(0, api.createCalls); assertEquals(1, api.prepareCalls)
        assertEquals(listOf(""), api.queriedRideGuids)
    }
    @Test fun confirmedUnlockDisplaysTheCloudRidingPageWithoutInventingMissingFields() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                reading = HellobikeRideReading.Ride("", "", null)
                orderReading = HellobikeOrderReading(20, 0)
                return super.prepare(account, bikeNo, region, position).copy(orderGuid = "", skipConfirmation = true, serverUnlockStarted = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals("", store.load().sessions.single().rideGuid); assertNull(model.state.value.error)
        assertEquals(listOf(""), api.queriedRideGuids); assertEquals(0, api.createCalls)
    }
    @Test fun serverUnlockWaitsForTheOrderWithoutShowingAnErrorOrOfferingAnotherUnlock() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition) =
                super.prepare(account, bikeNo, region, position).copy(orderGuid = "", skipConfirmation = true, serverUnlockStarted = true)
        }
        val store = BikeRecords().store(); val model = model(api, store); ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        assertTrue(model.state.value.busy); assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
        assertEquals("", store.load().sessions.single().rideGuid)
        assertNull(model.state.value.error); assertNull(model.state.value.precheck)
        api.reading = riding(); api.orderReading = HellobikeOrderReading(20, 0); advanceTimeBy(1001); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase); assertEquals(0, api.createCalls)
        assertEquals(fixtureBikeSession.rideGuid, store.load().sessions.single().rideGuid)
    }
    @Test fun overDuringUnlockKeepsPollingWithoutReturningOrFetchingFees() = bikeTest {
        val api = FakeHellobikeService().apply { orderReading = HellobikeOrderReading(30, null, "历史订单已结束") }
        val store = BikeRecords().store(); val model = model(api, store)
        vehicle(model); model.startRide(); runCurrent()
        assertEquals(HellobikePhase.Opening, store.load().sessions.single().phase)
        assertNull(model.state.value.error)
        assertEquals(0, api.detailCalls)
        assertEquals(listOf(fixtureBikeSession.rideGuid), api.queriedRideGuids)
        api.orderReading = HellobikeOrderReading(20, 0)
        advanceTimeBy(1001); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(List(2) { fixtureBikeSession.rideGuid }, api.queriedRideGuids)
        assertEquals(1, api.createCalls); assertEquals(0, api.closeCalls); assertEquals(0, api.detailCalls)
    }
    @Test fun repeatedOverWithoutAnOrderIdTimesOutWithoutEndingTheRide() = bikeTest {
        val api = FakeHellobikeService().apply {
            createdRideGuid = ""
            orderReading = HellobikeOrderReading(30, null)
        }
        val model = model(api, BikeRecords().store())
        vehicle(model); model.startRide(); runCurrent()
        advanceTimeBy(35_001); runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals("车辆响应较慢，结果尚未确认，请刷新原订单", model.state.value.error)
        advanceTimeBy(6000); runCurrent()
        assertEquals(HellobikePhase.Opening, model.state.value.session?.phase)
        assertTrue(model.state.value.session!!.uncertain)
        assertTrue(api.queriedRideGuids.all(String::isBlank))
        assertEquals(1, api.createCalls); assertEquals(0, api.detailCalls)
    }
    @Test fun serverUnlockWithoutAnIdIgnoresAFinishedRideUntilActiveRideInfoArrives() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                orderReading = HellobikeOrderReading(20, 0)
                reading = riding().copy(rideGuid = "finished-order", status = 2)
                return super.prepare(account, bikeNo, region, position).copy(orderGuid = "", skipConfirmation = true, serverUnlockStarted = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        val waiting = store.load().sessions.single()
        assertTrue(waiting.unlockConfirmed); assertEquals(HellobikePhase.Opening, waiting.phase)
        assertEquals("", waiting.rideGuid); assertEquals(0, api.detailCalls)
        api.reading = riding()
        model.refreshState(); runCurrent()
        assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(fixtureBikeSession.rideGuid, model.state.value.session?.rideGuid)
        assertEquals(listOf(""), api.queriedRideGuids)
        assertEquals(1, api.prepareCalls); assertEquals(0, api.createCalls); assertEquals(0, api.detailCalls)
    }
    @Test fun ridingRefreshOnlyFinishesTheMatchingOrder() = bikeTest {
        val api = FakeHellobikeService()
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession))
        val model = model(api, store); ready(model)
        api.reading = riding().copy(rideGuid = "", status = 0)
        model.openSession(fixtureBikeSession.sessionId); runCurrent()
        assertEquals(fixtureBikeSession.rideGuid, model.state.value.session?.rideGuid)
        for (unrelatedGuid in listOf("", "finished-order")) {
            api.reading = riding().copy(rideGuid = unrelatedGuid, status = 2)
            model.refreshState(); runCurrent()
            assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
            assertEquals(0, api.detailCalls)
        }
        api.reading = riding().copy(status = 2)
        model.refreshState(); runCurrent()
        assertEquals(HellobikePhase.Ended, store.load().sessions.single().phase)
        assertEquals(1, api.detailCalls); assertEquals(0, api.closeCalls)
    }
    @Test fun temporaryLockVerifiesThenConfirmsAndResumesTheSameRide() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(temporaryLockSupported = true) }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkTemporaryLock(); runCurrent()
        assertEquals(600, model.state.value.temporaryLockProof?.canPauseSeconds)
        assertTrue(api.temporaryCommands.isEmpty()); assertFalse(store.load().sessions.single().temporarilyLocked)
        api.beforeTemporary = { assertNotNull(store.load().sessions.single().temporaryAction) }
        model.commitTemporaryLock(); model.commitTemporaryLock(); runCurrent()
        assertEquals(listOf(false), api.temporaryCommands)
        assertEquals(1, api.temporaryVerifyCalls)
        assertEquals(HellobikeTemporaryAction.Lock, store.load().sessions.single().temporaryAction)
        advanceTimeBy(3001); runCurrent()
        assertTrue(store.load().sessions.single().temporarilyLocked); assertNull(store.load().sessions.single().temporaryAction)
        assertEquals(fixtureBikeSession.startedAtMillis, store.load().sessions.single().startedAtMillis)
        model.resumeRide(); model.resumeRide(); runCurrent(); advanceTimeBy(3001); runCurrent()
        assertEquals(listOf(false, true), api.temporaryCommands)
        assertFalse(store.load().sessions.single().temporarilyLocked); assertEquals(HellobikePhase.Riding, model.state.value.session?.phase)
        assertEquals(0, api.createCalls); assertEquals(0, api.closeCalls)
        assertEquals(fixtureBikeSession.rideGuid, model.state.value.session?.rideGuid)
    }
    @Test fun lostTemporaryLockResponseOnlyQueriesAndRestoresTheAcceptedLock() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(temporaryLockSupported = true); temporaryFailure = IOException("response lost") }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkTemporaryLock(); runCurrent(); model.commitTemporaryLock(); runCurrent()
        assertTrue(store.load().sessions.single().uncertain)
        assertEquals(HellobikeTemporaryAction.Lock, store.load().sessions.single().temporaryAction)
        model.checkTemporaryLock(); model.resumeRide(); runCurrent(); assertEquals(listOf(false), api.temporaryCommands)
        api.reading = riding().copy(status = 1, temporaryLockSupported = true)
        model.refreshState(); runCurrent()
        assertTrue(model.state.value.session!!.temporarilyLocked); assertNull(model.state.value.session!!.temporaryAction)
        assertNull(model.state.value.error); assertEquals(listOf(false), api.temporaryCommands)
    }
    @Test fun temporaryLockEligibilityFailureNeverSubmitsALockAndPausedRestoreCanReturn() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(temporaryLockSupported = true)
            temporaryVerifyFailure = HellobikeException("禁停区内无法进入临时锁车", rejected = true) }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession)))
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkTemporaryLock(); runCurrent(); model.commitTemporaryLock(); runCurrent()
        assertNull(model.state.value.temporaryLockProof); assertTrue(api.temporaryCommands.isEmpty())
        api.reading = riding().copy(status = 1, temporaryLockSupported = true)
        model.refreshState(); runCurrent(); assertTrue(model.state.value.session!!.temporarilyLocked)
        model.checkClose(); runCurrent(); assertNotNull(model.state.value.closeProof)
        api.reading = riding().copy(temporaryLockSupported = true)
        model.refreshState(); runCurrent(); assertFalse(model.state.value.session!!.temporarilyLocked)
        assertEquals(fixtureBikeSession.startedAtMillis, model.state.value.session?.startedAtMillis)
    }
    @Test fun deniedTemporaryLockLocationRetriesEligibilityWithoutReturningTheBike() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding().copy(temporaryLockSupported = true) }
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession)), location)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkTemporaryLock(); runCurrent()
        val request = model.state.value.locationPermissionRequest!!
        model.locationPermissionReturned(request, granted = false, permanentlyDenied = true)
        assertTrue(api.temporaryCommands.isEmpty()); assertEquals(0, api.preCloseCalls)
        model.retryDeviceCheck(); runCurrent()
        location.failure = null
        model.locationPermissionReturned(model.state.value.locationPermissionRequest!!, granted = true)
        runCurrent()
        assertNotNull(model.state.value.temporaryLockProof)
        assertTrue(api.temporaryCommands.isEmpty()); assertEquals(0, api.preCloseCalls)
    }
    @Test fun temporaryLockTimeoutPreservesOneOperationAndQueriesStayInsideTheOfficialWindow() = bikeTest {
        val queryTimes = mutableListOf<Long>()
        val api = object : FakeHellobikeService() {
            override suspend fun temporaryLockState(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition,
                region: HellobikeRegion): Int {
                queryTimes += dispatcher.scheduler.currentTime
                throw IOException("temporary query interrupted")
            }
        }.apply { reading = riding().copy(temporaryLockSupported = true); holdTemporary = true }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession)); val model = model(api, store)
        ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkTemporaryLock(); runCurrent(); model.commitTemporaryLock(); runCurrent()
        advanceTimeBy(35_001); runCurrent()
        assertTrue(queryTimes.isNotEmpty()); assertTrue(queryTimes.all { it < 35_000 })
        assertTrue(store.load().sessions.single().uncertain)
        assertEquals(HellobikeTemporaryAction.Lock, store.load().sessions.single().temporaryAction)
        assertEquals(listOf(false), api.temporaryCommands)
        assertFalse(model.state.value.busy)
    }
    @Test fun aMissingIdDoesNotUseTheRidingPageAsAFallbackBeforeUnlockSuccess() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
                reading = riding().copy(bikeNo = "another-bike")
                orderReading = HellobikeOrderReading(null, null)
                return super.prepare(account, bikeNo, region, position).copy(orderGuid = "", skipConfirmation = true, serverUnlockStarted = true)
            }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        ready(model); model.openDevice(fixtureBikeSession.bikeNo); runCurrent()
        assertNull(model.state.value.error); assertEquals(listOf(""), api.queriedRideGuids)
        advanceTimeBy(35_001); runCurrent()
        assertEquals("", store.load().sessions.single().rideGuid)
        assertTrue(store.load().sessions.single().uncertain)
        assertTrue(api.queriedRideGuids.all(String::isBlank)); assertEquals(0, api.createCalls)
    }

    @Test fun endedOriginalOrderFinishesDespiteStaleRideDiscoveryAndDelayedBilling() = bikeTest {
        for (billFailure in listOf(false, true)) {
            val api = FakeHellobikeService().apply { reading = riding(); holdClose = true }
            val store = BikeRecords().store(sessions = listOf(fixtureBikeSession))
            val model = model(api, store); ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
            model.checkClose(); runCurrent(); model.commitClose(); runCurrent()
            api.endedOrder = true
            if (billFailure) api.detailFailure = IOException("费用暂未生成")
            advanceTimeBy(1801); runCurrent()
            assertEquals(HellobikePhase.Ended, store.load().sessions.single().phase)
            assertFalse(store.load().sessions.single().uncertain)
            assertFalse(model.state.value.officialRequired)
            if (billFailure) assertEquals("费用待确认", store.load().sessions.single().title) else assertNull(model.state.value.error)
            assertEquals(2, api.closeCalls)
            model.environment(false); model.environment(true); runCurrent()
            assertEquals("Resume cannot resurrect a returned ride", HellobikePhase.Ended, store.load().sessions.single().phase)
            model.environment(false); models.clear(); runCurrent()
        }
    }
    @Test fun explicitP10SuccessFinishesBeforeRideDiscoveryUpdates() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun preClose(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition,
                region: HellobikeRegion, poll: Boolean, commit: Boolean) =
                super.preClose(account, session, position, region, poll, commit).copy(completed = commit)
        }.apply { reading = riding(); holdClose = true }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession))
        val model = model(api, store); ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        model.checkClose(); runCurrent(); model.commitClose(); runCurrent()
        assertEquals(HellobikePhase.Ended, store.load().sessions.single().phase)
        assertNull(model.state.value.error); assertEquals(1, api.closeCalls)
    }
    @Test fun noActiveRideAloneNeverSettlesAnUnconfirmedOriginalOrder() = bikeTest {
        val api = FakeHellobikeService().apply { reading = HellobikeRideReading.NoRide; endedOrder = false }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Closing)))
        val model = model(api, store); ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        assertEquals(HellobikePhase.Closing, store.load().sessions.single().phase)
        assertEquals(0, api.closeCalls); assertFalse(model.state.value.officialRequired)
    }

    @Test fun campusLogoutWaitsForOldRequestAndErasesItsRecordWithoutOpeningLoginDuringPurchase() = bikeTest {
        val gate = CompletableDeferred<Unit>()
        val purchase = HellobikeCardPurchase("校园卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", fixtureBikeRegion)
        val product = HellobikeCampusProduct("product", "校园卡", "6.90", "官方说明", com.mercury.yktwx.data.jsonObjectOf("businessType" to 1))
        val api = object : FakeHellobikeService() {
            override suspend fun studentAudit(account: HellobikeAccount) = HellobikeStudentAudit(2, 1)
            override suspend fun userCards(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?, businessType: Int, page: Int) = HellobikeUserCardPage(emptyList(), false)
            override suspend fun campusProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?) = HellobikeCampusProducts("校园卡", listOf(product))
            override suspend fun preOrderCard(account: HellobikeAccount, product: HellobikeCampusProduct, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCardPurchase = withContext(NonCancellable) { gate.await(); purchase }
        }
        val store = BikeRecords().store(); val model = model(api, store)
        runCurrent(); model.environment(true); model.cards.pageVisible(true); runCurrent()
        model.cards.choose("product"); runCurrent()
        assertTrue(model.cards.state.value.busy); assertFalse(model.manageLogin(true))
        assertTrue(model.removeAccount()); runCurrent()
        gate.complete(Unit); runCurrent()
        assertNull(store.load().account); assertTrue(store.load().cardPurchases.isEmpty())
        assertNull(model.cards.state.value.purchase); assertFalse(model.cards.state.value.busy)
        assertEquals(0, api.payCalls)
    }
    @Test fun expiredCampusAuditClearsAccountButPreservesOriginalPurchaseForRelogin() = bikeTest {
        val purchase = HellobikeCardPurchase("校园卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", fixtureBikeRegion)
        val store = BikeRecords().store().also { it.saveCardPurchase(fixtureBikeAccount.userGuid, purchase) }
        val api = object : FakeHellobikeService() {
            override suspend fun studentAudit(account: HellobikeAccount): HellobikeStudentAudit = throw HellobikeException("官方登录已过期", loginExpired = true)
        }
        val model = model(api, store); runCurrent(); model.environment(true); model.cards.pageVisible(true); runCurrent()
        assertNull(store.load().account); assertFalse(model.state.value.connected)
        assertEquals(purchase, store.load().cardPurchases[fixtureBikeAccount.userGuid])
        assertEquals(LifeAccountStatus.Expired, model.accountConnection.value.status)
        assertNull(model.cards.state.value.purchase)
    }

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

    @Test fun recreatedLoginRestoresOnlyPhoneAndRejectsAnotherCampusOwner() = bikeTest {
        val handle = SavedStateHandle()
        val records = BikeRecords()
        val original = model(FakeHellobikeService(), records.store(), savedState = handle)
        runCurrent(); original.bindPresentation("campus", false, 1)
        original.manageLogin(); original.phoneChanged("12345678901"); original.codeChanged("123456")
        val restored = handle.recreated(); models.clear()
        val api = FakeHellobikeService()
        val next = model(api, records.store(), savedState = restored)
        next.bindPresentation("campus", false, 8)
        assertFalse(next.state.value.loginVisible)
        runCurrent(); next.bindPresentation("campus", false, 8)
        assertTrue(next.state.value.loginVisible)
        assertEquals("12345678901", next.state.value.phone)
        assertEquals("", next.state.value.code)
        assertNull(next.state.value.slider)
        assertEquals(0, api.smsCalls); assertEquals(0, api.payCalls); assertEquals(0, api.createCalls)
        next.bindPresentation("another-campus", false, 9)
        assertFalse(next.state.value.loginVisible)
    }
    @Test fun recreatedPaymentRestoresOriginalRideAndMethodWithoutSubmitting() = bikeTest {
        val handle = SavedStateHandle()
        val records = BikeRecords()
        val original = model(FakeHellobikeService(), records.store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))),
            savedState = handle)
        ended(original); original.bindPresentation("campus", false, 1)
        original.selectPaymentMethod("1201")
        val restored = handle.recreated(); models.clear()
        val api = FakeHellobikeService()
        val next = model(api, HellobikeAccountStore(records), savedState = restored)
        ready(next); next.bindPresentation("campus", false, 10)
        assertTrue(next.state.value.paymentRestorePending)
        assertFalse(next.state.value.paymentVisible)
        next.openSession(fixtureBikeSession.sessionId); runCurrent()
        next.resumePresentation(true, false, true)
        assertFalse(next.state.value.paymentVisible)
        next.resumePresentation(true, true, true); runCurrent()
        assertTrue(next.state.value.paymentVisible)
        assertEquals("1201", next.state.value.paymentMethod)
        assertEquals(fixtureBikeSession.sessionId, next.state.value.selectedSessionId)
        assertEquals(0, api.payCalls); assertEquals(0, api.createCalls); assertEquals(0, api.smsCalls)
        assertNull(next.state.value.paymentLaunch)
        next.bindPresentation("campus", false, 11)
        assertFalse(next.state.value.paymentRestorePending)
        assertFalse(next.state.value.paymentVisible)
    }

    @Test fun recreatedSliderGetsFreshImageWithoutSendingSmsAndDismissCancelsPendingImage() = bikeTest {
        val handle = SavedStateHandle()
        val records = BikeRecords()
        val firstApi = FakeHellobikeService().apply { sms = HellobikeSmsResult(true, "old-challenge") }
        val first = model(firstApi, records.store(account = null), savedState = handle)
        ready(first); first.bindPresentation("campus", false, 1)
        first.manageLogin(); first.phoneChanged(fixtureBikeAccount.phone); first.sendCode(); runCurrent()
        assertTrue(first.state.value.sliderVerification)
        val restored = handle.recreated(); val cancelledHandle = handle.recreated(); models.clear()
        val api = FakeHellobikeService()
        val next = model(api, HellobikeAccountStore(records), savedState = restored)
        ready(next); next.bindPresentation("campus", false, 10)
        assertNull(next.state.value.slider)
        next.resumePresentation(true, false, false); runCurrent()
        assertEquals(0, api.sliderCalls)
        next.resumePresentation(true, true, false); runCurrent()
        assertEquals(1, api.sliderCalls); assertNotNull(next.state.value.slider)
        assertEquals(0, api.smsCalls); assertEquals(0, api.createCalls); assertEquals(0, api.payCalls)
        val cancelApi = FakeHellobikeService()
        val cancelled = model(cancelApi, HellobikeAccountStore(records), savedState = cancelledHandle)
        ready(cancelled); cancelled.bindPresentation("campus", false, 30); cancelled.dismissLogin()
        cancelled.resumePresentation(true, true, false); runCurrent()
        assertEquals(0, cancelApi.sliderCalls); assertFalse(cancelled.state.value.loginVisible)
    }

    @Test fun restoredRouteWaitsForAccountAndSelectsTheSavedRideWithoutCommands() = bikeTest {
        val api = FakeHellobikeService().apply { reading = riding() }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession))
        val original = model(api, store); runCurrent()
        val handle = SavedStateHandle(); val nav = AppNavigationViewModel(handle)
        nav.bind("campus", false, 1)
        nav.showLife(AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride, sessionId = fixtureBikeSession.sessionId,
            account = original.restorationOwner), LifeEntryOrigin.User)
        val restoredHandle = handle.recreated(); models.clear()
        val next = model(api, store)
        val restoredNav = AppNavigationViewModel(restoredHandle).apply { bind("campus", false, 1) }
        val route = restoredNav.backStack.last() as AppRoute.LifeDevice
        assertEquals(LifeRouteRestore.WaitingForAccount, restoreLifeRoute(route, false, next))
        runCurrent()
        assertEquals(LifeRouteRestore.Restored, restoreLifeRoute(route, false, next))
        assertEquals(fixtureBikeSession.sessionId, next.state.value.selectedSessionId)
        assertEquals(LifeRouteRestore.AccountChanged, restoreLifeRoute(route.copy(account = "another-account"), true, next))
        assertEquals(LifeRouteRestore.Missing, restoreLifeRoute(route.copy(sessionId = "missing-session"), true, next))
        val deviceRoute = route.copy(sessionId = null, deviceId = fixtureBikeSession.bikeNo)
        assertEquals(LifeRouteRestore.WaitingForNetwork, restoreLifeRoute(deviceRoute, false, next))
        assertEquals(0, api.smsCalls); assertEquals(0, api.createCalls); assertEquals(0, api.closeCalls); assertEquals(0, api.payCalls)
    }
    @Test fun busyRideCommandDefersSessionRestoreUntilItFinishes() = bikeTest {
        val api = FakeHellobikeService().apply { createdRideGuid = "new-ride-guid" }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession)))
        vehicle(model)
        api.createGate = CompletableDeferred()
        model.startRide()
        assertTrue(model.state.value.busy)
        val route = AppRoute.LifeDevice(LifeProvider.Hellobike, LifeFeature.Ride,
            sessionId = fixtureBikeSession.sessionId, account = model.restorationOwner)
        assertEquals(LifeRouteRestore.WaitingForIdle, restoreLifeRoute(route, true, model))
        assertEquals(0, api.closeCalls)
        api.createGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.busy)
        assertEquals(LifeRouteRestore.Restored, restoreLifeRoute(route, true, model))
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
    @Test fun ordinaryCardPermissionRetryCannotReplaceAnOutstandingRequest() = bikeTest {
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(FakeHellobikeService(), BikeRecords().store(), location)
        runCurrent(); model.environment(true); model.cards.pageVisible(true); runCurrent()
        model.cards.expandOrdinary(); runCurrent(); model.cards.requestOrdinaryLocation()
        val id = model.state.value.locationPermissionRequest!!
        assertTrue(model.claimLocationPermission(id))
        model.cards.requestOrdinaryLocation()
        assertEquals(id, model.state.value.locationPermissionRequest)
        model.locationPermissionReturned(id, false); runCurrent()
        assertNull(model.state.value.locationPermissionRequest)
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
    @Test fun accountCardLocationAuthorizationRefreshesCardsWithoutOpeningTheRidePage() = bikeTest {
        val requests = mutableListOf<HellobikePosition?>()
        val api = object : FakeHellobikeService() {
            override suspend fun ordinaryProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): List<HellobikeCampusProduct> {
                requests += position
                return emptyList()
            }
        }
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(api, BikeRecords().store(), location)
        runCurrent(); model.environment(true); model.cards.pageVisible(true); runCurrent()
        model.cards.expandOrdinary(); runCurrent(); assertEquals(listOf<HellobikePosition?>(null), requests)
        model.cards.requestOrdinaryLocation(); runCurrent()
        val id = model.state.value.locationPermissionRequest!!
        model.pageVisible(false)
        assertTrue(model.claimLocationPermission(id)); assertFalse(model.claimLocationPermission(id))
        location.failure = null; model.locationPermissionReturned(id, true); runCurrent()
        assertEquals(listOf(null, fixtureBikePosition), requests)
        assertNull(model.cards.state.value.ordinaryLocationError)
        assertNull(model.state.value.locationPermissionRequest); assertEquals(0, api.createCalls)
    }
    @Test fun denyingAccountCardLocationAuthorizationKeepsTheCardNotice() = bikeTest {
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(FakeHellobikeService(), BikeRecords().store(), location)
        runCurrent(); model.environment(true); model.cards.pageVisible(true); runCurrent()
        model.cards.expandOrdinary(); runCurrent(); model.cards.requestOrdinaryLocation()
        val id = model.state.value.locationPermissionRequest!!
        model.claimLocationPermission(id); model.locationPermissionReturned(id, false); runCurrent()
        assertTrue(model.cards.state.value.ordinaryLocationPermissionRequired)
        assertNotNull(model.cards.state.value.ordinaryLocationError)
        assertNull(model.state.value.error); assertNull(model.state.value.locationPermissionRequest)
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
    @Test fun cardSettingsReturnWithoutGrantKeepsPermissionRecoveryAvailable() = bikeTest {
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        val model = model(FakeHellobikeService(), BikeRecords().store(), location)
        runCurrent(); model.environment(true); model.cards.pageVisible(true); runCurrent()
        model.cards.expandOrdinary(); runCurrent(); model.cards.requestOrdinaryLocation()
        val id = model.state.value.locationPermissionRequest!!
        model.claimLocationPermission(id); model.locationSettingsOpening(id)
        model.environment(false); model.environment(true); runCurrent()
        model.locationPermissionReturned(id, false, permanentlyDenied = true); runCurrent()
        assertTrue(model.state.value.locationPermissionBlocked)
        assertTrue(model.cards.state.value.ordinaryLocationPermissionRequired)
        assertNotNull(model.cards.state.value.ordinaryLocationError)
        model.cards.requestOrdinaryLocation(); assertNotNull(model.state.value.locationPermissionRequest)
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
    @Test fun billGenerationIsCheckedAutomaticallyAndTheDrawerReusesTheTradeIdOnResume() = bikeTest {
        var tradeStatus = HellobikeTradeStatus.Preparing
        val tradeIds = mutableListOf<String>()
        val detailTradeIds = mutableListOf<String>()
        val api = object : FakeHellobikeService() {
            override suspend fun paymentTradeStatus(account: HellobikeAccount, session: HellobikeSession): HellobikeTradeStatus {
                tradeIds += session.tradeId
                return tradeStatus
            }
            override suspend fun payDetail(account: HellobikeAccount, session: HellobikeSession): HellobikePayDetail {
                detailTradeIds += session.tradeId
                return super.payDetail(account, session)
            }
        }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val model = model(api, store); ready(model); model.openSession(fixtureBikeSession.sessionId); runCurrent()
        assertEquals(0, api.detailCalls)
        assertFalse(model.state.value.canShowPayment)
        assertFalse(model.state.value.paymentVisible)
        tradeStatus = HellobikeTradeStatus.Ready
        advanceTimeBy(5999); runCurrent(); assertEquals(0, api.detailCalls)
        advanceTimeBy(1); runCurrent(); assertEquals(1, api.detailCalls)
        assertTrue(model.state.value.canShowPayment)
        model.showPayment(); runCurrent()
        assertEquals(api.channels, model.state.value.payOptions)
        assertEquals(fixtureBikeDetail.tradeId, store.load().sessions.single().tradeId)
        assertEquals(0, api.payCalls)

        val tradeChecks = tradeIds.size
        model.environment(false); runCurrent()
        api.detail = fixtureBikeDetail.copy(amount = "3.00", orders = listOf(fixtureBikeOrder.copy(amount = "3.00")))
        model.environment(true); runCurrent()
        assertEquals(tradeChecks, tradeIds.size)
        assertEquals(fixtureBikeDetail.tradeId, detailTradeIds.last())
        assertEquals("3.00", model.state.value.payDetail?.amount)
        assertEquals(api.channels, model.state.value.payOptions); assertEquals(0, api.payCalls)
    }
    @Test fun paymentDrawerDiscardsStaleQuotesWhenTheRefreshedBillIsNoLongerPayable() = bikeTest {
        val api = FakeHellobikeService()
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model)
        model.environment(false); runCurrent()
        api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Unknown, orders = emptyList())
        val details = api.detailCalls
        model.environment(true); runCurrent(); model.pay("001"); runCurrent()
        assertEquals(details + 1, api.detailCalls); assertEquals(0, api.payCalls)
        assertTrue(model.state.value.payOptions.isEmpty())
        assertEquals(HellobikeSettlement.Unknown, model.state.value.session?.settlement)
    }
    @Test fun cancelledPaymentCanReopenFromThePendingRideWithoutRegeneratingItsBill() = bikeTest {
        var tradeChecks = 0
        var generationAvailable = true
        val api = object : FakeHellobikeService() {
            override suspend fun paymentTradeStatus(account: HellobikeAccount, session: HellobikeSession): HellobikeTradeStatus {
                tradeChecks++
                if (!generationAvailable) throw IOException("fixture generation status unavailable")
                return HellobikeTradeStatus.Ready
            }
        }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val model = model(api, store); ended(model); model.pay("001"); runCurrent()
        val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id))
        model.dismissPayment(); model.pageVisible(false); model.environment(false); runCurrent()
        model.paymentReturned(launch.id, "6001"); runCurrent()
        generationAvailable = false
        val previousTradeChecks = tradeChecks
        model.environment(true); runCurrent()
        val pending = store.load().sessions.single()
        assertEquals(HellobikeSettlement.Pending, pending.settlement); assertNull(pending.paymentAttempt)
        assertTrue(model.openSession(pending.sessionId)); model.pageVisible(true); runCurrent()
        model.showPayment(); runCurrent()
        assertEquals(previousTradeChecks, tradeChecks)
        assertEquals(api.channels, model.state.value.payOptions); assertNull(model.state.value.paymentFeedback)
        assertNull(model.state.value.error); assertEquals(1, api.payCalls)
        model.pay("001"); runCurrent()
        assertEquals(2, api.payCalls)
    }
    @Test fun restartQueriesThePersistedPaymentOnceAndNeverLaunchesOrSubmitsItAgain() = bikeTest {
        val attempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "001", 1_000_000)
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying, paymentAttempt = attempt)
        val store = BikeRecords().store(sessions = listOf(session)); val api = FakeHellobikeService(); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        assertEquals(1, api.queryCalls); assertEquals(setOf(fixtureBikeOrder.guid), api.queriedGuids.toSet())
        assertEquals(0, api.payCalls); assertNull(model.state.value.paymentLaunch); assertFalse(store.load().sessions.single().settled)
    }
    @Test fun anSdkSuccessCallbackAloneCannotMarkTheRidePaidOrRetryPayment() = bikeTest {
        val records = BikeRecords(); val store = records.store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val api = FakeHellobikeService(); val model = model(api, store); ended(model)
        api.beforePay = { assertNotNull(store.load().sessions.single().paymentAttempt) }
        model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id)); assertFalse(model.claimPayment(launch.id))
        api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Unknown, orders = emptyList())
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
    @Test fun explicitPaymentExitsPreserveTheBillAndCancellationRequiresExplicitContinuation() = bikeTest {
        for (status in listOf("6001", "launch_failed", "4000")) {
            val api = FakeHellobikeService()
            val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
            val model = model(api, store); ended(model)
            model.pay("001"); runCurrent()
            val launch = model.state.value.paymentLaunch!!
            assertTrue(model.claimPayment(launch.id))
            model.environment(false); model.paymentReturned(launch.id, status); runCurrent()
            assertNotNull(store.load().sessions.single().paymentAttempt?.retryableExit)
            assertEquals(0, api.queryCalls)
            model.environment(true); runCurrent()
            assertEquals(HellobikeSettlement.Pending, store.load().sessions.single().settlement)
            assertNull(store.load().sessions.single().paymentAttempt)
            assertTrue(model.state.value.paymentVisible)
            if (status == "6001") {
                assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
                assertTrue(model.state.value.paymentFeedback?.cancelled == true)
                model.pay("001"); runCurrent(); assertEquals(1, api.payCalls)
                model.continueCancelledPayment(); runCurrent(); assertEquals(1, api.payCalls)
            }
            assertNull(model.state.value.paymentFeedback)
            assertEquals("未能打开支付应用".takeIf { status == "launch_failed" }, model.state.value.paymentNotice)
            assertEquals(api.channels, model.state.value.payOptions)
            assertEquals(1, api.payCalls)
            model.pay("001"); runCurrent(); assertEquals(2, api.payCalls)
            model.environment(false); runCurrent()
        }
    }
    @Test fun cancellationDoesNotOfferContinuationUntilTheOriginalBillIsVerifiedAutomatically() = bikeTest {
        val api = FakeHellobikeService()
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id))
        api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Unknown, orders = emptyList())
        model.paymentReturned(launch.id, "6001"); runCurrent()
        assertTrue(model.state.value.paymentFeedback?.cancelled == true)
        assertFalse(model.state.value.canShowPayment)
        model.continueCancelledPayment(); model.pay("001"); runCurrent()
        assertTrue(model.state.value.paymentFeedback?.cancelled == true); assertEquals(1, api.payCalls)

        api.detail = fixtureBikeDetail; advanceTimeBy(6000); runCurrent()
        assertTrue(model.state.value.canShowPayment); assertTrue(model.state.value.paymentFeedback?.cancelled == true)
        model.continueCancelledPayment(); runCurrent()
        assertNull(model.state.value.paymentFeedback); assertEquals(api.channels, model.state.value.payOptions)
        assertEquals(1, api.payCalls)
    }
    @Test fun explicitPaymentFailureRecoversUsingTheCurrentBillEvenIfTheAttemptQueryFails() = bikeTest {
        var tradeStatus = HellobikeTradeStatus.Ready
        val api = object : FakeHellobikeService() {
            override suspend fun paymentTradeStatus(account: HellobikeAccount, session: HellobikeSession) = tradeStatus
        }.apply { responseChannel = "1201" }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val model = model(api, store); ended(model); model.pay("1201"); runCurrent()
        val launch = model.state.value.paymentLaunch!!
        assertEquals("52", launch.method); assertTrue(model.claimPayment(launch.id))
        tradeStatus = HellobikeTradeStatus.Preparing
        val renewed = fixtureBikeOrder.copy(guid = "renewed-payment")
        api.detail = fixtureBikeDetail.copy(orders = listOf(renewed))
        api.queryPaid = { throw IOException("fixture query unavailable") }
        model.paymentReturned(launch.id, "fail"); runCurrent()
        assertEquals(listOf(fixtureBikeOrder.guid), api.queriedGuids)
        assertEquals(HellobikeSettlement.Pending, store.load().sessions.single().settlement)
        assertNull(store.load().sessions.single().paymentAttempt)
        assertTrue(model.state.value.paymentVisible); assertNull(model.state.value.paymentFeedback)
        assertNull(model.state.value.paymentNotice)
        assertEquals(api.channels, model.state.value.payOptions); assertEquals(1, api.payCalls)
        model.pay("1201"); runCurrent()
        assertEquals(2, api.payCalls)
        assertEquals(listOf(renewed), store.load().sessions.single().paymentAttempt?.billedOrders)
    }
    @Test fun anExistingPaymentWithoutACallbackRecoversWhenTheLatestBillContainsTheUnpaidItems() = bikeTest {
        val attempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "001", 1_000_000)
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying, paymentAttempt = attempt)
        val store = BikeRecords().store(sessions = listOf(session))
        val api = FakeHellobikeService().apply { detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Unknown, orders = emptyList()) }
        val model = model(api, store); ready(model); model.openSession(session.sessionId); runCurrent()
        assertEquals(HellobikeSettlement.Paying, model.state.value.session?.settlement)
        assertFalse(model.state.value.canShowPayment)
        api.queryPaid = { api.detail = fixtureBikeDetail; false }
        advanceTimeBy(6000); runCurrent()
        assertEquals(HellobikeSettlement.Pending, store.load().sessions.single().settlement)
        assertNull(store.load().sessions.single().paymentAttempt)
        assertNull(model.state.value.paymentNotice)
        assertFalse(model.state.value.paymentVisible); assertTrue(model.state.value.canShowPayment)
        model.showPayment(); runCurrent()
        assertEquals(api.channels, model.state.value.payOptions)
        assertEquals(0, api.payCalls)
        model.pay("001"); runCurrent(); assertEquals(1, api.payCalls)
        assertEquals(listOf(fixtureBikeOrder), model.state.value.session?.paymentAttempt?.orders)
    }
    @Test fun ambiguousReturnsAndUnverifiedOrdersCannotUnlockAnotherSubmission() = bikeTest {
        for (scenario in listOf("unconfirmed-bill", "wrong-ride", "wrong-payment", "query-error")) {
            val api = object : FakeHellobikeService() {
                override suspend fun payDetail(account: HellobikeAccount, session: HellobikeSession) =
                    if (scenario == "wrong-ride") detail else super.payDetail(account, session)
            }
            val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
            ended(model); model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
            assertTrue(model.claimPayment(launch.id))
            when (scenario) {
                "unconfirmed-bill" -> api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Unknown, orders = emptyList())
                "wrong-ride" -> api.detail = fixtureBikeDetail.copy(rideGuid = "other-ride", settlement = HellobikeSettlement.Paid)
                "wrong-payment" -> api.detail = fixtureBikeDetail.copy(orders = listOf(fixtureBikeOrder.copy(guid = "other-payment")))
                "query-error" -> api.queryPaid = { throw IOException("fixture query unavailable") }
            }
            model.paymentReturned(launch.id, null); runCurrent()
            advanceTimeBy(9001); runCurrent()
            assertEquals(scenario, RechargePhase.TimedOut, model.state.value.paymentFeedback?.phase)
            assertNotNull(scenario, model.state.value.session?.paymentAttempt)
            assertTrue(model.state.value.payOptions.isEmpty()); assertNull(model.state.value.paymentNotice)
            model.pay("001"); runCurrent(); assertEquals(1, api.payCalls)
            model.environment(false); runCurrent()
        }
    }
    @Test fun aReturnedPaymentIdentifierIsQueriedButRetryUsesTheOriginalBillItems() = bikeTest {
        val returnedOrder = fixtureBikeOrder.copy(guid = "returned-payment", orderId = "returned-order-id")
        val api = object : FakeHellobikeService() {
            override suspend fun pay(account: HellobikeAccount, session: HellobikeSession, orders: List<HellobikePayOrder>,
                option: HellobikePayOption) = super.pay(account, session, orders, option).copy(orders = listOf(returnedOrder))
        }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val model = model(api, store); ended(model); model.pay("001"); runCurrent()
        assertEquals(listOf(fixtureBikeOrder), store.load().sessions.single().paymentAttempt?.billedOrders)
        val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id))
        model.paymentReturned(launch.id, "6001"); runCurrent()
        assertEquals(listOf(returnedOrder.guid), api.queriedGuids)
        assertEquals(HellobikeSettlement.Pending, model.state.value.session?.settlement)
        assertEquals(api.channels, model.state.value.payOptions); assertEquals(1, api.payCalls)
    }
    @Test fun aBillThatBecomesPaidDuringQueryNeverReopensPaymentOptions() = bikeTest {
        val api = FakeHellobikeService()
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id))
        api.queryPaid = { api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Paid); false }
        model.paymentReturned(launch.id, "6001"); runCurrent()
        assertTrue(model.state.value.session!!.settled)
        assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
        assertTrue(model.state.value.payOptions.isEmpty()); assertEquals(1, api.payCalls)
    }
    @Test fun aPartialPaymentCannotBeMarkedPaidOrResubmittedEvenIfTheNextQueryShowsNoSuccess() = bikeTest {
        val orders = listOf(fixtureBikeOrder, fixtureBikeOrder.copy(guid = "second-payment"))
        val attempt = HellobikePaymentAttempt(orders, "001", 1_000_000)
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying, paymentAttempt = attempt)
        val store = BikeRecords().store(sessions = listOf(session))
        val api = FakeHellobikeService().apply {
            detail = fixtureBikeDetail.copy(orders = orders)
            queryPaid = { it == orders.first() }
        }
        val model = model(api, store); ready(model); model.openSession(session.sessionId); runCurrent()
        assertEquals(orders.map { it.guid }.toSet(), api.queriedGuids.toSet())
        assertEquals(setOf(orders.first().guid), store.load().sessions.single().paymentAttempt?.confirmedGuids)
        api.queryPaid = { false }
        model.showPayment(); runCurrent()
        assertEquals(RechargePhase.TimedOut, model.state.value.paymentFeedback?.phase)
        assertEquals(HellobikeSettlement.Unknown, store.load().sessions.single().settlement)
        assertTrue(model.state.value.payOptions.isEmpty())
        assertEquals(0, api.payCalls)
    }
    @Test fun aPartialPaymentSurvivesBackgroundingWhileAnotherVoucherIsStillBeingQueried() = bikeTest {
        val orders = listOf(fixtureBikeOrder, fixtureBikeOrder.copy(guid = "second-payment"))
        val queryBlocked = CompletableDeferred<Unit>()
        val api = object : FakeHellobikeService() {
            override suspend fun paymentPaid(account: HellobikeAccount, order: HellobikePayOrder,
                extension: kotlinx.serialization.json.JsonObject): Boolean {
                if (order == orders.first()) return true
                queryBlocked.complete(Unit)
                awaitCancellation()
            }
        }.apply { detail = fixtureBikeDetail.copy(orders = orders) }
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying,
            paymentAttempt = HellobikePaymentAttempt(orders, "001", 1_000_000))
        val store = BikeRecords().store(sessions = listOf(session))
        val model = model(api, store); runCurrent(); model.environment(true); runCurrent()
        assertTrue(queryBlocked.isCompleted)
        model.environment(false); runCurrent()
        assertEquals(setOf(orders.first().guid), store.load().sessions.single().paymentAttempt?.confirmedGuids)
        assertEquals(HellobikeSettlement.Unknown, store.load().sessions.single().settlement)
        assertEquals(0, api.payCalls)
    }
    @Test fun openingTheRideDuringPaymentRecoveryDoesNotRecheckAnAttemptAlreadyReconciled() = bikeTest {
        val gate = CompletableDeferred<Unit>()
        var queriesStarted = 0
        val api = object : FakeHellobikeService() {
            override suspend fun paymentPaid(account: HellobikeAccount, order: HellobikePayOrder,
                extension: kotlinx.serialization.json.JsonObject): Boolean {
                queriesStarted++
                gate.await()
                return super.paymentPaid(account, order, extension)
            }
        }
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying,
            paymentAttempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "001", 1_000_000))
        val store = BikeRecords().store(sessions = listOf(session))
        val model = model(api, store); ready(model); model.openSession(session.sessionId); runCurrent()
        assertEquals(1, queriesStarted)
        gate.complete(Unit); runCurrent()
        assertEquals(1, queriesStarted)
        assertEquals(HellobikeSettlement.Pending, store.load().sessions.single().settlement)
        model.showPayment(); runCurrent()
        assertEquals(api.channels, model.state.value.payOptions)
        assertNull(model.state.value.paymentFeedback)
        assertEquals(0, api.payCalls)
    }
    @Test fun paidServerResultWinsEvenWhenExternalPaymentWasReportedCancelled() = bikeTest {
        val api = FakeHellobikeService()
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id))
        api.queryPaid = { api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Paid); true }
        model.paymentReturned(launch.id, "6001"); runCurrent()
        assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
        assertTrue(model.state.value.session!!.settled); assertTrue(model.state.value.payOptions.isEmpty())
        assertEquals(1, api.payCalls)
    }
    @Test fun aFailedChannelRefreshAfterCancellationKeepsTheBillPayableWithoutResubmitting() = bikeTest {
        var channelsUnavailable = false
        val api = object : FakeHellobikeService() {
            override suspend fun payChannels(account: HellobikeAccount, session: HellobikeSession, detail: HellobikePayDetail): List<HellobikePayOption> {
                if (channelsUnavailable) throw IOException("fixture channels unavailable")
                return super.payChannels(account, session, detail)
            }
        }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("001"); runCurrent(); val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimPayment(launch.id)); channelsUnavailable = true
        model.paymentReturned(launch.id, "6001"); runCurrent()
        assertNull(model.state.value.session?.paymentAttempt); assertEquals(HellobikeSettlement.Pending, model.state.value.session?.settlement)
        assertNotNull(model.state.value.error); assertTrue(model.state.value.payOptions.isEmpty()); assertFalse(model.state.value.paymentLoading)
        channelsUnavailable = false; advanceTimeBy(6000); runCurrent()
        assertEquals(api.channels, model.state.value.payOptions); assertEquals(1, api.payCalls)
    }
    @Test fun rejectedPaymentQuoteRefreshesTheOpenCashierWithoutSubmittingAgain() = bikeTest {
        var oldChannelAvailable = true
        val updatedOrder = fixtureBikeOrder.copy(amount = "3.00")
        val api = object : FakeHellobikeService() {
            override suspend fun payChannels(account: HellobikeAccount, session: HellobikeSession, detail: HellobikePayDetail) =
                channels.map { option -> option.copy(amount = detail.amount!!,
                    channel = option.channel.copy(available = option.channel.method != "001" || oldChannelAvailable)) }
        }
        val store = BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))
        val model = model(api, store); ended(model)
        api.beforePay = {
            api.detail = fixtureBikeDetail.copy(amount = "3.00", orders = listOf(updatedOrder))
            oldChannelAvailable = false
        }
        api.payFailure = HellobikeException("优惠已变化", rejected = true, paymentQuoteChanged = true)
        model.pay("001"); runCurrent()
        assertEquals(1, api.payCalls); assertEquals(0, api.queryCalls)
        assertTrue(model.state.value.paymentVisible); assertNull(model.state.value.paymentFeedback)
        assertNull(model.state.value.paymentLaunch); assertNull(store.load().sessions.single().paymentAttempt)
        assertEquals(HellobikeSettlement.Pending, store.load().sessions.single().settlement)
        assertEquals("3.00", model.state.value.payDetail?.amount)
        assertEquals("003", model.state.value.paymentMethod)
        assertNotNull(model.state.value.paymentNotice)
        api.payFailure = null
        model.pay("1201"); runCurrent()
        assertEquals(2, api.payCalls)
        assertEquals(listOf(updatedOrder), store.load().sessions.single().paymentAttempt?.orders)
    }
    @Test fun failedPaymentDispatchShowsAQueryResultInsteadOfLeavingTheDrawerLoading() = bikeTest {
        val api = FakeHellobikeService().apply { payFailure = HellobikeException("付款请求未确认") }
        val model = model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended))))
        ended(model); model.pay("001"); runCurrent()
        assertEquals(RechargePhase.TimedOut, model.state.value.paymentFeedback?.phase)
        assertEquals("付款请求未确认", model.state.value.paymentFeedback?.detail)
        assertFalse(model.state.value.paymentFeedback!!.loading)
        advanceTimeBy(6000); runCurrent()
        assertEquals(1, api.queryCalls); assertEquals(1, api.payCalls)
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
    @Test fun aPersistedPaymentReceiptRefreshesTheBillWithoutAssumingSettlementOrRequeryingPayment() = bikeTest {
        val session = fixtureBikeSession.copy(phase = HellobikePhase.Ended, settlement = HellobikeSettlement.Paying,
            paymentAttempt = HellobikePaymentAttempt(listOf(fixtureBikeOrder), "003", 1_000_000,
                confirmedGuids = setOf(fixtureBikeOrder.guid)))
        val api = FakeHellobikeService().apply {
            detailFailure = IOException("fixture unavailable")
            queryPaid = { throw IOException("fixture query unavailable") }
        }
        val store = BikeRecords().store(sessions = listOf(session)); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        assertEquals(0, api.queryCalls); assertEquals(setOf(fixtureBikeOrder.guid), store.load().sessions.single().paymentAttempt!!.confirmedGuids)
        assertFalse(store.load().sessions.single().settled)
        assertEquals(HellobikeSettlement.Unknown, store.load().sessions.single().settlement); assertEquals(0, api.payCalls)
        model.pageVisible(true); assertTrue(model.openSession(session.sessionId)); runCurrent()
        model.showPayment(); runCurrent()
        assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
        assertTrue(model.state.value.payOptions.isEmpty()); assertEquals(0, api.queryCalls)
        api.detailFailure = null
        api.detail = fixtureBikeDetail.copy(settlement = HellobikeSettlement.Paid)
        advanceTimeBy(6000); runCurrent()
        assertTrue(store.load().sessions.single().settled)
        assertEquals(0, api.queryCalls); assertEquals(0, api.payCalls)
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
