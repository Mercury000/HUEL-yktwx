package com.mercury.yktwx.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LoginConfigurationTest {
    private fun method() = JSONObject().put("key", "snoNew").put("encryption", "keyboard").put("openCaptcha", 0)
    private fun data(method: JSONObject = method(), encoded: Boolean = true): JSONObject {
        val methods = JSONArray().put(method)
        val front = JSONObject().put("loginType", if (encoded) methods.toString() else methods)
        return JSONObject().put("getFrontConfig", front.toString())
    }
    @Test fun liveWebsiteDoubleEncodedFormatIsParsed() {
        val data = data()
        // The previous implementation throws before any password request is made.
        assertThrows(JSONException::class.java) {
            JSONObject(data.getString("getFrontConfig")).getJSONArray("loginType")
        }
        assertEquals("snoNew", LoginConfiguration.selectLoginType(data))
    }
    @Test fun directArrayIsAlsoSupported() {
        assertEquals("snoNew", LoginConfiguration.selectLoginType(data(encoded = false)))
    }
    @Test fun directConfigObjectIsAlsoSupported() {
        val value = data(); value.put("getFrontConfig", JSONObject(value.getString("getFrontConfig")))
        assertEquals("snoNew", LoginConfiguration.selectLoginType(value))
    }
    @Test fun missingKeyboardTypeUsesStandardDefault() {
        assertEquals("snoNew", LoginConfiguration.selectLoginType(data(method())))
    }
    @Test fun invalidConfigIsReportedWithoutServerContent() {
        val sensitive = "fake-secret-value"
        val error = assertThrows(YktException::class.java) {
            LoginConfiguration.selectLoginType(JSONObject().put("getFrontConfig", sensitive))
        }
        assertFalse(error.message.orEmpty().contains(sensitive))
        assertTrue(error.message.orEmpty().contains("配置解析失败"))
    }
    @Test fun unsupportedLoginMethodIsRejected() {
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data(method().put("key", "phone"))) }
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data(method().put("encryption", "other"))) }
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data(method().put("keyboardType", "2"))) }
    }
    @Test fun captchaStopsInsteadOfSubmittingPassword() {
        assertThrows(CaptchaRequired::class.java) { LoginConfiguration.selectLoginType(data(method().put("openCaptcha", 1))) }
    }
    @Test fun stringAndNullCaptchaAllowPasswordLogin() {
        for (value in listOf("0", "", JSONObject.NULL)) {
            assertEquals("snoNew", LoginConfiguration.selectLoginType(data(method().put("openCaptcha", value))))
        }
    }
    @Test fun malformedLoginTypeIsRejected() {
        val data = JSONObject().put("getFrontConfig", JSONObject().put("loginType", "not-json").toString())
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data) }
    }
}
