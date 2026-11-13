package com.mercury.yktwx.data.life

import java.net.URI
import java.net.URLDecoder
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Device codes use explicit fields; a UUID is the observed laundry NQT, not an IMEI. */
object LifeDeviceCode {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun identify(raw: String): Pair<LifeFeature, String>? = LifeFeature.entries.mapNotNull { feature ->
        parse(raw, feature)?.let { feature to it }
    }.singleOrNull()
    fun parse(raw: String, feature: LifeFeature): String? {
        if (feature == LifeFeature.Water) return DeviceSerial.parse(raw)
        if (feature != LifeFeature.Laundry) return null
        val value = raw.trim()
        if (uuid.matches(value)) return value
        return runCatching {
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host != "h5.qiekj.com" || uri.path != "/skip") return null
            val values = uri.rawQuery.orEmpty().split('&').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8").equals("NQT", true))
                    URLDecoder.decode(pair[1], "UTF-8") else null
            }
            values.singleOrNull()?.takeIf(uuid::matches)
        }.getOrNull()
    }
}

@Serializable enum class LaundryPhase { Checking, Creating, Paying, Starting, Running, Refunding, Ended, Closed }
data class LaundryQuote(val deviceId: String, val skuId: String, val price: String, val minutes: Int?,
    val channels: List<LifePayChannel>) {
    val payable: BigDecimal get() = price.toBigDecimal()
}
data class LaundryOrder(val orderNo: String, val orderId: String)
data class LaundryReading(val orderNo: String, val orderId: String, val deviceId: String, val skuId: String,
    val status: Int?, val price: String, val program: String, val minutes: Int?,
    val paidAtMillis: Long?, val expectedEndMillis: Long?, val finishedAtMillis: Long?, val createdAtMillis: Long? = null,
    val deviceName: String = "", val shopName: String = "", val payTypeName: String = "", val programFeature: String = "")
@Serializable data class LaundrySession(val device: LifeDevice, val skuId: String, val price: String, val payMethod: String,
    val phase: LaundryPhase = LaundryPhase.Checking, val createSent: Boolean = false,
    val orderNo: String = "", val orderId: String = "", val paymentSent: Boolean = false,
    val paid: Boolean = false, val minutes: Int? = null, @SerialName("startedAt") val startedAtMillis: Long? = null,
    @SerialName("expectedEnd") val expectedEndMillis: Long? = null, @SerialName("finishedAt") val finishedAtMillis: Long? = null,
    val sessionId: String = java.util.UUID.randomUUID().toString(), @SerialName("createdAt") val createdAtMillis: Long? = null,
    val payTypeName: String = "", @SerialName("completionObservedAt") val completionObservedAtMillis: Long? = null,
    val creationUncertain: Boolean = false, val islandDismissed: Boolean = false, val serverStatus: Int? = null) {
    val active: Boolean get() = phase !in setOf(LaundryPhase.Ended, LaundryPhase.Closed)
    val canDismissUnknownCreation: Boolean get() = phase == LaundryPhase.Creating && createSent &&
        orderNo.isBlank() && creationUncertain
    val canCancel: Boolean get() = phase == LaundryPhase.Paying && !paid && orderNo.isNotBlank() && orderId.isNotBlank()
    val program: String get() = device.skus.firstOrNull { it.id == skuId }?.name.orEmpty()
    /** Display estimate only; the server decides whether an order can still be paid. */
    fun paymentRemainingSeconds(nowMillis: Long): Long? = createdAtMillis?.takeIf { phase == LaundryPhase.Paying && !paid }
        ?.let { (((it + 5 * 60_000L - nowMillis).coerceAtLeast(0) + 999) / 1000).coerceAtMost(300) }
    /** Only confirmed completion has a reminder deadline; estimated finish does not expire a running order. */
    fun completionReminderDeadlineMillis(): Long? {
        if (phase != LaundryPhase.Ended) return null
        val completed = finishedAtMillis ?: completionObservedAtMillis ?: return null
        val zone = ZoneId.of("Asia/Shanghai")
        return Instant.ofEpochMilli(completed).atZone(zone).toLocalDate().plusDays(1)
            .atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
    }
    internal fun withCompletionObservation(nowMillis: Long): LaundrySession =
        if (phase == LaundryPhase.Ended && finishedAtMillis == null && completionObservedAtMillis == null)
            copy(completionObservedAtMillis = nowMillis) else this
}

internal const val LaundryCreationUnknownMessage = "下单结果未知，请在胖乖生活核对，避免重复下单"
data class LifePaymentLaunch(val id: Long, val orderNo: String, val payload: String, val method: String = "13")

interface LifeLaundryService {
    suspend fun laundryDevice(code: String, token: String): LifeDevice
    suspend fun laundryQuote(device: LifeDevice, skuId: String, token: String): LaundryQuote
    suspend fun checkLaundryDevice(deviceId: String, token: String): String
    suspend fun laundryMachineReady(messageId: String, token: String): Boolean
    suspend fun createLaundryOrder(deviceId: String, skuId: String, token: String): LaundryOrder
    suspend fun cancelLaundryOrder(orderId: String, token: String)
    suspend fun laundryOrder(orderNo: String, token: String): LaundryReading
    suspend fun laundryChannels(session: LaundrySession, token: String): List<LifePayChannel>
    suspend fun payLaundryBalance(orderNo: String, token: String)
    suspend fun prepareLaundryPayment(orderNo: String, method: String, token: String): String
    suspend fun laundryPaid(orderNo: String, method: String, token: String): Boolean
    suspend fun laundryStarted(orderNo: String, token: String): Boolean
}

val LifePayChannel.laundrySupported: Boolean get() = method in setOf("2", "13", "52")

fun LifePayChannel.laundryUnavailableReason(): String? = when {
    available == false -> remark ?: "暂不可用"
    needsOpen == true -> "请先在胖乖生活开通"
    !laundrySupported -> "暂未接入，请在胖乖生活使用"
    else -> null
}
