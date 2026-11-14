package com.mercury.yktwx

import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import com.mercury.yktwx.data.jsonObjectOf
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HellobikeCardsControllerTest {
    private val product = HellobikeCampusProduct("product", "校园月卡", "6.90", "官方说明", jsonObjectOf("businessType" to 1))
    private val purchase = HellobikeCardPurchase("校园月卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", fixtureBikeRegion)
    private inner class CardsApi : FakeHellobikeService() {
        var audit = HellobikeStudentAudit(2, 1)
        var productCalls = 0
        var preorders = 0
        var cardPays = 0
        var ordinaryCalls = 0
        var gate: CompletableDeferred<Unit>? = null
        var auditFailure: Exception? = null
        var cardPayFailure: Exception? = null
        val pages = mutableListOf<Pair<Int, Int>>()
        var myCards = emptyList<HellobikeUserCard>()
        override suspend fun studentAudit(account: HellobikeAccount): HellobikeStudentAudit {
            gate?.let { withContext(NonCancellable) { it.await() } }; auditFailure?.let { throw it }; return audit
        }
        override suspend fun userCards(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?, businessType: Int, page: Int): HellobikeUserCardPage {
            pages += businessType to page
            return HellobikeUserCardPage(if (businessType == 1) myCards else emptyList(), businessType == 1 && page == 0 && myCards.isNotEmpty())
        }
        override suspend fun campusProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCampusProducts {
            productCalls++; return HellobikeCampusProducts("校园卡", listOf(product))
        }
        override suspend fun ordinaryProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): List<HellobikeCampusProduct> {
            ordinaryCalls++; return listOf(product.copy(key = "ordinary", campus = false))
        }
        override suspend fun preOrderCard(account: HellobikeAccount, product: HellobikeCampusProduct, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCardPurchase { preorders++; return purchase }
        override suspend fun cardPayChannels(account: HellobikeAccount, purchase: HellobikeCardPurchase) = listOf(HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "4.90"))
        override suspend fun payCard(account: HellobikeAccount, purchase: HellobikeCardPurchase, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent {
            cardPays++; beforePay(); cardPayFailure?.let { throw it }
            return HellobikePayComponent("001", "", orders, payload = "fixture-payload")
        }
    }
    private fun TestScope.controller(api: CardsApi, store: HellobikeStore, owner: () -> HellobikeAccount? = { fixtureBikeAccount }) =
        HellobikeCardsController(backgroundScope, api, store, StandardTestDispatcher(testScheduler), owner, { fixtureBikeRegion }, FakeBikeLocation(), { true }, { _, _ -> }, { 1000L }).also {
            it.bind(store.load().cardPurchases[owner()?.userGuid]); it.environment(true); it.pageVisible(true)
        }
    @Test fun onlyApprovedStudentsSeeProductsButAllAccountsCanReadTheirCards() = runTest {
        for (audit in listOf(HellobikeStudentAudit(0, 1), HellobikeStudentAudit(1, 1), HellobikeStudentAudit(2, 2), HellobikeStudentAudit(3, 1))) {
            val api = CardsApi().also { it.audit = audit }
            val controller = controller(api, BikeRecords().store()); runCurrent()
            assertEquals(0, api.productCalls); assertTrue(controller.state.value.products.isEmpty())
            assertNull(controller.state.value.cardsError); assertEquals(listOf(1 to 0, 2 to 0), api.pages)
            controller.choose("product"); runCurrent(); assertEquals(0, api.preorders); controller.pageVisible(false)
        }
    }
    @Test fun ordinaryProductsLoadOnExpansionWithoutStudentApprovalAndRefreshOnResume() = runTest {
        val api = CardsApi().apply { audit = HellobikeStudentAudit(0, 1) }
        val controller = controller(api, BikeRecords().store()); runCurrent()
        assertEquals(0, api.ordinaryCalls)
        controller.expandOrdinary(); runCurrent()
        assertEquals(1, api.ordinaryCalls); assertTrue(controller.state.value.ordinaryLoaded)
        controller.choose("ordinary"); runCurrent(); assertEquals(1, api.preorders)
        controller.dismissPayment(); controller.environment(false); controller.environment(true); runCurrent()
        assertEquals(2, api.ordinaryCalls)
        controller.expandOrdinary(false); controller.expandOrdinary(true); runCurrent(); assertEquals(2, api.ordinaryCalls)
    }
    @Test fun auditFailureIsShownWithoutTurningAnEmptyCardListIntoAnError() = runTest {
        val api = CardsApi().also { it.auditFailure = HellobikeException("官方认证查询原因") }
        val controller = controller(api, BikeRecords().store()); runCurrent()
        assertEquals("官方认证查询原因", controller.state.value.auditError); assertNull(controller.state.value.cardsError)
        assertEquals(0, api.productCalls)
    }
    @Test fun discountedPaymentIsDurableBeforeSendingAndSdkSuccessStillNeedsOriginalOrderQuery() = runTest {
        val store = BikeRecords().store(); val api = CardsApi()
        val controller = controller(api, store); runCurrent(); controller.choose("product"); runCurrent()
        assertEquals(purchase, store.load().cardPurchases[fixtureBikeAccount.userGuid])
        api.beforePay = { assertEquals("4.90", store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.attempt!!.orders.single().amount) }
        controller.pay(); runCurrent()
        val launch = controller.state.value.paymentLaunch!!
        assertTrue(controller.claimPayment(launch.id)); assertFalse(controller.claimPayment(launch.id))
        controller.paymentReturned(launch.id, "9000"); runCurrent()
        assertFalse(store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.paid)
        assertEquals(1, api.cardPays); assertEquals(1, api.queryCalls)
        api.queryPaid = { it.guid == "pay-no" && it.orderId == "payment-id" && it.amount == "4.90" }
        controller.checkPayment(); runCurrent()
        assertTrue(controller.state.value.purchase!!.paid)
        assertFalse(store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.paid)
        assertEquals(RechargePhase.Paid, controller.state.value.feedback!!.phase)
        assertEquals(1, api.preorders); assertEquals(1, api.cardPays)
    }
    @Test fun recreationQueriesPersistedAttemptWithoutResendingOrderOrPayment() = runTest {
        val store = BikeRecords().store(); val api = CardsApi()
        store.saveCardPurchase(fixtureBikeAccount.userGuid, purchase.copy(attempt = HellobikePaymentAttempt(purchase.orders, "001", 1000)))
        val controller = controller(api, store); runCurrent()
        assertEquals(0, api.preorders); assertEquals(0, api.cardPays); assertEquals(1, api.queryCalls)
        controller.showPurchase(); runCurrent(); controller.pay(); runCurrent()
        assertEquals(0, api.cardPays); assertNull(controller.state.value.paymentLaunch)
    }
    @Test fun historicalLocalPaymentFlagsAreIgnoredAndRestorationAlwaysReadsCloud() = runTest {
        val store = BikeRecords().store(); val api = CardsApi()
        store.saveCardPurchase(fixtureBikeAccount.userGuid, purchase.copy(paid = true, paymentCancelled = true,
            attempt = HellobikePaymentAttempt(purchase.orders, "001", 1000)))
        val controller = controller(api, store); runCurrent()
        assertEquals(1, api.queryCalls); assertFalse(controller.state.value.purchase!!.paid)
        assertFalse(controller.state.value.purchase!!.paymentCancelled)
        assertEquals(RechargePhase.Checking, controller.state.value.feedback!!.phase)
        assertEquals(0, api.cardPays); assertEquals(0, api.preorders)
    }
    @Test fun accountChangeDiscardsLateResponsesAndKeepsPurchasesBoundToOriginalOwner() = runTest {
        val store = BikeRecords().store(); val api = CardsApi(); var owner = fixtureBikeAccount
        api.gate = CompletableDeferred()
        val controller = controller(api, store) { owner }; runCurrent()
        owner = fixtureBikeAccount.copy(userGuid = "other-owner")
        store.saveAccount(owner); controller.bind(null)
        api.gate!!.complete(Unit); runCurrent()
        assertEquals(1, api.productCalls); assertNull(controller.state.value.purchase)
        assertTrue(store.load().cardPurchases.isEmpty())
        controller.pageVisible(false)
    }
    @Test fun failedPaymentDispatchCanOnlyQueryTheSavedAttempt() = runTest {
        val store = BikeRecords().store(); val api = CardsApi().also { it.cardPayFailure = HellobikeException("官方付款原因") }
        val controller = controller(api, store); runCurrent(); controller.choose("product"); runCurrent(); controller.pay(); runCurrent()
        assertEquals("官方付款原因", controller.state.value.paymentError)
        assertNotNull(store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.attempt)
        controller.pay(); controller.choose("product"); runCurrent()
        assertEquals(1, api.preorders); assertEquals(1, api.cardPays)
    }
    @Test fun cancellingBeforeDispatchEndsTheCashierAndDoesNotLeaveAPurchaseBlock() = runTest {
        val store = BikeRecords().store(); val api = CardsApi()
        val controller = controller(api, store); runCurrent(); controller.choose("product"); runCurrent()
        controller.cancelPayment(); runCurrent()
        assertNull(controller.state.value.purchase); assertFalse(controller.state.value.paymentVisible)
        assertNull(store.load().cardPurchases[fixtureBikeAccount.userGuid]); assertEquals(0, api.cardPays)
        controller.choose("product"); runCurrent(); assertEquals(2, api.preorders)
    }
    @Test fun sdkCancellationCanContinueTheOriginalCloudOrderWithoutANewPreorder() = runTest {
        val store = BikeRecords().store(); val api = CardsApi()
        val controller = controller(api, store); runCurrent(); controller.choose("product"); runCurrent(); controller.pay(); runCurrent()
        val launch = controller.state.value.paymentLaunch!!; assertTrue(controller.claimPayment(launch.id))
        controller.paymentReturned(launch.id, "6001"); runCurrent()
        assertTrue(controller.state.value.purchase!!.paymentCancelled); assertFalse(controller.state.value.purchase!!.paid)
        assertEquals(RechargePhase.Failed, controller.state.value.feedback!!.phase)
        controller.continueCancelledPayment(); runCurrent()
        assertNull(controller.state.value.purchase!!.attempt); assertNull(controller.state.value.feedback)
        controller.pay(); runCurrent()
        assertEquals(1, api.preorders); assertEquals(2, api.cardPays)
        assertEquals("payment-id", controller.state.value.purchase!!.attempt!!.orders.single().orderId)
    }
    @Test fun cloudPaidOverridesSdkCancellationAndCloudQueryFailureCannotBeAbandonedOrRepaid() = runTest {
        for (paid in listOf(true, false)) {
            val store = BikeRecords().store(); val api = CardsApi()
            val controller = controller(api, store); runCurrent(); controller.choose("product"); runCurrent(); controller.pay(); runCurrent()
            val launch = controller.state.value.paymentLaunch!!; assertTrue(controller.claimPayment(launch.id))
            api.queryPaid = { if (paid) true else throw HellobikeException("查单网络失败") }
            controller.paymentReturned(launch.id, "6001"); runCurrent()
            if (paid) {
                assertTrue(controller.state.value.purchase!!.paid); assertFalse(controller.state.value.purchase!!.paymentCancelled)
                assertEquals(RechargePhase.Paid, controller.state.value.feedback!!.phase)
            } else {
                assertEquals(RechargePhase.Checking, controller.state.value.feedback!!.phase)
                controller.cancelPayment(); runCurrent()
                assertNotNull(store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.attempt)
                controller.choose("product"); controller.continueCancelledPayment(); controller.pay(); runCurrent()
                assertEquals(1, api.preorders); assertEquals(1, api.cardPays)
            }
            controller.pageVisible(false)
        }
    }
}
