package com.mercury.yktwx.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL

class CardPasswordApiTest {
    private class FakeResponse(url: URL, private val body: String, private val status: Int = 200, private val timeout: Boolean = false) : HttpFixture(url) {
        val sent = ByteArrayOutputStream()
        var closed = false

        override fun close() { closed = true }

        override fun statusCode(): Int { if (timeout) throw IOException("fake timeout"); return status }
        override fun bodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun errorBodyStream() = ByteArrayInputStream(body.toByteArray())
        override fun requestBodyStream() = sent

    }
    private val card = CampusCard("fake-card", "校园卡", "202600000001", false, false, 0)
    private val configuration = JSONObject().put("code", 200).put("data", JSONObject()
        .put("getFrontConfig", "{}").put("getEcardConfig", "{}")).toString()
    private val cards = JSONObject().put("code", 200).put("data", JSONObject().put("retcode", "0").put("card", JSONArray().put(
        JSONObject().put("account", "fake-card").put("lostflag", 0).put("freezeflag", 0).put("acc_status", 0)))).toString()
    private val calls = mutableListOf<FakeResponse>()
    private fun api(result: String = "{\"code\":200,\"data\":{\"retcode\":\"0\"}}", status: Int = 200,
        timeout: Boolean = false, keyboard: String? = null, config: String = configuration,
        keyboardMappings: List<String> = listOf("abcdefghij", "klmnopqrst", "ABCDEFGHIJ")): CardServiceApi {
        var keyboardCount = 0
        return fixtureCardServiceApi { url ->
            val body = when {
                url.path.endsWith("frontInfo") -> config
                url.path.endsWith("getCampusCards") -> cards
                url.path.endsWith("keyboard") -> {
                    keyboardCount++
                    keyboard ?: JSONObject().put("code", 200).put("data", JSONObject()
                        .put("numberKeyboard", keyboardMappings[keyboardCount - 1])
                        .put("uuid", "fake-uuid-$keyboardCount")).toString()
                }
                url.path.endsWith("modifyPwd") -> result
                else -> error("Unexpected fake path: " + url.path)
            }
            FakeResponse(url, body, if (url.path.endsWith("modifyPwd")) status else 200,
                timeout && url.path.endsWith("modifyPwd")).also(calls::add)
        }
    }
    private fun change(api: CardServiceApi, old: String = "012345", new: String = "678901", confirmation: String = new) =
        runBlocking { api.modifyCardPassword("fake-token", card, old, new, confirmation) }
    private fun rejection(action: () -> Unit): YktException {
        try { action(); fail("Expected failure") } catch (e: YktException) { return e }
        error("unreachable")
    }
    @Test fun usesThreeIndependentFixedKeyboardsAndOneAuthenticatedJsonWrite() {
        change(api())
        val post = calls.single { it.request.method == "POST" }
        val body = JSONObject(post.sent.toString("UTF-8"))
        assertEquals("/berserker-app/ykt/tsm/modifyPwd", post.url.path)
        assertNull(post.url.query)
        assertEquals("fake-card", body.getString("account"))
        assertEquals("1\$1\$abcdef\$1\$fake-uuid-1", body.getString("oldpw"))
        assertEquals("1\$1\$qrstkl\$1\$fake-uuid-2", body.getString("newpw"))
        assertEquals("1\$1\$GHIJAB\$1\$fake-uuid-3", body.getString("renewpw"))
        assertEquals("h5", body.getString("synAccessSource"))
        assertFalse(body.has("pwdType"))
        assertEquals(5, body.length())
        assertEquals(3, calls.count { it.url.path.endsWith("keyboard") })
        assertTrue(calls.filter { it.url.path.endsWith("keyboard") }.all { it.url.query.contains("type=Number") && it.url.query.contains("order=0") })
        assertTrue(calls.all { it.request.header("Cookie") == null && it.request.header("synjones-auth") == "bearer fake-token" &&
            !AppHttp.service.followRedirects && it.closed })
        assertTrue(calls.none { it.url.path.endsWith("oauth/token") })
    }
    @Test fun dollarInAnyOfTheThreeKeyboardsIsAcceptedOnFirstSubmissionWithoutRetry() {
        for (fieldIndex in 0..2) {
            calls.clear()
            val mappings = MutableList(3) { "abcdefghij" }.apply { this[fieldIndex] = "\$bcdefghij" }
            change(api(keyboardMappings = mappings), new = "012345")
            val post = calls.single { it.request.method == "POST" }
            val body = JSONObject(post.sent.toString("UTF-8"))
            val field = listOf("oldpw", "newpw", "renewpw")[fieldIndex]
            assertEquals("1\$1\$\$bcdef\$1\$fake-uuid-${fieldIndex + 1}", body.getString(field))
            assertEquals(3, calls.count { it.url.path.endsWith("keyboard") })
            assertEquals(1, calls.count { it.request.method == "POST" })
        }
    }
    @Test fun outerFailurePreservesMsgExactly() {
        val msg = "  服务端原始错误\n请稍后再试  "
        val result = JSONObject().put("code", 500).put("msg", msg).toString()
        assertEquals(msg, rejection { change(api(result)) }.message)
        assertEquals(1, calls.count { it.request.method == "POST" })
    }
    @Test fun innerFailurePreservesErrmsgRatherThanOuterSuccessText() {
        val msg = "  原密码校验失败\n请重新输入 "
        val result = JSONObject().put("code", 200).put("success", true).put("msg", "封装成功")
            .put("data", JSONObject().put("retcode", "60004").put("errmsg", msg)).toString()
        assertEquals(msg, rejection { change(api(result)) }.message)
        assertEquals(1, calls.count { it.request.method == "POST" })
    }
    @Test fun innerFailureWithoutErrmsgUsesOriginalMsg() {
        val result = "{\"code\":200,\"msg\":\"official fallback\",\"data\":{\"retcode\":\"9\"}}"
        assertEquals("official fallback", rejection { change(api(result)) }.message)
    }
    @Test fun authenticationRejectPreservesOfficialMsgAndNeverFollowsRedirects() {
        val result = "{\"code\":401,\"msg\":\"official expired message\"}"
        val failure = rejection { change(api(result, status = 401)) }
        assertTrue(failure is AuthenticationRequired)
        assertEquals("official expired message", failure.message)
        assertEquals(1, calls.count { it.request.method == "POST" })
    }
    @Test fun http200WithoutSuccessfulInnerResultIsNotSuccess() {
        for (result in listOf("{\"code\":200,\"data\":{}}", "{\"code\":200}", "not-json",
            "{\"code\":200,\"success\":false,\"msg\":\"rejected\",\"data\":{\"retcode\":\"0\"}}")) {
            calls.clear()
            rejection { change(api(result)) }
            assertEquals(1, calls.count { it.request.method == "POST" })
        }
    }
    @Test fun mismatchedAccountEchoIsNotSuccess() {
        rejection { change(api("{\"code\":200,\"data\":{\"retcode\":\"0\",\"account\":\"other-card\"}}")) }
        assertEquals(1, calls.count { it.request.method == "POST" })
    }
    @Test fun postTimeoutIsUnconfirmedAndIsNeverReplayed() {
        assertTrue(rejection { change(api(timeout = true)) }.message!!.contains("未确认"))
        assertEquals(1, calls.count { it.request.method == "POST" })
    }
    @Test fun invalidInputDoesNotReadOrWriteAnything() {
        val api = api()
        rejection { change(api, old = "12345") }
        rejection { change(api, new = "12345x") }
        rejection { change(api, confirmation = "111111") }
        assertTrue(calls.isEmpty())
    }
    @Test fun badKeyboardStopsBeforeWriteAndPreservesItsOfficialFailure() {
        val msg = "keyboard server message"
        assertEquals(msg, rejection { change(api(keyboard = "{\"code\":500,\"msg\":\"$msg\"}")) }.message)
        assertTrue(calls.none { it.request.method == "POST" })
    }
    @Test fun preflightAuthenticationRejectionPreservesOriginalMessageAndNeverWrites() {
        val msg = "  official keyboard authentication message\n"
        val keyboard = JSONObject().put("code", 401).put("msg", msg).toString()
        val failure = rejection { change(api(keyboard = keyboard)) }
        assertTrue(failure is AuthenticationRequired)
        assertEquals(msg, failure.message)
        assertTrue(calls.none { it.request.method == "POST" })
    }
    @Test fun unsupportedPasswordRulesStopBeforeWrite() {
        val config = JSONObject().put("code", 200).put("data", JSONObject().put("getFrontConfig", "{}")
            .put("getEcardConfig", "{\"passwordType\":\"1\"}")).toString()
        rejection { change(api(config = config)) }
        assertTrue(calls.none { it.request.method == "POST" || it.url.path.endsWith("keyboard") })
    }

    @Test fun passwordErrorScopeUsesResponseCodeAndPreservesOfficialText() {
        val password = rejection { change(api("{\"code\":200,\"data\":{\"retcode\":\"60005\",\"errmsg\":\"untranslated error\"}}")) }
        assertEquals("untranslated error", password.message)
        assertEquals(setOf(FormField.OldPassword), inputErrorFields(password))
        val other = rejection { change(api("{\"code\":200,\"data\":{\"retcode\":\"60050\",\"errmsg\":\"密码服务异常\"}}")) }
        assertTrue(inputErrorFields(other).isEmpty())
    }

}
