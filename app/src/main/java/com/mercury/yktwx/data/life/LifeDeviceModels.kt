package com.mercury.yktwx.data.life

import java.net.URI
import java.net.URLDecoder

enum class LifeFeature(val title: String) { Water("接水"), Laundry("洗衣") }

data class LifeAccount(val phone: String, val token: String) {
    val maskedPhone: String get() = if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else ""
}

data class LifeSku(val id: String, val name: String, val available: Boolean, val price: String = "", val feature: String = "",
    val minutes: Int? = null)
data class LifePayChannel(val method: String, val name: String, val available: Boolean,
    val needsOpen: Boolean, val remark: String? = null, val link: String? = null)

/** Route shopId and detail shopId have different meanings in the captured protocol. */
data class LifeDevice(
    val id: String, val serial: String, val name: String, val category: String,
    val routeShopId: String, val orgId: String, val shopId: String, val imei: String,
    val shopName: String, val address: String, val payment: String, val bluetooth: Boolean,
    val supportsFinish: Boolean, val error: String?, val skus: List<LifeSku>,
    val channels: List<LifePayChannel>,
) {
    val alipay: LifePayChannel? get() = channels.firstOrNull { it.method == "15" }
    val startError: String? get() = when {
        category != "04" -> "请扫描饮水设备"
        bluetooth -> "该设备需要蓝牙操作，请使用胖乖生活"
        payment != "after" -> "该设备的支付方式暂未接入"
        error != null -> error
        skus.none { it.available } -> "设备当前没有可用的接水选项"
        alipay == null -> "该设备没有可用的支付宝免密渠道"
        alipay?.needsOpen == true -> "请先在胖乖生活开通支付宝免密，返回后刷新设备"
        alipay?.available != true -> alipay?.remark ?: "支付宝免密暂不可用"
        else -> null
    }
    val laundryError: String? get() = when {
        category != "00" -> "请扫描洗衣设备"
        bluetooth -> "该设备需要蓝牙操作，请使用胖乖生活"
        payment != "before" -> "该设备的支付方式暂未接入"
        error != null -> error
        skus.none { it.available } -> "设备当前没有可用的洗衣程序"
        else -> null
    }
}

enum class WaterPhase { Ready, Starting, Running, Stopping, Ended }
data class WaterSession(
    val device: LifeDevice, val skuId: String, val phase: WaterPhase = WaterPhase.Ready,
    val startSent: Boolean = false, val messageId: String = "", val orderNo: String = "",
    val orderId: String = "", val startedAtMillis: Long? = null, val endedAtMillis: Long? = null,
    val finalized: Boolean = false,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
) {
    val active: Boolean get() = phase in setOf(WaterPhase.Starting, WaterPhase.Running, WaterPhase.Stopping)
}

data class WaterUnlock(val messageId: String, val orderNo: String)
// amount is a device consumption field with unknown units, not a displayed payment amount.
data class WaterReading(val status: Int?, val workStatus: Int?, val orderNo: String, val zeroConsumption: Boolean = false)
data class WaterMessage(val state: String, val code: String)
class LifeDeviceException(message: String, val loginExpired: Boolean = false) : Exception(message)

/** Accept literal captured SNs or explicit SN query fields; never guess numbers in arbitrary QR text. */
object DeviceSerial {
    fun parse(raw: String): String? {
        val value = raw.trim()
        if (value.matches(Regex("[0-9]{15}"))) return value
        return runCatching {
            val uri = URI(value)
            if (uri.scheme != "https" || uri.host != "h5.qiekj.com" || uri.path != "/skip") return null
            val query = uri.rawQuery ?: return null
            val serials = query.split('&').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size == 2 && URLDecoder.decode(pair[0], "UTF-8").equals("SN", true))
                    URLDecoder.decode(pair[1], "UTF-8") else null
            }
            serials.singleOrNull()?.takeIf { it.matches(Regex("[0-9]{15}")) }
        }.getOrNull()
    }
}

interface LifeDeviceService {
    suspend fun sendCode(phone: String)
    suspend fun login(phone: String, code: String): LifeAccount
    suspend fun device(serial: String, token: String): LifeDevice
    suspend fun selectAfterPay(token: String)
    suspend fun unlock(skuId: String, token: String): WaterUnlock
    suspend fun message(messageId: String, token: String): WaterMessage?
    suspend fun water(skuId: String, token: String): WaterReading
    suspend fun endWater(goodsId: String, token: String)
    suspend fun createAfterPay(orderNo: String, token: String): String
    suspend fun syncOrder(orderNo: String, token: String)
}

interface LifeDeviceStore {
    fun account(): LifeAccount?
    fun saveAccount(account: LifeAccount)
    fun clearAccount()
    fun session(): WaterSession?
    fun saveSession(session: WaterSession)
    fun clearSession()
    fun laundrySession(): LaundrySession? = null
    fun saveLaundrySession(session: LaundrySession) { throw LifeDeviceException("无法保存洗衣进度") }
    fun clearLaundrySession() {}
    fun waterSessions(): List<WaterSession> = listOfNotNull(session())
    fun laundrySessions(): List<LaundrySession> = listOfNotNull(laundrySession())
    fun saveWaterSessions(sessions: List<WaterSession>) {
        sessions.lastOrNull()?.let(::saveSession) ?: clearSession()
    }
    fun saveLaundrySessions(sessions: List<LaundrySession>) {
        sessions.lastOrNull()?.let(::saveLaundrySession) ?: clearLaundrySession()
    }
}
