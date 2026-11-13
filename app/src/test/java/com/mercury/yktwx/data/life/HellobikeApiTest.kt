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
        for (body in listOf("""{"guid":"other","payStatus":1}""", """{"payStatus":1}""",
            """{"guid":"${fixtureBikeOrder.guid}","amount":"99.00","payStatus":1}""")) {
            rejected { api("""{"code":0,"data":$body}""").paymentPaid(fixtureBikeAccount, fixtureBikeOrder, JsonObject(emptyMap())) }
        }
    }
    @Test fun onlyNumericOneConfirmsPaymentAndQueriesUseThePaymentGuid() = runBlocking {
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
