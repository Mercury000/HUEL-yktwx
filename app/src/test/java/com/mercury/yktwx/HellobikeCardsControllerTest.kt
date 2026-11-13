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
        val ordinaryRequests = mutableListOf<Pair<HellobikeRegion?, HellobikePosition?>>()
        var ordinaryResponse: (suspend (HellobikeRegion?, HellobikePosition?) -> List<HellobikeCampusProduct>)? = null
        var gate: CompletableDeferred<Unit>? = null
        var auditFailure: Exception? = null
        var cardPayFailure: Exception? = null
        var orderedPurchase = purchase
        var channelOptions = listOf(HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "4.90"))
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
            ordinaryCalls++; ordinaryRequests += region to position
            return ordinaryResponse?.invoke(region, position) ?: listOf(product.copy(key = "ordinary", campus = false))
        }
        override suspend fun preOrderCard(account: HellobikeAccount, product: HellobikeCampusProduct, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCardPurchase { preorders++; return orderedPurchase }
        override suspend fun cardPayChannels(account: HellobikeAccount, purchase: HellobikeCardPurchase) = channelOptions
        override suspend fun payCard(account: HellobikeAccount, purchase: HellobikeCardPurchase, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent {
            cardPays++; beforePay(); cardPayFailure?.let { throw it }
            return HellobikePayComponent("001", "", orders, payload = "fixture-payload")
        }
    }
    private fun TestScope.controller(api: CardsApi, store: HellobikeStore,
        location: HellobikeLocationService = FakeBikeLocation(), requestLocationPermission: () -> Unit = {},
        owner: () -> HellobikeAccount? = { fixtureBikeAccount }) =
        HellobikeCardsController(backgroundScope, api, store, StandardTestDispatcher(testScheduler), owner, { fixtureBikeRegion }, location, { true }, { _, _ -> }, { 1000L }, requestLocationPermission).also {
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
    @Test fun expandingOrdinaryCardsWaitsForTheCurrentLocation() = runTest {
        val position = CompletableDeferred<HellobikePosition>()
        val location = object : HellobikeLocationService {
            override suspend fun current() = position.await()
        }
        val api = CardsApi()
        val controller = controller(api, BikeRecords().store(), location); runCurrent()
        controller.expandOrdinary(); runCurrent()
        assertEquals(0, api.ordinaryCalls); assertTrue(controller.state.value.ordinaryLoading)
        position.complete(fixtureBikePosition); runCurrent()
        assertEquals(listOf(fixtureBikeRegion to fixtureBikePosition), api.ordinaryRequests)
        assertTrue(controller.state.value.ordinaryLoaded); assertFalse(controller.state.value.ordinaryLoading)
    }
    @Test fun refreshingLocationDiscardsALateOrdinaryCardResponse() = runTest {
        val staleResponse = CompletableDeferred<Unit>()
        val location = FakeBikeLocation()
        val freshPosition = fixtureBikePosition.copy(lat = fixtureBikePosition.lat + 0.01)
        val freshProducts = (1..8).map { product.copy(key = "ordinary:$it", campus = false) }
        val api = CardsApi().apply {
            ordinaryResponse = { _, point ->
                if (point == fixtureBikePosition) {
                    withContext(NonCancellable) { staleResponse.await() }
                    listOf(product.copy(key = "stale-national-card", campus = false))
                } else freshProducts
            }
        }
        val controller = controller(api, BikeRecords().store(), location); runCurrent()
        controller.expandOrdinary(); runCurrent(); assertEquals(1, api.ordinaryCalls)
        location.point = freshPosition; controller.refresh(); runCurrent()
        assertEquals(2, api.ordinaryCalls); assertEquals(freshPosition, api.ordinaryRequests.last().second)
        assertEquals(freshProducts, controller.state.value.ordinaryProducts)
        staleResponse.complete(Unit); runCurrent()
        assertEquals(freshProducts, controller.state.value.ordinaryProducts)
        controller.expandOrdinary(false); controller.expandOrdinary(true); runCurrent()
        assertEquals(2, api.ordinaryCalls)
    }
    @Test fun cancelledCardRefreshCannotClearTheNextLocationRefresh() = runTest {
        val oldLocation = CompletableDeferred<HellobikePosition>()
        val newLocation = CompletableDeferred<HellobikePosition>()
        var calls = 0
        val location = object : HellobikeLocationService {
            override suspend fun current(): HellobikePosition = when (++calls) {
                1 -> fixtureBikePosition
                2 -> withContext(NonCancellable) { oldLocation.await() }
                else -> newLocation.await()
            }
        }
        val api = CardsApi()
        val controller = controller(api, BikeRecords().store(), location); runCurrent()
        controller.expandOrdinary(); runCurrent()
        controller.refresh(); runCurrent()
        controller.choose(product.key); runCurrent()
        controller.dismissPayment()
        controller.refresh(); runCurrent()
        oldLocation.complete(fixtureBikePosition); runCurrent()
        assertTrue("Old finally block must not finish the new refresh", controller.state.value.loading)
        assertTrue(controller.state.value.ordinaryLoading)
        controller.choose("ordinary"); runCurrent()
        assertEquals("An ordinary purchase must wait for the current location", 1, api.preorders)
        val point = fixtureBikePosition.copy(lat = fixtureBikePosition.lat + .02)
        newLocation.complete(point); runCurrent()
        assertFalse(controller.state.value.loading)
        assertEquals(point, api.ordinaryRequests.last().second)
    }
    @Test fun locationProgressEndsBeforeUnrelatedStudentAndCardQueries() = runTest {
        val position = CompletableDeferred<HellobikePosition>()
        val student = CompletableDeferred<Unit>()
        val api = CardsApi().apply { gate = student }
        val controller = controller(api, BikeRecords().store(), object : HellobikeLocationService {
            override suspend fun current() = position.await()
        })
        runCurrent()
        assertTrue(controller.state.value.ordinaryLocationLoading)
        position.complete(fixtureBikePosition); runCurrent()
        assertFalse("Student audit must not keep the location indicator active", controller.state.value.ordinaryLocationLoading)
        assertTrue(controller.state.value.loading)
        student.complete(Unit); runCurrent()
        assertFalse(controller.state.value.loading)
    }
    @Test fun missingLocationKeepsTheOfficialFallbackButOffersAuthorizationAndRefresh() = runTest {
        val location = FakeBikeLocation().apply { failure = HellobikeLocationPermissionRequired() }
        var permissionRequests = 0
        val api = CardsApi()
        val controller = controller(api, BikeRecords().store(), location, { permissionRequests++ }); runCurrent()
        controller.expandOrdinary(); runCurrent()
        assertNull(api.ordinaryRequests.single().second)
        assertTrue(controller.state.value.ordinaryLocationPermissionRequired)
        assertNotNull(controller.state.value.ordinaryLocationError)
        assertTrue(controller.state.value.ordinaryProducts.isNotEmpty())
        controller.requestOrdinaryLocation(); assertEquals(1, permissionRequests)
        location.failure = null; controller.refresh(); runCurrent()
        assertEquals(fixtureBikePosition, api.ordinaryRequests.last().second)
        assertNull(controller.state.value.ordinaryLocationError)
        assertFalse(controller.state.value.ordinaryLocationPermissionRequired)
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
        api.beforePay = {
            val attempt = store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.attempt!!
            assertEquals("6.90", attempt.orders.single().amount); assertEquals("4.90", attempt.displayAmount)
        }
        controller.pay(); runCurrent()
        val launch = controller.state.value.paymentLaunch!!
        assertTrue(controller.claimPayment(launch.id)); assertFalse(controller.claimPayment(launch.id))
        controller.paymentReturned(launch.id, "9000"); runCurrent()
        assertFalse(store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.paid)
        assertEquals(1, api.cardPays); assertEquals(1, api.queryCalls)
        api.queryPaid = { it.guid == "pay-no" && it.orderId == "payment-id" && it.amount == "6.90" }
        advanceTimeBy(3001); runCurrent()
        assertTrue(controller.state.value.purchase!!.paid)
        assertFalse(store.load().cardPurchases[fixtureBikeAccount.userGuid]!!.paid)
        assertEquals(RechargePhase.Paid, controller.state.value.feedback!!.phase)
        assertEquals(1, api.preorders); assertEquals(1, api.cardPays)
    }
    @Test fun multiOrderCashierUsesTheOfficialFirstOrderQueryAndKeepsTheSelectedOffer() = runTest {
        val api = CardsApi().apply {
            orderedPurchase = purchase.copy(orders = listOf(purchase.orders.single(),
                HellobikePayOrder("extra-order", "1.00", "extra-payment", "29")))
            channelOptions = listOf(
                HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "4.90", selectionId = "001:first"),
                HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "3.90", selectionId = "001:second"))
            queryPaid = { it.guid == "pay-no" }
        }
        val store = BikeRecords().store(); val controller = controller(api, store)
        runCurrent(); controller.choose("product"); runCurrent()
        controller.selectMethod("001:second"); controller.pay(); runCurrent()
        val attempt = controller.state.value.purchase!!.attempt!!
        assertEquals("001", attempt.channel); assertEquals("3.90", attempt.displayAmount)
        assertEquals(api.orderedPurchase.orders, attempt.orders)
        val launch = controller.state.value.paymentLaunch!!; assertTrue(controller.claimPayment(launch.id))
        controller.paymentReturned(launch.id, "9000"); runCurrent()
        assertEquals(listOf("pay-no"), api.queriedGuids); assertTrue(controller.state.value.purchase!!.paid)
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
        controller.paymentReturned(launch.id, "6001"); runCurrent(); advanceTimeBy(9001); runCurrent()
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
