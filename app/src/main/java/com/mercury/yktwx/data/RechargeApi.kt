package com.mercury.yktwx.data

import okhttp3.Call
import org.json.JSONObject

/** No browser, Cookie, redirect following, JS execution, or side-effect retries. */
class RechargeApi(private val client: Call.Factory = AppHttp.service) : RechargeService {
    private val http = PaymentHttp(client)
    private suspend fun request(path: String, token: String, params: Map<String, String> = emptyMap(), form: Map<String, String>? = null) =
        http.request(path, token, params, form)
    private suspend fun json(response: PaymentHttp.Response) = http.json(response, rechargePayment = true)
    override suspend fun rechargeConfiguration(token: String, preferredTarget: String?): RechargeConfiguration {
        val data = json(request("/berserker-app/frontInfo", token)).getJSONObject("data")
        val front = JSONObject(data.getString("getFrontConfig"))
        val ecard = JSONObject(data.getString("getEcardConfig"))
        val id = front.rechargeText("recharge")
        if (!id.matches(Regex("[0-9]{1,12}"))) throw YktException("充值项目配置异常")
        val fee = json(request("/charge/feeitem/singleFeeitem", token, mapOf("feeitemid" to id))).getJSONObject("feeitem")
        val account = selectedAccount(token, preferredTarget, ecard)
        return amountConfiguration(id, account, front, fee)
    }
    companion object {
        internal fun amountConfiguration(id: String, target: String, front: JSONObject,
            fee: JSONObject, recipient: RechargeRecipient? = null): RechargeConfiguration {
            val minimum = fee.rechargeLimit("retain_money")
            // The official page applies the fee item's bound after the general front config.
            val maximum = fee.rechargeLimit("maxmoney") ?: front.rechargeLimit("rechargeMaxAmount")
            val options = fee.rechargeText("layout").split(',').mapNotNull { RechargeMoney.cents(it.trim()) }
                .distinct().filter { (minimum == null || it >= minimum) && (maximum == null || it <= maximum) }
            val flag = fee.rechargeText("flag").getOrNull(7)
            if (minimum != null && maximum != null && minimum > maximum || (flag == '1' && options.isEmpty()))
                throw YktException("没有可用的充值金额")
            return RechargeConfiguration(id, target, options, minimum, maximum, flag != '1', flag == '2', recipient)
        }
    }

