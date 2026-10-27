package com.mercury.yktwx.data

/** Order creation or payment parameters were explicitly refused; no external payment was launched. */
internal class RechargePaymentRejected(hint: String? = null) : YktException(
    hint?.takeIf { it.isNotEmpty() } ?: "Error",
)
/** A read-only check refused the target before creating any order. */
internal class RechargeTargetUnavailable(message: String) : YktException(message)

internal object RechargeErrors {
    /** Creation and payment share the official JSON/redirect rejection envelopes. */
    fun creationFailure(response: PaymentHttp.Response) {
        paymentFailure(response)
    }

    // Match official eachCardList configuration; do not invent unconditional status blocks.
    // Official language keys: lostNotRchargeDescription / freezeNotRchargeDescription.
    fun requireAvailable(card: org.json.JSONObject, ecard: org.json.JSONObject) {
        val lost = card.optInt("lostflag", -1)
        val frozen = card.optInt("freezeflag", -1)
        if (lost !in 0..1 || frozen !in 0..1) throw RechargeTargetUnavailable("校园卡状态数据异常")
        val freezeConfig = ecard.opt("freezeRecharge")
        val blocksFrozen = when {
            freezeConfig is String -> freezeConfig.isNotEmpty() && freezeConfig != "1"
            freezeConfig is Number -> freezeConfig.toDouble() != 0.0
            freezeConfig is Boolean -> freezeConfig
            else -> false
        }
        // The official freeze message replaces the loss message when both are blocked.
        if (frozen == 1 && blocksFrozen) throw RechargeTargetUnavailable("已冻结，禁止充值，请先解冻")
        if (lost == 1 && ecard.opt("showLost") != "1")
            throw RechargeTargetUnavailable("已挂失，禁止充值，请先解挂")
    }
    fun paymentFailure(response: PaymentHttp.Response) {
        PaymentErrors.redirectMessage(response)?.let { throw RechargePaymentRejected(it) }
        val body = runCatching { org.json.JSONObject(response.body) }.getOrNull() ?: return
        if (response.status in listOf(401, 403) || body.optInt("code") in listOf(401, 403, 4011, 4030, 4037, 4038))
            throw AuthenticationRequired((body.opt("msg") as? String)?.takeIf { it.isNotEmpty() } ?: "登录已失效，请重新登录")
        if (response.status == 200 && !PaymentErrors.rechargeSucceeded(body))
            throw RechargePaymentRejected(body.opt("msg") as? String)
        if (response.status !in 200..299)
            throw YktException((body.opt("msg") as? String)?.takeIf { it.isNotEmpty() } ?: "Error")
    }
}
