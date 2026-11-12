package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLDecoder

class CardServiceApiTest {
    private class FakeResponse(url: URL, private val body: String, private val status: Int = 200) : HttpFixture(url) {
        val sent = ByteArrayOutputStream()
        var closed = false

        override fun close() { closed = true }

        override fun statusCode() = status
        override fun bodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun errorBodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream() = sent

    }
    private fun configuration(lock: String? = null, unlock: String? = null, type: String = "1", showLost: String? = null): String {
        val front = jsonObjectOf("recharge" to "401", "lockFlag" to lock, "unlockFlag" to unlock)
        val ecard = jsonObjectOf("type" to type, "showLost" to showLost)
        return jsonObjectOf("code" to 200, "data" to jsonObjectOf("getFrontConfig" to front.toString(),
            "getEcardConfig" to ecard.toString())).toString()
    }
    private fun card(lost: Int = 0) = jsonObjectOf("account" to ("recipient-card"), "cardname" to ("校园卡"), "sno" to ("202600000002"), "name" to ("示例同学"), "lostflag" to (lost), "freezeflag" to (0), "acc_status" to (0), "db_balance" to (100), "unsettle_amount" to (200), "accinfo" to (jsonArrayOf(listOf(jsonObjectOf("type" to ("recipient-electronic"), "name" to ("统一账户"), "balance" to (300))))))
    private fun cardResponse(lost: Int = 0) = jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("0"), "card" to (jsonArrayOf(listOf(card(lost))))))).toString()
    private fun params(text: String?) = text.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val p = it.split('=', limit = 2); URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
    }
    private fun selected(lost: Boolean = false) = CampusCard("recipient-card", "校园卡", "202600000002", lost, false, 0)
    private fun lookup(api: CardServiceApi) = runBlocking { api.rechargeRecipients("fake-token", RecipientQueryType.StudentNumber,
        "202600000002", RecipientCaptcha("fake-key", byteArrayOf(1)), "abcde") }

    @Test fun missingFlagsFollowWebsiteLostWithoutPasswordAndUnlostWithPassword() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url, if (url.path.endsWith("frontInfo")) configuration() else cardResponse()).also(calls::add) }
        val result = api.cardConfiguration("fake-token")
        assertFalse(result.lostNeedsPassword); assertTrue(result.unlostNeedsPassword)
        assertFalse(result.cards.single().lost)
        assertTrue(calls.all { it.request.header("Cookie") == null && it.request.header("synjones-auth") == "bearer fake-token" && it.closed && !testHttpClients.service.followRedirects })
    }
    @Test fun unlostUsesFreshNumberKeyboardSingleJsonWriteAndRefreshesState() = runBlocking {
        var lost = true
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url ->
            val body = when {
                url.path.endsWith("frontInfo") -> configuration()
                url.path.endsWith("getCampusCards") -> cardResponse(if (lost) 1 else 0)
                url.path.endsWith("keyboard") -> "{\"code\":200,\"data\":{\"numberKeyboard\":\"abcdefghij\",\"uuid\":\"fake-uuid\"}}"
                url.path.endsWith("unlostCard") -> { lost = false; "{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"recipient-card\"}}" }
                else -> error("Unexpected local request")
            }
            FakeResponse(url, body).also(calls::add)
        }
        assertFalse(api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "012345").single().lost)
        val post = calls.single { it.request.method == "POST" }
        val body = parseObject(post.sent.toString("UTF-8"))
        assertEquals("recipient-card", body.stringValue("account"))
        assertEquals("1\$1\$abcdef\$1\$fake-uuid", body.stringValue("pwd"))
        assertEquals(1, body.intValue("pwdType")); assertEquals("h5", body.stringValue("synAccessSource"))
        assertFalse(post.sent.toString("UTF-8").contains("012345"))
        assertEquals("application/json;charset=UTF-8", post.request.header("Content-Type"))
        val keyboard = calls.single { it.url.path.endsWith("keyboard") }
        assertEquals("Number", params(keyboard.url.query)["type"]); assertEquals("0", params(keyboard.url.query)["order"])
        assertEquals(2, calls.count { it.url.path.endsWith("getCampusCards") })
    }
    @Test fun alreadyReachedStateDoesNotPost() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url, if (url.path.endsWith("frontInfo")) configuration() else cardResponse(1)).also(calls::add) }
        assertTrue(api.changeCardState("fake-token", selected(), CampusCardAction.Lost, "").single().lost)
        assertTrue(calls.none { it.request.method == "POST" })
    }
    @Test fun explicitNoPasswordOmitsEmptyKeyboardFields() = runBlocking {
        var lost = false
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url ->
            FakeResponse(url, when {
                url.path.endsWith("frontInfo") -> configuration("0", "0")
                url.path.endsWith("getCampusCards") -> cardResponse(if (lost) 1 else 0)
                url.path.endsWith("lostCard") -> { lost = true; "{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"recipient-card\"}}" }
                else -> error("Unexpected request")
            }).also(calls::add)
        }
        api.changeCardState("fake-token", selected(), CampusCardAction.Lost, "")
        val body = parseObject(calls.single { it.request.method == "POST" }.sent.toString("UTF-8"))
        assertFalse(body.containsKey("pwd")); assertFalse(body.containsKey("pwdType")); assertEquals(0, calls.count { it.url.path.endsWith("keyboard") })
    }
    @Test fun rejectedWritePreservesInnerServerErrorWithoutRetryOrStateRead() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val message = "  查询密码有误，请重新输入！\n"
        val api = fixtureCardServiceApi { url -> FakeResponse(url, when {
            url.path.endsWith("frontInfo") -> configuration("0", "0")
            url.path.endsWith("getCampusCards") -> cardResponse()
            else -> jsonObjectOf("code" to (200), "data" to (jsonObjectOf("retcode" to ("60005"), "account" to ("recipient-card"), "errmsg" to (message))), "msg" to ("外层提示")).toString()
        }).also(calls::add) }
        val error = assertThrows(CardOperationRejected::class.java) {
            runBlocking { api.changeCardState("fake-token", selected(), CampusCardAction.Lost, "") }
        }
        assertEquals(message, error.message)
        assertEquals(1, calls.count { it.request.method == "POST" })
        assertEquals(1, calls.count { it.url.path.endsWith("getCampusCards") })
    }
    @Test fun recipientQueryUsesQueryParametersAndElectronicTargetNotInputOrPayer() {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url, if (url.path.endsWith("frontInfo")) configuration() else cardResponse()).also(calls::add) }
        val recipient = lookup(api).single()
        assertEquals("recipient-electronic", recipient.targetId); assertEquals("recipient-card", recipient.cardAccount)
        val p = params(calls.first().url.query)
        assertEquals("sno", p["idtype"]); assertEquals("202600000002", p["id"]); assertEquals("recharge", p["scene"])
        assertEquals("fake-key", p["captcha_header_key"]); assertEquals("abcde", p["captcha_header_code"])
        assertNull(calls.first().request.header("captcha_header_key"))
        assertTrue(calls.none { it.url.path.endsWith("queryCard") || it.request.method == "POST" })
    }
    @Test fun nestedAccountMissingIsNotOuterSuccessAndCaptchaErrorIsDistinct() {
        val missing = fixtureCardServiceApi { url -> FakeResponse(url, "{\"code\":200,\"success\":true,\"data\":{\"retcode\":\"60003\",\"card\":null},\"msg\":\"操作成功\"}") }
        try { lookup(missing); fail("Expected missing account") } catch (e: RecipientLookupRejected) { assertTrue(e.message!!.contains("不存在")) }
        val captcha = fixtureCardServiceApi { url -> FakeResponse(url, "{\"code\":8002,\"success\":false,\"data\":null}") }
        try { lookup(captcha); fail("Expected captcha failure") } catch (e: RecipientLookupRejected) { assertTrue(e.message!!.contains("验证码")) }
    }
    @Test fun captchaReadsTopLevelKeyAndBoundedInlineImage() = runBlocking {
        val api = fixtureCardServiceApi { url -> FakeResponse(url, "{\"key\":\"fake-key\",\"image\":\"data:image/png;base64,AQID\"}") }
        val c = api.recipientCaptcha("fake-token")
        assertEquals("fake-key", c.key); assertArrayEquals(byteArrayOf(1, 2, 3), c.image)
    }
    @Test fun recipientAmountConfigurationNeverRequeriesPayerAccount() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url, if (url.path.endsWith("frontInfo")) configuration()
            else "{\"code\":200,\"feeitem\":{\"layout\":\"10,50,100\",\"flag\":\"1100000000\"}}").also(calls::add) }
        val recipient = RechargeRecipient("recipient-card", "recipient-electronic", "示例同学", "202600000002", "统一账户")
        val config = api.recipientRechargeConfiguration("fake-token", recipient)
        assertSame(recipient, config.recipient); assertEquals(recipient.targetId, config.targetId)
        assertEquals(listOf(1000L, 5000L, 10000L), config.amountsCents)
        assertTrue(calls.none { it.url.path.endsWith("queryCard") })
    }
    @Test fun unlostAcceptsLegitimateDollarMappingWithoutFetchingAnotherKeyboard() = runBlocking {
        var lost = true
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url ->
            val body = when {
                url.path.endsWith("frontInfo") -> configuration()
                url.path.endsWith("getCampusCards") -> cardResponse(if (lost) 1 else 0)
                url.path.endsWith("keyboard") -> jsonObjectOf("code" to (200), "data" to (jsonObjectOf("numberKeyboard" to ("\$bcdefghij"), "uuid" to ("fake-uuid")))).toString()
                url.path.endsWith("unlostCard") -> { lost = false; "{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"recipient-card\"}}" }
                else -> error("Unexpected local path")
            }
            FakeResponse(url, body).also(calls::add)
        }
        assertFalse(api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "000000").single().lost)
        val body = parseObject(calls.single { it.request.method == "POST" }.sent.toString("UTF-8"))
        assertEquals("1\$1\$" + "\$".repeat(6) + "\$1\$fake-uuid", body.stringValue("pwd"))
        assertEquals(1, calls.count { it.url.path.endsWith("keyboard") })
        assertEquals(1, calls.count { it.request.method == "POST" })
    }
    @Test fun numberEncoderRejectsBadInputsAndSensitiveModelsAreRedacted() {
        for ((password, mapping, uuid) in listOf(Triple("12345", "abcdefghij", "fake"), Triple("123456", "aaaaaaaaaa", "fake"), Triple("123456", "abcdefghij", "bad\$id"))) {
            try { NumberKeyboardEncoder.encode(password, mapping, uuid); fail("Expected invalid input") } catch (_: YktException) { }
        }
        assertEquals("CampusCard([redacted])", selected().toString())
        assertEquals("RecipientCaptcha([redacted])", RecipientCaptcha("secret", byteArrayOf()).toString())
        assertFalse(selected().label.contains("202600000002"))
    }
    @Test fun businessFlagsAreParsedFromGetBusinessConfigAndMatchWebsiteConditions() = runBlocking {
        val payload = parseObject(configuration()).objectValue("data")
            .withField("getBusinessConfig", jsonObjectOf("businessHandleConfig" to ("{\"lockFlag\":\"1\",\"unlockFlag\":\"0\"}")))
        val wrapper = jsonObjectOf("code" to (200), "data" to (payload))
        val api = fixtureCardServiceApi { url -> FakeResponse(url, if (url.path.endsWith("frontInfo")) wrapper.toString() else cardResponse()) }
        val c = api.cardConfiguration("fake-token")
        assertTrue(c.lostNeedsPassword); assertFalse(c.unlostNeedsPassword)
    }
    @Test fun recipientUiValuesAreNotMaskedButStringificationIsRedacted() {
        val recipient = RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户")
        assertEquals("示例同学", recipient.name)
        assertEquals("202600000002", recipient.sno)
        assertEquals("RechargeRecipient([redacted])", recipient.toString())
    }

    @Test fun lostRecipientShowsSpecificReasonWithoutCreatingOrPaying() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url,
            if (url.path.endsWith("frontInfo")) configuration() else cardResponse(1)).also(calls::add) }
        val error = assertThrows(RecipientLookupRejected::class.java) { lookup(api) }
        assertEquals("已挂失，禁止充值，请先解挂", error.message)
        assertTrue(calls.all { it.request.method == "GET" })
        assertTrue(calls.none { it.url.path.endsWith("queryCard") })
    }

    @Test fun officialShowLostAllowsRecipientSelectionAndLeavesPaymentDecisionToServer() = runBlocking {
        val api = fixtureCardServiceApi { url -> FakeResponse(url,
            if (url.path.endsWith("frontInfo")) configuration(showLost = "1") else cardResponse(1)) }
        assertEquals("recipient-electronic", lookup(api).single().targetId)
    }

    @Test fun failuresBeforeSubmissionAreNotAmbiguousWrites() = runBlocking {
        for (failedStage in listOf("frontInfo", "getCampusCards", "keyboard")) {
            val calls = mutableListOf<String>()
            val api = fixtureCardServiceApi { url ->
                calls += url.path
                if (url.path.endsWith(failedStage)) throw java.io.IOException("fake outage")
                val body = when {
                    url.path.endsWith("frontInfo") -> configuration()
                    url.path.endsWith("getCampusCards") -> cardResponse(1)
                    else -> error("No write may be sent")
                }
                FakeResponse(url, body)
            }
            val error = assertThrows(CardOperationNotStarted::class.java) {
                runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "012345") }
            }
            assertTrue(error.message!!.contains("网络"))
            assertTrue(calls.none { it.endsWith("unlostCard") })
        }
    }

    @Test fun invalidKeyboardMappingIsReportedBeforeAnyWrite() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url, when {
            url.path.endsWith("frontInfo") -> configuration()
            url.path.endsWith("getCampusCards") -> cardResponse(1)
            url.path.endsWith("keyboard") -> "{\"code\":200,\"data\":{\"numberKeyboard\":\"aaaaaaaaaa\",\"uuid\":\"fake-uuid\"}}"
            else -> error("No write may be sent")
        }).also(calls::add) }
        val error = assertThrows(CardOperationNotStarted::class.java) {
            runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "012345") }
        }
        assertTrue(error.message!!.contains("映射"))
        assertTrue(calls.none { it.request.method == "POST" })
    }

    @Test fun outerRejectionsPreserveBackendMessageAndDoNotPerformConfirmationReads() = runBlocking {
        for ((status, response) in listOf(
            200 to "{\"code\":60005,\"success\":false,\"data\":null,\"msg\":\"密码错误\"}",
            200 to "{\"code\":60005,\"data\":null,\"msg\":\"后端明确拒绝\"}",
            400 to "{\"message\":\"安全键盘会话失效\"}",
            200 to "{\"code\":200,\"success\":false,\"data\":null,\"msg\":\"操作被拒绝\"}",
            200 to "{\"code\":200,\"data\":{\"retcode\":\"60005\"},\"msg\":\"外层业务提示\"}",
            200 to "{\"code\":200,\"success\":true,\"data\":{\"retcode\":\"60005\"},\"msg\":\"内层业务拒绝\"}")) {
            val calls = mutableListOf<FakeResponse>()
            val api = fixtureCardServiceApi { url -> FakeResponse(url, when {
                url.path.endsWith("frontInfo") -> configuration("0", "0")
                url.path.endsWith("getCampusCards") -> cardResponse(1)
                else -> response
            }, if (url.path.endsWith("unlostCard")) status else 200).also(calls::add) }
            val error = assertThrows(CardOperationRejected::class.java) {
                runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "") }
            }
            val body = parseObject(response)
            assertEquals(body.scalarOrElement("msg") as? String ?: body.stringOrDefault("message"), error.message)
            assertEquals(1, calls.count { it.request.method == "POST" })
            assertEquals(1, calls.count { it.url.path.endsWith("getCampusCards") })
        }
    }

    @Test fun transportAndMalformedOrContradictoryWriteResponsesRemainUnconfirmed() = runBlocking {
        for ((status, response) in listOf(
            503 to "{\"code\":500,\"success\":false,\"msg\":\"上游异常\"}",
            200 to "{\"code\":500,\"success\":false,\"msg\":\"服务异常\"}",
            200 to "not-json", 200 to "{}",
            200 to "{\"code\":200,\"success\":false,\"data\":{\"retcode\":\"0\"}}",
            200 to "{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"different-card\"}}")) {
            val calls = mutableListOf<FakeResponse>()
            val api = fixtureCardServiceApi { url -> FakeResponse(url, when {
                url.path.endsWith("frontInfo") -> configuration("0", "0")
                url.path.endsWith("getCampusCards") -> cardResponse(1)
                else -> response
            }, if (url.path.endsWith("unlostCard")) status else 200).also(calls::add) }
            assertThrows(CardOperationUnconfirmed::class.java) {
                runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "") }
            }
            assertEquals(1, calls.count { it.request.method == "POST" })
        }
        var writes = 0
        val api = fixtureCardServiceApi { url ->
            if (url.path.endsWith("unlostCard")) { writes++; throw java.io.IOException("timeout") }
            FakeResponse(url, if (url.path.endsWith("frontInfo")) configuration("0", "0") else cardResponse(1))
        }
        assertThrows(CardOperationUnconfirmed::class.java) {
            runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "") }
        }
        assertEquals(1, writes)
    }

    @Test fun acceptedWriteWithNoAccountEchoStillRequiresConfirmedTargetState() = runBlocking {
        for (postReadFails in listOf(false, true)) {
            var writes = 0
            val api = fixtureCardServiceApi { url ->
                if (url.path.endsWith("getCampusCards") && writes > 0 && postReadFails) throw java.io.IOException("read failed")
                FakeResponse(url, when {
                    url.path.endsWith("frontInfo") -> configuration("0", "0")
                    url.path.endsWith("getCampusCards") -> cardResponse(if (writes > 0) 0 else 1)
                    else -> { writes++; "{\"code\":200,\"success\":true,\"data\":{\"retcode\":\"0\"}}" }
                })
            }
            if (postReadFails) assertThrows(CardOperationUnconfirmed::class.java) {
                runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "") }
            }
            else assertFalse(api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "").single().lost)
            assertEquals(1, writes)
        }
    }

    @Test fun acceptedButUnchangedStateRemainsUnconfirmedAndAuthenticationIsDistinct() = runBlocking {
        val api = fixtureCardServiceApi { url -> FakeResponse(url, when {
            url.path.endsWith("frontInfo") -> configuration("0", "0")
            url.path.endsWith("getCampusCards") -> cardResponse(1)
            else -> "{\"code\":200,\"data\":{\"retcode\":\"0\"}}"
        }) }
        assertThrows(CardOperationUnconfirmed::class.java) {
            runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "") }
        }
        val auth = fixtureCardServiceApi { url -> FakeResponse(url, when {
            url.path.endsWith("frontInfo") -> configuration("0", "0")
            url.path.endsWith("getCampusCards") -> cardResponse(1)
            else -> "not-json"
        }, if (url.path.endsWith("unlostCard")) 401 else 200) }
        assertThrows(AuthenticationRequired::class.java) {
            runBlocking { auth.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "") }
        }
        Unit
    }

    @Test fun cardStateReadPreservesBackendFailureBeforeSubmitting() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        val api = fixtureCardServiceApi { url -> FakeResponse(url, if (url.path.endsWith("frontInfo")) configuration()
            else "{\"code\":200,\"data\":{\"retcode\":\"60003\",\"errmsg\":\"校园卡查询失败的原始提示\"}}").also(calls::add) }
        val error = assertThrows(CardOperationNotStarted::class.java) {
            runBlocking { api.changeCardState("fake-token", selected(true), CampusCardAction.Unlost, "012345") }
        }
        assertEquals("校园卡查询失败的原始提示", error.message)
        assertTrue(calls.none { it.request.method == "POST" })
    }

}
