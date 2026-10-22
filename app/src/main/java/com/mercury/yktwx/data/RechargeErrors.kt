package com.mercury.yktwx.data

/** Payment parameters were explicitly refused; no external payment was launched. */
internal class RechargePaymentRejected(hint: String? = null) : YktException(
    hint?.takeIf { it.isNotEmpty() } ?: "Error",
)
/** A read-only check refused the target before creating any order. */
internal class RechargeTargetUnavailable(message: String) : YktException(message)

internal object RechargeErrors {
    // Match official eachCardList configuration; do not invent unconditional status blocks.
    // Official language keys: lostNotRchargeDescription / freezeNotRchargeDescription.
    fun requireAvailable(card: org.json.JSONObject, ecard: org.json.JSONObject) {
        val lost = card.optInt("lostflag", -1)
        val frozen = card.optInt("freezeflag", -1)
        if (lost !in 0..1 || frozen !in 0..1) throw RechargeTargetUnavailable("校园卡状态数据异常")
        val freezeConfig = ecard.opt("freezeRecharge")
        val blocksFrozen = when (freezeConfig) {
            is String -> freezeConfig.isNotEmpty() && freezeConfig != "1"
            is Number -> freezeConfig.toDouble() != 0.0
            is Boolean -> freezeConfig
            else -> false
        }
        // The official freeze message replaces the loss message when both are blocked.
        if (frozen == 1 && blocksFrozen) throw RechargeTargetUnavailable("已冻结，禁止充值，请先解冻")
        if (lost == 1 && ecard.opt("showLost") != "1")
            throw RechargeTargetUnavailable("已挂失，禁止充值，请先解挂")
    }
    fun paymentFailure(response: PaymentHttp.Response) {
        val body = runCatching { org.json.JSONObject(response.body) }.getOrNull() ?: return
        if (response.status == 200 && body.opt("code") == 60007 && body.opt("success") == false &&
            (body.isNull("state") || body.opt("state") == 60007) &&
            (body.isNull("data") || body.opt("data") == "")) throw RechargePaymentRejected(body.opt("msg") as? String)
    }
}
