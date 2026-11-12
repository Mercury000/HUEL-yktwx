package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Internal amounts are integral cents; the charge API itself uses decimal yuan. */
object RechargeMoney {
    fun cents(value: String): Long? = runCatching {
        require(value.matches(Regex("[0-9]{1,8}(\\.[0-9]{1,2})?")))
        BigDecimal(value).movePointRight(2).longValueExact().also { require(it > 0) }
    }.getOrNull()
    fun yuan(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()
    fun label(cents: Long): String = BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString()
    fun responseCents(value: Any?): Long = runCatching {
        BigDecimal(value.toString()).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact()
            .also { require(it >= 0) }
    }.getOrElse { throw YktException("充值金额数据异常") }
}

class RechargeConfiguration(
    val feeItemId: String,
    val targetId: String,
    val amountsCents: List<Long>,
    // Null means the configuration did not disclose a bound, not that the backend has none.
    val minimumCents: Long?,
    val maximumCents: Long?,
    val allowCustom: Boolean,
    val integerOnly: Boolean,
    val recipient: RechargeRecipient? = null,
) {
    fun amountError(amountCents: Long?): String? = when {
        amountCents == null || amountCents <= 0 ->
            if (integerOnly) "请输入有效的整数金额" else "请输入有效金额，最多两位小数"
        minimumCents != null && amountCents < minimumCents -> "最低充值 ¥ " + RechargeMoney.label(minimumCents)
        maximumCents != null && amountCents > maximumCents -> "单次最高 ¥ " + RechargeMoney.label(maximumCents)
        integerOnly && amountCents % 100 != 0L -> "仅支持整数金额"
        !allowCustom && amountCents !in amountsCents -> "请选择配置的充值金额"
        else -> null
    }
    override fun toString() = "RechargeConfiguration([redacted])"
}

/** The current in-memory attempt; orderId is populated after the creation response. */
class PendingRecharge(
    val username: String,
    val targetId: String,
    val feeItemId: String,
    val amountCents: Long,
    val orderId: String? = null,
    val payId: String? = null,
    val recipient: RechargeRecipient? = null,
) {
    fun withOrder(orderId: String, payId: String?) = PendingRecharge(
        username, targetId, feeItemId, amountCents, orderId, payId, recipient,
    )
    override fun toString() = "PendingRecharge([redacted])"
}

class RechargeOrder(val orderId: String, val payId: String?) {
    override fun toString() = "RechargeOrder([redacted])"
}
class WechatRechargeLaunch(val uri: String) {
    override fun toString() = "WechatRechargeLaunch([redacted])"
}
class RechargeResult(val paid: Boolean, val serverStatus: Int? = null, val message: String? = null)

interface RechargeService {
    suspend fun rechargeConfiguration(token: String, preferredTarget: String?): RechargeConfiguration
    suspend fun createRecharge(token: String, config: RechargeConfiguration, amountCents: Long): RechargeOrder
    suspend fun launchRecharge(token: String, pending: PendingRecharge): WechatRechargeLaunch
    suspend fun rechargeResult(token: String, pending: PendingRecharge): RechargeResult
}

internal fun JsonObject.rechargeText(key: String): String = stringOrDefault(key).takeUnless { it == "null" }.orEmpty()

/** Missing/zero backend bounds do not justify inventing a client-side payment limit. */
internal fun JsonObject.rechargeLimit(key: String): Long? {
    val raw = rechargeText(key).trim()
    if (raw.isEmpty()) return null
    val cents = RechargeMoney.responseCents(raw)
    return cents.takeIf { it > 0 }
}
