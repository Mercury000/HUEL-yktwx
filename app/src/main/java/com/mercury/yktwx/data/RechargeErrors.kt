package com.mercury.yktwx.data

/** Payment parameters were explicitly refused; no external payment was launched. */
internal class RechargePaymentRejected(hint: String? = null) : YktException(
    hint?.takeIf { it.isNotEmpty() } ?: "Error",
)
/** A read-only check refused the target before creating any order. */
internal class RechargeTargetUnavailable(message: String) : YktException(message)

internal object RechargeErrors {
    // From official lostNotRchargeDescription / freezeNotRchargeDescription, without an account prefix.
    fun requireAvailable(card: org.json.JSONObject) {
        when {
            card.optInt("lostflag", -1) == 1 -> throw RechargeTargetUnavailable("已挂失，禁止充值，请先解挂")
            card.optInt("freezeflag", -1) == 1 -> throw RechargeTargetUnavailable("已冻结，禁止充值，请先解冻")
            card.optInt("lostflag", -1) != 0 || card.optInt("freezeflag", -1) != 0 ||
                (card.has("acc_status") && card.optInt("acc_status", -1) != 0) ->
                throw RechargeTargetUnavailable("收款账户状态异常，请在官网核实")
        }
    }
    fun paymentFailure(response: PaymentHttp.Response) {
        val body = runCatching { org.json.JSONObject(response.body) }.getOrNull() ?: return
        if (response.status == 200 && body.opt("code") == 60007 && body.opt("success") == false &&
            (body.isNull("state") || body.opt("state") == 60007) &&
            (body.isNull("data") || body.opt("data") == "")) throw RechargePaymentRejected(body.opt("msg") as? String)
    }
}
