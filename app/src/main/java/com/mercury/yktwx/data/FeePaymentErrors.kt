package com.mercury.yktwx.data

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal enum class FeeRejectionReason { Password, Balance, Other }

/** Fixed UI messages only: never retain or display the server's raw payment error. */
internal class FeePaymentRejected(val reason: FeeRejectionReason) : YktException(when (reason) {
    FeeRejectionReason.Password -> "支付密码错误，请重新输入"
    FeeRejectionReason.Balance -> "一卡通余额不足"
    FeeRejectionReason.Other -> "支付未完成，请核实后重试"
})
internal class FeeOrderExpired : YktException("订单已过期，请重新发起缴费")
internal class FeeOrderAlreadyPaid : YktException("订单已完成，正在确认结果")
/** Read-only validation failed before the password request was sent. Safe for a manual retry. */
internal class FeePaymentNotStarted(message: String) : YktException(message)

internal object FeePaymentErrors {
    // Verified business codes: 60005 = password rejection; 60050 = insufficient balance.
    // Do not infer rejection from unknown codes or contradictory success data.
    fun rejection(body: JSONObject): FeePaymentRejected? {
        val code = body.opt("code")
        if (code !in setOf(400, 60005, 60050) || (!body.isNull("state") && body.opt("state") != code) ||
            (!body.isNull("success") && body.opt("success") != false) ||
            (!body.isNull("data") && body.opt("data") != "")) return null
        if (code == 60005 || code == 60050) {
            if (body.opt("success") != false) return null
            return FeePaymentRejected(if (code == 60005) FeeRejectionReason.Password else FeeRejectionReason.Balance)
        }
        // code=400 is a generic rejection, not a password/balance/expiry classification.
        // The human-readable msg has no role in control flow and is never retained.
        return FeePaymentRejected(FeeRejectionReason.Other)
    }
}

internal object FeeOrderValidity {
    private val format = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    fun requireCurrent(expiry: String, serverTime: String) {
        val expires = parse(expiry)
        val now = parse(serverTime)
        if (!expires.isAfter(now)) throw FeeOrderExpired()
    }
    private fun parse(value: String): LocalDateTime = runCatching {
        require(value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")))
        LocalDateTime.parse(value, format)
    }.getOrElse { throw YktException("无法确认订单有效期，请稍后重试") }
}
