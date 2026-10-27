package com.mercury.yktwx.data

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Parses only allowlisted protocol fields; never evaluates the returned HTML or JavaScript. */
object RechargeProtocol {
    const val SCHOOL = "https://yktwx2.huel.edu.cn"
    private const val PUBLIC_APP_ID = "56321"
    // Public integration value shipped by the school frontend, NOT a WeChat merchant private key.
    private const val PUBLIC_FRONTEND_KEY = "0osTIhce7uPvDKHz6aa67bhCukaKoYl4"
    fun signed(fields: Map<String, String>, time: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")), nonce: String = UUID.randomUUID().toString().replace("-", "")): Map<String, String> {
        val params = fields + mapOf("APP_ID" to PUBLIC_APP_ID, "TIMESTAMP" to time, "SIGN_TYPE" to "SHA256", "NONCE" to nonce)
        val raw = params.toSortedMap().filter { (k, v) -> k !in listOf("SIGN", "SECRET_KEY") && v.isNotEmpty() }
            .entries.joinToString("") { "${it.key}=${it.value}&" } + "SECRET_KEY=" + PUBLIC_FRONTEND_KEY
        val sign = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        return params + ("SIGN" to sign)
    }
    fun query(uri: URI): Map<String, String> {
        val pairs = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map {
            val p = it.split('=', limit = 2)
            URLDecoder.decode(p[0], "UTF-8") to URLDecoder.decode(p.getOrElse(1) { "" }, "UTF-8")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size)
        return pairs.toMap()
    }
    fun orderFromLocation(location: String): RechargeOrder = runCatching {
        val uri = URI(SCHOOL + "/").resolve(location)
        require(uri.scheme == "https" && uri.host == "yktwx2.huel.edu.cn" && uri.port == -1 && uri.userInfo == null && uri.fragment == null)
        require(uri.path == "/payment/" && location.length < 16_384)
        val q = query(uri)
        val order = q["orderid"].orEmpty()
        require(validId(order) && q["paytype"] == "CAMPUSCARD")
        RechargeOrder(order, q["paytypeid"]?.also { require(it.matches(Regex("[0-9]{1,12}"))) })
    }.getOrElse { throw YktException("充值下单响应异常") }
    fun validId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,128}"))
    private fun htmlDecode(value: String) = value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    private fun attributes(tag: String): Map<String, String> {
        val matches = Regex("([A-Za-z][A-Za-z0-9_-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')").findAll(tag).toList()
        require(matches.map { it.groupValues[1].lowercase() }.distinct().size == matches.size)
        return matches.associate { it.groupValues[1].lowercase() to htmlDecode(it.groupValues[2].ifEmpty { it.groupValues[3] }) }
    }
    fun paymentFormAction(html: String, pending: PendingRecharge, feePayment: Boolean = false): URI = runCatching {
        require(html.length <= 65_536)
        val forms = Regex("<form\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).toList()
        require(forms.size == 1)
        val attrs = attributes(forms.single().value)
        require(attrs["method"].equals("post", ignoreCase = true))
        // Only the observed empty hidden input is supported. Never forward school credentials.
        Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach {
            val input = attributes(it.value)
            require(input["name"].isNullOrEmpty() && input["value"].isNullOrEmpty())
        }
        val uri = URI(attrs["action"] ?: error("No action"))
        require(uri.scheme == "https" && uri.host == "wx.tenpay.com" && uri.port == -1 && uri.userInfo == null && uri.fragment == null)
        require(uri.path == "/cgi-bin/mmpayweb-bin/checkmweb")
        val q = query(uri)
        require(q.keys == setOf("prepay_id", "ct", "sign", "package", "redirect_url"))
        require(q.values.all { it.isNotBlank() })
        val back = URI(q.getValue("redirect_url"))
        val returnPath = if (feePayment) "/charge-app" else "/payment/"
        require(back.scheme == "https" && back.host == "yktwx2.huel.edu.cn" && back.port == -1 && back.userInfo == null && back.fragment == null && back.path == returnPath)
        val bq = query(back)
        require(bq.keys == setOf("name", "orderid", "payid") && bq["name"] == (if (feePayment) "payResult" else "result") && bq["orderid"] == pending.orderId && bq["payid"] == pending.payId)
        uri
    }.getOrElse { throw YktException("暂不支持该微信支付响应") }
    fun wechatUri(html: String, expectedPrepayId: String? = null): WechatRechargeLaunch = runCatching {
        require(html.length <= 262_144)
        val uris = Regex("weixin://wap/pay\\?[^\\s\"'<>]+", RegexOption.IGNORE_CASE).findAll(html).map { it.value }.distinct().toList()
        require(uris.size == 1)
        val raw = uris.single()
        require(raw.length <= 4096 && !raw.contains('\\'))
        val uri = URI(raw)
        require(uri.scheme == "weixin" && uri.host == "wap" && uri.path == "/pay" && uri.userInfo == null && uri.port == -1 && uri.fragment == null)
        // The observed bridge encodes the first '=' as %3D. Preserve the original signed URI.
        val decoded = URLDecoder.decode(uri.rawQuery, "UTF-8")
        val q = query(URI("https://placeholder.invalid/?" + decoded))
        require(q.keys == setOf("prepayid", "package", "noncestr", "sign") && q.values.all { it.isNotBlank() })
        require(expectedPrepayId == null || q["prepayid"] == expectedPrepayId)
        WechatRechargeLaunch(raw)
    }.getOrElse { throw YktException("未取得受支持的微信跳转") }
}
