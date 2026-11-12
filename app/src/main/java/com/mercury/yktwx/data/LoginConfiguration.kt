package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException

/** The live website JSON-encodes both getFrontConfig and its loginType property. */
internal object LoginConfiguration {
    fun selectLoginType(data: JsonObject): String {
        try {
            val rawConfig: Any? = data.scalarOrElement("getFrontConfig")
            val config = when (rawConfig) {
                is String -> parseObject(rawConfig)
                is JsonObject -> rawConfig
                else -> throw SerializationException("Invalid front config type")
            }
            // The website may return loginType as a JSON string; parse it before reading the array.
            val rawMethods: Any? = config.scalarOrElement("loginType")
            val methods = when (rawMethods) {
                is String -> jsonArrayOf(rawMethods)
                is JsonArray -> rawMethods
                else -> throw SerializationException("Invalid login type")
            }
            val method = (0 until methods.size).mapNotNull { methods.objectOrNull(it) }
                .firstOrNull { it.stringOrDefault("key") == "snoNew" }
                ?: throw YktException("官网登录配置已变化，请联系开发者")
            if (method.stringOrDefault("encryption") != "keyboard" || method.stringOrDefault("keyboardType", "1") != "1") {
                throw YktException("官网登录配置已变化，请联系开发者")
            }
            val captcha = method.scalarOrElement("openCaptcha")
            if (captcha != null && captcha != JsonNull && captcha.toString() !in listOf("0", "")) {
                throw CaptchaRequired()
            }
            return method.stringValue("key")
        } catch (_: SerializationException) {
            // No server content, passwords or tokens in error messages.
            throw YktException("官网登录配置解析失败，请更新应用或稍后重试")
        }
    }
}
