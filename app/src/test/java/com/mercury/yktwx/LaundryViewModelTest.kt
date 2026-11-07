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
        var waters = listOfNotNull(water)
        var laundries = listOfNotNull(washing)
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
        override fun waterSessions() = waters
        override fun laundrySessions() = laundries
        override fun saveWaterSessions(sessions: List<WaterSession>) { waters = sessions; water = sessions.lastOrNull() }
        override fun saveLaundrySessions(sessions: List<LaundrySession>) { laundries = sessions; washing = sessions.lastOrNull() }
    }
    private inner class Api : LifeDeviceService, LifeLaundryService {
        var creates = 0; var balancePays = 0; var alipayPrepares = 0; var details = 0; var paidReads = 0
        var unionPayPrepares = 0
        val preparedPayments = mutableListOf<Pair<String, String>>()
        val paidQueries = mutableListOf<Pair<String, String>>()
        var status = 0; var paid = false; var started = false; var price = "0.00"; var readSku = "clean"
        var lostCreate = false; var lostPayment = false
        var waterReads = 0; var waterReading: WaterReading? = null; var waterFails = false
        var scannedWaterDevice: LifeDevice? = null
        var readyGate: CompletableDeferred<Unit>? = null
        var readyError: String? = null
        var prepayGate: CompletableDeferred<Unit>? = null
        var prepayError = false
        var paidError = false
        var readyChecks = 0
        var deviceReads = 0
        var deviceGate: CompletableDeferred<Unit>? = null
        var quoteGate: CompletableDeferred<Unit>? = null
        var quoteFailure: Exception? = null
        val quotedSkus = mutableListOf<String>()
        var createdAt: Long? = null
        val cancelledOrderIds = mutableListOf<String>()
        var cancelGate: CompletableDeferred<Unit>? = null
        var cancelFailure: Exception? = null
        val balance = LifePayChannel("2", "余额支付", true, false)
        val alipay = LifePayChannel("13", "支付宝", true, false)
        val unionPay = LifePayChannel("52", "银联支付", true, false)
        var channels = listOf(balance, alipay, unionPay)
        val devices = mutableMapOf<String, LifeDevice>()
        val orders = mutableMapOf<String, Pair<String, String>>()
        val readings = mutableMapOf<String, LaundryReading>()
        val readOrders = mutableListOf<String>()
        var detailGate: CompletableDeferred<Unit>? = null
        val orderStatuses = mutableMapOf<String, Int>()
        val paidOrders = mutableSetOf<String>()
        override suspend fun sendCode(phone: String) {}
        override suspend fun login(phone: String, code: String) = LifeAccount(phone, "test-token")
        override suspend fun device(serial: String, token: String): LifeDevice = scannedWaterDevice ?: error("No water endpoint")
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
        override suspend fun laundryDevice(code: String, token: String): LifeDevice {
            deviceReads++; deviceGate?.await()
            return devices[code] ?: device
        }
        override suspend fun laundryQuote(device: LifeDevice, skuId: String, token: String): LaundryQuote {
            quotedSkus += skuId
            quoteGate?.let { withContext(NonCancellable) { it.await() } }
            quoteFailure?.let { throw it }
            return LaundryQuote(device.id, skuId, price, 5, channels)
        }
        override suspend fun checkLaundryDevice(deviceId: String, token: String): String {
            readyChecks++; readyGate?.await()
            readyError?.let { throw LifeDeviceException(it) }
            return "msg-1"
        }
        override suspend fun laundryMachineReady(messageId: String, token: String) = true
        override suspend fun createLaundryOrder(deviceId: String, skuId: String, token: String): LaundryOrder {
            creates++; if (lostCreate) throw java.io.IOException("lost create response")
            val order = LaundryOrder("wash-$creates", if (creates == 1) "wash-id" else "wash-id-$creates")
            orders[order.orderNo] = deviceId to skuId
            orderStatuses[order.orderNo] = 0
            return order
        }
        override suspend fun cancelLaundryOrder(orderId: String, token: String) {
            cancelledOrderIds += orderId
            cancelGate?.await()
            cancelFailure?.let { throw it }
            status = 1
        }
        override suspend fun laundryOrder(orderNo: String, token: String): LaundryReading {
            details++
            readOrders += orderNo
            detailGate?.await()
            readings[orderNo]?.let { return it }
            val orderStatus = if (orders.size > 1) orderStatuses.getValue(orderNo) else status
            return LaundryReading(orderNo, if (orderNo == "wash-1") "wash-id" else "wash-id-${orderNo.substringAfterLast('-')}",
                orders[orderNo]?.first ?: device.id, readSku, orderStatus, price, "桶自洁", 5,
                100_000L.takeIf { orderStatus in setOf(2, 3) }, 400_000L.takeIf { orderStatus == 2 }, 521_000L.takeIf { orderStatus == 3 }, createdAt)
        }
        override suspend fun laundryChannels(session: LaundrySession, token: String) = channels
        override suspend fun payLaundryBalance(orderNo: String, token: String) {
            balancePays++; paid = true; started = true; status = 2
            paidOrders += orderNo; orderStatuses[orderNo] = 2
            if (lostPayment) throw java.io.IOException("lost payment response")
        }
        override suspend fun prepareLaundryPayment(orderNo: String, method: String, token: String): String {
            preparedPayments += orderNo to method
            when (method) {
                "13" -> alipayPrepares++
                "52" -> unionPayPrepares++
                else -> error("Unsupported payment")
            }
            prepayGate?.await()
            if (prepayError) throw java.io.IOException("prepay unavailable")
            return if (method == "52") "202611071234567890123" else "test-signed-payload"
        }
        override suspend fun laundryPaid(orderNo: String, method: String, token: String): Boolean {
            paidReads++
            paidQueries += orderNo to method
            if (paidError) throw java.io.IOException("payment query unavailable")
            return if (orders.size > 1) orderNo in paidOrders else paid
        }
        override suspend fun laundryStarted(orderNo: String, token: String) = if (orders.size > 1) orderStatuses[orderNo] == 2 else started
    }
    private fun model(api: Api, store: Store, now: () -> Long = { 100_000L }) = LifeDevicesViewModel(ApplicationProvider.getApplicationContext<Application>(), api, store,
        dispatcher, now).also { models.put("model", it) }
    private fun TestScope.prepare(model: LifeDevicesViewModel) {
        runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent()
        assertNull(model.state.value.laundryQuote)
        model.showLaundryPayment(); runCurrent()
        assertNotNull(model.state.value.laundryQuote)
    }
    @Test fun scanningAndSelectingProgramsDoNotQuoteUntilThePaymentDrawerIsOpened() = runTest(dispatcher) {
        val api = Api().apply { devices[device.serial] = device.copy(skus = device.skus + LifeSku("quick", "快洗", true, "2.50")) }
        val model = model(api, Store()); runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent()
        model.selectSku("quick"); model.selectSku("quick"); runCurrent()
        assertTrue(api.quotedSkus.isEmpty()); assertNull(model.state.value.laundryQuote)
        assertFalse(model.state.value.quoteLoading); assertEquals("quick", model.state.value.selectedSkuId)
        api.quoteGate = CompletableDeferred()
        model.showLaundryPayment()
        assertTrue(model.state.value.paymentVisible); assertTrue(model.state.value.quoteLoading)
        model.payLaundry("2"); model.showLaundryPayment(); runCurrent()
        assertEquals(listOf("quick"), api.quotedSkus); assertEquals(0, api.creates); assertEquals(0, api.balancePays)
        api.quoteGate!!.complete(Unit); runCurrent()
        assertEquals("quick", model.state.value.laundryQuote?.skuId)
        assertFalse(model.state.value.quoteLoading); assertEquals(0, api.creates)
    }
    @Test fun failedPaymentQuoteCanBeRetriedInsideTheDrawerWithoutCreatingAnOrder() = runTest(dispatcher) {
        val api = Api().apply { quoteFailure = java.io.IOException("fixture offline") }
        val model = model(api, Store()); runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent(); model.showLaundryPayment(); runCurrent()
        assertTrue(model.state.value.paymentVisible); assertNotNull(model.state.value.error)
        assertNull(model.state.value.laundryQuote); assertFalse(model.state.value.quoteLoading)
        api.quoteFailure = null; model.showLaundryPayment(); runCurrent()
        assertNotNull(model.state.value.laundryQuote); assertNull(model.state.value.error)
        assertEquals(2, api.quotedSkus.size); assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
    @Test fun dismissingTheDrawerDiscardsALateQuoteAfterAnotherProgramIsSelected() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = Api().apply {
            quoteGate = gate
            devices[device.serial] = device.copy(skus = device.skus + LifeSku("quick", "快洗", true, "2.50"))
        }
        val model = model(api, Store()); runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent(); model.showLaundryPayment(); runCurrent()
        model.dismissLaundryPayment(); model.selectSku("quick")
        api.quoteGate = null; model.showLaundryPayment(); runCurrent()
        assertEquals("quick", model.state.value.laundryQuote?.skuId)
        gate.complete(Unit); runCurrent()
        assertEquals("quick", model.state.value.laundryQuote?.skuId)
        assertNull(model.state.value.error); assertFalse(model.state.value.quoteLoading)
        assertEquals(listOf("clean", "quick"), api.quotedSkus)
    }
    @Test fun blockedDeviceRetryReadsAgainAndRedisplaysUnchangedError() = runTest(dispatcher) {
        val message = "设备工作中，请更换设备使用"
        val api = Api().apply { devices[device.serial] = device.copy(error = message) }
        val model = model(api, Store())
        runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent()
        assertEquals(message, model.state.value.error)
        assertNull(model.state.value.laundryQuote)
        api.deviceGate = CompletableDeferred()
        model.retryLaundry(); runCurrent()
        assertTrue(model.state.value.loadingDevice)
        assertNull(model.state.value.error)
        model.retryLaundry(); runCurrent()
        assertEquals(2, api.deviceReads)
        api.deviceGate!!.complete(Unit); runCurrent()
        assertFalse(model.state.value.loadingDevice)
        assertEquals(message, model.state.value.error)
        model.retryLaundry(); runCurrent()
        assertEquals(3, api.deviceReads)
        assertEquals(message, model.state.value.error)
        assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
    @Test fun blockedDeviceRetryDefersQuoteUntilPaymentWhenTheDeviceBecomesAvailable() = runTest(dispatcher) {
        val api = Api().apply { devices[device.serial] = device.copy(error = "设备工作中，请更换设备使用") }
        val model = model(api, Store())
        runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent()
        api.devices[device.serial] = device
        model.retryLaundry(); runCurrent()
        assertEquals(2, api.deviceReads)
        assertNull(model.state.value.error)
        assertNull(model.state.value.laundryQuote); assertTrue(api.quotedSkus.isEmpty())
        assertFalse(model.state.value.loadingDevice); assertFalse(model.state.value.quoteLoading)
        model.showLaundryPayment(); runCurrent(); assertNotNull(model.state.value.laundryQuote)
        assertEquals(0, api.creates)
    }
    @Test fun orderDisplayUsesLatestServerNamesAndRetainsThemWhenLaterFieldsAreAbsent() = runTest(dispatcher) {
        val api = Api(); val store = Store(); val model = model(api, store)
        prepare(model); model.showLaundryPayment(); model.payLaundry("2"); runCurrent()
        assertEquals(api.channels, store.washing!!.device.channels)
        val reading = api.laundryOrder("wash-1", "test-token").copy(
            deviceName = "服务端设备名", shopName = "服务端商户名", program = "服务端程序名",
            programFeature = "服务端程序说明", payTypeName = "余额")
        api.readings[reading.orderNo] = reading
        model.retryLaundry(); runCurrent()
        val updated = checkNotNull(model.state.value.laundry)
        assertEquals("服务端设备名", updated.device.name)
        assertEquals("服务端商户名", updated.device.shopName)
        assertEquals("服务端程序名", updated.program)
        assertEquals("服务端程序说明", updated.device.skus.single().feature)
        assertEquals("余额", updated.payTypeName)
        assertEquals(updated, store.washing)
        api.readings[reading.orderNo] = reading.copy(deviceName = "", shopName = "", program = "", programFeature = "", payTypeName = "")
        model.retryLaundry(); runCurrent()
        assertEquals(updated, model.state.value.laundry)
        assertEquals(1, api.creates); assertEquals(1, api.balancePays)
    }
    @Test fun leavingBlockedDeviceRetryDiscardsItsLateResult() = runTest(dispatcher) {
        val api = Api().apply { devices[device.serial] = device.copy(error = "设备工作中，请更换设备使用") }
        val model = model(api, Store())
        runCurrent(); model.open(LifeFeature.Laundry); model.pageVisible(true)
        model.scanned(device.serial); runCurrent()
        api.deviceGate = CompletableDeferred()
        model.retryLaundry(); runCurrent()
        model.scan(); api.deviceGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.scanning)
        assertNull(model.state.value.device); assertNull(model.state.value.error)
        assertFalse(model.state.value.loadingDevice)
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
        assertTrue(model.state.value.scanning); assertEquals(LaundryPhase.Ended, store.washing?.phase)
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
        assertTrue(model.state.value.scanning)
        model.scanned(device.serial); runCurrent()
        assertFalse(model.state.value.scanning); assertNull(model.state.value.laundry)
        assertNull(model.state.value.laundryQuote)
        assertEquals(LaundryPhase.Creating, model.state.value.laundrySessions.single().phase)
        assertEquals(1, api.creates)
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
    @Test fun unionPayCallbackQueriesServerAndRetriesOnlyTheSameOrder() = runTest(dispatcher) {
        val api = Api().apply { channels = listOf(unionPay); price = "3.50" }
        val store = Store(); val model = model(api, store)
        prepare(model); model.showLaundryPayment(); model.payLaundry("52"); runCurrent()
        val first = checkNotNull(model.state.value.paymentLaunch)
        assertEquals("52", first.method); assertEquals("202611071234567890123", first.payload)
        model.launchLaundryPayment(first.id) { error("UnionPay must not enter Alipay") }; runCurrent()
        assertTrue(model.claimLaundryPayment(first.id)); assertFalse(model.claimLaundryPayment(first.id))
        model.payLaundry("52"); runCurrent()
        assertEquals(1, api.unionPayPrepares)
        model.laundryPaymentReturned(first.id, "CANCEL"); runCurrent()
        assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
        assertEquals("支付已取消，可继续支付同一订单", model.state.value.paymentFeedback?.detail)
        assertEquals(first.orderNo to "52", api.paidQueries.last())
        model.payLaundry("52"); runCurrent()
        val second = checkNotNull(model.state.value.paymentLaunch)
        assertEquals(first.orderNo, second.orderNo)
        model.laundryPaymentReturned(first.id, "success"); runCurrent()
        assertEquals(second, model.state.value.paymentLaunch)
        model.laundryPaymentReturned(second.id, "SUCCESS"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertFalse(model.state.value.laundry!!.paid)
        assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
        assertEquals(1, api.creates); assertEquals(2, api.unionPayPrepares)
        assertEquals(0, api.alipayPrepares); assertEquals(0, api.balancePays)
        assertTrue(api.preparedPayments.all { it == first.orderNo to "52" })
        api.status = 2; model.retryLaundry(); runCurrent()
        assertEquals(LaundryPhase.Running, store.washing?.phase)
        assertEquals(1, api.creates); assertEquals(2, api.unionPayPrepares)
    }
    @Test fun unionPayCancelCannotOverrideBackendPaymentAndDeviceStartupIsSeparate() = runTest(dispatcher) {
        val api = Api().apply { channels = listOf(unionPay) }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("52"); runCurrent()
        api.paid = true
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "cancel"); runCurrent()
        assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
        assertEquals(LaundryPhase.Starting, model.state.value.laundry?.phase)
        api.started = true; advanceTimeBy(1000); runCurrent()
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        assertEquals(1, api.creates); assertEquals(1, api.unionPayPrepares)
    }
    @Test fun missingUnionPayCallbackResultUsesBoundedReadOnlyPolling() = runTest(dispatcher) {
        val api = Api().apply { channels = listOf(unionPay) }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("52"); runCurrent()
        val reads = api.paidReads
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, null); runCurrent()
        assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(reads + 3, api.paidReads)
        assertFalse(model.state.value.laundry!!.paid)
        assertNull(model.state.value.paymentLaunch)
        assertEquals(1, api.creates); assertEquals(1, api.unionPayPrepares)
    }
    @Test fun restoredUnionPayAttemptRejectsPreviousProcessCallbackForTheSameOrder() = runTest(dispatcher) {
        val api = Api().apply { channels = listOf(unionPay) }; val store = Store()
        val firstModel = model(api, store)
        prepare(firstModel); firstModel.payLaundry("52"); runCurrent()
        val first = checkNotNull(firstModel.state.value.paymentLaunch)
        val restored = model(api, store)
        runCurrent(); restored.open(LifeFeature.Laundry); restored.showLaundryPayment(); runCurrent()
        restored.payLaundry("52"); runCurrent()
        val next = checkNotNull(restored.state.value.paymentLaunch)
        assertEquals(first.orderNo, next.orderNo); assertNotEquals(first.id, next.id)
        restored.laundryPaymentReturned(first.id, "success"); runCurrent()
        assertEquals(next, restored.state.value.paymentLaunch)
        assertFalse(restored.state.value.laundry!!.paid)
        assertEquals(1, api.creates); assertEquals(2, api.unionPayPrepares)
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
    @Test fun failedInitialDeviceCheckKeepsTheFormAndRetryCreatesAndLaunchesOnce() = runTest(dispatcher) {
        val message = "设备工作中，请更换设备使用"
        val api = Api().apply { readyError = message }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        val sessionId = model.state.value.laundry!!.sessionId
        assertTrue(model.state.value.paymentVisible)
        assertEquals(RechargePhase.Creating, model.state.value.paymentFeedback?.phase)
        assertEquals(message, model.state.value.error)
        assertEquals(0, api.creates); assertEquals(0, api.alipayPrepares)
        model.dismissLaundryPayment(); model.showLaundryPayment(); runCurrent()
        assertEquals(message, model.state.value.error)
        api.readyError = null
        model.payLaundry("13"); model.payLaundry("13"); runCurrent()
        assertEquals(sessionId, model.state.value.laundry?.sessionId)
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares)
        assertNotNull(model.state.value.paymentLaunch); assertNull(model.state.value.error)
        assertEquals(RechargePhase.AwaitingPayment, model.state.value.paymentFeedback?.phase)
        assertTrue(model.state.value.paymentVisible)
    }
    @Test fun occupiedExistingOrderErrorSurvivesPollingAndReopeningUntilADeviceCheckSucceeds() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "6001"); runCurrent()
        api.readyError = "设备工作中，请更换设备使用"
        model.payLaundry("13"); runCurrent()
        assertEquals(api.readyError, model.state.value.error)
        assertNull(model.state.value.paymentLaunch); assertEquals(1, api.alipayPrepares)
        model.environment(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(api.readyError, model.state.value.error)
        model.dismissLaundryPayment(); model.showLaundryPayment(); runCurrent()
        assertEquals(api.readyError, model.state.value.error)
        api.readyError = null
        model.payLaundry("13"); runCurrent()
        assertNull(model.state.value.error); assertNotNull(model.state.value.paymentLaunch)
        assertEquals(1, api.creates); assertEquals(2, api.alipayPrepares)
        model.environment(false)
    }
    @Test fun preparationAndUnconsumedLaunchIgnoreRepeatedClicksAndStaleReturns() = runTest(dispatcher) {
        val api = Api().apply { prepayGate = CompletableDeferred() }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        assertTrue(model.state.value.laundryBusy); assertTrue(model.state.value.paymentVisible)
        assertEquals(RechargePhase.Creating, model.state.value.paymentFeedback?.phase)
        model.showLaundryPayment(); model.payLaundry("13"); model.payLaundry("2"); runCurrent()
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares); assertEquals(0, api.balancePays)
        api.prepayGate!!.complete(Unit); runCurrent()
        val first = model.state.value.paymentLaunch!!
        model.showLaundryPayment(); model.payLaundry("13"); model.openScan(); runCurrent()
        assertEquals(first, model.state.value.paymentLaunch)
        assertEquals(1, api.alipayPrepares); assertFalse(model.state.value.scanning)
        assertTrue(model.claimLaundryPayment(first.id)); assertFalse(model.claimLaundryPayment(first.id))
        model.laundryPaymentReturned(first.id, "6001"); runCurrent()
        assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
        model.payLaundry("13"); runCurrent()
        val second = model.state.value.paymentLaunch!!
        model.laundryPaymentReturned(first.id, "9000"); runCurrent()
        assertEquals(second, model.state.value.paymentLaunch)
        assertEquals(1, api.creates); assertEquals(2, api.alipayPrepares)
    }
    @Test fun prepayFailureKeepsFormAndSignedPayloadIsLaunchedOnTheNextSingleRetry() = runTest(dispatcher) {
        val api = Api().apply { prepayError = true }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        assertTrue(model.state.value.paymentVisible); assertNull(model.state.value.paymentLaunch)
        assertEquals(RechargePhase.Creating, model.state.value.paymentFeedback?.phase)
        assertNotNull(model.state.value.error)
        api.prepayError = false
        model.payLaundry("13"); runCurrent()
        assertEquals("test-signed-payload", model.state.value.paymentLaunch?.payload)
        assertEquals(1, api.creates); assertEquals(2, api.alipayPrepares)
    }
    @Test fun missingSdkResultGivesImmediateFailureAndNeverClaimsPaymentSucceeded() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, null); runCurrent()
        assertFalse(model.state.value.laundryBusy)
        assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
        assertEquals("支付宝未完成支付，可继续支付同一订单", model.state.value.paymentFeedback?.detail)
        assertFalse(model.state.value.laundry!!.paid)
    }
    @Test fun sdkSuccessUsesSharedThreeQueryRoundAndDoesNotInventPaymentTimeout() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        val reads = api.paidReads
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "9000"); runCurrent()
        assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        assertTrue(model.state.value.paymentFeedback!!.loading)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(reads + 3, api.paidReads)
        assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
        assertFalse(model.state.value.laundry!!.paid)
        assertFalse(model.state.value.laundryBusy)
    }
    @Test fun queryFailureOffersOnlyReadRetryAndBackendRunningStatusMeansPaid() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        api.paidError = true
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "9000"); runCurrent()
        assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        assertFalse(model.state.value.paymentFeedback!!.loading)
        assertNotNull(model.state.value.paymentFeedback?.detail)
        api.paidError = false; api.status = 2
        model.checkLaundryPayment(); runCurrent()
        assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        assertTrue(model.state.value.paymentVisible)
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares); assertEquals(0, api.balancePays)
    }
    @Test fun dismissingFeedbackDoesNotLoseProgressOrReopenForLateConfirmation() = runTest(dispatcher) {
        val api = Api(); val store = Store(); val model = model(api, store)
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        val request = model.state.value.paymentLaunch!!
        model.dismissLaundryPayment()
        assertNull(model.state.value.paymentFeedback); assertNotNull(model.state.value.paymentLaunch)
        api.status = 2
        model.laundryPaymentReturned(request.id, "6001"); runCurrent()
        assertEquals(LaundryPhase.Running, store.washing?.phase)
        assertTrue(store.washing!!.paid)
        assertFalse(model.state.value.paymentVisible); assertNull(model.state.value.paymentFeedback)
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares)
    }
    @Test fun resultRetryReadsExistingOrderChannelsBeforePreparingPaymentAgain() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "6001"); runCurrent()
        api.channels = listOf(api.alipay.copy(available = false, remark = "支付渠道暂不可用"))
        model.payLaundry("13"); runCurrent()
        assertEquals("支付渠道暂不可用", model.state.value.error)
        assertEquals(api.channels, model.state.value.laundryQuote?.channels)
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares)
        assertNull(model.state.value.paymentLaunch)
    }
    @Test fun paymentCountdownUsesServerCreationAcrossRetryCloseAndRestore() = runTest(dispatcher) {
        var clock = 100_000L
        val api = Api().apply { createdAt = clock }; val store = Store(); val model = model(api, store) { clock }
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        assertEquals(300L, model.state.value.laundry?.paymentRemainingSeconds(clock))
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "6001"); runCurrent()
        clock += 60_000
        model.dismissLaundryPayment(); model.showLaundryPayment(); runCurrent(); model.payLaundry("13"); runCurrent()
        assertEquals(240L, model.state.value.laundry?.paymentRemainingSeconds(clock))
        assertEquals(100_000L, store.washing?.createdAtMillis)
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "6001"); runCurrent()
        val restored = model(api, store) { clock }; runCurrent(); restored.open(LifeFeature.Laundry)
        assertEquals(240L, restored.state.value.laundry?.paymentRemainingSeconds(clock))
        assertEquals(1, api.creates); assertEquals(2, api.alipayPrepares)
    }
    @Test fun localDeadlineKeepsUnpaidOrderAndAllowsOnlyResultQueries() = runTest(dispatcher) {
        var clock = 100_000L
        val api = Api().apply { createdAt = clock }; val model = model(api, Store()) { clock }
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "6001"); runCurrent()
        clock = 400_000L
        model.payLaundry("13"); runCurrent()
        assertEquals(0L, model.state.value.laundry?.paymentRemainingSeconds(clock))
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        assertEquals(LaundryPaymentExpiredMessage, model.state.value.paymentFeedback?.detail)
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares)
        api.status = 2
        model.checkLaundryPayment(); runCurrent()
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        assertEquals(RechargePhase.Paid, model.state.value.paymentFeedback?.phase)
        assertNull(model.state.value.laundry?.paymentRemainingSeconds(clock))
    }
    @Test fun latePrepayPayloadCannotLaunchAfterTheFiveMinuteDeadline() = runTest(dispatcher) {
        var clock = 100_000L
        val api = Api().apply { createdAt = clock; prepayGate = CompletableDeferred() }
        val model = model(api, Store()) { clock }
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        clock = 400_000L
        api.prepayGate!!.complete(Unit); runCurrent()
        assertNull(model.state.value.paymentLaunch)
        assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        assertEquals(1, api.creates); assertEquals(1, api.alipayPrepares)
    }
    @Test fun queuedSdkLaunchCannotBeClaimedAfterDeadline() = runTest(dispatcher) {
        var clock = 100_000L
        val api = Api().apply { createdAt = clock }; val model = model(api, Store()) { clock }
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        val launch = model.state.value.paymentLaunch!!
        clock = 400_000L
        assertFalse(model.claimLaundryPayment(launch.id))
        assertNull(model.state.value.paymentLaunch)
        assertEquals(RechargePhase.Checking, model.state.value.paymentFeedback?.phase)
        assertFalse(model.state.value.laundry!!.paid)
    }
    @Test fun serverClosedOrderStopsPollingCanBeDismissedAndCannotPayOrReappear() = runTest(dispatcher) {
        val session = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash-1", orderId = "wash-id", createdAtMillis = 100_000L)
        val api = Api().apply { status = 1; createdAt = 100_000L }
        val store = Store(session); val model = model(api, store) { 450_000L }
        runCurrent(); model.open(LifeFeature.Laundry); model.environment(true); runCurrent()
        assertEquals(LaundryPhase.Closed, model.state.value.laundry?.phase)
        assertFalse(model.state.value.laundry!!.paid); assertFalse(model.state.value.laundry!!.active)
        assertNull(model.state.value.error)
        val reads = api.details; advanceTimeBy(20_000); runCurrent(); assertEquals(reads, api.details)
        model.showLaundryPayment(); runCurrent()
        assertEquals(RechargePhase.TimedOut, model.state.value.paymentFeedback?.phase)
        model.payLaundry("13"); model.retryLaundry(); runCurrent()
        assertEquals(0, api.creates); assertEquals(0, api.alipayPrepares); assertEquals(0, api.balancePays)
        model.dismissSession(LifeFeature.Laundry, session.sessionId); runCurrent()
        assertTrue(store.laundries.isEmpty()); assertNull(model.state.value.laundry)
        model.environment(false); model.environment(true); runCurrent()
        assertTrue(model.state.value.laundrySessions.isEmpty())
    }
    @Test fun serverClosureBeforeLocalDeadlineIsStillFinalAndNotMistakenForSuccess() = runTest(dispatcher) {
        val api = Api().apply { createdAt = 100_000L }; val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        api.status = 1
        model.laundryPaymentReturned(model.state.value.paymentLaunch!!.id, "9000"); runCurrent()
        assertEquals(LaundryPhase.Closed, model.state.value.laundry?.phase)
        assertFalse(model.state.value.laundry!!.paid)
        assertEquals(RechargePhase.Failed, model.state.value.paymentFeedback?.phase)
        assertEquals("订单已关闭", model.state.value.paymentFeedback?.detail)
    }
    @Test fun cancellationUsesCurrentOrderIdOnceAndKeepsClosedRecordUntilManualRemoval() = runTest(dispatcher) {
        val pending = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash-1", orderId = "wash-id", createdAtMillis = 100_000L)
        val other = pending.copy(sessionId = "other-session", orderNo = "wash-2", orderId = "wash-id-2",
            phase = LaundryPhase.Ended, paid = true)
        val api = Api().apply { cancelGate = CompletableDeferred() }
        val store = Store(pending).apply { laundries = listOf(pending, other) }
        val model = model(api, store)
        runCurrent(); model.openSession(LifeFeature.Laundry, pending.sessionId)
        model.cancelLaundry(); model.cancelLaundry(); runCurrent()
        assertEquals(listOf("wash-id"), api.cancelledOrderIds)
        assertTrue(model.state.value.laundryBusy)
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        model.dismissSession(LifeFeature.Laundry, pending.sessionId)
        assertEquals(2, model.state.value.laundrySessions.size)
        api.cancelGate!!.complete(Unit); runCurrent()
        assertEquals(LaundryPhase.Closed, model.state.value.laundry?.phase)
        assertFalse(model.state.value.laundry!!.paid)
        assertFalse(model.state.value.laundry!!.canCancel)
        assertEquals(other, store.laundries.last())
        val reads = api.details
        model.environment(true); advanceTimeBy(20_000); runCurrent()
        model.cancelLaundry(); runCurrent()
        assertEquals(reads, api.details); assertEquals(1, api.cancelledOrderIds.size)
        model.dismissSession(LifeFeature.Laundry, pending.sessionId); runCurrent()
        assertEquals(listOf(other), store.laundries)
    }
    @Test fun cancellationRechecksServerClosureAndPaymentBeforeSendingAnyCancelCommand() = runTest(dispatcher) {
        val pending = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash-1", orderId = "wash-id")
        for ((status, phase) in listOf(1 to LaundryPhase.Closed, 2 to LaundryPhase.Running, 3 to LaundryPhase.Ended,
            0 to LaundryPhase.Running)) {
            val api = Api().apply { this.status = status; paid = status == 0; started = true }
            val model = model(api, Store(pending))
            runCurrent(); model.open(LifeFeature.Laundry); model.cancelLaundry(); runCurrent()
            assertEquals(phase, model.state.value.laundry?.phase)
            assertTrue(api.cancelledOrderIds.isEmpty())
            assertEquals(0, api.creates); assertEquals(0, api.balancePays); assertEquals(0, api.alipayPrepares)
        }
    }
    @Test fun cancellationIsUnavailableForPaidMissingOrNonPendingOrders() = runTest(dispatcher) {
        val pending = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash-1", orderId = "wash-id")
        val sessions = LaundryPhase.entries.filter { it != LaundryPhase.Paying }.map { pending.copy(phase = it) } +
            listOf(pending.copy(paid = true), pending.copy(orderId = ""), pending.copy(orderNo = ""))
        for (session in sessions) {
            val api = Api(); val model = model(api, Store(session))
            runCurrent(); model.open(LifeFeature.Laundry); model.cancelLaundry(); runCurrent()
            assertEquals(session.phase, model.state.value.laundry?.phase)
            assertEquals(0, api.details); assertTrue(api.cancelledOrderIds.isEmpty())
        }
    }
    @Test fun cancellationRejectionKeepsOrderAndRetryFirstConfirmsWhetherItAlreadyClosed() = runTest(dispatcher) {
        val pending = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash-1", orderId = "wash-id")
        val api = Api().apply { cancelFailure = LifeDeviceException("订单暂不可取消") }
        val store = Store(pending); val model = model(api, store)
        runCurrent(); model.open(LifeFeature.Laundry); model.cancelLaundry(); runCurrent()
        assertEquals(LaundryPhase.Paying, store.washing?.phase)
        assertEquals("订单暂不可取消", model.state.value.error)
        assertEquals(1, api.cancelledOrderIds.size)
        api.status = 1; api.cancelFailure = null
        model.cancelLaundry(); runCurrent()
        assertEquals(LaundryPhase.Closed, store.washing?.phase)
        assertNull(model.state.value.error)
        assertEquals(1, api.cancelledOrderIds.size)
    }
    @Test fun cancellationDoesNotProceedWhenPaymentQueryFailsOrOrderIdentityDoesNotMatch() = runTest(dispatcher) {
        val pending = LaundrySession(device, "clean", "0.00", "13", LaundryPhase.Paying,
            orderNo = "wash-1", orderId = "wash-id")
        val api = Api().apply { paidError = true }; val model = model(api, Store(pending))
        runCurrent(); model.open(LifeFeature.Laundry); model.cancelLaundry(); runCurrent()
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        assertNotNull(model.state.value.error); assertTrue(api.cancelledOrderIds.isEmpty())
        api.paidError = false; api.readSku = "another-program"
        model.cancelLaundry(); runCurrent()
        assertEquals(LaundryPhase.Paying, model.state.value.laundry?.phase)
        assertTrue(api.cancelledOrderIds.isEmpty())
    }
    @Test fun cancellationCannotCompeteWithQueuedOrClaimedAlipayLaunch() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store())
        prepare(model); model.showLaundryPayment(); model.payLaundry("13"); runCurrent()
        val launch = model.state.value.paymentLaunch!!
        model.cancelLaundry(); runCurrent(); assertTrue(api.cancelledOrderIds.isEmpty())
        assertTrue(model.claimLaundryPayment(launch.id))
        model.cancelLaundry(); runCurrent(); assertTrue(api.cancelledOrderIds.isEmpty())
        assertEquals(launch, model.state.value.paymentLaunch)
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
        val api = Api().apply { status = 2; waterReading = WaterReading(1, 2, "water-1") }
        val model = model(api, Store(washing, water))
        runCurrent(); api.waterFails = true; model.open(LifeFeature.Laundry); model.environment(true); runCurrent()
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
    @Test fun scanKeepsMultipleOrdersAndPollingDoesNotReplaceTheSelectedOrderOrScanner() = runTest(dispatcher) {
        val api = Api()
        val secondDevice = device.copy(id = "78", name = "78", serial = "00000000-0000-4000-8000-000000000002")
        api.devices[secondDevice.serial] = secondDevice
        val store = Store(); val model = model(api, store)
        prepare(model); model.showLaundryPayment(); model.payLaundry("2"); runCurrent()
        val first = model.state.value.laundry!!
        model.openScan(); runCurrent()
        assertTrue(model.state.value.scanning); assertNull(model.state.value.laundry)
        assertEquals(listOf(first), store.laundries)
        model.scanned(secondDevice.serial); runCurrent()
        model.showLaundryPayment(); runCurrent(); model.payLaundry("2"); runCurrent()
        val second = model.state.value.laundry!!
        assertEquals(2, api.creates); assertEquals(2, api.balancePays); assertEquals(2, store.laundries.size)
        assertNotEquals(first.sessionId, second.sessionId)
        model.openSession(LifeFeature.Laundry, first.sessionId); runCurrent()
        model.environment(true); runCurrent()
        assertEquals(first.sessionId, model.state.value.laundry?.sessionId)
        assertTrue(api.readOrders.containsAll(listOf(first.orderNo, second.orderNo)))
        model.openScan(); runCurrent()
        val reads = api.details
        advanceTimeBy(5000); runCurrent()
        assertTrue(api.details >= reads + 2)
        assertTrue(model.state.value.scanning); assertNull(model.state.value.device); assertNull(model.state.value.laundry)
        model.scanned(secondDevice.serial); runCurrent()
        assertFalse(model.state.value.scanning)
        assertNull(model.state.value.laundry); assertNull(model.state.value.laundryQuote)
        assertEquals(secondDevice, model.state.value.device)
        assertEquals(2, api.creates)
        model.environment(false); runCurrent()
    }
    @Test fun rescanEveryLaundryPhaseOpensNormalDeviceFlowAndOfficialBusyErrorInsteadOfOldProgress() = runTest(dispatcher) {
        for (phase in LaundryPhase.entries) {
            val previous = LaundrySession(device, "clean", "0.00", "13", phase,
                orderNo = "wash-1", orderId = "wash-id", createdAtMillis = 1L)
            val api = Api().apply { readyError = "设备工作中，请更换设备使用" }
            val store = Store(previous); val model = model(api, store)
            runCurrent(); model.openScan(); model.pageVisible(true); model.scanned(device.serial); runCurrent()
            assertNull("Rescan must not select $phase history", model.state.value.laundry)
            assertNull(model.state.value.laundryQuote); assertEquals(listOf(previous), store.laundries)
            model.showLaundryPayment(); runCurrent(); model.payLaundry("13"); runCurrent()
            assertEquals("设备工作中，请更换设备使用", model.state.value.error)
            assertNotEquals(previous.sessionId, model.state.value.laundry?.sessionId)
            assertEquals(0, api.creates); assertEquals(0, api.alipayPrepares)
            model.openSession(LifeFeature.Laundry, previous.sessionId)
            assertEquals(previous.sessionId, model.state.value.laundry?.sessionId)
        }
    }
    @Test fun serverTimingAndIndependentCompletionSurviveRestoreUntilManualDismissal() = runTest(dispatcher) {
        val first = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Running,
            true, "wash-1", "wash-id", true, true, 5, 100_000, 400_000)
        val secondDevice = device.copy(id = "78", name = "78")
        val second = first.copy(device = secondDevice, orderNo = "wash-2", orderId = "wash-id-2", sessionId = "second-session")
        val store = Store().apply { laundries = listOf(first, second) }
        val api = Api().apply {
            readings[first.orderNo] = LaundryReading(first.orderNo, first.orderId, device.id, "clean", 3, "0.00", "桶自洁",
                5, 100_000, null, 521_000)
            readings[second.orderNo] = LaundryReading(second.orderNo, second.orderId, secondDevice.id, "clean", 2, "0.00", "桶自洁",
                5, 200_000, 500_000, null)
        }
        val model = model(api, store)
        runCurrent(); model.openSession(LifeFeature.Laundry, second.sessionId)
        model.environment(true); runCurrent(); advanceTimeBy(600_000); runCurrent()
        val finished = model.state.value.laundrySessions.first { it.sessionId == first.sessionId }
        assertEquals(LaundryPhase.Ended, finished.phase)
        assertEquals(400_000L, finished.expectedEndMillis); assertEquals(521_000L, finished.finishedAtMillis)
        assertEquals(LaundryPhase.Running, model.state.value.laundry?.phase)
        assertEquals(200_000L, model.state.value.laundry?.startedAtMillis)
        assertEquals(500_000L, model.state.value.laundry?.expectedEndMillis)
        model.dismissSession(LifeFeature.Laundry, second.sessionId); runCurrent()
        assertEquals(2, store.laundries.size)
        model.environment(false); runCurrent()
        val restored = model(api, store); runCurrent()
        assertEquals(listOf(finished, model.state.value.laundry), restored.state.value.laundrySessions)
        restored.openSession(LifeFeature.Laundry, first.sessionId)
        assertEquals(finished, restored.state.value.laundry)
        restored.dismissSession(LifeFeature.Laundry, first.sessionId); runCurrent()
        assertEquals(listOf(second.sessionId), store.laundries.map { it.sessionId })
        assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
    @Test fun anOrderReadFinishingAfterDismissalCannotRestoreTheCompletedCard() = runTest(dispatcher) {
        val finished = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Ended,
            orderNo = "wash-1", orderId = "wash-id", paid = true)
        val store = Store(finished)
        val api = Api().apply { status = 3; detailGate = CompletableDeferred() }
        val model = model(api, store)
        runCurrent(); model.openSession(LifeFeature.Laundry, finished.sessionId)
        model.retryLaundry(); runCurrent()
        assertTrue(model.state.value.laundryBusy)
        model.dismissSession(LifeFeature.Laundry, finished.sessionId); runCurrent()
        assertTrue(store.laundries.isEmpty())
        api.detailGate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.laundrySessions.isEmpty()); assertNull(model.state.value.laundry)
        assertTrue(store.laundries.isEmpty())
        assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
    @Test fun unifiedScannerRoutesEachCodeByTypeAndRejectsAmbiguousCodes() = runTest(dispatcher) {
        val waterDevice = device.copy(id = "water", category = "04", serial = "867369079842630", payment = "after",
            skus = listOf(LifeSku("water-sku", "饮水", true)))
        val api = Api().apply { scannedWaterDevice = waterDevice }
        val model = model(api, Store()); runCurrent()
        model.openScan(); model.pageVisible(true)
        assertEquals(LifeFeature.Water, model.state.value.feature)
        model.scanned(device.serial); runCurrent()
        assertEquals(LifeFeature.Laundry, model.state.value.feature)
        assertEquals(device, model.state.value.device); assertNull(model.state.value.laundryQuote)
        model.openScan(); runCurrent()
        model.scanned("https://h5.qiekj.com/skip?SN=${waterDevice.serial}&NQT=${device.serial}"); runCurrent()
        assertTrue(model.state.value.scanning); assertNotNull(model.state.value.error); assertNull(model.state.value.device)
        model.scanned("https://h5.qiekj.com/skip?SN=${waterDevice.serial}"); runCurrent()
        assertEquals(LifeFeature.Water, model.state.value.feature)
        assertEquals(waterDevice, model.state.value.device); assertNull(model.state.value.laundryQuote)
        assertEquals(0, api.creates); assertEquals(0, api.balancePays)
    }
}
