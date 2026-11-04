package com.mercury.yktwx

import android.app.Application
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaundryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private val device = LifeDevice("77", "00000000-0000-4000-8000-000000000001", "77", "00", "22", "22", "shop", "serial-77",
        "学校", "", "before", false, false, null, listOf(LifeSku("clean", "桶自洁", true, "0.00", "设备桶自洁")), emptyList())
    private class Store(var washing: LaundrySession? = null, var water: WaterSession? = null) : LifeDeviceStore {
        var owner: LifeAccount? = LifeAccount("13800000000", "test-token")
        override fun account() = owner
        override fun saveAccount(account: LifeAccount) { owner = account }
        override fun clearAccount() { owner = null }
        override fun session(): WaterSession? = water
        override fun saveSession(session: WaterSession) {
            check(water != null) { "Laundry must not write water session" }
            water = session
        }
        override fun clearSession() { error("Laundry must not clear water session") }
        override fun laundrySession() = washing
        override fun saveLaundrySession(session: LaundrySession) { washing = session }
        override fun clearLaundrySession() { washing = null }
    }
    private inner class Api : LifeDeviceService, LifeLaundryService {
        var creates = 0; var balancePays = 0; var alipayPrepares = 0; var details = 0; var paidReads = 0
        var status = 0; var paid = false; var started = false; var price = "0.00"; var readSku = "clean"
        var lostCreate = false; var lostPayment = false
        var waterReads = 0; var waterReading: WaterReading? = null; var waterFails = false
        var readyGate: CompletableDeferred<Unit>? = null
        val balance = LifePayChannel("2", "余额支付", true, false)
        val alipay = LifePayChannel("13", "支付宝", true, false)
        var channels = listOf(balance, alipay)
        override suspend fun sendCode(phone: String) {}
        override suspend fun login(phone: String, code: String) = LifeAccount(phone, "test-token")
        override suspend fun device(serial: String, token: String): LifeDevice = error("No water endpoint")
        override suspend fun selectAfterPay(token: String) { error("No after pay") }
        override suspend fun unlock(skuId: String, token: String): WaterUnlock = error("No water unlock")
        override suspend fun message(messageId: String, token: String): WaterMessage? = error("No water sync")
        override suspend fun water(skuId: String, token: String): WaterReading {
            waterReads++
            if (waterFails) throw java.io.IOException("offline water")
            return waterReading ?: error("No water read")
        }
        override suspend fun endWater(goodsId: String, token: String) { error("No water stop") }
        override suspend fun createAfterPay(orderNo: String, token: String): String = error("No after pay order")
        override suspend fun syncOrder(orderNo: String, token: String) { error("No after pay sync") }
        override suspend fun laundryDevice(code: String, token: String) = device
        override suspend fun laundryQuote(device: LifeDevice, skuId: String, token: String) = LaundryQuote(device.id, skuId, price, 5, channels)
        override suspend fun checkLaundryDevice(deviceId: String, token: String): String { readyGate?.await(); return "msg-1" }
        override suspend fun laundryMachineReady(messageId: String, token: String) = true
        override suspend fun createLaundryOrder(deviceId: String, skuId: String, token: String): LaundryOrder {
            creates++; if (lostCreate) throw java.io.IOException("lost create response")
            return LaundryOrder("wash-1", "wash-id")
        }
        override suspend fun laundryOrder(orderNo: String, token: String): LaundryReading {
            details++
            return LaundryReading("wash-1", "wash-id", device.id, readSku, status, price, "桶自洁", 5,
                100_000L.takeIf { status in setOf(2, 3) }, 400_000L.takeIf { status == 2 }, 521_000L.takeIf { status == 3 })
        }
        override suspend fun laundryChannels(session: LaundrySession, token: String) = channels
        override suspend fun payLaundryBalance(orderNo: String, token: String) {
            balancePays++; paid = true; started = true; status = 2
            if (lostPayment) throw java.io.IOException("lost payment response")
        }
        override suspend fun prepareLaundryAlipay(orderNo: String, token: String): String { alipayPrepares++; return "test-signed-payload" }
        override suspend fun laundryPaid(orderNo: String, method: String, token: String): Boolean { paidReads++; return paid }
        override suspend fun laundryStarted(orderNo: String, token: String) = started
    }
    private fun model(api: Api, store: Store) = LifeDevicesViewModel(ApplicationProvider.getApplicationContext<Application>(), api, store,
        dispatcher, { 100_000L }).also { models.put("model", it) }
    private fun TestScope.prepare(model: LifeDevicesViewModel) {
        runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent()
        assertNotNull(model.state.value.laundryQuote)
    }
    @Test fun zeroCleanCreatesPaysRunsAndFinishesOnlyFromSameOrderResponse() = runTest(dispatcher) {
        val api = Api(); val store = Store(); val model = model(api, store)
        prepare(model); model.showLaundryPayment(); model.payLaundry("2"); runCurrent()
        assertEquals(1, api.creates); assertEquals(1, api.balancePays)
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        model.open(LifeFeature.Laundry); model.retryLaundry(); runCurrent()
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        api.status = 3; model.retryLaundry(); runCurrent()
        assertEquals(LaundryPhase.Ended, model.state.value.laundry?.phase)
        assertEquals(521_000L, store.washing?.finishedAtMillis)
        assertNull(model.state.value.error)
        model.scan(); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(store.washing)
        assertEquals(1, api.creates); assertEquals(1, api.balancePays)
    }
    @Test fun repeatClicksAndLostCreateCannotGenerateAnotherOrder() = runTest(dispatcher) {
        val api = Api().apply { lostCreate = true; readyGate = CompletableDeferred() }
        val store = Store(); val model = model(api, store)
        prepare(model); model.showLaundryPayment(); model.payLaundry("2"); runCurrent()
        model.payLaundry("2"); runCurrent()
        assertEquals(LaundryPhase.Checking, model.state.value.laundry?.phase)
        api.readyGate!!.complete(Unit); runCurrent()
        assertEquals(1, api.creates); assertEquals(LaundryPhase.Creating, store.washing?.phase)
        model.retryLaundry(); model.scan(); runCurrent()
        assertEquals(1, api.creates); assertEquals(0, api.balancePays)
        assertFalse(model.state.value.scanning); assertNotNull(model.state.value.error)
    }
    @Test fun lostBalanceResponseChecksServerWithoutPayingOrCreatingAgain() = runTest(dispatcher) {
        val api = Api().apply { lostPayment = true }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("2"); runCurrent()
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        assertNotNull(model.state.value.error)
        model.retryLaundry(); runCurrent()
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        assertEquals(1, api.creates); assertEquals(1, api.balancePays)
    }
    @Test fun alipayCancelContinuesSameOrderAndSdkSuccessIsNotProofOfPayment() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        val launch = model.state.value.paymentLaunch!!
        assertTrue(model.claimLaundryPayment(launch.id)); assertFalse(model.claimLaundryPayment(launch.id))
        model.laundryPaymentReturned(launch.id, "6001"); runCurrent()
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        model.showLaundryPayment(); runCurrent(); model.payLaundry("13"); runCurrent()
        assertEquals(1, api.creates); assertEquals(2, api.alipayPrepares)
        val second = model.state.value.paymentLaunch!!
        model.laundryPaymentReturned(second.id, "9000"); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        assertFalse(model.state.value.laundry!!.paid)
        api.paid = true; api.status = 2; model.retryLaundry(); runCurrent()
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        assertEquals(1, api.creates)
    }
    @Test fun mismatchedProgramOrPricePreventsPaymentAndDoesNotAdoptAnotherOrder() = runTest(dispatcher) {
        val api = Api().apply { readSku = "other-program" }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("2"); runCurrent()
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        assertNotNull(model.state.value.error); assertEquals(0, api.balancePays)
    }
    @Test fun unsupportedPaymentChannelCannotCreateAnOrder() = runTest(dispatcher) {
        val api = Api().apply { channels = listOf(LifePayChannel("32", "微信支付", true, false)) }
        val model = model(api, Store()); prepare(model)
        model.showLaundryPayment(); model.payLaundry("32"); runCurrent()
        assertEquals(0, api.creates); assertNull(model.state.value.laundry)
    }
    @Test fun restoredFinishedOrderAndForegroundPollingKeepExpectedTimeSeparateFromCompletion() = runTest(dispatcher) {
        val session = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Running, true, "wash-1", "wash-id", true, true,
            5, 100_000, 400_000)
        val api = Api().apply { status = 2 }
        val store = Store(session); val model = model(api, store)
        runCurrent(); model.open(LifeFeature.Laundry); model.environment(true); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        api.status = 3; advanceTimeBy(5000); runCurrent()
        assertEquals(LaundryPhase.Ended, model.state.value.laundry?.phase)
        val reads = api.details; advanceTimeBy(20_000); runCurrent(); assertEquals(reads, api.details)
        assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
    @Test fun interruptedCheckCanRetryAndInterruptedCreationCannotCreateAgainAfterRestore() = runTest(dispatcher) {
        val api = Api()
        val checking = Store(LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Checking))
        val model = model(api, checking)
        runCurrent(); model.open(LifeFeature.Laundry); runCurrent()
        assertNotNull(model.state.value.error)
        model.retryLaundry(); runCurrent()
        assertEquals(1, api.creates)
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        val lost = Store(LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Creating, createSent = true))
        val restored = model(api, lost)
        runCurrent(); restored.open(LifeFeature.Laundry); runCurrent()
        assertNotNull(restored.state.value.error)
        restored.retryLaundry(); runCurrent()
        assertEquals(1, api.creates)
        assertEquals(LaundryPhase.Creating, restored.state.value.laundry?.phase)
    }
    @Test fun bothHomeSessionsResumeWithoutOpeningAPageAndKeepPollingAcrossFeatureChanges() = runTest(dispatcher) {
        val washing = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Running,
            true, "wash-1", "wash-id", true, true, 5, 100_000, 400_000)
        val water = WaterSession(device.copy(id = "water-device", category = "04"), "water-sku",
            WaterPhase.Running, orderNo = "water-1", startedAtMillis = 100_000)
        val api = Api().apply { status = 2; waterReading = WaterReading(1, 2, "water-1") }
        val model = model(api, Store(washing, water))
        model.environment(true); runCurrent()
        assertTrue(api.details > 0); assertTrue(api.waterReads > 0)
        model.open(LifeFeature.Laundry); model.pageVisible(false); runCurrent()
        val reads = api.waterReads
        api.status = 3; advanceTimeBy(5000); runCurrent()
        assertEquals(LaundryPhase.Ended, model.state.value.laundry?.phase)
        assertEquals(WaterPhase.Running, model.state.value.water?.phase)
        assertTrue(api.waterReads > reads)
        api.waterReading = WaterReading(5, 1, "water-1", true)
        advanceTimeBy(1500); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase)
        val stopped = api.waterReads to api.details
        advanceTimeBy(20_000); runCurrent()
        assertEquals(stopped, api.waterReads to api.details)
        assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
    @Test fun backgroundPausesBothReadersAndHiddenWaterFailureDoesNotOverwriteLaundry() = runTest(dispatcher) {
        val washing = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Running,
            true, "wash-1", "wash-id", true, true)
        val water = WaterSession(device.copy(category = "04"), "water-sku", WaterPhase.Running, orderNo = "water-1")
        val api = Api().apply { status = 2; waterFails = true }
        val model = model(api, Store(washing, water))
        runCurrent(); model.open(LifeFeature.Laundry); model.environment(true); runCurrent()
        assertNull(model.state.value.error)
        model.environment(false); runCurrent()
        val paused = api.waterReads to api.details
        advanceTimeBy(20_000); runCurrent()
        assertEquals(paused, api.waterReads to api.details)
        model.open(LifeFeature.Water); runCurrent()
        assertNotNull(model.state.value.error)
        model.environment(true); runCurrent()
        assertTrue(api.waterReads > paused.first); assertTrue(api.details > paused.second)
        assertNotNull(model.state.value.error)
        model.environment(false); runCurrent()
    }
}
