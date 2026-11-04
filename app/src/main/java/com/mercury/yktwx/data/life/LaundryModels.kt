package com.mercury.yktwx.data.life

import java.net.URI
import java.net.URLDecoder
import java.math.BigDecimal

/** Device codes use explicit fields; a UUID is the observed laundry NQT, not an IMEI. */
object LifeDeviceCode {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    fun identify(raw: String): Pair<LifeFeature, String>? = LifeFeature.entries.mapNotNull { feature ->
        parse(raw, feature)?.let { feature to it }
    }.singleOrNull()
    fun parse(raw: String, feature: LifeFeature): String? {
        if (feature == LifeFeature.Water) return DeviceSerial.parse(raw)
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

enum class LaundryPhase { Checking, Creating, Paying, Starting, Running, Ended }
data class LaundryQuote(val deviceId: String, val skuId: String, val price: String, val minutes: Int?,
    val channels: List<LifePayChannel>) {
    val payable: BigDecimal get() = price.toBigDecimal()
}
data class LaundryOrder(val orderNo: String, val orderId: String)
data class LaundryReading(val orderNo: String, val orderId: String, val deviceId: String, val skuId: String,
    val status: Int?, val price: String, val program: String, val minutes: Int?,
    val paidAtMillis: Long?, val expectedEndMillis: Long?, val finishedAtMillis: Long?)
data class LaundrySession(val device: LifeDevice, val skuId: String, val price: String, val payMethod: String,
    val phase: LaundryPhase = LaundryPhase.Checking, val createSent: Boolean = false,
    val orderNo: String = "", val orderId: String = "", val paymentSent: Boolean = false,
    val paid: Boolean = false, val minutes: Int? = null, val startedAtMillis: Long? = null,
    val expectedEndMillis: Long? = null, val finishedAtMillis: Long? = null,
    val sessionId: String = java.util.UUID.randomUUID().toString()) {
    val active: Boolean get() = phase != LaundryPhase.Ended
    val program: String get() = device.skus.firstOrNull { it.id == skuId }?.name.orEmpty()
}
data class LifeAlipayLaunch(val id: Long, val orderNo: String, val payload: String)

interface LifeLaundryService {
    suspend fun laundryDevice(code: String, token: String): LifeDevice
    suspend fun laundryQuote(device: LifeDevice, skuId: String, token: String): LaundryQuote
    suspend fun checkLaundryDevice(deviceId: String, token: String): String
    suspend fun laundryMachineReady(messageId: String, token: String): Boolean
    suspend fun createLaundryOrder(deviceId: String, skuId: String, token: String): LaundryOrder
    suspend fun laundryOrder(orderNo: String, token: String): LaundryReading
    suspend fun laundryChannels(session: LaundrySession, token: String): List<LifePayChannel>
    suspend fun payLaundryBalance(orderNo: String, token: String)
    suspend fun prepareLaundryAlipay(orderNo: String, token: String): String
    suspend fun laundryPaid(orderNo: String, method: String, token: String): Boolean
    suspend fun laundryStarted(orderNo: String, token: String): Boolean
}

fun LifePayChannel.laundryUnavailableReason(): String? = when {
    !available -> remark ?: "暂不可用"
    needsOpen -> "请先在胖乖生活开通"
    method !in setOf("2", "13") -> "暂未接入，请在胖乖生活使用"
    else -> null
}
