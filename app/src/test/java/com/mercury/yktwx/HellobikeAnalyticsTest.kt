package com.mercury.yktwx

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.analytics.*
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HellobikeAnalyticsTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val backend = RecordingAnalyticsBackend()
    private val analytics = AppAnalytics(backend)
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun setup() { Dispatchers.setMain(dispatcher); analytics.resume(context, agreed = true) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private fun bikeTest(block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try { block() } finally { models.clear(); runCurrent() }
    }
    private fun TestScope.model(api: FakeHellobikeService, store: HellobikeStore): HellobikeViewModel {
        api.elapsed = { 50_000 + dispatcher.scheduler.currentTime }
        return HellobikeViewModel(api, store, FakeBikeLocation(), dispatcher,
            { 1_000_000 + dispatcher.scheduler.currentTime }, api.elapsed, analytics = analytics).also {
            models.put("bike", it); it.initialize(preview = false); runCurrent()
            it.environment(true); it.pageVisible(true); runCurrent()
        }
    }
    private fun TestScope.vehicle(api: FakeHellobikeService, records: BikeRecords = BikeRecords()): HellobikeViewModel =
        model(api, records.store()).also { it.openDevice(fixtureBikeSession.bikeNo); runCurrent(); assertNotNull(it.state.value.precheck) }
    private fun TestScope.payment(api: FakeHellobikeService): HellobikeViewModel =
        model(api, BikeRecords().store(sessions = listOf(fixtureBikeSession.copy(phase = HellobikePhase.Ended)))).also {
            it.openSession(fixtureBikeSession.sessionId); runCurrent(); it.showPayment(); runCurrent()
            assertEquals(3, it.state.value.payOptions.size)
        }
    private fun assertEvents(vararg names: String) { assertEquals(names.toList(), backend.events.map { it.first }) }

    @Test fun manualLoginCountsOnceAndRecoveryDoesNotBecomeAnotherLogin() = bikeTest {
        val api = FakeHellobikeService()
        val model = model(api, BikeRecords().store(account = null))
        model.connectAccount(); model.phoneChanged(fixtureBikeAccount.phone); model.codeChanged("123456")
        model.login(); model.login(); runCurrent()
        assertEquals(1, api.loginCalls)
        assertEvents("login_result")
        assertEquals(mapOf("platform" to "hellobike", "outcome" to "success"), backend.events.single().second)
        model.environment(false); model.environment(true); runCurrent()
        assertEvents("login_result")
    }
    @Test fun loginFailureUsesItsCategoryWithoutCloudTextOrAccountData() = bikeTest {
        val api = object : FakeHellobikeService() {
            override suspend fun login(phone: String, code: String, region: HellobikeRegion?, clientId: String, loginGuid: String): HellobikeAccount =
                throw HellobikeException("private-account-and-token", loginExpired = true)
        }
        val model = model(api, BikeRecords().store(account = null))
        model.connectAccount(); model.phoneChanged(fixtureBikeAccount.phone); model.codeChanged("123456"); model.login(); runCurrent()
        assertEvents("login_result")
        assertEquals(mapOf("platform" to "hellobike", "outcome" to "failure", "error_kind" to "auth"), backend.events.single().second)
    }
    @Test fun unlockRecoveryAndPendingPollingDoNotCreateFailureOrDuplicateSubmission() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = HellobikeException("你有一辆开锁中车辆", rejected = true) }
        val model = vehicle(api); api.orderReading = HellobikeOrderReading(20, 22)
        model.startRide(); model.startRide(); runCurrent(); advanceTimeBy(21_601); runCurrent()
        assertEquals(1, api.createCalls)
        assertEvents("hellobike_unlock_requested")
        assertTrue(backend.events.single().second.isEmpty())
    }
    @Test fun failedStorageAndExpiredPrecheckNeverCountARequestThatWasNotSent() = bikeTest {
        val records = BikeRecords(); val api = FakeHellobikeService(); val model = vehicle(api, records)
        records.failWrite = true; model.startRide(); runCurrent()
        assertEquals(0, api.createCalls); assertEvents()
        records.failWrite = false; advanceTimeBy(60_001); runCurrent(); model.startRide(); runCurrent()
        assertEquals(0, api.createCalls); assertEvents()
    }
    @Test fun cancellationAfterSubmittingUnlockIsNotAnOperationFailure() = bikeTest {
        val api = FakeHellobikeService().apply { createGate = CompletableDeferred() }
        val model = vehicle(api); model.startRide(); runCurrent()
        model.pageVisible(false); model.environment(false); runCurrent()
        assertEquals(1, api.createCalls); assertEvents("hellobike_unlock_requested")
    }
    @Test fun rejectedUnlockWithoutConfirmedRecoveryCountsOneCategorizedFailure() = bikeTest {
        val api = FakeHellobikeService().apply { createFailure = HellobikeException("private-order", rejected = true) }
        val model = vehicle(api); api.orderReading = HellobikeOrderReading(0, 0)
        model.startRide(); runCurrent()
        assertEquals(1, api.createCalls)
        assertEvents("hellobike_unlock_requested", "operation_failed")
        assertEquals(mapOf("feature" to "hellobike_ride", "error_kind" to "business"), backend.events.last().second)
    }
    @Test fun paymentCancellationAndQueriesDoNotDuplicateItsSubmission() = bikeTest {
        val api = FakeHellobikeService(); val model = payment(api)
        model.pay("001"); model.pay("001"); runCurrent()
        assertEquals(1, api.payCalls); assertEvents("hellobike_pay_submitted")
        val id = model.state.value.paymentLaunch!!.id
        assertTrue(model.claimPayment(id)); model.paymentReturned(id, "cancel"); runCurrent()
        advanceTimeBy(12_001); runCurrent(); assertEvents("hellobike_pay_submitted")
        model.checkPayment(); runCurrent(); assertEvents("hellobike_pay_submitted")
    }
    @Test fun explicitPaymentRejectionCountsOneFailureWithBoundedProperties() = bikeTest {
        val api = FakeHellobikeService().apply { payFailure = HellobikeException("private-order", rejected = true) }
        val model = payment(api); model.pay("003"); runCurrent()
        assertEvents("hellobike_pay_submitted", "operation_failed")
        assertEquals(mapOf("feature" to "hellobike_payment", "error_kind" to "business"), backend.events.last().second)
        model.pay("003"); runCurrent(); assertEquals(1, api.payCalls)
        assertEvents("hellobike_pay_submitted", "operation_failed")
    }
    @Test fun paymentBridgeFailureCountsOnceAndIgnoresDuplicateCallbacks() = bikeTest {
        val api = FakeHellobikeService(); val model = payment(api)
        model.pay("001"); runCurrent()
        val id = model.state.value.paymentLaunch!!.id
        assertTrue(model.claimPayment(id)); model.paymentReturned(id, "6002"); model.paymentReturned(id, "launch_failed"); runCurrent()
        assertEvents("hellobike_pay_submitted", "operation_failed")
        assertEquals(mapOf("feature" to "hellobike_payment", "error_kind" to "external"), backend.events.last().second)
    }
    @Test fun revokeDropsTheLateLoginResult() = bikeTest {
        val gate = CompletableDeferred<Unit>()
        val api = object : FakeHellobikeService() {
            override suspend fun login(phone: String, code: String, region: HellobikeRegion?, clientId: String, loginGuid: String): HellobikeAccount {
                gate.await(); return fixtureBikeAccount.copy(phone = phone, clientId = clientId)
            }
        }
        val model = model(api, BikeRecords().store(account = null))
        model.connectAccount(); model.phoneChanged(fixtureBikeAccount.phone); model.codeChanged("123456"); model.login(); runCurrent()
        analytics.revoke(); gate.complete(Unit); runCurrent()
        assertTrue(model.state.value.connected); assertEvents()
    }
    @Test fun withoutAuthorizationBusinessStillRunsAndNoEventsAreRecorded() = bikeTest {
        analytics.revoke()
        val api = FakeHellobikeService(); val model = vehicle(api); model.startRide(); runCurrent()
        assertEquals(1, api.createCalls); assertEvents()
    }
}
