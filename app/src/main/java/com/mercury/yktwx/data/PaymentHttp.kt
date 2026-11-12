package com.mercury.yktwx.data

import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Shared first-party transport; no Cookie jar, redirects or retries of POSTs. */
internal class PaymentHttp(private val client: Call.Factory = AppHttp.service) {
    internal class Response(val status: Int, val body: String, val location: String?)
    suspend fun request(path: String, token: String, params: Map<String, String> = emptyMap(), form: Map<String, String>? = null,
        chargeClient: Boolean = false, jsonBody: JSONObject? = null): Response {
        require(form == null || jsonBody == null)
        val url = (RechargeProtocol.SCHOOL + path).toHttpUrl().newBuilder().apply {
            if (form == null && jsonBody == null) (params + ("synAccessSource" to "h5")).forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return exchange(url.toUri(), token, form, chargeClient = chargeClient, jsonBody = jsonBody)
    }
    private suspend fun exchange(uri: URI, token: String?, form: Map<String, String>?, bridge: Boolean = false,
        chargeClient: Boolean = false, jsonBody: JSONObject? = null): Response {
        try {
            val request = AppHttp.request(uri.toString())
            if (bridge) {
                request.header("Referer", RechargeProtocol.SCHOOL + "/")
                request.header("Accept", "text/html")
                request.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            } else {
                request.header("synAccessSource", "h5")
                request.header("synjones-auth", "bearer " + token)
                if (chargeClient) request.header("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("charge:charge_secret".toByteArray(Charsets.UTF_8)))
            }
            if (form != null || jsonBody != null) {
                request.post(jsonBody?.toString()?.toRequestBody("application/json;charset=UTF-8".toMediaType()) ?: AppHttp.form(form!!))
            }
            return client.readResponse(request.build()) { response ->
                val bytes = response.readBytesAtMost(1_048_576)
                if (bytes.size > 1_048_576) throw YktException("服务器响应异常")
                Response(response.code, String(bytes, Charsets.UTF_8), response.header("Location"))
            }
        } catch (e: YktException) { throw e }
        catch (e: java.io.IOException) { throw NetworkFailure(cause = e) }
    }
    suspend fun json(response: Response, feePayment: Boolean = false, preserveAuthenticationMessage: Boolean = false,
        rechargePayment: Boolean = false): JSONObject = withContext(Dispatchers.Default) {
        if (feePayment) PaymentErrors.redirectMessage(response)?.let {
            throw FeePaymentRejected(FeeRejectionReason.Other, it)
        }
        val body = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.status in listOf(401, 403) || body?.optInt("code") in listOf(401, 403, 4011, 4030, 4037, 4038)) {
            val msg = (body?.opt("msg") as? String)?.takeIf { it.isNotEmpty() }
            throw AuthenticationRequired(if (preserveAuthenticationMessage || feePayment || rechargePayment)
                msg ?: "登录已失效，请重新登录" else "登录已失效，请重新登录")
        }
        if (feePayment && response.status == 200 && body != null) {
            FeePaymentErrors.rejection(body)?.let { throw it }
        }
        val accepted = body != null && when {
            feePayment -> response.status == 200 && PaymentErrors.feeSucceeded(body)
            rechargePayment -> response.status == 200 && PaymentErrors.rechargeSucceeded(body)
            else -> response.status in 200..299 && body.optInt("code") == 200 && body.opt("success") != false
        }
        if (!accepted) {
            // Show the official business message without deciding payment outcome from its text.
            val hint = (body?.opt("msg") as? String)?.takeIf { it.isNotEmpty() }
            throw YktException(hint ?: if (body == null) "服务器响应异常" else "Error")
        }
        body
    }
    suspend fun wechatLaunch(html: String, pending: PendingRecharge, feePayment: Boolean = false): WechatRechargeLaunch = withContext(Dispatchers.Default) {
        val action = RechargeProtocol.paymentFormAction(html, pending, feePayment)
        val response = exchange(action, null, emptyMap(), bridge = true)
        if (response.status != 200) throw YktException("微信支付跳转失败")
        RechargeProtocol.wechatUri(response.body, RechargeProtocol.query(action).getValue("prepay_id"))
    }
}
