package com.mercury.yktwx.data.life

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** c3x.me is parsed locally. A naked number never changes the unified scanner's provider. */
object HellobikeDeviceCode {
    fun parse(raw: String): String? = runCatching {
        val value = raw.trim()
        if (value.length > 4096 || value.any(Char::isISOControl)) return null
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        if (scheme !in setOf("http", "https") || uri.host?.lowercase() != "c3x.me" || uri.userInfo != null ||
            uri.port !in setOf(-1, if (scheme == "https") 443 else 80) || uri.fragment != null) return null
        val fields = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        if (fields.any { it.first.lowercase() in setOf("m", "e", "x", "s", "c", "p") }) return null
        val bike = fields.filter { it.first.lowercase() in setOf("n", "u") }
        if (bike.isEmpty() || bike.any { it.first !in setOf("n", "u") } ||
            bike.groupBy { it.first }.any { it.value.size != 1 }) return null
        bike.map { it.second }.distinct().singleOrNull()?.takeIf(::validIdentifier)
    }.getOrNull()

    // REA's QR parser has no ten-character rule. That rule belongs to manual bicycle entry.
    internal fun validIdentifier(value: String) = value.isNotEmpty() && value.length <= 128 &&
        value.none { it.isWhitespace() || it.isISOControl() }
    fun manual(raw: String): String? = raw.trim().takeIf { it.matches(Regex("[0-9]{10}")) }
}

@Serializable data class HellobikeRegion(val cityCode: String, val adCode: String, val label: String = "") {
    val valid: Boolean get() = cityCode.isNotBlank() && adCode.isNotBlank()
}

@Serializable data class HellobikeAccount(
    val phone: String, val token: String, val userGuid: String, val clientId: String,
    val ticket: String = "", val systemCode: String = "62", val version: String = "7.0.75",
    val h5Version: String = "7.0.70",
) {
    val maskedPhone: String get() = phone.take(3) + "****" + phone.takeLast(4)
    override fun toString() = "HellobikeAccount([redacted])"
}

data class HellobikePosition(val lat: Double, val lng: Double, val accuracyMetres: Float, val capturedAtElapsedMillis: Long) {
    fun fresh(elapsedMillis: Long) = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0 &&
        accuracyMetres.isFinite() && accuracyMetres >= 0f && capturedAtElapsedMillis <= elapsedMillis
    override fun toString() = "HellobikePosition([redacted])"
}

data class HellobikeSmsResult(val sliderRequired: Boolean = false, val challenge: String? = null)
data class HellobikeSlider(val background: ByteArray, val piece: ByteArray, val y: Int,
    val width: Int, val height: Int, val pieceWidth: Int, val pieceHeight: Int) {
    val maximum: Int get() = width - pieceWidth
    override fun toString() = "HellobikeSlider([redacted])"
}
data class HellobikePrecheck(val bikeNo: String, val orderGuid: String, val priceRule: String,
    val preparedAtElapsedMillis: Long, val region: HellobikeRegion, val position: HellobikePosition, val scanReqId: String = "",
    val notice: String? = null) {
    // Kept only for the local monotonic clock; business expiry is decided by user.ride.create.
    fun fresh(elapsedMillis: Long) = elapsedMillis >= preparedAtElapsedMillis
}
data class HellobikeCloseProof(val sessionId: String, val checkedAtElapsedMillis: Long,
    val region: HellobikeRegion, val position: HellobikePosition, val detail: String? = null) {
    fun fresh(elapsedMillis: Long) = elapsedMillis >= checkedAtElapsedMillis
}

sealed interface HellobikeRideReading {
    data object NoRide : HellobikeRideReading
    data class Ride(val rideGuid: String, val bikeNo: String, val status: Int, val seconds: Long? = null,
        val userGuid: String? = null) : HellobikeRideReading
}
data class HellobikeEndInfo(val rideGuid: String, val ended: Boolean, val amount: String? = null,
    val seconds: Long? = null, val distance: String? = null)
data class HellobikeCloseReading(val processing: Boolean, val ready: Boolean, val detail: String? = null, val completed: Boolean = false)

/** user.ride.order.status has its own status space; it is not rideInfo.rideStatus. */
data class HellobikeOrderReading(val rideStatus: Int?, val subStatus: Int?, val detail: String? = null) {
    val opening: Boolean get() = rideStatus == 20 && subStatus in setOf(22, 23)
    val unlocked: Boolean get() = rideStatus == 20 && subStatus !in setOf(22, 23)
    val ended: Boolean get() = rideStatus == 30
    val lockStuck: Boolean get() = !unlocked && subStatus == -12
}

