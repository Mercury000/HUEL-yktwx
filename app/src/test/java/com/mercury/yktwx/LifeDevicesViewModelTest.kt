package com.mercury.yktwx

import android.app.Application
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifeDevicesViewModelTest {
    @Test fun recreatedLoginRestoresPhoneWithoutCodeOrBusinessCalls() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        fun fresh(api: Api, state: SavedStateHandle) = LifeDevicesViewModel(api, Store(), dispatcher, { 100_000L },
            com.mercury.yktwx.analytics.AnalyticsFixtures.from(ApplicationProvider.getApplicationContext<Application>()), state)
        val firstApi = Api()
        val first = fresh(firstApi, handle).also { viewModels.put("first", it) }
        runCurrent(); first.bindPresentation("campus", false, 1)
        first.manageLogin(); first.phoneChanged("12345678901"); first.codeChanged("123456")
        val restored = handle.recreated(); viewModels.clear()
        val api = Api()
        val next = fresh(api, restored).also { viewModels.put("next", it) }
        next.bindPresentation("campus", false, 20)
        assertFalse(next.state.value.loginVisible)
        runCurrent(); next.bindPresentation("campus", false, 20)
        assertTrue(next.state.value.loginVisible)
        assertEquals("12345678901", next.state.value.phone)
        assertEquals("", next.state.value.code)
        assertEquals(0, api.loginCalls); assertEquals(0, api.deviceCalls); assertEquals(0, api.unlockCalls)
        next.bindPresentation("campus", false, 21)
        assertFalse(next.state.value.loginVisible)
    }
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { viewModels.clear(); Dispatchers.resetMain() }
    private class Store(var savedAccount: LifeAccount? = null, var savedSession: WaterSession? = null) : LifeDeviceStore {
        var savedLaundry: List<LaundrySession> = emptyList()
        override fun account() = savedAccount
        override fun session() = savedSession
        override fun saveAccount(account: LifeAccount) { savedAccount = account }
        override fun clearAccount() { savedAccount = null }
        override fun saveSession(session: WaterSession) { savedSession = session }
        override fun clearSession() { savedSession = null }
        override fun laundrySessions() = savedLaundry
        override fun saveLaundrySessions(sessions: List<LaundrySession>) { savedLaundry = sessions }
    }
    private val device = LifeDevice("11", "867369079842630", "开水", "04", "22", "22", "shop", "867369079842630",
        "", "", "after", false, true, null, listOf(LifeSku("33", "饮水", true)),
        listOf(LifePayChannel("15", "支付宝支付", true, false)))
    private inner class Api : LifeDeviceService {
        var returnedDevice = device
        var showerUnlockCalls = 0; var showerReads = 0; var showerStopCalls = 0
        val afterPayOrders = mutableListOf<String>()
        var showerEndReading = WaterReading(5, 1, "order-1", zeroConsumption = true)
        var loginCalls = 0; var deviceCalls = 0; var unlockCalls = 0; var stopCalls = 0; var createCalls = 0; var syncCalls = 0
        var lostUnlock = false
        var unlockFailure: Exception? = null
        var messageGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        var createGate: CompletableDeferred<Unit>? = null
        var reading = WaterReading(1, 2, "order-1")
        var waterReads = 0
        var holdEnd = false
        var createFailure = false
        var zeroConsumption = false
        var incompleteOrder = false
        var afterPayExpired = false
        var waterFailure = false
        var waterGate: CompletableDeferred<Unit>? = null
        override suspend fun sendCode(phone: String) {}
        override suspend fun login(phone: String, code: String): LifeAccount { loginCalls++; return LifeAccount(phone, "test-token") }
        override suspend fun device(serial: String, token: String): LifeDevice { deviceCalls++; return returnedDevice }
        override suspend fun selectAfterPay(token: String) {
            if (afterPayExpired) throw LifeDeviceException("fixture expired", true)
        }
        override suspend fun unlock(skuId: String, token: String): WaterUnlock {
            unlockCalls++
            unlockFailure?.let { throw it }
            if (lostUnlock) throw java.io.IOException("lost response")
            return WaterUnlock("message-1", "order-1")
        }
        override suspend fun message(messageId: String, token: String): WaterMessage? { messageGate?.await(); return WaterMessage("OK", "0") }
        override suspend fun unlockShower(skuId: String, token: String): WaterUnlock {
            showerUnlockCalls++
            if (lostUnlock) throw java.io.IOException("lost response")
            return WaterUnlock("message-1", "order-1")
        }
        override suspend fun shower(skuId: String, token: String): WaterReading {
            showerReads++; waterGate?.await()
            if (waterFailure) throw java.io.IOException("offline")
            return reading
        }
        override suspend fun endShower(goodsId: String, orderNo: String, token: String) {
            assertEquals(returnedDevice.id, goodsId); assertEquals("order-1", orderNo)
            showerStopCalls++; stopGate?.await()
            if (!holdEnd) reading = showerEndReading
        }
        override suspend fun water(skuId: String, token: String): WaterReading {
            waterReads++; waterGate?.await()
            if (waterFailure) throw java.io.IOException("offline")
            return reading
        }
        override suspend fun endWater(goodsId: String, token: String) { stopCalls++; stopGate?.await(); if (!holdEnd) reading = WaterReading(5, 1, "order-1", zeroConsumption) }
        override suspend fun createAfterPay(orderNo: String, token: String): String {
            afterPayOrders.add(orderNo)
            createCalls++
            createGate?.await()
            if (createFailure) throw java.io.IOException("lost create response")
            if (incompleteOrder) throw LifeDeviceException("设备响应信息不完整")
            return "id-1"
        }
        override suspend fun syncOrder(orderNo: String, token: String) { syncCalls++ }
    }
    private fun model(api: Api, store: Store): LifeDevicesViewModel = lifeDevicesViewModelFixture(
        ApplicationProvider.getApplicationContext<Application>(), api, store, dispatcher, { 100_000L }
    ).also { viewModels.put("model", it) }

    @Test fun restoredRouteRequeriesTheSelectedOutletWithoutUnlocking() = runTest(dispatcher) {
        val api = Api().apply { returnedDevice = device.copy(skus = listOf(LifeSku("first", "一号", true), LifeSku("saved", "二号", true))) }
        val store = Store(LifeAccount("13800000000", "test-token"))
        val before = model(api, store); runCurrent()
        val handle = SavedStateHandle(); val nav = AppNavigationViewModel(handle)
        nav.bind("campus", false, 1)
        nav.showLife(AppRoute.LifeDevice(deviceId = device.serial, account = before.restorationOwner, skuId = "saved"))
        val nextHandle = handle.recreated(); viewModels.clear()
        val after = model(api, store); runCurrent()
        val restoredNav = AppNavigationViewModel(nextHandle).apply { bind("campus", false, 1) }
        val route = restoredNav.backStack.last() as AppRoute.LifeDevice
        assertEquals(LifeRouteRestore.WaitingForNetwork, restoreLifeRoute(route, false, after))
        assertEquals(0, api.deviceCalls)
        assertEquals(LifeRouteRestore.Restored, restoreLifeRoute(route, true, after)); runCurrent()
        assertEquals("saved", after.state.value.selectedSkuId); assertEquals(1, api.deviceCalls)
        assertFalse(after.state.value.scanning)
        assertEquals(LifeRouteRestore.AccountChanged, restoreLifeRoute(route.copy(account = "other"), true, after))
        assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls); assertEquals(0, api.createCalls)
    }
    @Test fun restoredRouteSelectsTheMatchingWaterOrLaundrySession() = runTest(dispatcher) {
        val water = savedRunning()
        val laundry = LaundrySession(device.copy(category = "00"), "wash", "1", "2", LaundryPhase.Running, sessionId = "saved-laundry")
        val api = Api(); val store = Store(LifeAccount("13800000000", "test-token"), water).apply { savedLaundry = listOf(laundry) }
        model(api, store); runCurrent(); viewModels.clear()
        val after = model(api, store); runCurrent()
        val waterRoute = AppRoute.LifeDevice(sessionId = water.sessionId, account = after.restorationOwner)
        assertEquals(LifeRouteRestore.Restored, restoreLifeRoute(waterRoute, false, after))
        assertEquals(water.sessionId, after.state.value.water?.sessionId)
        val laundryRoute = waterRoute.copy(feature = LifeFeature.Laundry, sessionId = laundry.sessionId)
        assertEquals(LifeRouteRestore.Restored, restoreLifeRoute(laundryRoute, false, after))
        assertEquals(LifeFeature.Laundry, after.state.value.feature)
        assertEquals(laundry.sessionId, after.state.value.laundry?.sessionId)
        assertEquals(LifeRouteRestore.Missing, restoreLifeRoute(laundryRoute.copy(sessionId = "missing"), false, after))
        assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls); assertEquals(0, api.createCalls)
    }
    @Test fun explicitlyRejectedWaterStartClearsProgressAndAnotherClickReachesTheServer() = runTest(dispatcher) {
        val api = Api().apply { unlockFailure = LifeDeviceException("设备拒绝启动", businessRejected = true) }
        val store = Store(LifeAccount("13800000000", "test-token")); val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        assertNull(model.state.value.water); assertNull(store.savedSession)
        assertEquals("设备拒绝启动", model.state.value.error)
        api.unlockFailure = null; model.startWater(); runCurrent()
        assertEquals(2, api.unlockCalls); assertEquals(WaterPhase.Running, model.state.value.water?.phase)
    }
    @Test fun unknownWaterStartCanBeRemovedWithoutHiddenStorageOrReplayingTheCommand() = runTest(dispatcher) {
        val api = Api().apply { lostUnlock = true }; val store = Store(LifeAccount("13800000000", "test-token"))
        val model = model(api, store); prepare(model); model.startWater(); runCurrent()
        assertTrue(model.state.value.water!!.canDismissException)
        model.rescanWaterException(); runCurrent()
        assertTrue(model.state.value.scanning); assertTrue(model.state.value.waterSessions.isEmpty()); assertNull(store.savedSession)
        assertEquals(1, api.unlockCalls)
    }
    @Test fun staleWaterRejectionIsRefreshedBeforeStartingAndActiveCapabilitiesCanBeRefreshed() = runTest(dispatcher) {
        val api = Api().apply { returnedDevice = device.copy(error = "旧设备错误") }
        val model = model(api, Store(LifeAccount("13800000000", "test-token"))); prepare(model)
        api.returnedDevice = device.copy(supportsFinish = null)
        model.startWater(); runCurrent()
        assertEquals(1, api.unlockCalls); assertEquals(WaterPhase.Running, model.state.value.water?.phase)
        api.returnedDevice = device.copy(supportsFinish = true)
        model.refreshDevice(); runCurrent()
        assertEquals(true, model.state.value.water!!.device.supportsFinish)
        model.stopWater(); runCurrent(); assertEquals(1, api.stopCalls)
    }
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
    @Test fun connectingFromInlineNoticeStaysOnPaymentPageUntilScannerIsRequested() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store()); runCurrent()
        assertFalse(model.state.value.loginVisible)
        model.connectAccount()
        assertTrue(model.state.value.loginVisible)
        model.phoneChanged("13800000000"); model.codeChanged("123456"); model.login(); runCurrent()
        assertFalse(model.state.value.loginVisible)
        assertTrue(model.state.value.connectedPhone.isNotBlank())
        assertEquals(0, model.state.value.entryRequest); assertFalse(model.state.value.scanning)
        model.openScan(); runCurrent()
        assertEquals(1, model.state.value.entryRequest); assertTrue(model.state.value.scanning)
        assertEquals(0, api.deviceCalls)
    }

    @Test fun unifiedScannerDoesNotRequirePangguiLoginOrLoadADeviceBeforeRouting() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store()); runCurrent()
        model.openUnifiedScan(); runCurrent()
        assertTrue(model.state.value.scanning)
        assertFalse(model.state.value.loginVisible)
        assertEquals(1, model.state.value.entryRequest)
        assertEquals(0, api.loginCalls)
        assertEquals(0, api.deviceCalls)
    }

    private fun TestScope.prepare(model: LifeDevicesViewModel) {
        runCurrent(); model.open(LifeFeature.Water); model.pageVisible(true)
        model.scanned("https://h5.qiekj.com/skip?SN=867369079842630"); runCurrent()
    }

    @Test fun unifiedScanWaitsForRecoveryWithoutSelectingRecoveredWaterOrReplayingCommands() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply { waterGate = gate }
        val model = model(api, Store(LifeAccount("13800000000", "test-token"), savedRunning()))
        model.openUnifiedScan(); model.pageVisible(true)
        assertTrue(model.state.value.restoring); assertTrue(model.state.value.scanning)
        model.scanned(device.serial); runCurrent()
        assertEquals(device.serial, model.state.value.scannedSerial)
        assertFalse(model.state.value.scanning); assertFalse(model.state.value.loginVisible)
        assertEquals(0, api.deviceCalls)
        gate.complete(Unit); runCurrent()
        assertFalse(model.state.value.restoring); assertEquals(device, model.state.value.device)
        assertNull(model.state.value.water); assertEquals(1, model.state.value.waterSessions.size)
        assertEquals(1, api.deviceCalls); assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls)
        assertEquals(0, api.createCalls); assertEquals(0, api.syncCalls)
    }

    @Test fun unconnectedScanQueriesTheSameDeviceAfterLoginWithoutAnotherScan() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        model.openUnifiedScan(); model.pageVisible(true); model.scanned(device.serial); runCurrent()
        assertFalse(model.state.value.restoring); assertFalse(model.state.value.scanning)
        assertFalse(model.state.value.loginVisible); assertEquals(device.serial, model.state.value.scannedSerial)
        assertEquals(0, api.deviceCalls)
        model.connectAccount(clearPending = false)
        model.phoneChanged("13800000000"); model.codeChanged("123456"); model.login(); runCurrent()
        assertFalse(model.state.value.loginVisible); assertEquals(device, model.state.value.device)
        assertEquals(1, model.state.value.entryRequest); assertEquals(1, api.deviceCalls)
        assertEquals(0, api.unlockCalls); assertEquals(0, api.createCalls)
    }

    @Test fun expiredLoginAfterDeviceQueryRequeriesTheDeviceWithoutReplayingStart() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); assertEquals(1, api.deviceCalls)
        api.afterPayExpired = true; model.startWater(); runCurrent()
        assertTrue(model.state.value.connectedPhone.isBlank()); assertNull(model.state.value.water)
        assertTrue(model.state.value.loginVisible); assertEquals(0, api.unlockCalls)
        api.afterPayExpired = false; model.connectAccount(clearPending = false)
        model.phoneChanged("13800000000"); model.codeChanged("123456"); model.login(); runCurrent()
        assertFalse(model.state.value.loginVisible); assertEquals(device, model.state.value.device)
        assertEquals(3, api.deviceCalls); assertEquals(0, api.unlockCalls); assertEquals(0, api.createCalls)
    }

    @Test fun leavingOrRescanningDuringRecoveryCancelsThePendingDeviceQuery() = runTest(dispatcher) {
        for (rescan in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val api = Api().apply { waterGate = gate }
            val model = model(api, Store(LifeAccount("13800000000", "test-token"), savedRunning()))
            model.openUnifiedScan(); model.pageVisible(true); model.scanned(device.serial); runCurrent()
            if (rescan) model.scan() else model.pageVisible(false)
            gate.complete(Unit); runCurrent()
            assertEquals(0, api.deviceCalls); assertNull(model.state.value.device)
            assertEquals(1, model.state.value.waterSessions.size)
            if (rescan) { assertTrue(model.state.value.scanning); assertNull(model.state.value.water) }
            assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls)
        }
    }

    private fun showerApi() = Api().apply { returnedDevice = device.copy(category = "08", name = "淋浴设备") }

    @Test fun showerStartsOnceThenPollsSharedWaterAndWaitsForEndAcknowledgment() = runTest(dispatcher) {
        val api = showerApi(); val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); api.messageGate = CompletableDeferred()
        model.startWater(); model.startWater(); runCurrent()
        assertEquals(1, api.showerUnlockCalls); assertEquals(0, api.unlockCalls); assertEquals(0, api.showerReads)
        api.messageGate!!.complete(Unit); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.water?.phase); assertEquals(1, api.showerReads)
        api.stopGate = CompletableDeferred(); model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Stopping, model.state.value.water?.phase)
        assertEquals(1, api.showerStopCalls); assertEquals(0, api.stopCalls); assertTrue(api.waterReads > 0)
        model.stopWater(); runCurrent(); assertEquals(1, api.showerStopCalls)
        api.stopGate!!.complete(Unit); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase); assertTrue(model.state.value.water!!.finalized)
        assertEquals(0, api.createCalls); assertEquals(0, api.syncCalls)
    }

    @Test fun showerStatusThreeSettlesPositiveConsumptionForOriginalOrder() = runTest(dispatcher) {
        val api = showerApi().apply { showerEndReading = WaterReading(3, null, "order-1", positiveConsumption = true) }
        val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent(); model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase); assertTrue(model.state.value.water!!.finalized)
        assertEquals(listOf("order-1"), api.afterPayOrders); assertEquals(1, api.syncCalls)
        model.retryWater(); runCurrent(); assertEquals(1, api.createCalls); assertEquals(1, api.syncCalls)
    }

    @Test fun showerLostUnlockCannotBeReplayedOrAdoptDeviceOrder() = runTest(dispatcher) {
        val api = showerApi().apply { lostUnlock = true }
        val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent(); model.retryWater(); model.startWater(); runCurrent()
        assertEquals(1, api.showerUnlockCalls); assertEquals(WaterPhase.Starting, model.state.value.water?.phase)
        assertEquals(0, api.showerReads); assertEquals(0, api.showerStopCalls); assertEquals(0, api.createCalls)
    }

    @Test fun showerLostEndResponseKeepsSessionAvailableAndRetryDoesNotSendCommand() = runTest(dispatcher) {
        val api = showerApi(); val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent()
        api.stopGate = CompletableDeferred(); model.stopWater(); runCurrent()
        api.stopGate!!.completeExceptionally(java.io.IOException("lost response")); runCurrent()
        assertEquals(WaterPhase.Stopping, model.state.value.water?.phase)
        assertTrue(model.state.value.waterSessions.single().active); assertNotNull(model.state.value.error)
        model.retryWater(); runCurrent()
        assertEquals(WaterPhase.Running, model.state.value.water?.phase)
        assertEquals(1, api.showerStopCalls); assertEquals(1, api.showerUnlockCalls)
    }

    @Test fun showerForeignOrderAtStopIsRemovedWithoutSendingEndOrPayment() = runTest(dispatcher) {
        val api = showerApi(); val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent()
        api.reading = WaterReading(3, 1, "other-order", positiveConsumption = true)
        model.stopWater(); runCurrent()
        assertTrue(model.state.value.waterSessions.isEmpty()); assertNull(model.state.value.water)
        assertEquals(0, api.showerStopCalls); assertEquals(0, api.createCalls)
    }

    @Test fun showerFaultDoesNotBecomeCompletedOrCreatePayment() = runTest(dispatcher) {
        val api = showerApi().apply { reading = WaterReading(6, 1, "order-1", positiveConsumption = true) }
        val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.water?.phase)
        assertEquals("设备异常，请更换设备使用", model.state.value.error)
        assertEquals(0, api.createCalls); assertEquals(0, api.showerStopCalls)
        assertTrue(model.state.value.water!!.canDismissException)
        model.rescanWaterException(); runCurrent(); assertTrue(model.state.value.waterSessions.isEmpty())
    }

    @Test fun showerRestartVerifiesRunningOrderWithoutReplayingCommands() = runTest(dispatcher) {
        val api = showerApi()
        val saved = WaterSession(api.returnedDevice, "33", WaterPhase.Stopping, startSent = true,
            orderNo = "order-1", startedAtMillis = 90_000, sessionId = "saved-shower")
        val model = model(api, Store(LifeAccount("13800000000", "test-token"), saved)); runCurrent()
        assertEquals(saved.copy(phase = WaterPhase.Running), model.state.value.waterSessions.single())
        assertEquals(1, api.showerReads); assertEquals(0, api.showerUnlockCalls); assertEquals(0, api.showerStopCalls)
        model.environment(true); runCurrent(); assertTrue(api.waterReads > 0); model.environment(false)
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

    @Test fun waterPollsEveryOneAndAHalfSecondsDuringUseAndEndConfirmation() = runTest(dispatcher) {
        val api = Api().apply { holdEnd = true }
        val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent(); model.environment(true); runCurrent()
        val runningReads = api.waterReads
        advanceTimeBy(1499); runCurrent(); assertEquals(runningReads, api.waterReads)
        advanceTimeBy(1); runCurrent(); assertEquals(runningReads + 1, api.waterReads)
        model.stopWater(); runCurrent()
        val stoppingReads = api.waterReads
        assertEquals(WaterPhase.Stopping, model.state.value.water?.phase)
        advanceTimeBy(1499); runCurrent(); assertEquals(stoppingReads, api.waterReads)
        advanceTimeBy(1); runCurrent(); assertEquals(stoppingReads + 1, api.waterReads)
        api.reading = WaterReading(5, 1, "order-1", zeroConsumption = true)
        advanceTimeBy(1499); runCurrent(); assertEquals(stoppingReads + 1, api.waterReads)
        advanceTimeBy(1); runCurrent(); assertEquals(stoppingReads + 2, api.waterReads)
        assertEquals(WaterPhase.Ended, model.state.value.water?.phase)
        assertEquals(1, api.stopCalls); assertEquals(0, api.createCalls); assertEquals(0, api.syncCalls)
        model.environment(false)
    }

    @Test fun mismatchedDeviceOrderDuringStartCannotBecomeRunningOrEnded() = runTest(dispatcher) {
        val api = Api().apply { reading = WaterReading(5, 1, "different-order") }
        val model = model(api, Store(LifeAccount("13800000000", "test-token")))
        prepare(model); model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.water?.phase)
        assertNotNull(model.state.value.error)
        assertEquals(0, api.createCalls); assertEquals(0, api.stopCalls)
    }

    private fun savedRunning(phase: WaterPhase = WaterPhase.Running) = WaterSession(device, "33", phase,
        startSent = true, orderNo = "order-1", startedAtMillis = 90_000L, sessionId = "saved-water")

    @Test fun restartShowsOnlyVerifiedRunningOrderAndKeepsLaundryDuringVerification() = runTest(dispatcher) {
        val api = Api().apply { waterGate = CompletableDeferred() }
        val store = Store(LifeAccount("13800000000", "test-token"), savedRunning(WaterPhase.Stopping))
        val washing = LaundrySession(device.copy(category = "00"), "wash", "1", "2", LaundryPhase.Running)
        store.savedLaundry = listOf(washing)
        val model = model(api, store); runCurrent()
        assertTrue(model.state.value.waterSessions.isEmpty())
        assertEquals(listOf(washing), model.state.value.laundrySessions)
        api.waterGate!!.complete(Unit); runCurrent()
        assertEquals(listOf(savedRunning()), model.state.value.waterSessions)
        assertEquals(listOf(washing), store.savedLaundry)
        assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls)
        assertEquals(0, api.createCalls); assertEquals(0, api.syncCalls)
    }

    @Test fun restartDropsConfirmedCompletedAndForeignOrdersButUnknownCreationIsDismissible() = runTest(dispatcher) {
        val cases = listOf(
            savedRunning(WaterPhase.Ended) to WaterReading(1, 2, "order-1"),
            savedRunning() to WaterReading(5, 1, "order-1"),
            savedRunning() to WaterReading(1, 2, "another-order"),
            savedRunning().copy(orderNo = "") to WaterReading(1, 2, "order-1"),
        )
        for ((session, reading) in cases) {
            val api = Api().apply { this.reading = reading }
            val store = Store(LifeAccount("13800000000", "test-token"), session)
            val model = model(api, store); runCurrent()
            if (session.orderNo.isBlank()) {
                val restored = model.state.value.waterSessions.single()
                assertTrue(restored.canDismissException)
                model.dismissSession(LifeFeature.Water, restored.sessionId); runCurrent()
            }
            assertTrue(model.state.value.waterSessions.isEmpty()); assertNull(store.savedSession)
            assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls)
            assertEquals(0, api.createCalls); assertEquals(0, api.syncCalls)
        }
    }

    @Test fun restartRetainsKnownOrdersWhenStatusIsUnknownAndLaterQueriesRecoverThem() = runTest(dispatcher) {
        for (reading in listOf(WaterReading(1, 2, ""), WaterReading(null, null, "order-1"))) {
            val api = Api().apply { this.reading = reading }
            val store = Store(LifeAccount("13800000000", "test-token"), savedRunning())
            val model = model(api, store); runCurrent()
            assertEquals(listOf(savedRunning()), model.state.value.waterSessions); assertNotNull(store.savedSession)
            model.environment(true); runCurrent()
            val reads = api.waterReads
            api.reading = WaterReading(1, 2, "order-1")
            advanceTimeBy(1500); runCurrent(); assertTrue(api.waterReads > reads)
            model.environment(false); model.environment(true); runCurrent()
            assertEquals(1, model.state.value.waterSessions.size); model.environment(false)
            val restarted = model(api, store); runCurrent(); restarted.environment(true); runCurrent()
            assertEquals(1, restarted.state.value.waterSessions.size); assertTrue(api.waterReads > reads)
            restarted.environment(false)
        }
    }
    @Test fun unknownStatusWithKnownWaterOrderCanBeDeletedWithoutSendingStop() = runTest(dispatcher) {
        for (shower in listOf(false, true)) {
            val session = savedRunning().let { if (shower) it.copy(device = it.device.copy(category = "08")) else it }
            val api = Api().apply { reading = WaterReading(null, null, "order-1") }
            val store = Store(LifeAccount("13800000000", "test-token"), session)
            val model = model(api, store); runCurrent()
            assertTrue(model.state.value.sessionErrors.containsKey(session.sessionId))
            if (shower) assertTrue(api.showerReads > 0)
            api.reading = WaterReading(1, 2, "order-1"); api.waterGate = CompletableDeferred()
            model.environment(true); runCurrent()
            model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
            api.waterGate!!.complete(Unit); runCurrent()
            assertNull(store.savedSession); assertTrue(model.state.value.waterSessions.isEmpty())
            assertEquals(0, api.stopCalls); assertEquals(0, api.showerStopCalls); assertEquals(0, api.createCalls)
            val restarted = model(api, store); runCurrent()
            assertTrue(restarted.state.value.waterSessions.isEmpty())
            model.environment(false)
        }
    }

    @Test fun verifiedSessionSurvivesNetworkFailureAndNormalBackgroundReturn() = runTest(dispatcher) {
        val api = Api()
        val store = Store(LifeAccount("13800000000", "test-token"), savedRunning())
        val model = model(api, store); runCurrent()
        assertEquals(listOf(savedRunning()), model.state.value.waterSessions)
        api.waterFailure = true
        model.environment(true); runCurrent(); model.environment(false)
        val reads = api.waterReads; advanceTimeBy(10_000); runCurrent(); assertEquals(reads, api.waterReads)
        assertEquals(listOf(savedRunning()), model.state.value.waterSessions)
        api.waterFailure = false
        model.environment(true); runCurrent()
        assertEquals(listOf(savedRunning()), model.state.value.waterSessions)
        model.pageVisible(false); assertEquals(listOf(savedRunning()), model.state.value.waterSessions)
        api.reading = WaterReading(5, 1, "order-1", true)
        advanceTimeBy(1500); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.waterSessions.single().phase)
        model.environment(false)
    }

    @Test fun stopRechecksTheOrderAndCannotStopALaterUsersWater() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(LifeAccount("13800000000", "test-token"), savedRunning()))
        runCurrent(); api.reading = WaterReading(1, 2, "another-order")
        model.stopWater(); runCurrent()
        assertEquals(0, api.stopCalls); assertTrue(model.state.value.waterSessions.isEmpty())
        assertEquals(0, api.createCalls); assertEquals(0, api.syncCalls)
    }
    @Test fun failedRecoveryKeepsKnownProgressAccessibleWithoutTakingOverTheScanner() = runTest(dispatcher) {
        val api = Api().apply { waterFailure = true }
        val store = Store(LifeAccount("13800000000", "test-token"), savedRunning())
        val model = model(api, store); runCurrent()
        model.openUnifiedScan(); model.environment(true); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(model.state.value.water)
        api.waterFailure = false; advanceTimeBy(1500); runCurrent()
        assertEquals(1, model.state.value.waterSessions.size); assertNotNull(store.savedSession)
        assertTrue(model.state.value.scanning); assertNull(model.state.value.water); assertNull(model.state.value.device)
        val reads = api.waterReads
        assertEquals(0, api.unlockCalls); assertEquals(0, api.stopCalls); model.environment(false)
        val restarted = model(api, store); runCurrent()
        assertEquals(1, restarted.state.value.waterSessions.size); assertTrue(api.waterReads > reads)
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

    @Test fun missingAfterPayFieldsKeepConfirmedEndDismissibleAcrossRestart() = runTest(dispatcher) {
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
        val session = model.state.value.water!!
        model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
        assertTrue(model.state.value.waterSessions.isEmpty())
        assertNull(store.savedSession)
        val restored = model(api, store); runCurrent()
        restored.environment(true); runCurrent(); advanceTimeBy(3000); runCurrent()
        assertTrue(restored.state.value.waterSessions.isEmpty())
        assertEquals(1, api.createCalls)
        assertEquals(0, api.syncCalls)
    }
    @Test fun dismissedEndedWaterCannotResumeAfterDelayedOrderResponse() = runTest(dispatcher) {
        val api = Api().apply { createGate = CompletableDeferred() }
        val store = Store(LifeAccount("13800000000", "test-token"))
        val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        model.stopWater(); runCurrent()
        val session = model.state.value.water!!
        assertEquals(WaterPhase.Ended, session.phase)
        assertFalse(session.finalized)
        model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
        api.createGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.waterSessions.isEmpty())
        assertNull(model.state.value.water)
        assertNull(store.savedSession)
        assertEquals(1, api.createCalls)
        assertEquals(0, api.syncCalls)
    }
    @Test fun scanEntryPreservesWaterAndSameDeviceOpensFreshFlowUntilProgressIsExplicitlySelected() = runTest(dispatcher) {
        val api = Api(); val store = Store(LifeAccount("13800000000", "test-token")); val model = model(api, store)
        prepare(model); model.startWater(); runCurrent()
        val session = model.state.value.water!!
        model.openScan(); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(model.state.value.water)
        assertEquals(session, store.savedSession)
        model.environment(true); runCurrent(); advanceTimeBy(1500); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(model.state.value.device)
        model.scanned(device.serial); runCurrent()
        assertFalse(model.state.value.scanning); assertNull(model.state.value.water)
        assertEquals(device, model.state.value.device); assertEquals(1, api.unlockCalls)
        model.openSession(LifeFeature.Water, session.sessionId); runCurrent()
        assertEquals(session.sessionId, model.state.value.water?.sessionId)
        model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
        assertEquals(1, model.state.value.waterSessions.size)
        model.stopWater(); runCurrent()
        assertEquals(WaterPhase.Ended, store.savedSession?.phase)
        model.openScan(); runCurrent()
        assertEquals(1, model.state.value.waterSessions.size)
        model.dismissSession(LifeFeature.Water, session.sessionId); runCurrent()
        assertTrue(model.state.value.waterSessions.isEmpty()); assertNull(store.savedSession)
        model.environment(false); runCurrent()
    }
}
