package com.mercury.yktwx.data.life

import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable
import com.mercury.yktwx.data.*
import android.content.Context
import com.mercury.yktwx.data.SecureVault

class Hui798AccountStore internal constructor(private val records: LifeDeviceRecords) : Hui798Store {
    constructor(context: Context) : this(hui798Records(context))
    override fun account(): Hui798Account? = runCatching {
        val data = records.read("account") ?: return null
        StoredRecordJson.decode<Hui798Account>(data).takeIf { it.phone.isNotBlank() && it.token.isNotBlank() }
    }.getOrNull()
    override fun saveAccount(account: Hui798Account) {
        val owner = records.read("sessions")?.text("owner")
        records.write(mapOf("account" to StoredRecordJson.encode(account).toString()))
        if (owner != null && owner != account.phone) records.remove("sessions")
    }
    override fun clearAccount() { records.remove("account") }
    override fun removeAccount() { records.remove("sessions"); clearAccount() }
    override fun sessions(): List<Hui798Session> {
        val owner = account() ?: return emptyList()
        val data = records.read("sessions") ?: return emptyList()
        if (data.text("owner") != owner.phone) return emptyList()
        require(data.arrayValue("items").all { it.jsonObject.text("sessionId").isNotBlank() })
        return StoredRecordJson.decode<StoredHuiSessions>(data).items.map { it.session() }
    }

    override fun saveSessions(sessions: List<Hui798Session>) {
        // Completed records can be dismissed after authentication expires.
        val owner = account()?.phone ?: records.read("sessions")?.text("owner")?.takeIf { it.isNotBlank() }
            ?: throw Hui798Exception("请先登录慧生活798", loginExpired = true)
        records.write(mapOf("sessions" to StoredRecordJson.encode(StoredHuiSessions(owner, sessions.map(::storedHuiSession))).toString()))
    }
    override fun removeSession(id: String) {
        records.read("sessions")?.let { data ->
            val items = data.arrayValue("items").objects().filterNot { it.text("sessionId") == id }
            records.write(mapOf("sessions" to StoredRecordJson.updated(data.withField("items", jsonArrayOf(items))).toString()))
        }
    }
}

@Serializable private data class StoredHuiSessions(val owner: String, val items: List<StoredHuiSession>) {
    override fun toString() = "StoredHuiSessions([redacted])"
}

private fun hui798Records(context: Context): LifeDeviceRecords {
    val vault = SecureVault(context, "encrypted_hui798", "yktwx.hui798.aes.v1")
    return object : LifeDeviceRecords {
        override fun read(key: String) = vault.read(key)
        override fun write(values: Map<String, String>) = vault.write(values)
        override fun remove(key: String) = vault.remove(key)
    }
}

/** Stable persisted field names let one serializer read both existing and newly written records. */
@Serializable private data class StoredHuiSession(val deviceId: String, val name: String,
    val phase: WaterPhase, val attemptedAt: Long, val acknowledged: Boolean = false,
    val serverTime: Long? = null, val startedAt: Long? = null, val endedAt: Long? = null,
    val sessionId: String, val uncertain: Boolean = false) {
    fun session() = Hui798Session(Hui798Device(deviceId, name), phase, attemptedAt, acknowledged,
        serverTime?.takeIf { it > 0 }, startedAt?.takeIf { it > 0 }, endedAt?.takeIf { it > 0 }, sessionId,
        uncertain || phase == WaterPhase.Starting && (serverTime ?: 0) <= 0)
    override fun toString() = "StoredHuiSession([redacted])"
}
private fun storedHuiSession(value: Hui798Session) = with(value) {
    StoredHuiSession(device.id, device.name, phase, attemptedAtMillis, startAcknowledged, serverTime,
        startedAtMillis, endedAtMillis, sessionId, uncertain)
}
