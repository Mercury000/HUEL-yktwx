package com.mercury.yktwx.data.life

import android.content.Context
import com.mercury.yktwx.data.SecureVault
import org.json.JSONArray
import org.json.JSONObject

class Hui798AccountStore internal constructor(private val records: LifeDeviceRecords) : Hui798Store {
    constructor(context: Context) : this(hui798Records(context))
    override fun account(): Hui798Account? = records.read("account")?.let {
        val phone = it.text("phone"); val token = it.text("token")
        if (phone.isBlank() || token.isBlank()) null else Hui798Account(phone, token, it.text("userId").takeIf(String::isNotBlank))
    }
    override fun saveAccount(account: Hui798Account) {
        val owner = records.read("sessions")?.text("owner")
        records.write(mapOf("account" to JSONObject().put("phone", account.phone).put("token", account.token)
            .put("userId", account.userId).toString()))
        if (owner != null && owner != account.phone) records.remove("sessions")
    }
    override fun clearAccount() { records.remove("account") }
    override fun removeAccount() { records.remove("sessions"); clearAccount() }
    override fun sessions(): List<Hui798Session> {
        val owner = account() ?: return emptyList()
        val data = records.read("sessions") ?: return emptyList()
        if (data.text("owner") != owner.phone) return emptyList()
        return data.getJSONArray("items").objects().map {
            Hui798Session(Hui798Device(it.getString("deviceId"), it.getString("name")), WaterPhase.valueOf(it.getString("phase")),
                it.getLong("attemptedAt"), it.optBoolean("acknowledged"), it.optLong("serverTime").takeIf { value -> value > 0 },
                it.optLong("startedAt").takeIf { value -> value > 0 }, it.optLong("endedAt").takeIf { value -> value > 0 }, it.getString("sessionId"))
        }
    }
    override fun saveSessions(sessions: List<Hui798Session>) {
        // Completed records can be dismissed after authentication expires.
        val owner = account()?.phone ?: records.read("sessions")?.text("owner")?.takeIf { it.isNotBlank() }
            ?: throw Hui798Exception("请先登录慧生活798", loginExpired = true)
        val items = sessions.map { JSONObject().put("deviceId", it.device.id).put("name", it.device.name).put("phase", it.phase.name)
            .put("attemptedAt", it.attemptedAtMillis).put("acknowledged", it.startAcknowledged).put("serverTime", it.serverTime)
            .put("startedAt", it.startedAtMillis).put("endedAt", it.endedAtMillis).put("sessionId", it.sessionId) }
        records.write(mapOf("sessions" to JSONObject().put("owner", owner).put("items", JSONArray(items)).toString()))
    }
}

private fun hui798Records(context: Context): LifeDeviceRecords {
    val vault = SecureVault(context, "encrypted_hui798", "yktwx.hui798.aes.v1")
    return object : LifeDeviceRecords {
        override fun read(key: String) = vault.read(key)
        override fun write(values: Map<String, String>) = vault.write(values)
        override fun remove(key: String) = vault.remove(key)
    }
}
