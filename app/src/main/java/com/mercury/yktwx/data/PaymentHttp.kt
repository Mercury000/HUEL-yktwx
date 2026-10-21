package com.mercury.yktwx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** Shared first-party transport; no Cookie jar, redirects or retries of POSTs. */
internal class PaymentHttp(private val openConnection: (URL) -> HttpsURLConnection) {
    internal class Response(val status: Int, val body: String, val location: String?)
    suspend fun request(path: String, token: String, params: Map<String, String> = emptyMap(), form: Map<String, String>? = null,
        chargeClient: Boolean = false, jsonBody: JSONObject? = null): Response {
        require(form == null || jsonBody == null)
        return exchange(URI(RechargeProtocol.SCHOOL + path + if (form == null && jsonBody == null)
            "?" + encode(params + ("synAccessSource" to "h5")) else ""), token, form, chargeClient = chargeClient, jsonBody = jsonBody)
    }
    private suspend fun exchange(uri: URI, token: String?, form: Map<String, String>?, bridge: Boolean = false,
        chargeClient: Boolean = false, jsonBody: JSONObject? = null): Response = withContext(Dispatchers.IO) {
        val connection = openConnection(uri.toURL())
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-store")
            if (bridge) {
                connection.setRequestProperty("Referer", RechargeProtocol.SCHOOL + "/")
                connection.setRequestProperty("Accept", "text/html")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
            } else {
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("synAccessSource", "h5")
                connection.setRequestProperty("synjones-auth", "bearer " + token)
                if (chargeClient) connection.setRequestProperty("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("charge:charge_secret".toByteArray(Charsets.UTF_8)))
            }
            if (form != null || jsonBody != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (jsonBody != null) "application/json;charset=UTF-8" else "application/x-www-form-urlencoded")
                val bytes = (jsonBody?.toString() ?: encode(form!!)).toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readNBytes(1_048_577) } ?: byteArrayOf()
            if (bytes.size > 1_048_576) throw YktException("服务响应异常，请在官网核实订单")
            Response(status, String(bytes, Charsets.UTF_8), connection.getHeaderField("Location"))
        } catch (e: YktException) { throw e }
        catch (_: java.io.IOException) { throw NetworkFailure() }
        finally { connection.disconnect() }
    }
    fun json(response: Response, feePayment: Boolean = false): JSONObject {
        val body = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.status in listOf(401, 403) || body?.optInt("code") in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
        if (feePayment && response.status == 200 && body != null) {
            FeePaymentErrors.rejection(body)?.let { throw it }
        }
        if (response.status !in 200..299 || body?.optInt("code") != 200) throw YktException("缴费服务不可用，请在官网核实账户或已有订单")
        return body
    }
    suspend fun wechatLaunch(html: String, pending: PendingRecharge, feePayment: Boolean = false): WechatRechargeLaunch {
        val action = RechargeProtocol.paymentFormAction(html, pending, feePayment)
        val response = exchange(action, null, emptyMap(), bridge = true)
        if (response.status != 200) throw YktException("微信支付跳转未确认，请查询已有订单")
        return RechargeProtocol.wechatUri(response.body, RechargeProtocol.query(action).getValue("prepay_id"))
    }
    private fun encode(params: Map<String, String>) = params.entries.joinToString("&") {
        URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
    }
}
