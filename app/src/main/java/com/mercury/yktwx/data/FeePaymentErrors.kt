package com.mercury.yktwx.data

import kotlinx.serialization.json.*
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
    fun rejection(body: JsonObject): FeePaymentRejected? {
        if (PaymentErrors.feeSucceeded(body)) return null
        val reason = when (body.intOrDefault("code")) {
            60005 -> FeeRejectionReason.Password
            60050 -> FeeRejectionReason.Rejected
            else -> FeeRejectionReason.Other
        }
        return FeePaymentRejected(reason, body.scalarOrElement("msg") as? String)
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
