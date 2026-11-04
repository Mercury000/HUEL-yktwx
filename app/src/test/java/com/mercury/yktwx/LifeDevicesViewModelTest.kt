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
class LifeDevicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { viewModels.clear(); Dispatchers.resetMain() }
    private class Store(var savedAccount: LifeAccount? = null, var savedSession: WaterSession? = null) : LifeDeviceStore {
        override fun account() = savedAccount
        override fun session() = savedSession
        override fun saveAccount(account: LifeAccount) { savedAccount = account }
        override fun clearAccount() { savedAccount = null }
        override fun saveSession(session: WaterSession) { savedSession = session }
        override fun clearSession() { savedSession = null }
    }
    private val device = LifeDevice("11", "867369079842630", "开水", "04", "22", "22", "shop", "867369079842630",
        "", "", "after", false, true, null, listOf(LifeSku("33", "饮水", true)),
        listOf(LifePayChannel("15", "支付宝支付", true, false)))
    private inner class Api : LifeDeviceService {
        var loginCalls = 0; var deviceCalls = 0; var unlockCalls = 0; var stopCalls = 0; var createCalls = 0; var syncCalls = 0
        var lostUnlock = false
        var messageGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var reading = WaterReading(1, 2, "order-1")
        var createFailure = false
        var zeroConsumption = false
        var incompleteOrder = false
        override suspend fun sendCode(phone: String) {}
        override suspend fun login(phone: String, code: String): LifeAccount { loginCalls++; return LifeAccount(phone, "test-token") }
        override suspend fun device(serial: String, token: String): LifeDevice { deviceCalls++; return device }
        override suspend fun selectAfterPay(token: String) {}
        override suspend fun unlock(skuId: String, token: String): WaterUnlock {
            unlockCalls++
            if (lostUnlock) throw java.io.IOException("lost response")
            return WaterUnlock("message-1", "order-1")
        }
        override suspend fun message(messageId: String, token: String): WaterMessage? { messageGate?.await(); return WaterMessage("OK", "0") }
        override suspend fun water(skuId: String, token: String) = reading
        override suspend fun endWater(goodsId: String, token: String) { stopCalls++; stopGate?.await(); reading = WaterReading(5, 1, "order-1", zeroConsumption) }
        override suspend fun createAfterPay(orderNo: String, token: String): String {
            createCalls++
            if (createFailure) throw java.io.IOException("lost create response")
            if (incompleteOrder) throw LifeDeviceException("设备响应信息不完整")
            return "id-1"
        }
        override suspend fun syncOrder(orderNo: String, token: String) { syncCalls++ }
    }
    private fun model(api: Api, store: Store): LifeDevicesViewModel = LifeDevicesViewModel(
        ApplicationProvider.getApplicationContext<Application>(), api, store, dispatcher, { 100_000L }
    ).also { viewModels.put("model", it) }

    @Test fun eitherEntryRequiresSameLoginAndLaundryDoesNotLoadDevices() = runTest(dispatcher) {
        val api = Api(); val store = Store(); val model = model(api, store)
        model.open(LifeFeature.Laundry); runCurrent()
        assertTrue(model.state.value.loginVisible)
        assertEquals(0, model.state.value.entryRequest)
        model.phoneChanged("13800000000"); model.codeChanged("123456"); model.login(); runCurrent()
        assertFalse(model.state.value.loginVisible)
        assertEquals(LifeFeature.Laundry, model.state.value.feature)
        assertEquals(1, model.state.value.entryRequest)
        assertEquals(1, api.loginCalls)
        assertEquals(0, api.deviceCalls)
        model.open(LifeFeature.Water); runCurrent()
        assertFalse(model.state.value.loginVisible)
        assertEquals(2, model.state.value.entryRequest)
        assertTrue(model.state.value.scanning)
        assertEquals(1, api.loginCalls)
    }

    @Test fun dismissingLoginNeverOpensAnEntry() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store()); runCurrent()
        model.open(LifeFeature.Water)
        model.dismissLogin(); model.open(LifeFeature.Laundry)
        assertTrue(model.state.value.loginVisible)
        assertEquals(0, model.state.value.entryRequest)
        assertEquals(0, api.loginCalls)
    }

    private fun TestScope.prepare(model: LifeDevicesViewModel) {
        runCurrent(); model.open(LifeFeature.Water); model.pageVisible(true)
        model.scanned("https://h5.qiekj.com/skip?SN=867369079842630"); runCurrent()
    }

    @Test fun startAndStopKeepOneOrderAndNeverFinishFromAButtonClickAlone() = runTest(dispatcher) {
        val api = Api(); val store = Store(LifeAccount("13800000000", "test-token")); val model = model(api, store)
        prepare(model)
        api.messageGate = CompletableDeferred()
        model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.water?.phase)
        model.startWater(); runCurrent()
        assertEquals(1, api.unlockCalls)
        api.messageGate!!.complete(Unit); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.water?.phase)
        model.pageVisible(false); model.open(LifeFeature.Water); runCurrent()
        assertFalse(model.state.value.scanning)
        api.stopGate = CompletableDeferred()
        model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Stopping, model.state.value.water?.phase)
        model.stopWater(); runCurrent()
        assertEquals(1, api.stopCalls)
        api.stopGate!!.complete(Unit); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase)
        assertTrue(model.state.value.water!!.finalized)
        assertEquals("id-1", store.savedSession?.orderId)
        assertEquals(1, api.createCalls)
        assertEquals(1, api.syncCalls)
    }

    @Test fun lostUnlockResponseCannotLaunchAnotherOrderOrAdoptAnOldDeviceOrder() = runTest(dispatcher) {
        val api = Api().apply { lostUnlock = true }; val store = Store(LifeAccount("13800000000", "test-token")); val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        assertNotNull(model.state.value.error)
        model.startWater(); model.retryWater(); runCurrent()
        assertEquals(1, api.unlockCalls)
        assertEquals("", model.state.value.water?.orderNo)
        assertEquals(WaterPhase.Starting, model.state.value.water?.phase)
        assertEquals(0, api.createCalls)
    }

    @Test fun mismatchedDeviceOrderCannotBecomeRunningOrEnded() = runTest(dispatcher) {
        val api = Api().apply { reading = WaterReading(5, 1, "different-order") }
        val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.water?.phase)
        assertNotNull(model.state.value.error)
        assertEquals(0, api.createCalls)
    }

    @Test fun afterPayFailureKeepsEndedDeviceAndDoesNotAutomaticallyRepeatMutation() = runTest(dispatcher) {
        val api = Api().apply { createFailure = true }
        val store = Store(LifeAccount("13800000000", "test-token")); val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        model.environment(true); runCurrent()
        model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase)
        assertFalse(model.state.value.water!!.finalized)
        assertNotNull(model.state.value.error)
        assertEquals(1, api.createCalls)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, api.createCalls)
        api.createFailure = false; model.retryWater(); runCurrent()
        assertTrue(model.state.value.water!!.finalized)
        assertEquals(2, api.createCalls)
    }

    @Test fun capturedZeroConsumptionEndsWithoutCreatingOrSyncingAfterPayOrder() = runTest(dispatcher) {
        val api = Api().apply { zeroConsumption = true }
        val store = Store(LifeAccount("13800000000", "test-token"))
        val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        model.environment(true); model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase)
        assertTrue(model.state.value.water!!.finalized)
        assertNull(model.state.value.error)
        assertEquals(WaterPhase.Ended, store.savedSession?.phase)
        model.open(LifeFeature.Water); model.retryWater(); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, api.stopCalls)
        assertEquals(0, api.createCalls)
        assertEquals(0, api.syncCalls)
        model.scan(); runCurrent()
        assertTrue(model.state.value.scanning)
    }

    @Test fun missingAfterPayFieldsCannotUndoConfirmedDeviceEnd() = runTest(dispatcher) {
        val api = Api().apply { incompleteOrder = true }
        val store = Store(LifeAccount("13800000000", "test-token"))
        val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase)
        assertEquals(WaterPhase.Ended, store.savedSession?.phase)
        assertFalse(model.state.value.water!!.finalized)
        assertEquals("设备响应信息不完整", model.state.value.error)
        assertEquals(1, api.createCalls)
        assertEquals(0, api.syncCalls)
    }
    @Test fun scanEntryPreservesWaterAndSameDeviceResumesWithoutUnlockingAgain() = runTest(dispatcher) {
        val api = Api(); val store = Store(LifeAccount("13800000000", "test-token")); val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        val session = model.state.value.water!!
        model.openScan(LifeFeature.Water); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(model.state.value.water)
        assertEquals(session, store.savedSession)
        model.environment(true); runCurrent(); advanceTimeBy(1500); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(model.state.value.device)
        model.scanned(device.serial); runCurrent()
        assertFalse(model.state.value.scanning); assertEquals(session.sessionId, model.state.value.water?.sessionId)
        model.startWater(); runCurrent(); assertEquals(1, api.unlockCalls)
        model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
        assertEquals(1, model.state.value.waterSessions.size)
        model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Ended, store.savedSession?.phase)
        model.openScan(LifeFeature.Water); runCurrent()
        assertEquals(1, model.state.value.waterSessions.size)
        model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
        assertTrue(model.state.value.waterSessions.isEmpty()); assertNull(store.savedSession)
        model.environment(false); runCurrent()
    }
}
