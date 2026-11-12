package com.mercury.yktwx.data.life

import android.content.Context
import com.mercury.yktwx.data.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Independent platform records, backed by the app's existing Keystore encryption. */
internal interface LifeDeviceRecords {
    fun read(key: String): JsonObject?
    fun write(values: Map<String, String>)
    fun remove(key: String)
}

class LifeDeviceAccountStore internal constructor(private val vault: LifeDeviceRecords) : LifeDeviceStore {
    constructor(context: Context) : this(encryptedRecords(context))
    override fun account(): LifeAccount? = runCatching {
        val data = vault.read("account") ?: return null
        StoredRecordJson.decode<LifeAccount>(data).takeIf { it.phone.isNotBlank() && it.token.isNotBlank() }
    }.getOrNull()
    override fun saveAccount(account: LifeAccount) {
        val changing = account().let { it != null && it.phone != account.phone }
        vault.write(mapOf("account" to StoredRecordJson.encode(account).toString()))
        if (changing) {
            clearSession(); clearLaundrySession(); vault.remove("water_sessions"); vault.remove("laundry_sessions")
        }
    }
    override fun clearAccount() { vault.remove("account") }
    override fun removeAccount() {
        vault.remove("water"); vault.remove("laundry"); vault.remove("water_sessions"); vault.remove("laundry_sessions")
        clearAccount()
    }
    override fun session(): WaterSession? = single<WaterSession>("water", "water")?.normalized()
    override fun laundrySession(): LaundrySession? = single<LaundrySession>("laundry", "laundry")?.normalized()
    override fun waterSessions(): List<WaterSession> = records<WaterSession>("water_sessions", "water").map { it.normalized() }
    override fun laundrySessions(): List<LaundrySession> = records<LaundrySession>("laundry_sessions", "laundry").map { it.normalized() }

    private inline fun <reified T> single(key: String, kind: String): T? = runCatching {
        val data = vault.read(key) ?: return null
        if (data.text("owner") != account()?.phone) return null
        StoredRecordJson.decode<T>(StoredRecordJson.single(data, kind))
    }.getOrNull()

    private inline fun <reified T> records(key: String, kind: String): List<T> {
        val data = StoredRecordJson.listSource(vault.read(key), { vault.read(kind) }, kind) ?: return emptyList()
        if (data.text("owner") != account()?.phone) return emptyList()
        require(data.arrayValue("sessions").all { it.jsonObject.text("sessionId").isNotBlank() })
        return StoredRecordJson.decode<StoredLifeSessions<T>>(data).sessions
    }

    private inline fun <reified T> saveSingle(key: String, session: T) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        vault.write(mapOf(key to StoredRecordJson.encode(session).withField("owner", owner.phone).toString()))
    }
    override fun saveSession(session: WaterSession) = saveSingle("water", session)
    override fun saveLaundrySession(session: LaundrySession) = saveSingle("laundry", session)
    override fun clearSession() { vault.remove("water") }
    override fun clearLaundrySession() { vault.remove("laundry") }

    override fun removeLaundrySession(sessionId: String) {
        // Local dismissal also works after credentials expire; it needs no platform request.
        vault.read("laundry_sessions")?.let { data ->
            val sessions = data.arrayValue("sessions").objects().filterNot { it.text("sessionId") == sessionId }
            vault.write(mapOf("laundry_sessions" to StoredRecordJson.updated(data.withField("sessions", jsonArrayOf(sessions))).toString()))
        }
        vault.read("laundry")?.let { data ->
            val id = StoredRecordJson.singleId(data, "laundry")
            if (id == sessionId) clearLaundrySession()
        }
    }
    override fun removeWaterSession(sessionId: String) {
        vault.read("water_sessions")?.let { data ->
            val sessions = data.arrayValue("sessions").objects().filterNot { it.text("sessionId") == sessionId }
            vault.write(mapOf("water_sessions" to StoredRecordJson.updated(data.withField("sessions", jsonArrayOf(sessions))).toString()))
        }
        vault.read("water")?.let { data ->
            val id = StoredRecordJson.singleId(data, "water")
            if (id == sessionId) clearSession()
        }
    }
    override fun saveWaterSessions(sessions: List<WaterSession>) {
        saveTypedRecords("water_sessions", sessions)
        clearSession()
    }
    override fun saveLaundrySessions(sessions: List<LaundrySession>) {
        saveTypedRecords("laundry_sessions", sessions)
        clearLaundrySession()
    }
    private inline fun <reified T> saveTypedRecords(key: String, sessions: List<T>) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        vault.write(mapOf(key to StoredRecordJson.encode(StoredLifeSessions(owner.phone, sessions)).toString()))
    }

}

private fun encryptedRecords(context: Context): LifeDeviceRecords {
    val vault = SecureVault(context, "encrypted_life_devices", "yktwx.life.devices.aes.v1")
    return object : LifeDeviceRecords {
        override fun read(key: String) = vault.read(key)
        override fun write(values: Map<String, String>) = vault.write(values)
        override fun remove(key: String) = vault.remove(key)
    }
}

@Serializable private data class StoredLifeSessions<T>(val owner: String, val sessions: List<T>) {
    override fun toString() = "StoredLifeSessions([redacted])"
}

private fun LifeDevice.normalized() = copy(skus = skus.map { it.copy(minutes = it.minutes?.takeIf { value -> value > 0 }) },
    error = error?.takeIf(String::isNotBlank), channels = channels.map {
        it.copy(remark = it.remark?.takeIf(String::isNotBlank), link = it.link?.takeIf(String::isNotBlank))
    })
private fun WaterSession.normalized() = copy(device = device.normalized(),
    startedAtMillis = startedAtMillis?.takeIf { it > 0 }, endedAtMillis = endedAtMillis?.takeIf { it > 0 },
    fault = fault?.takeIf(String::isNotBlank))
private fun LaundrySession.normalized() = copy(device = device.normalized(), minutes = minutes?.takeIf { it > 0 },
    startedAtMillis = startedAtMillis?.takeIf { it > 0 }, expectedEndMillis = expectedEndMillis?.takeIf { it > 0 },
    finishedAtMillis = finishedAtMillis?.takeIf { it > 0 }, createdAtMillis = createdAtMillis?.takeIf { it > 0 },
    completionObservedAtMillis = completionObservedAtMillis?.takeIf { it > 0 })
