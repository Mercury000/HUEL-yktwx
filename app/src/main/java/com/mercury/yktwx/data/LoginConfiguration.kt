package com.mercury.yktwx.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** The live website JSON-encodes both getFrontConfig and its loginType property. */
internal object LoginConfiguration {
    fun selectLoginType(data: JSONObject): String {
        try {
            val config = when (val raw = data.opt("getFrontConfig")) {
                is String -> JSONObject(raw)
                is JSONObject -> raw
                else -> throw JSONException("Invalid front config type")
            }
            // Do not call getJSONArray on a JSON string: parse its contents first,
            // exactly as PasswordLoginClient._login_type does in payment_api.py.
            val methods = when (val raw = config.opt("loginType")) {
                is String -> JSONArray(raw)
                is JSONArray -> raw
                else -> throw JSONException("Invalid login type")
            }
            val method = (0 until methods.length()).mapNotNull { methods.optJSONObject(it) }
                .firstOrNull { it.optString("key") == "snoNew" }
                ?: throw YktException("官网登录配置已变化，请联系开发者")
            if (method.optString("encryption") != "keyboard" || method.optString("keyboardType", "1") != "1") {
                throw YktException("官网登录配置已变化，请联系开发者")
            }
            val captcha = method.opt("openCaptcha")
            if (captcha != null && captcha != JSONObject.NULL && captcha.toString() !in listOf("0", "")) {
                throw CaptchaRequired()
            }
            return method.getString("key")
        } catch (_: JSONException) {
            // No server content, passwords or tokens in error messages.
            throw YktException("官网登录配置解析失败，请更新应用或稍后重试")
        }
    }
}
