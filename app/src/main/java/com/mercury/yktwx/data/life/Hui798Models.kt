package com.mercury.yktwx.data.life

import java.net.URI
import java.net.URLDecoder
import kotlinx.serialization.Serializable

enum class LifeProvider(val displayName: String, val services: String, val tag: String) {
    Panggui("胖乖生活", "接水 · 淋浴 · 洗衣", "panggui"),
    Hui798("慧生活798", "生活用水", "hui798"),
    Hellobike("哈啰", "单车骑行", "hellobike"),
}
data class DeviceScanRoute(val provider: LifeProvider, val feature: LifeFeature, val code: String)

/** Camera QR identity and manual device numbers use separate routing rules. */
object UnifiedDeviceCode {
    fun manualCandidates(raw: String): List<DeviceScanRoute> = listOfNotNull(
        LifeDeviceCode.manual(raw)?.let { DeviceScanRoute(LifeProvider.Panggui, it.first, it.second) },
        Hui798DeviceCode.manual(raw)?.let { DeviceScanRoute(LifeProvider.Hui798, LifeFeature.Water, it) },
        HellobikeDeviceCode.manual(raw)?.let { DeviceScanRoute(LifeProvider.Hellobike, LifeFeature.Ride, it) },
    )
    fun identifyManual(raw: String, provider: LifeProvider? = null): DeviceScanRoute? {
        val candidates = manualCandidates(raw)
        return if (provider == null) candidates.singleOrNull() else candidates.singleOrNull { it.provider == provider }
    }
    fun identify(raw: String): DeviceScanRoute? {
        val panggui = LifeDeviceCode.identify(raw)?.let { DeviceScanRoute(LifeProvider.Panggui, it.first, it.second) }
        val hui = Hui798DeviceCode.parse(raw)?.let { DeviceScanRoute(LifeProvider.Hui798, LifeFeature.Water, it) }
        val bike = HellobikeDeviceCode.parse(raw)?.let { DeviceScanRoute(LifeProvider.Hellobike, LifeFeature.Ride, it) }
        return listOfNotNull(panggui, hui, bike).singleOrNull()
    }
}

object Hui798DeviceCode {
    private val number = Regex("[0-9]{8,32}")
    fun manual(raw: String): String? = raw.trim().takeIf(number::matches)
    fun parse(raw: String): String? = runCatching {
        val uri = URI(raw.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443)) return null
        val host = uri.host?.lowercase() ?: return null
        val query = uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank).map {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }
        val deviceFields = query.filter { it.first.lowercase() in setOf("id", "did", "deviceid", "device_id", "sn", "nqt") }
        when {
            host == "i.hnkzy.com" && uri.path.matches(Regex("/q/1/[0-9]{8,32}")) && deviceFields.isEmpty() && uri.fragment == null ->
                uri.path.substringAfterLast('/')
            host == "cloud.hnkzy.com" && uri.path == "/h5/" && deviceFields.size == 1 && deviceFields.single().first == "id" &&
                query.filter { it.first == "atype" }.let { it.size == 1 && it.single().second == "1" } &&
                uri.fragment in setOf(null, "/pages/app/index") -> deviceFields.single().second.takeIf(number::matches)
            else -> null
        }
    }.getOrNull()
}

@Serializable data class Hui798Account(val phone: String, val token: String, val userId: String? = null) {
    val maskedPhone: String get() = phone.take(3) + "****" + phone.takeLast(4)
    override fun toString() = "Hui798Account([redacted])"
}
@Serializable data class Hui798Device(val id: String, val name: String)
data class Hui798Reading(val status: Int, val sessionTime: Long?)
data class Hui798Overview(val userId: String, val devices: List<Hui798Device>)
data class Hui798DeviceUse(val device: Hui798Device, val reading: Hui798Reading, val userId: String?)
@Serializable data class Hui798Session(
    val device: Hui798Device, val phase: WaterPhase = WaterPhase.Starting,
    val attemptedAtMillis: Long, val startAcknowledged: Boolean = false,
    val serverTime: Long? = null, val startedAtMillis: Long? = null, val endedAtMillis: Long? = null,
    val sessionId: String = java.util.UUID.randomUUID().toString(),
    val uncertain: Boolean = false,
) {
    val active: Boolean get() = phase != WaterPhase.Ended
    val canDismissException: Boolean get() = uncertain && phase == WaterPhase.Starting && serverTime == null
}

class Hui798Exception(message: String, val loginExpired: Boolean = false, val rejected: Boolean = false) : Exception(message)

interface Hui798Service {
    suspend fun captcha(key: String): ByteArray
    suspend fun sendCode(phone: String, imageCode: String, key: String)
    suspend fun login(phone: String, code: String): Hui798Account
    suspend fun accountStatus(token: String): String
    suspend fun device(id: String, token: String): Hui798Device
    suspend fun overview(token: String): Hui798Overview
    suspend fun deviceUse(id: String, token: String): Hui798DeviceUse
    suspend fun status(id: String, token: String): Hui798Reading
    suspend fun start(id: String, token: String)
    suspend fun end(id: String, token: String)
}

interface Hui798Store {
    fun account(): Hui798Account?
    fun saveAccount(account: Hui798Account)
    fun clearAccount()
    fun removeAccount() { if (account() != null) saveSessions(emptyList()); clearAccount() }
    fun sessions(): List<Hui798Session>
    fun saveSessions(sessions: List<Hui798Session>)
    fun removeSession(id: String) { saveSessions(sessions().filterNot { it.sessionId == id }) }
}
