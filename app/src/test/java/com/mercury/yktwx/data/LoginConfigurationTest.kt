package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class LoginConfigurationTest {
    private fun method() = jsonObjectOf("key" to ("snoNew"), "encryption" to ("keyboard"), "openCaptcha" to (0))
    private fun data(method: JsonObject = method(), encoded: Boolean = true): JsonObject {
        val methods = jsonArrayOf(listOf(method))
        val front = jsonObjectOf("loginType" to (if (encoded) methods.toString() else methods))
        return jsonObjectOf("getFrontConfig" to (front.toString()))
    }
    @Test fun liveWebsiteDoubleEncodedFormatIsParsed() {
        val data = data()
        // The previous implementation throws before any password request is made.
        assertThrows(SerializationException::class.java) {
            parseObject(data.stringValue("getFrontConfig")).arrayValue("loginType")
        }
        assertEquals("snoNew", LoginConfiguration.selectLoginType(data))
    }
    @Test fun directArrayIsAlsoSupported() {
        assertEquals("snoNew", LoginConfiguration.selectLoginType(data(encoded = false)))
    }
    @Test fun directConfigObjectIsAlsoSupported() {
        val value = data().let { it.withField("getFrontConfig", parseObject(it.stringValue("getFrontConfig"))) }
        assertEquals("snoNew", LoginConfiguration.selectLoginType(value))
    }
    @Test fun missingKeyboardTypeUsesStandardDefault() {
        assertEquals("snoNew", LoginConfiguration.selectLoginType(data(method())))
    }
    @Test fun invalidConfigIsReportedWithoutServerContent() {
        val sensitive = "fake-secret-value"
        val error = assertThrows(YktException::class.java) {
            LoginConfiguration.selectLoginType(jsonObjectOf("getFrontConfig" to (sensitive)))
        }
        assertFalse(error.message.orEmpty().contains(sensitive))
        assertTrue(error.message.orEmpty().contains("配置解析失败"))
    }
    @Test fun unsupportedLoginMethodIsRejected() {
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data(method().withField("key", "phone"))) }
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data(method().withField("encryption", "other"))) }
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data(method().withField("keyboardType", "2"))) }
    }
    @Test fun captchaStopsInsteadOfSubmittingPassword() {
        assertThrows(CaptchaRequired::class.java) { LoginConfiguration.selectLoginType(data(method().withField("openCaptcha", 1))) }
    }
    @Test fun stringAndNullCaptchaAllowPasswordLogin() {
        for (value in listOf("0", "", JsonNull)) {
            assertEquals("snoNew", LoginConfiguration.selectLoginType(data(method().withField("openCaptcha", value))))
        }
    }
    @Test fun malformedLoginTypeIsRejected() {
        val data = jsonObjectOf("getFrontConfig" to (jsonObjectOf("loginType" to ("not-json")).toString()))
        assertThrows(YktException::class.java) { LoginConfiguration.selectLoginType(data) }
    }
}
