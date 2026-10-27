package com.mercury.yktwx.data

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** Shared school payment responses; messages describe the error, never define local amount rules. */
internal object PaymentErrors {
    // The two official cashiers have different success predicates. Neither uses
    // success/data or a list of known failure codes to decide its error callback.
    fun rechargeSucceeded(body: JSONObject) = body.opt("code") == 200
    fun feeSucceeded(body: JSONObject): Boolean = body.opt("code") == 200 ||
        body.opt("code") == 302 || body.opt("state") == 200 ||
        (!truthy(body.opt("code")) && !truthy(body.opt("state")))

    private fun truthy(value: Any?): Boolean = when (value) {
        null, JSONObject.NULL, false, "" -> false
        is Number -> value.toDouble() != 0.0
        else -> true
    }

    /** Decode the official error page locally, without following its redirect or replaying a POST. */
    fun redirectMessage(response: PaymentHttp.Response): String? {
        if (response.status != 302) return null
        val location = response.location ?: return null
        return runCatching {
            require(location.length < 16_384)
            val uri = URI(RechargeProtocol.SCHOOL + "/").resolve(location)
            require(uri.scheme == "https" && uri.host == "yktwx2.huel.edu.cn" &&
                uri.port == -1 && uri.userInfo == null && uri.fragment == null && uri.path == "/plat/notfound")
            val query = RechargeProtocol.query(uri)
            require(query.keys.all { it in setOf("msg", "type", "backUrl", "msgLanguage", "code") })
            // These branches replace msg with configured/localized text on the official page.
            require(query["type"] != "ecard" && query["msgLanguage"].isNullOrEmpty() && query["code"].isNullOrEmpty())
            val encoded = query.getValue("msg")
            val decoded = if (Regex("%[0-9a-fA-F]{2}").containsMatchIn(encoded))
                URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") else encoded
            decoded.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
