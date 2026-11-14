package com.mercury.yktwx.data.life

import android.graphics.Bitmap
import com.mercury.yktwx.data.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HellobikeApiTest {
    private class Reply(url: URL, private val content: String, private val status: Int = 200) : HttpFixture(url) {
        val sent = ByteArrayOutputStream()
        var closed = false
        val json get() = parseObject(sent.toString("UTF-8"))
        override fun statusCode() = status
        override fun bodyStream() = ByteArrayInputStream(content.toByteArray())
        override fun requestBodyStream() = sent
        override fun close() { closed = true }
    }
    private fun api(body: String, calls: MutableList<Reply> = mutableListOf(), httpStatus: Int = 200) =
        HellobikeApi(fixtureHttp { url -> Reply(url, body, httpStatus).also(calls::add) }, { 1_000_000 }, { 50_000 })
    private suspend fun rejected(operation: suspend () -> Any?) {
        try { operation(); fail("A malformed or mismatched response cannot be accepted") } catch (_: HellobikeException) {}
    }
    @Test fun studentStatusAndMyCardsUseTheirOwnH5FieldsAndKeepEmptyCardsSuccessful() = runBlocking {
        val calls = mutableListOf<Reply>()
        val approved = api("""{"code":0,"data":{"authState":2,"userType":1,"stuName":"ignored"}}""", calls).studentAudit(fixtureBikeAccount)
        assertTrue(approved.studentApproved)
        assertFalse(HellobikeStudentAudit(2, 2).studentApproved)
        assertFalse(HellobikeStudentAudit(1, 1).studentApproved)
        assertEquals("63", calls.single().json.hbText("systemCode")); assertEquals("0", calls.single().json.hbText("userNewId"))
        val page = api("""{"code":0,"data":{"rideCards":[{"cardGuid":"owned-card","name":"任意骑行卡","remainDay":23,"status":0,"expireSecond":1796572799,"desc":"官方使用说明"}],"hasNext":true}}""", calls)
            .userCards(fixtureBikeAccount, fixtureBikeRegion, fixtureBikePosition, 1, 1)
        assertEquals(1796572799L, page.cards.single().expireSecond); assertEquals("生效中", page.cards.single().statusLabel); assertTrue(page.hasNext)
        assertEquals("1", calls.last().json.hbText("pageStart")); assertEquals("2", calls.last().json.hbText("pageSize"))
        assertEquals("4", calls.last().json.hbText("platform")); assertEquals("7.0.75", calls.last().json.hbText("version"))
        assertNull(calls.last().json.hbText("sourceMarkup"))
        assertTrue(api("""{"code":0,"data":{"rideCards":[],"hasNext":false}}""").userCards(fixtureBikeAccount, null, null, 2, 0).cards.isEmpty())
    }
    @Test fun campusOrderPreservesPromotionObjectsAndUsesPaymentIdInsteadOfBusinessOrderNo() = runBlocking {
        val calls = mutableListOf<Reply>()
        val product = api("""{"code":0,"data":{"moduleTitle":"校园卡","cardInfoList":[{"packages":[{"showName":"校园月卡","packageGuid":"dynamic-package","channelCode":101088,"businessType":1,"purchasePrice":6.9,"actualPrice":{"finalPrice":"6.9","promotion":"keep"},"pendingInfos":[{"key":"keep"}],"commodityDesc":"官方使用说明"}]}]}}""", calls)
            .campusProducts(fixtureBikeAccount, null, null).products.single()
        assertEquals("6.90", product.price); assertEquals("7.0.65", calls.single().json.hbText("version"))
        val purchase = api("""{"code":0,"data":{"businessType":28,"businessScene":2801,"totalAmount":6.9,"orderGoPayList":[{"businessType":28,"orderNo":"business-order","paymentId":"payment-id","payNo":"pay-no","payPrice":6.9}]}}""", calls)
            .preOrderCard(fixtureBikeAccount, product, fixtureBikeRegion, fixtureBikePosition)
        assertEquals("28", purchase.businessType); assertEquals("2801", purchase.scene)
        assertEquals("pay-no", purchase.orders.single().guid); assertEquals("payment-id", purchase.orders.single().orderId)
        val pkg = calls.last().json.objectOrNull("bizContent")!!.arrayOrNull("packageList")!!.single() as JsonObject
        assertEquals(product.raw["actualPrice"], pkg["actualPrice"]); assertEquals(product.raw["pendingInfos"], pkg["pendingInfos"])
        val options = api("""{"code":0,"data":{"channelList":[{"channelCode":"001","realAmount":4.9,"enableSelect":true,"marketingActivityId":"dynamic-promo","marketingType":"discount"}]}}""", calls).cardPayChannels(fixtureBikeAccount, purchase)
        assertEquals("4.90", options.single().amount)
        assertEquals("28", calls.last().json.hbText("businessType")); assertEquals("2801", calls.last().json.hbText("scene"))
        assertEquals("payment-id", calls.last().json.hbText("orderId")); assertEquals("pay-no", calls.last().json.hbText("waitPayGuid"))
        assertEquals("1.55.0", calls.last().json.hbText("currentSdkVersion")); assertEquals("Android", calls.last().json.hbText("currentSdkOS"))
        assertEquals("", (calls.last().json["amount"] as JsonPrimitive).content)
        assertEquals("0", calls.last().json.hbText("alipayInstallFlag")); assertNull(calls.last().json["from"])
        assertNull(calls.last().url.query)
        val paidOrders = purchase.orders.map { it.copy(amount = options.single().amount) }
        val component = api("""{"code":0,"data":{"channel":"001","credential":{"params":"fixture-payload"}}}""", calls).payCard(fixtureBikeAccount, purchase, paidOrders, options.single())
        assertEquals("28", calls.last().json.hbText("businessType")); assertEquals("6.90", calls.last().json.hbText("amount"))
        assertEquals("dynamic-promo", calls.last().json.hbText("marketingActivityId"))
        assertEquals("4.90", component.orders.single().amount)
        assertEquals("6.90", parseArray(calls.last().json.hbText("orders")!!).objectOrNull(0)!!.hbText("amount"))
        assertFalse((calls.last().json["amount"] as JsonPrimitive).isString)
        assertEquals("[]", calls.last().json.hbText("combinedWith"))
        assertEquals("zh-CN", calls.last().json.hbText("locale"))
        api("""{"code":0,"data":{"guid":"pay-no","businessId":"payment-id","amount":4.9,"payStatus":1}}""", calls)
            .cardPaymentPaid(fixtureBikeAccount, paidOrders.single(), JsonObject(emptyMap()))
        assertEquals("Android", calls.last().json.hbText("currentSdkOS")); assertNull(calls.last().json["from"])
        assertTrue(api("""{"code":0,"data":{"businessId":"business-order","amount":6.9,"payStatus":1}}""")
            .cardPaymentPaid(fixtureBikeAccount, paidOrders.single(), JsonObject(emptyMap())))
        assertFalse(api("""{"code":0,"data":{"guid":"pay-no","payStatus":"1"}}""").paymentPaid(fixtureBikeAccount, paidOrders.single(), JsonObject(emptyMap())))
        assertTrue(api("""{"code":0,"data":{"guid":"pay-no","businessId":"payment-id","amount":4.9,"payStatus":1}}""").paymentPaid(fixtureBikeAccount, paidOrders.single(), JsonObject(emptyMap())))
        rejected { api("""{"code":0,"data":{"guid":"other","payStatus":1}}""").paymentPaid(fixtureBikeAccount, paidOrders.single(), JsonObject(emptyMap())) }
    }
    @Test fun ordinaryCardsKeepPromotionsAndUseSubscriptionsSigningRoute() = runBlocking {
        val calls = mutableListOf<Reply>()
        val products = api("""{"code":0,"data":{"packageGroups":[{"channelCode":101000,"businessType":1,"canBuy":0,"packages":[{"showName":"普通90天卡","packageGuid":"ordinary-package","actualPrice":{"finalPrice":19.9,"promo":{"id":"keep"}},"pendingInfos":[{"id":"keep"}],"paymentLimit":0},{"showName":"连续包月","actualPrice":{"finalPrice":9.9},"paymentLimit":1}]}]}}""", calls)
            .ordinaryProducts(fixtureBikeAccount, null, null)
        assertTrue(products[0].purchasable); assertFalse(products[0].campus)
        assertTrue(products[1].purchasable); assertTrue(products[1].signingRequired)
        assertEquals(listOf(JsonPrimitive(101000)), calls.single().json.arrayOrNull("channelCodes"))
        assertEquals(true, calls.single().json.objectOrNull("filterParams")!!.hbBoolean("filterCoBranded"))
        api("""{"code":0,"data":{"businessType":28,"businessScene":2801,"totalAmount":19.9,"orderGoPayList":[{"payNo":"pay-no","paymentId":"payment-id","payPrice":19.9}]}}""", calls)
            .preOrderCard(fixtureBikeAccount, products[0], null, null)
        assertEquals(true, calls.last().json.hbBoolean("fromTwoWheel"))
        val pkg = calls.last().json.objectOrNull("bizContent")!!.arrayOrNull("packageList")!!.single() as JsonObject
        assertEquals(products[0].raw["actualPrice"], pkg["actualPrice"])
        assertEquals(products[0].raw["pendingInfos"], pkg["pendingInfos"])
        assertNull(pkg["payChannelDiscountInfo"])
        assertTrue(products[0].copy(raw = products[0].raw.withField("canBuy", 1)).purchasable)
        assertFalse(products[0].copy(raw = products[0].raw.withField("canBuy", 2)).purchasable)
    }
    @Test fun campusDoesNotInventDisabledButtonsOrAppLimitsAndSupportsBothVehicleTypes() = runBlocking {
        val product = HellobikeCampusProduct("campus", "校园助力车卡", "6.90", "", jsonObjectOf(
            "businessType" to 2, "channelCode" to 102088, "packageGuid" to "package",
            "actualPrice" to jsonObjectOf("finalPrice" to "6.90"), "appLimit" to true,
            "button" to jsonObjectOf("canClick" to false)))
        assertTrue(product.purchasable)
        val calls = mutableListOf<Reply>()
        api("""{"code":0,"data":{"businessType":28,"businessScene":2801,"totalAmount":6.9,"orderGoPayList":[{"payNo":"pay-no","paymentId":"payment-id","payPrice":6.9}]}}""", calls)
            .preOrderCard(fixtureBikeAccount, product, null, null)
        assertEquals(2, calls.single().json.objectOrNull("bizContent")!!.hbInt("businessType"))
        assertEquals(102088, calls.single().json.objectOrNull("bizContent")!!.hbInt("channelCode"))
    }
    @Test fun subscriptionPreservesCloudPeriodRulesAndSendsPayAndSignToAlipay() = runBlocking {
        val product = HellobikeCampusProduct("monthly", "连续包月", "9.90", "", jsonObjectOf(
            "businessType" to 1, "channelCode" to 101000, "packageGuid" to "package", "paymentLimit" to 1,
            "actualPrice" to jsonObjectOf("finalPrice" to "9.90")), campus = false)
        val calls = mutableListOf<Reply>()
        val purchase = api("""{"code":0,"data":{"businessType":28,"businessScene":2801,"totalAmount":9.9,"paySignType":1,"periodType":"MONTH","periodNum":1,"maxDeductAmount":"9.90","orderGoPayList":[{"payNo":"pay-no","paymentId":"payment-id","payPrice":9.9}]}}""", calls)
            .preOrderCard(fixtureBikeAccount, product, null, null)
        val period = parseObject(purchase.signingFields.hbText("periodRuleParams")!!)
        assertEquals("MONTH", period.hbText("period_type")); assertEquals("1", period.hbText("period")); assertEquals("9.90", period.hbText("single_amount"))
        val client = api("""{"code":0,"data":{"channel":"102","credential":{"params":"signed-pay-payload"}}}""", calls)
        val option = client.cardPayChannels(fixtureBikeAccount, purchase).single()
        assertEquals("102", option.channel.method); assertEquals("9.90", option.amount); assertEquals(1, calls.size)
        val component = client.payCard(fixtureBikeAccount, purchase, purchase.orders, option)
        val body = calls.last().json
        assertEquals("102", body.hbText("channel")); assertEquals("RidecardBuying", body.hbText("actionOrigin"))
        assertEquals("normal", body.hbText("creditModel")); assertEquals(purchase.signingFields["periodRuleParams"], body["periodRuleParams"])
        assertEquals("yktwx://hellobike-pay/paysign/success", body.hbText("returnUrl"))
        assertEquals("13", component.channel.hellobikeBridgeMethod()); assertEquals("signed-pay-payload", component.payload)
    }
    @Test fun optionalAgreementDoesNotBlockTheOrdinaryPaymentRoute() = runBlocking {
        val options = api("""{"code":0,"data":{"channelList":[{"channelCode":"001","realAmount":2,"enableSelect":false,"supportAgreement":true,"signState":false,"agreementChannelCode":"102","agreementButtonModel":2,"agreementActionInfo":{"payGuideSign":true}}]}}""")
            .payChannels(fixtureBikeAccount, fixtureBikeSession, fixtureBikeDetail)
        assertTrue(options.single().channel.available == true); assertFalse(options.single().channel.needsOpen == true)
    }
    @Test fun nativeCashierSendsInstalledFlagAndFallsBackWhenEnableSelectIsFalse() = runBlocking {
        val calls = mutableListOf<Reply>()
        val api = HellobikeApi(fixtureHttp { url -> Reply(url, if (calls.isEmpty())
            """{"code":0,"data":{"channelList":[{"channelCode":"001","realAmount":6.9,"enableSelect":false}]}}"""
            else """{"code":0,"data":{"channel":"001","credential":{"params":"fixture"}}}""").also(calls::add) }, alipayInstalled = { true })
        val purchase = HellobikeCardPurchase("校园卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", null)
        val option = api.cardPayChannels(fixtureBikeAccount, purchase).single()
        assertEquals("1", calls.single().json.hbText("alipayInstallFlag"))
        assertTrue(option.channel.available == true); assertTrue(option.defaultSelected); assertNull(option.channel.remark)
        assertEquals("fixture", api.payCard(fixtureBikeAccount, purchase, purchase.orders, option).payload)
        assertEquals(2, calls.size)
    }
    @Test fun nativeDefaultSettingTakesPriorityAndInsufficientBalanceCannotBecomeDefault() = runBlocking {
        val purchase = HellobikeCardPurchase("校园卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", null)
        for ((balanceEnough, selected) in listOf(true to "003", false to "001")) {
            val options = api("""{"code":0,"data":{"extraInfos":{"defaultChannelSetting":"{\"defaultChannel\":\"003\"}"},"channelList":[{"channelCode":"001","realAmount":6.9,"enableSelect":true},{"channelCode":"003","realAmount":6.9,"enableSelect":false,"balanceEnough":$balanceEnough}]}}""")
                .cardPayChannels(fixtureBikeAccount, purchase)
            assertEquals(selected, options.single { it.defaultSelected }.channel.method)
        }
        val unavailable = api("""{"code":0,"data":{"channelList":[{"channelCode":"003","realAmount":6.9,"enableSelect":true,"balanceEnough":false}]}}""")
            .cardPayChannels(fixtureBikeAccount, purchase).single()
        assertFalse(unavailable.defaultSelected); assertFalse(unavailable.channel.available == true)
    }
    @Test fun nestedPaymentChannelsUseLeafRestrictionsAndKeepInsufficientBalanceUnavailable() = runBlocking {
        val purchase = HellobikeCardPurchase("校园卡", listOf(HellobikePayOrder("pay-no", "6.90", "payment-id", "28")), "6.90", "28", "2801", null)
        val options = api("""{"code":0,"data":{"channelList":[{"channelCode":"parent","enableSelect":false,"subChannelList":[{"channelCode":"001","realAmount":6.9,"enableSelect":true}]},{"channelCode":"003","realAmount":6.9,"enableSelect":true,"balanceEnough":false,"balanceText":"余额不足"}]}}""").cardPayChannels(fixtureBikeAccount, purchase)
        assertEquals(listOf("001", "003"), options.map { it.channel.method })
        assertTrue(options.first().channel.available == true); assertTrue(options.first().defaultSelected)
        assertFalse(options.last().channel.available == true); assertEquals("余额不足", options.last().channel.remark)
    }
    @Test fun smsAndLoginUsePublicCertificateDigestAndKeepTokensOutOfUrlAndErrors() = runBlocking {
        val calls = mutableListOf<Reply>()
        api("""{"code":0,"data":{"ok":true}}""", calls).sendCode(fixtureBikeAccount.phone, fixtureBikeRegion, "local-client", 73, "challenge")
        val login = api("""{"code":0,"data":{"token":"fixture-token-00000001","guid":"fixture-user-00000001"}}""", calls)
            .login(fixtureBikeAccount.phone, "123456", fixtureBikeRegion, "local-client", "login-attempt")
        assertEquals("local-client", login.clientId)
        assertEquals("/api", calls[0].url.path); assertEquals("73", calls[0].json.hbText("captcha"))
        assertEquals("challenge", calls[0].json.hbText("challenge")); assertEquals("/auth", calls[1].url.path)
        assertEquals("zkrFhoDpIPlkKlif+uTNtL1EYctTSHr7vJ/GE641gE4=\n", calls[1].json.hbText("apkSignHash"))
        calls.forEach { assertNull(it.url.query); assertTrue(it.closed); assertEquals("POST", it.request.method) }
        assertFalse(testHttpClients.service.followRedirects); assertFalse(testHttpClients.service.retryOnConnectionFailure)
    }
    @Test fun smsAndLoginAcceptNoCachedRegionAndNeverInventLocationCodes() = runBlocking {
        val calls = mutableListOf<Reply>()
        api("""{"code":0,"data":{"ok":true}}""", calls).sendCode(fixtureBikeAccount.phone, null, "local-client")
        api("""{"code":0,"data":{"token":"fixture-token-00000001","guid":"fixture-user-00000001"}}""", calls)
            .login(fixtureBikeAccount.phone, "123456", null, "local-client", "login-attempt")
        calls.forEach {
            assertNull(it.json.hbText("cityCode")); assertNull(it.json.hbText("adCode"))
            assertNull(it.json.hbText("lat")); assertNull(it.json.hbText("lng"))
        }
        api("""{"code":0,"data":{"ok":true}}""", calls).sendCode(fixtureBikeAccount.phone, fixtureBikeRegion, "local-client")
        assertEquals(fixtureBikeRegion.cityCode, calls.last().json.hbText("cityCode"))
        assertEquals(fixtureBikeRegion.adCode, calls.last().json.hbText("adCode"))
    }
    @Test fun sliderImagesCarryOriginalDimensionsAndRejectOversizedOrMismatchedPieces() = runBlocking {
        fun image(width: Int, height: Int): String {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle() }
                .toByteArray().let { Base64.getEncoder().encodeToString(it) }
        }
        val fields = jsonObjectOf("bigImage" to image(300, 150), "smallImage" to image(40, 40), "locationY" to 20)
        val slider = api(jsonObjectOf("code" to 0, "data" to fields).toString()).slider(fixtureBikeAccount.phone, fixtureBikeRegion, "client")
        assertEquals(300, slider.width); assertEquals(40, slider.pieceWidth); assertEquals(260, slider.maximum); assertEquals(20, slider.y)
        rejected { api(jsonObjectOf("code" to 0, "data" to fields.withField("locationY", 140)).toString())
            .slider(fixtureBikeAccount.phone, fixtureBikeRegion, "client") }
    }
    @Test fun nativeRideDiscoveryEmptyResultNeverAuthorizesAnOrderOrCloseCommand() = runBlocking {
        val api = api("""{"code":301,"data":{}}""")
        assertEquals(HellobikeRideReading.NoRide, api.checkRide(fixtureBikeAccount))
        rejected { api.orderEnded(fixtureBikeAccount, fixtureBikeSession.rideGuid) }
        rejected { api.preClose(fixtureBikeAccount, fixtureBikeSession, fixtureBikePosition, fixtureBikeRegion, false, true) }
    }
    @Test fun nativeRideDiscoveryUsesBikeQueryAndAcceptsAbsentRideOrOrder() = runBlocking {
        val calls = mutableListOf<Reply>()
        for (data in listOf("null", "{}", "{\"ride\":null}", "{\"ride\":{\"order\":null}}", "{\"ride\":{}}")) {
            assertEquals(HellobikeRideReading.NoRide, api("""{"code":0,"msg":"无骑行中订单","data":$data}""", calls)
                .checkRide(fixtureBikeAccount))
        }
        assertEquals(HellobikeRideReading.NoRide, api("""{"code":0,"msg":"无骑行中订单"}""").checkRide(fixtureBikeAccount))
        for (code in listOf(301, 401, 403, 987)) {
            assertEquals(HellobikeRideReading.NoRide, api("""{"code":$code,"msg":"普通业务返回"}""").checkRide(fixtureBikeAccount))
        }
        calls.forEach {
            assertEquals("bike.hellobike.com", it.url.host)
            assertEquals("/api", it.url.path)
            assertEquals("user.ride.bikeCheck", it.json.hbText("action"))
            assertEquals("7.0.75", it.json.hbText("version"))
            assertEquals(fixtureBikeAccount.clientId, it.json.hbText("clientId"))
            assertNull(it.json["bizType"]); assertNull(it.json["from"]); assertNull(it.json["h5Version"])
        }
        rejected { api("""{"code":0,"data":{"ride":{"order":{"status":0}}}}""").checkRide(fixtureBikeAccount) }
        rejected { api("""{"msg":"无骑行中订单"}""").checkRide(fixtureBikeAccount) }
    }
    @Test fun nativeRideDiscoveryReadsTheNativeOrderStatusAndIdentity() = runBlocking {
        for (status in listOf(0, 1, 2, 3)) {
            val ride = api("""{"code":0,"data":{"ride":{"order":{"orderGuid":"${fixtureBikeSession.rideGuid}","bikeNo":"${fixtureBikeSession.bikeNo}","status":$status}}}}""")
                .checkRide(fixtureBikeAccount) as HellobikeRideReading.Ride
            assertEquals(fixtureBikeSession.rideGuid, ride.rideGuid)
            assertEquals(fixtureBikeSession.bikeNo, ride.bikeNo)
            assertEquals(status, ride.status)
        }
    }
    @Test fun precheckAndCreateReuseTheOfficialScanIdAndTheirOwnPlatformValues() = runBlocking {
        val calls = mutableListOf<Reply>()
        val replies = listOf("""{"code":0,"data":{"result":true,"causeType":1100,"bikeType":0,"orderGuid":"${fixtureBikeSession.rideGuid}"}}""",
            """{"code":0,"data":{"ridePriceInfo":{"priceRule":"30分钟2元"}}}""",
            """{"code":0,"data":{"result":true,"rideId":"${fixtureBikeSession.rideGuid}"}}""")
        val client = HellobikeApi(fixtureHttp { url -> Reply(url, replies[calls.size]).also(calls::add) }, { 1_000_000 }, { 50_000 })
        val check = client.prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition)
        client.create(fixtureBikeAccount, check, fixtureBikePosition)
        val pre = calls[0].json; val create = calls[2].json
        assertTrue(check.scanReqId.matches(Regex("${Regex.escape(fixtureBikeAccount.userGuid)}_${Regex.escape(fixtureBikeSession.bikeNo)}_[0-9]{14}_mini_[0-9]{4}")))
        assertEquals(check.scanReqId, pre.hbText("scanReqId")); assertEquals(check.scanReqId, pre.hbText("scan_req_id"))
        assertEquals(check.scanReqId, create.hbText("scanReqId"))
        assertEquals(1, pre.hbInt("force")); assertEquals(0, pre.hbInt("platform")); assertEquals(2, create.hbInt("platform"))
        assertEquals("7.0.70", create.hbText("h5Version")); assertEquals("ohoSaaS", create.hbText("sourceMarkup"))
        assertNull(create.hbText("releaseVersion"))
    }
    @Test fun aMismatchedRideOrPaymentCannotBeConfirmed() = runBlocking {
        rejected { api("""{"code":0,"data":{"rideGuid":"other-ride","rideStatus":30}}""")
            .orderEnded(fixtureBikeAccount, fixtureBikeSession.rideGuid) }
        rejected { api("""{"code":0,"data":{"guid":"other","payStatus":1}}""")
            .paymentPaid(fixtureBikeAccount, fixtureBikeOrder, JsonObject(emptyMap())) }
    }
    @Test fun onlyNumericOneConfirmsPaymentAndQueriesUseThePaymentGuid() = runBlocking {
        assertTrue(api("""{"code":0,"data":{"payStatus":1,"businessId":"business-order","amount":6.9}}""")
            .paymentPaid(fixtureBikeAccount, fixtureBikeOrder, JsonObject(emptyMap())))
        for (status in listOf("0", "2", "\"1\"", "null", "1")) {
            val calls = mutableListOf<Reply>()
            val result = api("""{"code":0,"data":{"guid":"${fixtureBikeOrder.guid}","payStatus":$status}}""", calls)
                .paymentPaid(fixtureBikeAccount, fixtureBikeOrder, JsonObject(emptyMap()))
            assertEquals(status == "1", result)
            assertEquals("${fixtureBikeOrder.guid}", calls.single().json.hbText("guid"))
            assertEquals("com.alphapay.facade.orderSingleQuery", calls.single().json.hbText("action"))
            assertEquals("api.hellobike.com", calls.single().url.host)
        }
    }
    @Test fun conflictingEnvelopeCodesCannotClaimAConfirmedPayment() = runBlocking {
        for (codes in listOf("\"code\":0,\"resultStatus\":7", "\"code\":7,\"resultStatus\":0")) {
            rejected { api("""{$codes,"data":{"guid":"${fixtureBikeOrder.guid}","payStatus":1}}""")
                .paymentPaid(fixtureBikeAccount, fixtureBikeOrder, JsonObject(emptyMap())) }
        }
    }
    @Test fun rideDetailsUseUnpaidPayNumbersAndDoNotTurnClosedOrEmptyListsIntoPaid() = runBlocking {
        val calls = mutableListOf<Reply>()
        val response = """{"code":0,"data":{"rideGuid":"${fixtureBikeSession.rideGuid}","tradeId":"trade","bizType":0,"orderInfo":{"payStatus":"WAIT_PAY","payAmount":"2.00","unpaidPayList":[{"payNo":"${fixtureBikeOrder.guid}","payPrice":"2.00"}]}}}"""
        val detail = api(response, calls).payDetail(fixtureBikeAccount, fixtureBikeSession)
        assertEquals(listOf(fixtureBikeOrder), detail.orders); assertEquals(HellobikeSettlement.Pending, detail.settlement)
        assertTrue(detail.orders.single().orderId.isEmpty()); assertEquals("bike.hellobike.com", calls.single().url.host)
        assertEquals("${fixtureBikeSession.rideGuid}", calls.single().json.hbText("rideGuid"))
        val closed = api(response.replace("WAIT_PAY", "CLOSED")).payDetail(fixtureBikeAccount, fixtureBikeSession)
        assertEquals(HellobikeSettlement.Closed, closed.settlement)
        val empty = api("""{"code":0,"data":{"rideGuid":"${fixtureBikeSession.rideGuid}","bizType":0,"orderInfo":{"payStatus":"WAIT_PAY","payAmount":"0.00","unpaidPayList":[]}}}""")
            .payDetail(fixtureBikeAccount, fixtureBikeSession)
        assertEquals(HellobikeSettlement.Unknown, empty.settlement)
    }
    @Test fun onlyServerOfferedOrdinaryChannelsAreAvailableAndAgreementsStayOnOfficialFlow() = runBlocking {
        val calls = mutableListOf<Reply>()
        val body = """{"code":0,"data":{"channelList":[{"channelCode":"001","enableSelect":true,"realAmount":"2.00"},{"channelCode":"003","enableSelect":true,"balanceEnough":false},{"channelCode":"1201","enableSelect":true,"needGuideBindingCard":true},{"channelCode":"002","enableSelect":true},{"channelCode":"1205","enableSelect":true}]}}"""
        val channels = api(body, calls).payChannels(fixtureBikeAccount, fixtureBikeSession, fixtureBikeDetail)
        assertEquals(listOf("001", "003", "1201"), channels.map { it.channel.method })
        assertTrue(channels.first().channel.available == true); assertFalse(channels[1].channel.available == true)
        assertFalse(channels[2].channel.needsOpen == true)
        assertTrue(channels[2].channel.available == true)
        assertEquals(fixtureBikeOrder.guid, calls.single().json.hbText("waitPayGuid")); assertEquals("407", calls.single().json.hbText("scene"))
    }
    @Test fun returnedChannelAndCredentialsArePreservedWithoutSubstitutingRideIdForPaymentId() = runBlocking {
        val calls = mutableListOf<Reply>()
        val body = """{"code":0,"data":{"channel":"1201","status":"processing","credential":{"params":"sdk-transaction"},"orders":[{"guid":"${fixtureBikeOrder.guid}","amount":"2.00","businessType":"1","orderId":"payment-order"}]}}"""
        val option = HellobikePayOption(LifePayChannel("001", "支付宝", true, false), "2.00")
        val component = api(body, calls).pay(fixtureBikeAccount, fixtureBikeSession, listOf(fixtureBikeOrder), option)
        assertEquals("1201", component.channel); assertEquals("sdk-transaction", component.payload)
        val order = parseArray(calls.single().json.hbText("orders")!!).objectOrNull(0)!!
        assertEquals(fixtureBikeOrder.guid, order.hbText("guid")); assertEquals("001", calls.single().json.hbText("channel"))
        assertEquals("payment-order", component.orders.single().orderId)
        rejected { api(body.replace("2.00", "9.00")).pay(fixtureBikeAccount, fixtureBikeSession, listOf(fixtureBikeOrder), option) }
    }
    @Test fun preCloseUsesOfficialCauseTypesAndAllowsItsNormalConfirmation() {
        val base = jsonObjectOf("state" to 3, "causeType" to 1103, "penaltyFree" to 1, "skipConfirm" to true, "type" to 1)
        assertTrue(HellobikeProtocol.closeReading(base).ready)
        assertTrue(HellobikeProtocol.closeReading(base.withField("status", 2).withField("skipConfirm", false)).ready)
        assertTrue(HellobikeProtocol.closeReading(base.withField("causeType", 1102)).ready)
        assertTrue(HellobikeProtocol.closeReading(base.withField("causeType", 1100)).completed)
        for (informational in listOf(base.withField("penaltyDisplay", "2.00"), base.withField("bluetoothKey", "key"),
            base.withField("complexBluetoothKey", JsonArray(emptyList())), base.withField("penaltyFree", 0))) {
            assertTrue(HellobikeProtocol.closeReading(informational).ready)
        }
        for (unsupported in listOf(base.withField("type", 2), base.withField("type", 3))) {
            try { HellobikeProtocol.closeReading(unsupported); fail("Extra steps cannot commit a normal close") }
            catch (e: HellobikeException) { assertTrue(e.officialRequired) }
        }
        assertTrue(HellobikeProtocol.closeReading(jsonObjectOf("state" to 2, "causeType" to 1101)).processing)
        assertTrue(HellobikeProtocol.closeReading(jsonObjectOf("causeType" to 1108)).processing)
        try { HellobikeProtocol.closeReading(jsonObjectOf("causeType" to 1105, "causeDesc" to "请到停车点还车")); fail() }
        catch (e: HellobikeException) { assertEquals("请到停车点还车", e.message) }
        try { HellobikeProtocol.closeReading(jsonObjectOf("causeType" to 1105, "causeDesc" to "官方关锁拒绝原因"), commit = true); fail() }
        catch (e: HellobikeException) { assertEquals("官方关锁拒绝原因", e.message) }
        assertEquals("官方定位说明", HellobikeProtocol.closeReading(jsonObjectOf("causeType" to 1101, "causeDesc" to "官方定位说明")).detail)
    }
    @Test fun officialRejectionTextKeepsItsFullContentsAndNativePrecheckFields() = runBlocking {
        val text = "请核对车辆 1234567890：https://c3x.me/?n=1234567890\n" + "官方说明".repeat(100)
        try {
            api(jsonObjectOf("code" to 456, "msg" to text, "data" to JsonObject(emptyMap())).toString()).orderState(fixtureBikeAccount, fixtureBikeSession.rideGuid)
            fail()
        } catch (e: HellobikeException) { assertEquals(text, e.message) }
        val calls = mutableListOf<Reply>()
        val replies = listOf(jsonObjectOf("code" to 0, "data" to jsonObjectOf("result" to false, "causeType" to 1105,
                "cause" to "车辆已被使用", "causeContent" to text)).toString())
        val service = HellobikeApi(fixtureHttp { url -> Reply(url, replies[calls.size]).also(calls::add) }, elapsed = { 50_000 })
        try { service.prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition); fail() }
        catch (e: HellobikeException) { assertEquals("车辆已被使用\n$text", e.message) }
        assertEquals(1, calls.size)
    }
    @Test fun precheckUsesTheOfficialOrConditionAndKeepsNoticesWithoutInventingVehicleRestrictions() = runBlocking {
        for (result in listOf(jsonObjectOf("result" to true, "causeType" to 1105, "missBike" to true),
            jsonObjectOf("result" to false, "causeType" to 1100))) {
            val calls = mutableListOf<Reply>()
            val pre = result.withField("orderGuid", fixtureBikeSession.rideGuid).withField("cause", "官方提示")
            val replies = listOf(jsonObjectOf("code" to 0, "data" to pre).toString(),
                """{"code":0,"data":{"ridePriceInfo":{"priceRule":"30分钟2元"}}}""")
            val service = HellobikeApi(fixtureHttp { url -> Reply(url, replies[calls.size]).also(calls::add) }, elapsed = { 50_000 })
            val check = service.prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition)
            assertEquals("官方提示", check.notice)
            assertEquals("30分钟2元", check.priceRule)
            assertTrue(calls[0].json.hbBoolean("isHandleTag") == true)
        }
    }
    @Test fun absentCloudReasonIsReportedAsAbsentWithNoInventedFailureReason() = runBlocking {
        try { api("""{"code":987,"data":{}}""").orderState(fixtureBikeAccount, fixtureBikeSession.rideGuid); fail() }
        catch (e: HellobikeException) { assertEquals("user.ride.order.status：服务端未提供原因（code=987）", e.message) }
        try { HellobikeProtocol.closeReading(jsonObjectOf("causeType" to 1105)); fail() }
        catch (e: HellobikeException) { assertEquals("ride.hub.pre.close：服务端未提供原因（causeType=1105）", e.message) }
    }
    @Test fun missingPriceRuleDoesNotRejectSuccessfulPrecheckAndOnlyForcedBluetoothRequiresItsFlow() = runBlocking {
        for (popupType in listOf(1, 2, 3)) {
            val calls = mutableListOf<Reply>()
            val pre = jsonObjectOf("result" to true, "causeType" to 1100, "orderGuid" to fixtureBikeSession.rideGuid,
                "netBluetoothModel" to true, "popupPromptType" to popupType, "cause" to "官方蓝牙提示")
            val replies = listOf(jsonObjectOf("code" to 0, "data" to pre).toString(), """{"code":0,"data":{}}""")
            val service = HellobikeApi(fixtureHttp { url -> Reply(url, replies[calls.size]).also(calls::add) }, elapsed = { 50_000 })
            if (popupType == 3) {
                try { service.prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition); fail() }
                catch (e: HellobikeException) { assertTrue(e.officialRequired); assertEquals("官方蓝牙提示", e.message) }
                assertEquals(1, calls.size)
            } else {
                assertEquals("", service.prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition).priceRule)
                assertEquals(2, calls.size)
            }
        }
    }
    @Test fun specialPrecheckFlowIsNotMisclassifiedAsRejectionAndOrdinaryNoticesRemainConfirmable() = runBlocking {
        try { api("""{"code":0,"data":{"result":false,"causeType":1215,"cause":"请完成官方认证"}}""")
            .prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition); fail() }
        catch (e: HellobikeException) { assertTrue(e.officialRequired); assertFalse(e.rejected); assertEquals("请完成官方认证", e.message) }
        val calls = mutableListOf<Reply>()
        val replies = listOf("""{"code":0,"data":{"result":true,"causeType":1226,"orderGuid":"${fixtureBikeSession.rideGuid}","notices":[{"warnType":1213,"warnTitle":"官方告知","warnContent":"请阅读后继续"},{"warnType":1226,"warnTitle":"普通提示"}]}}""",
            """{"code":0,"data":{}}""")
        val service = HellobikeApi(fixtureHttp { url -> Reply(url, replies[calls.size]).also(calls::add) }, elapsed = { 50_000 })
        assertEquals("官方告知\n请阅读后继续\n普通提示", service.prepare(fixtureBikeAccount, fixtureBikeSession.bikeNo, fixtureBikeRegion, fixtureBikePosition).notice)
    }
    @Test fun successfulLoginDoesNotRejectMobileFlagsOrInventTokenLengthRules() = runBlocking {
        val owner = api("""{"code":0,"data":{"token":"token","guid":"user","unavailableMobile":true,"needRebindMobile":"1"}}""")
            .login(fixtureBikeAccount.phone, "123456", null, "client", "attempt")
        assertEquals("token", owner.token); assertEquals("user", owner.userGuid)
    }
    @Test fun smsHandlesTheOfficialCaptchaPriorityAndPreservesVerificationFailureText() = runBlocking {
        assertTrue(api("""{"code":0,"data":{"ok":true,"captchaType":1}}""")
            .sendCode(fixtureBikeAccount.phone, null, "client").sliderRequired)
        try { api("""{"code":0,"msg":"请重新完成安全验证","data":{"ok":false}}""")
            .sendCode(fixtureBikeAccount.phone, null, "client", 42); fail() }
        catch (e: HellobikeException) { assertEquals("请重新完成安全验证", e.message) }
    }
    @Test fun balanceFlagsFallbackToAccountBalanceAndUseTheOfficialExplanation() = runBlocking {
        val offered = """{"code":0,"data":{"channelList":[{"channelCode":"003","enableSelect":true,"accountBalance":"10.00"}]}}"""
        assertTrue(api(offered).payChannels(fixtureBikeAccount, fixtureBikeSession, fixtureBikeDetail).single().channel.available == true)
        val insufficient = offered.replace("10.00", "0.00").replace("\"accountBalance\"", "\"balanceText\":\"余额不足，请充值后再试\",\"accountBalance\"")
        assertEquals("余额不足，请充值后再试", api(insufficient).payChannels(fixtureBikeAccount, fixtureBikeSession, fixtureBikeDetail).single().channel.remark)
    }
    @Test fun loginUsesDeviceWebViewAgentOfficialChannelAndIndependentSsid() = runBlocking {
        val calls = mutableListOf<Reply>()
        val client = HellobikeApi(fixtureHttp { url -> Reply(url, """{"code":0,"data":{"token":"fixture-token-00000001","guid":"fixture-owner-00000001"}}""").also(calls::add) },
            userAgent = { "device-webview-agent" }, ssid = { "0123456789abcdef0123456789abcdef" })
        repeat(2) { client.login(fixtureBikeAccount.phone, "123456", null, "client-id", "login-guid") }
        calls.forEach {
            assertEquals("device-webview-agent", it.request.header("User-Agent"))
            assertEquals("a0010005", it.json.hbText("appChannel"))
            assertEquals("7.0.75", it.json.hbText("version"))
            assertEquals("86", it.json.hbText("intlAreaCode"))
            assertEquals("zh-CN", it.json.hbText("locale"))
            assertEquals("0123456789abcdef0123456789abcdef", it.json.hbText("ssid"))
            assertNotEquals(it.json.hbText("clientId"), it.json.hbText("ssid"))
        }
    }
    @Test fun orderQueryKeepsOpeningSubstatusesSeparateFromTheRideCheckStatus() = runBlocking {
        for (sub in listOf("22", "23", "0", "-12", "null")) {
            val calls = mutableListOf<Reply>()
            val reading = api("""{"code":0,"data":{"rideStatus":20,"subStatus":$sub}}""", calls)
                .orderState(fixtureBikeAccount, fixtureBikeSession.rideGuid)
            assertEquals(sub in listOf("22", "23"), reading.opening)
            assertEquals(sub !in listOf("22", "23"), reading.unlocked)
            assertFalse(reading.lockStuck)
            assertEquals("ebike.hellobike.com", calls.single().url.host)
            assertEquals("user.ride.order.status", calls.single().json.hbText("action"))
            assertEquals(fixtureBikeSession.rideGuid, calls.single().json.hbText("rideGuid"))
            assertEquals(1, calls.single().json.hbInt("bizType"))
        }
        val ride = api("""{"code":0,"data":{"ride":{"order":{"orderGuid":"${fixtureBikeSession.rideGuid}","status":0,"bikeNo":"${fixtureBikeSession.bikeNo}"}}}}""")
            .checkRide(fixtureBikeAccount) as HellobikeRideReading.Ride
        assertEquals(fixtureBikeSession.bikeNo, ride.bikeNo)
        assertEquals(0, ride.status)
    }
    @Test fun onlyOfficialExpiryCodesExpireLoginAndCloudRejectionsKeepTheirMessage() = runBlocking {
        for (code in listOf(103, -10003)) {
            try { api("""{"code":$code,"msg":"服务端提示"}""").checkRide(fixtureBikeAccount); fail() }
            catch (e: HellobikeException) { assertEquals(code in setOf(103, -10003), e.loginExpired); assertEquals("服务端提示", e.message) }
        }
        try { api("""{"code":7,"msg":"登录信息已失效，请重新登录"}""").orderState(fixtureBikeAccount, fixtureBikeSession.rideGuid); fail() }
        catch (e: HellobikeException) { assertFalse(e.loginExpired); assertEquals("登录信息已失效，请重新登录", e.message) }
        try { api("""{"code":403,"msg":"请求未被允许"}""", httpStatus = 403).checkRide(fixtureBikeAccount); fail() }
        catch (e: HellobikeException) { assertFalse(e.loginExpired); assertEquals("请求未被允许", e.message) }
    }
    @Test fun lbsUsesThePublishedArg0SignatureAndNestedRegionRatherThanDummyValues() = runBlocking {
        val calls = mutableListOf<Reply>()
        val region = api("""{"code":0,"data":{"data":{"reGeoCodesList":[{"addressComponent":{"cityCode":"0371","adCode":"410100","city":"示例地区"}}]}}}""", calls)
            .region(fixtureBikePosition)
        assertTrue(region.valid)
        val body = calls.single().json
        assertEquals("oho_ride_mobile_page", body.objectOrNull("arg1")?.hbText("appKey"))
        assertEquals(32, body.objectOrNull("arg1")?.hbText("signature")?.length)
        assertEquals("all", parseObject(body.hbText("arg0")!!).hbText("extensions"))
        assertNotEquals("00000000000000000000000000000000", body.objectOrNull("arg1")?.hbText("signature"))
    }
}
