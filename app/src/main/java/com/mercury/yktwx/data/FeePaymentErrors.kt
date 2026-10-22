package com.mercury.yktwx.data

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal enum class FeeRejectionReason { Password, Rejected, Other }

/** Preserve official business text; numeric codes only determine the verified interaction. */
internal class FeePaymentRejected(val reason: FeeRejectionReason, hint: String? = null) : YktException(
    hint?.takeIf { it.isNotEmpty() } ?: "Error", // Official cashier: msg || "Error".
)
internal class FeeOrderExpired : YktException("订单已过期，请重新发起缴费")
internal class FeeOrderAlreadyPaid : YktException("订单已完成，正在确认结果")
/** Read-only validation failed before the password request was sent. Safe for a manual retry. */
internal class FeePaymentNotStarted(message: String) : YktException(message)

internal object FeePaymentErrors {
    // 60050 covers multiple rejections (including lost cards), not just balance.
    // Do not infer rejection from unknown codes or contradictory success data.
    fun rejection(body: JSONObject): FeePaymentRejected? {
        val code = body.opt("code")
        if (code !in setOf(400, 60005, 60050) || (!body.isNull("state") && body.opt("state") != code) ||
            (!body.isNull("success") && body.opt("success") != false) ||
            (!body.isNull("data") && body.opt("data") != "")) return null
        if (code == 60005 || code == 60050) {
            if (body.opt("success") != false) return null
            return if (code == 60005) FeePaymentRejected(FeeRejectionReason.Password, body.opt("msg") as? String)
                else FeePaymentRejected(FeeRejectionReason.Rejected, body.opt("msg") as? String)
        }
        // code=400 is a generic rejection, not a password/balance/expiry classification.
        // The message is display-only; do not infer balance/password/expiry from its text.
        return FeePaymentRejected(FeeRejectionReason.Other, body.opt("msg") as? String)
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
