package com.mercury.yktwx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/** No browser, Cookie, redirect following, JS execution, or side-effect retries. */
class RechargeApi(private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : RechargeService {
    private class Response(val status: Int, val body: String, val location: String?)
    private suspend fun request(path: String, token: String, params: Map<String, String> = emptyMap(), form: Map<String, String>? = null): Response =
        exchange(URI(RechargeProtocol.SCHOOL + path + if (form == null) "?" + encode(params + ("synAccessSource" to "h5")) else ""), token, form)
    private suspend fun exchange(uri: URI, token: String?, form: Map<String, String>?, bridge: Boolean = false): Response = withContext(Dispatchers.IO) {
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
            }
            if (form != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val bytes = encode(form).toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readNBytes(1_048_577) } ?: byteArrayOf()
            if (bytes.size > 1_048_576) throw YktException("充值响应异常，请在官网核实订单")
            Response(status, String(bytes, Charsets.UTF_8), connection.getHeaderField("Location"))
        } catch (e: YktException) { throw e }
        catch (_: java.io.IOException) { throw NetworkFailure() }
        finally { connection.disconnect() }
    }
    private fun json(response: Response): JSONObject {
        val body = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.status in listOf(401, 403) || body?.optInt("code") in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
        if (response.status !in 200..299 || body?.optInt("code") != 200) throw YktException("充值服务不可用，请在官网核实账户或已有订单")
        return body
    }
    override suspend fun rechargeConfiguration(token: String, preferredTarget: String?): RechargeConfiguration {
        val data = json(request("/berserker-app/frontInfo", token)).getJSONObject("data")
        val front = JSONObject(data.getString("getFrontConfig"))
        val ecard = JSONObject(data.getString("getEcardConfig"))
        val id = front.rechargeText("recharge")
        if (!id.matches(Regex("[0-9]{1,12}"))) throw YktException("充值项目配置异常")
        val fee = json(request("/charge/feeitem/singleFeeitem", token, mapOf("feeitemid" to id))).getJSONObject("feeitem")
        val minimum = fee.rechargeText("retain_money").takeIf { it.isNotBlank() }?.let(RechargeMoney::cents) ?: 1L
        val configuredMax = listOf(fee.rechargeText("maxmoney"), front.rechargeText("rechargeMaxAmount"))
            .filter { it.isNotBlank() }.map { RechargeMoney.cents(it) ?: throw YktException("充值限额配置异常") }.minOrNull()
        // Conservative local cap when server gives no upper limit; not a claim of server acceptance.
        val maximum = minOf(configuredMax ?: 50_000L, 50_000L)
        val options = fee.rechargeText("layout").split(',').mapNotNull { RechargeMoney.cents(it.trim()) }
            .distinct().filter { it in minimum..maximum }
        val flag = fee.rechargeText("flag").getOrNull(7)
        // The homepage passes the parent card account, not payment's payacc.
        // Resolve the recharge ID from queryCard; pending-order checks stay exact.
        val account = selectedAccount(token, preferredTarget, ecard.rechargeText("type"), allowCardMatch = true)
        if (minimum > maximum || (flag == '1' && options.isEmpty())) throw YktException("没有可用的充值金额")
        return RechargeConfiguration(id, account.first, account.second.first, account.second.second, options, minimum, maximum, flag != '1', flag == '2')
    }
    private suspend fun selectedAccount(
        token: String,
        preferredTarget: String?,
        cardType: String = "1",
        allowCardMatch: Boolean = false,
    ): Pair<String, Pair<String, Long>> {
        val data = json(request("/berserker-app/ykt/tsm/queryCard", token, mapOf("scene" to "recharge"))).getJSONObject("data")
        if (data.rechargeText("retcode") != "0") throw YktException("没有可充值账户")
        val cards = data.optJSONArray("card") ?: throw YktException("没有可充值账户")
        val candidates = mutableListOf<Pair<String, Pair<String, Long>>>()
        val sameCardCandidates = mutableListOf<Pair<String, Pair<String, Long>>>()
        for (i in 0 until cards.length()) {
            val card = cards.getJSONObject(i)
            if (card.optInt("lostflag") != 0 || card.optInt("freezeflag") != 0) continue
            val cardAccount = card.rechargeText("account")
            val cardCandidates = mutableListOf<Pair<String, Pair<String, Long>>>()
            if (cardType != "1" && cardAccount.isNotBlank()) {
                cardCandidates += cardAccount to (card.rechargeText("cardname").ifBlank { "校园卡" } to (card.optLong("db_balance") + card.optLong("unsettle_amount")))
            }
            if (cardType != "2") {
                val accounts = card.optJSONArray("accinfo")
                val electronic = mutableListOf<Pair<String, Pair<String, Long>>>()
                for (j in 0 until (accounts?.length() ?: 0)) {
                    val a = accounts!!.getJSONObject(j)
                    val target = a.rechargeText("type")
                    if (target.isNotBlank()) electronic += target to (a.rechargeText("name").ifBlank { "统一账户" } to a.optLong("balance"))
                }
                // Match the website's default electronic-account ordering.
                cardCandidates += electronic.sortedBy { it.first }
            }
            candidates += cardCandidates
            if (cardAccount == preferredTarget) sameCardCandidates += cardCandidates
        }
        if (preferredTarget != null) {
            if (allowCardMatch) {
                // Initial selection is scoped to the homepage card. Never fall back
                // to another card just because payment's payacc differs.
                sameCardCandidates.firstOrNull()?.let { return it }
            }
            candidates.firstOrNull { it.first == preferredTarget }?.let { return it }
            throw YktException(if (allowCardMatch) "当前卡片没有可充值账户" else "原充值账户不可用，请在官网核实")
        }
        return candidates.firstOrNull() ?: throw YktException("没有可充值的正常账户")
    }
    override suspend fun createRecharge(token: String, config: RechargeConfiguration, amountCents: Long): RechargeOrder {
        config.amountError(amountCents)?.let { throw YktException(it) }
        val form = RechargeProtocol.signed(mapOf(
            "feeitemid" to config.feeItemId, "appid" to "56321", "tranamt" to RechargeMoney.yuan(amountCents),
            "source" to "app", "synjones-auth" to "bearer " + token, "yktcard" to config.targetId,
            "synAccessSource" to "h5", "abstracts" to "{\"type\":\"recharge\",\"othersRecharge\":false}",
        ))
        val response = request("/charge/order/thirdOrder", token, form = form)
        if (response.status != 302 || response.location == null) {
            // Never treat an ambiguous POST outcome as permission to create another order.
            throw YktException("充值下单状态未确认，请在官网核实，勿重复充值")
        }
        return RechargeProtocol.orderFromLocation(response.location)
    }
    override suspend fun launchRecharge(token: String, pending: PendingRecharge): WechatRechargeLaunch {
        val order = pending.orderId ?: throw YktException("订单号尚未确认，请在官网核实")
        val info = json(request("/charge/pay/getpayinfo", token, mapOf("orderid" to order, "userAgent" to "h5")))
        val o = info.getJSONObject("order")
        if (o.rechargeText("orderid") != order || RechargeMoney.responseCents(o.opt("tranamt")) != pending.amountCents) throw YktException("充值订单信息不匹配")
        if (o.optInt("status", -1) == 1) throw YktException("订单已支付，请查询结果")
        val methods = info.optJSONArray("payList") ?: throw YktException("没有可用的微信支付方式")
        val method = (0 until methods.length()).map { methods.getJSONObject(it) }
            .firstOrNull { it.rechargeText("code") == "CAMPUSCARD" && it.rechargeText("remark") == "weixin" && it.rechargeText("payid") == pending.payId }
            ?: throw YktException("该订单微信支付方式已变化，请在官网处理")
        // HAR confirms synAccessSource is added AFTER signing this payment request.
        val form = RechargeProtocol.signed(mapOf(
            "opAppId" to "", "paytypeid" to method.rechargeText("payid"), "paytype" to method.rechargeText("code"),
            "paystep" to "2", "orderid" to order, "redirect_url" to RechargeProtocol.SCHOOL + "/payment/?name=result", "userAgent" to "h5",
        )) + ("synAccessSource" to "h5")
        val payload = json(request("/blade-pay/pay", token, form = form)).getJSONObject("data")
        if (payload.rechargeText("orderid") != order) throw YktException("支付订单响应不匹配")
        val action = RechargeProtocol.paymentFormAction(payload.rechargeText("paysubmit"), pending)
        val bridge = exchange(action, null, emptyMap(), bridge = true)
        if (bridge.status != 200) throw YktException("微信支付跳转未确认，请查询已有订单")
        return RechargeProtocol.wechatUri(bridge.body, RechargeProtocol.query(action).getValue("prepay_id"))
    }
    override suspend fun rechargeResult(token: String, pending: PendingRecharge): RechargeResult {
        val order = pending.orderId ?: throw YktException("订单号未确认，请在官网核实，勿重复充值")
        val payload = json(request("/charge/order/personal_data", token, buildMap { put("orderid", order); pending.payId?.let { put("payid", it) } }))
        val list = payload.optJSONArray("orderList") ?: throw YktException("订单结果未确认")
        // The official result page synthesizes status=0 for an empty orderList.
        // Only a valid successful response with an actual empty array uses this
        // fallback; HTTP errors, malformed responses and mismatches stay unknown.
        if (list.length() == 0) return RechargeResult(false, pending.amountCents, null, 0)
        if (list.length() != 1) throw YktException("订单结果未确认")
        val o = list.getJSONObject(0)
        if (o.rechargeText("orderid") != order) throw YktException("订单结果与本次充值不匹配，请在官网核实")
        // Unlike the empty-list fallback, null status on an existing record is
        // NOT explicitly normalized by the official normal H5 result flow.
        val status = o.rechargeText("status").toIntOrNull() ?: -1
        val paid = status == 1
        fun textMatches(key: String, expected: String): Boolean {
            val value = o.rechargeText(key)
            return (!paid && value.isBlank()) || value == expected
        }
        val amountText = o.rechargeText("tranamt")
        val amountMatches = (!paid && amountText.isBlank()) ||
            RechargeMoney.responseCents(o.opt("tranamt")) == pending.amountCents
        val fees = o.optJSONArray("feeitemlist")
        val feesAbsent = o.isNull("feeitemlist") || fees?.length() == 0
        val feeMatches = (!paid && feesAbsent) ||
            (fees != null && (0 until fees.length()).any { fees.getJSONObject(it).rechargeText("feeitemid") == pending.feeItemId })
        // Payment completion fields may be absent on a provisional/unpaid order.
        // Present conflicting fields are still rejected; a successful order must
        // include all fields needed to prove its account, amount and fee item.
        val matches = textMatches("third_party", pending.targetId) && amountMatches &&
            (pending.payId == null || textMatches("payid", pending.payId)) && feeMatches
        if (!matches) throw YktException("订单结果与本次充值不匹配，请在官网核实")
        if (!paid) return RechargeResult(false, pending.amountCents, null, status)
        if (RechargeMoney.responseCents(o.opt("actulamt")) != pending.amountCents) throw YktException("实付金额异常，请在官网核实")
        return RechargeResult(true, pending.amountCents, null, status)
    }
    private fun encode(params: Map<String, String>) = params.entries.joinToString("&") { URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8) }
}