@Serializable enum class HellobikePhase { Opening, Riding, Closing, Ended }
@Serializable enum class HellobikeSettlement { Unknown, Pending, Paying, Paid, Closed }
@Serializable data class HellobikePayOrder(val guid: String, val amount: String, val orderId: String = "", val businessType: String = "1") {
    override fun toString() = "HellobikePayOrder([redacted])"
}
@Serializable data class HellobikePaymentAttempt(val orders: List<HellobikePayOrder>, val channel: String,
    val sentAtMillis: Long, val extension: JsonObject = JsonObject(emptyMap()), val confirmedGuids: Set<String> = emptySet()) {
    override fun toString() = "HellobikePaymentAttempt([redacted])"
}
@Serializable data class HellobikeSession(
    val rideGuid: String, val bikeNo: String, val region: HellobikeRegion?,
    val phase: HellobikePhase, val attemptedAtMillis: Long,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val startedAtMillis: Long? = null, val endedAtMillis: Long? = null,
    val priceRule: String = "", val seconds: Long? = null, val distance: String? = null, val amount: String? = null,
    val settlement: HellobikeSettlement = HellobikeSettlement.Unknown,
    val paymentAttempt: HellobikePaymentAttempt? = null, val uncertain: Boolean = false,
    val islandDismissed: Boolean = false,
) {
    val riding: Boolean get() = phase != HellobikePhase.Ended
    val settled: Boolean get() = phase == HellobikePhase.Ended && settlement == HellobikeSettlement.Paid
    val dismissible: Boolean get() = phase == HellobikePhase.Ended && settlement in setOf(HellobikeSettlement.Paid, HellobikeSettlement.Closed)
    val title: String get() = when (phase) {
        HellobikePhase.Opening -> if (uncertain) "开锁结果待确认" else "正在开锁"
        HellobikePhase.Riding -> "骑行中"
        HellobikePhase.Closing -> if (uncertain) "还车结果待确认" else "正在还车"
        HellobikePhase.Ended -> when (settlement) {
            HellobikeSettlement.Paid -> "已结清"
            HellobikeSettlement.Closed -> "订单已关闭"
            HellobikeSettlement.Pending -> "待支付"
            HellobikeSettlement.Paying -> "支付结果待确认"
            HellobikeSettlement.Unknown -> "费用待确认"
        }
    }
    override fun toString() = "HellobikeSession([redacted])"
}

data class HellobikePayDetail(val rideGuid: String, val tradeId: String = "", val amount: String? = null,
    val settlement: HellobikeSettlement, val orders: List<HellobikePayOrder> = emptyList(), val unsupported: String? = null)
data class HellobikePayOption(val channel: LifePayChannel, val amount: String, val marketingActivityId: String? = null,
    val marketingType: String? = null, val channelOperationInfoExt: String? = null, val extension: JsonObject = JsonObject(emptyMap()))
data class HellobikePayComponent(val channel: String, val status: String, val orders: List<HellobikePayOrder>,
    val extension: JsonObject = JsonObject(emptyMap()), val payload: String? = null) {
    override fun toString() = "HellobikePayComponent([redacted])"
}

class HellobikeException(message: String, val loginExpired: Boolean = false, val rejected: Boolean = false,
    val officialRequired: Boolean = false, val storageFailure: Boolean = false) : Exception(message)
internal class HellobikeLocationPermissionRequired : Exception()

interface HellobikeLocationService { suspend fun current(): HellobikePosition }
interface HellobikeService {
    suspend fun sendCode(phone: String, region: HellobikeRegion?, clientId: String, offset: Int? = null, challenge: String? = null): HellobikeSmsResult
    suspend fun slider(phone: String, region: HellobikeRegion?, clientId: String): HellobikeSlider
    suspend fun login(phone: String, code: String, region: HellobikeRegion?, clientId: String, loginGuid: String): HellobikeAccount
    suspend fun region(position: HellobikePosition): HellobikeRegion
    suspend fun checkRide(account: HellobikeAccount): HellobikeRideReading
    suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck
    suspend fun create(account: HellobikeAccount, precheck: HellobikePrecheck, position: HellobikePosition)
    suspend fun orderState(account: HellobikeAccount, rideGuid: String): HellobikeOrderReading
    suspend fun orderEnded(account: HellobikeAccount, rideGuid: String): Boolean
    suspend fun endInfo(account: HellobikeAccount, rideGuid: String): HellobikeEndInfo
    suspend fun preClose(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition, region: HellobikeRegion,
        poll: Boolean, commit: Boolean): HellobikeCloseReading
    suspend fun payDetail(account: HellobikeAccount, session: HellobikeSession): HellobikePayDetail
    suspend fun payChannels(account: HellobikeAccount, session: HellobikeSession, detail: HellobikePayDetail): List<HellobikePayOption>
    suspend fun pay(account: HellobikeAccount, session: HellobikeSession, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent
    suspend fun paymentPaid(account: HellobikeAccount, order: HellobikePayOrder, extension: JsonObject): Boolean
}

@Serializable data class HellobikeStoredState(val clientId: String = "", val account: HellobikeAccount? = null,
    val ownerGuid: String = "", val maskedPhone: String = "", val region: HellobikeRegion? = null,
    val sessions: List<HellobikeSession> = emptyList(),
    val retainedSessions: Map<String, List<HellobikeSession>> = emptyMap(), val recoveryRequired: Boolean = false) {
    override fun toString() = "HellobikeStoredState([redacted])"
}
interface HellobikeStore {
    fun load(): HellobikeStoredState
    fun saveClientId(clientId: String)
    fun saveRegion(region: HellobikeRegion)
    fun saveAccount(account: HellobikeAccount)
    fun saveSessions(ownerGuid: String, sessions: List<HellobikeSession>)
    fun clearAccount()
    fun removeAccount()
    fun recoverUnreadable(clientId: String)
    fun acknowledgeRecovery()
}

internal val HellobikeSupportedChannels = setOf("001", "003", "1201")
internal fun String.hellobikeBridgeMethod(): String? = when (this) { "001" -> "13"; "1201" -> "52"; else -> null }