    private suspend fun selectedAccount(
        token: String,
        preferredTarget: String?,
        ecard: JSONObject,
    ): String {
        val cardType = ecard.rechargeText("type")
        val data = json(request("/berserker-app/ykt/tsm/queryCard", token, mapOf("scene" to "recharge"))).getJSONObject("data")
        if (data.rechargeText("retcode") != "0") throw YktException("没有可充值账户")
        val cards = data.optJSONArray("card") ?: throw YktException("没有可充值账户")
        val candidates = mutableListOf<String>()
        val sameCardCandidates = mutableListOf<String>()
        var unavailable: RechargeTargetUnavailable? = null
        for (i in 0 until cards.length()) {
            val card = cards.getJSONObject(i)
            val matches = preferredTarget != null && (card.rechargeText("account") == preferredTarget ||
                card.optJSONArray("accinfo")?.let { a -> (0 until a.length()).any {
                    a.optJSONObject(it)?.rechargeText("type") == preferredTarget
                } } == true)
            try { RechargeErrors.requireAvailable(card, ecard) }
            catch (e: RechargeTargetUnavailable) {
                if (matches) throw e
                unavailable = unavailable ?: e
                continue
            }
            val cardAccount = card.rechargeText("account")
            val cardCandidates = mutableListOf<String>()
            if (cardType != "1" && cardAccount.isNotBlank()) {
                cardCandidates += cardAccount
            }
            if (cardType != "2") {
                val accounts = card.optJSONArray("accinfo")
                val electronic = mutableListOf<String>()
                for (j in 0 until (accounts?.length() ?: 0)) {
                    val a = accounts!!.getJSONObject(j)
                    val target = a.rechargeText("type")
                    if (target.isNotBlank()) electronic += target
                }
                // Match the website's default electronic-account ordering.
                cardCandidates += electronic.sorted()
            }
            candidates += cardCandidates
            if (cardAccount == preferredTarget) sameCardCandidates += cardCandidates
        }
        if (preferredTarget != null) {
            // Keep initial selection scoped to the homepage card.
            sameCardCandidates.firstOrNull()?.let { return it }
            candidates.firstOrNull { it == preferredTarget }?.let { return it }
            throw YktException("当前卡片没有可充值账户")
        }
        return candidates.firstOrNull() ?: throw (unavailable ?: RechargeTargetUnavailable("没有可充值的正常账户"))
    }
    override suspend fun createRecharge(token: String, config: RechargeConfiguration, amountCents: Long): RechargeOrder {
        config.amountError(amountCents)?.let { throw YktException(it) }
        if (config.recipient != null && config.recipient.targetId != config.targetId) throw YktException("收款账户不匹配，请重新查询")
        val form = RechargeProtocol.signed(mapOf(
            "feeitemid" to config.feeItemId, "appid" to "56321", "tranamt" to RechargeMoney.yuan(amountCents),
            "source" to "app", "synjones-auth" to "bearer " + token, "yktcard" to config.targetId,
            "synAccessSource" to "h5", "abstracts" to JSONObject().put("type", "recharge").put("othersRecharge", config.recipient != null).toString(),
        ))
        val response = request("/charge/order/thirdOrder", token, form = form)
        RechargeErrors.creationFailure(response)
        if (response.status != 302 || response.location == null) {
            throw YktException("充值下单响应异常")
        }
        return RechargeProtocol.orderFromLocation(response.location)
    }
    override suspend fun launchRecharge(token: String, pending: PendingRecharge): WechatRechargeLaunch {
        val order = pending.orderId ?: throw YktException("订单号缺失")
        val info = json(request("/charge/pay/getpayinfo", token, mapOf("orderid" to order, "userAgent" to "h5")))
        val o = info.getJSONObject("order")
        if (o.rechargeText("orderid") != order || RechargeMoney.responseCents(o.opt("tranamt")) != pending.amountCents) throw YktException("充值订单信息不匹配")
        val target = o.rechargeText("third_party")
        if (target.isNotBlank() && target != pending.targetId) throw YktException("订单收款账户不匹配，请在官网核实")
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
        val response = request("/blade-pay/pay", token, form = form)
        RechargeErrors.paymentFailure(response)
        val payload = json(response).getJSONObject("data")
        if (payload.rechargeText("orderid") != order) throw YktException("支付订单响应不匹配")
        return http.wechatLaunch(payload.rechargeText("paysubmit"), pending)
    }
    override suspend fun rechargeResult(token: String, pending: PendingRecharge): RechargeResult {
        val order = pending.orderId ?: throw YktException("订单号缺失")
        val payload = json(request("/charge/order/personal_data", token, buildMap { put("orderid", order); pending.payId?.let { put("payid", it) } }))
        val list = payload.optJSONArray("orderList") ?: throw YktException("订单查询响应异常")
        // The official result page synthesizes status=0 for an empty orderList.
        // Only a valid successful response with an actual empty array uses this
        // fallback; request/response errors remain request errors.
        if (list.length() == 0) return RechargeResult(false, 0)
        if (list.length() != 1) throw YktException("订单查询响应异常")
        val o = list.getJSONObject(0)
        if (o.rechargeText("orderid") != order) throw YktException("订单结果与本次充值不匹配")
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
        if (!matches) throw YktException("订单结果与本次充值不匹配")
        if (!paid) return RechargeResult(false, status, (o.opt("msg") as? String)?.takeIf { it.isNotEmpty() })
        if (RechargeMoney.responseCents(o.opt("actulamt")) != pending.amountCents) throw YktException("实付金额异常")
        return RechargeResult(true, status)
    }
}
