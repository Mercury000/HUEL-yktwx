package com.mercury.yktwx.data.life

import android.content.Context
import com.mercury.yktwx.data.SecureVault
import org.json.JSONArray
import org.json.JSONObject

/** Independent platform records, backed by the app's existing Keystore encryption. */
internal interface LifeDeviceRecords {
    fun read(key: String): JSONObject?
    fun write(values: Map<String, String>)
    fun remove(key: String)
}

class LifeDeviceAccountStore internal constructor(private val vault: LifeDeviceRecords) : LifeDeviceStore {
    constructor(context: Context) : this(encryptedRecords(context))
    override fun account(): LifeAccount? = vault.read("account")?.let {
        val phone = it.text("phone")
        val token = it.text("token")
        if (phone.isBlank() || token.isBlank()) null else LifeAccount(phone, token)
    }
    override fun saveAccount(account: LifeAccount) {
        val changing = account().let { it != null && it.phone != account.phone }
        vault.write(mapOf("account" to JSONObject().put("phone", account.phone).put("token", account.token).toString()))
        if (changing) {
            clearSession(); clearLaundrySession(); vault.remove("water_sessions"); vault.remove("laundry_sessions")
        }
    }
    override fun clearAccount() { vault.remove("account") }
    override fun removeAccount() {
        vault.remove("water"); vault.remove("laundry"); vault.remove("water_sessions"); vault.remove("laundry_sessions")
        clearAccount()
    }
    override fun session(): WaterSession? = runCatching {
        val data = vault.read("water") ?: return null
        val owner = account() ?: return null
        if (data.text("owner") != owner.phone) return null
        val device = data.getJSONObject("device")
        WaterSession(
            readDevice(device),
            data.getString("skuId"), WaterPhase.valueOf(data.getString("phase")), data.getBoolean("startSent"),
            data.text("messageId"), data.text("orderNo"), data.text("orderId"),
            data.optLong("startedAt").takeIf { it > 0 }, data.optLong("endedAt").takeIf { it > 0 }, data.optBoolean("finalized"),
            data.text("sessionId").ifBlank { "legacy-water-${data.text("orderNo")}-${data.getString("skuId")}" })
    }.getOrNull()
    override fun saveSession(session: WaterSession) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        val device = writeDevice(session.device)
        vault.write(mapOf("water" to JSONObject().put("owner", owner.phone).put("device", device).put("skuId", session.skuId)
            .put("phase", session.phase.name).put("startSent", session.startSent).put("messageId", session.messageId)
            .put("orderNo", session.orderNo).put("orderId", session.orderId).put("startedAt", session.startedAtMillis)
            .put("endedAt", session.endedAtMillis).put("finalized", session.finalized).put("sessionId", session.sessionId).toString()))
    }
    override fun clearSession() { vault.remove("water") }
    override fun laundrySession(): LaundrySession? = runCatching {
        val data = vault.read("laundry") ?: return null
        val owner = account() ?: return null
        if (data.text("owner") != owner.phone) return null
        LaundrySession(readDevice(data.getJSONObject("device")), data.getString("skuId"), data.getString("price"),
            data.getString("payMethod"), LaundryPhase.valueOf(data.getString("phase")), data.optBoolean("createSent"),
            data.text("orderNo"), data.text("orderId"), data.optBoolean("paymentSent"), data.optBoolean("paid"),
            data.optInt("minutes").takeIf { it > 0 }, data.optLong("startedAt").takeIf { it > 0 },
            data.optLong("expectedEnd").takeIf { it > 0 }, data.optLong("finishedAt").takeIf { it > 0 },
            data.text("sessionId").ifBlank { "legacy-laundry-${data.text("orderNo")}-${data.getString("skuId")}" },
            data.optLong("createdAt").takeIf { it > 0 }, data.text("payTypeName"),
            data.optLong("completionObservedAt").takeIf { it > 0 })
    }.getOrNull()
    override fun saveLaundrySession(session: LaundrySession) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        vault.write(mapOf("laundry" to JSONObject().put("owner", owner.phone).put("device", writeDevice(session.device))
            .put("skuId", session.skuId).put("price", session.price).put("payMethod", session.payMethod).put("phase", session.phase.name)
            .put("createSent", session.createSent).put("orderNo", session.orderNo).put("orderId", session.orderId)
            .put("paymentSent", session.paymentSent).put("paid", session.paid).put("minutes", session.minutes)
            .put("startedAt", session.startedAtMillis).put("expectedEnd", session.expectedEndMillis)
            .put("finishedAt", session.finishedAtMillis).put("sessionId", session.sessionId).put("createdAt", session.createdAtMillis)
            .put("payTypeName", session.payTypeName).put("completionObservedAt", session.completionObservedAtMillis).toString()))
    }
    override fun clearLaundrySession() { vault.remove("laundry") }
    override fun waterSessions(): List<WaterSession> = records("water_sessions")?.map { data ->
        WaterSession(readDevice(data.getJSONObject("device")), data.getString("skuId"),
            WaterPhase.valueOf(data.getString("phase")), data.optBoolean("startSent"), data.text("messageId"),
            data.text("orderNo"), data.text("orderId"), data.optLong("startedAt").takeIf { it > 0 },
            data.optLong("endedAt").takeIf { it > 0 }, data.optBoolean("finalized"), data.getString("sessionId"))
    } ?: listOfNotNull(session())
    override fun laundrySessions(): List<LaundrySession> = records("laundry_sessions")?.map { data ->
        LaundrySession(readDevice(data.getJSONObject("device")), data.getString("skuId"), data.getString("price"),
            data.getString("payMethod"), LaundryPhase.valueOf(data.getString("phase")), data.optBoolean("createSent"),
            data.text("orderNo"), data.text("orderId"), data.optBoolean("paymentSent"), data.optBoolean("paid"),
            data.optInt("minutes").takeIf { it > 0 }, data.optLong("startedAt").takeIf { it > 0 },
            data.optLong("expectedEnd").takeIf { it > 0 }, data.optLong("finishedAt").takeIf { it > 0 }, data.getString("sessionId"),
            data.optLong("createdAt").takeIf { it > 0 }, data.text("payTypeName"),
            data.optLong("completionObservedAt").takeIf { it > 0 })
    } ?: listOfNotNull(laundrySession())
    override fun saveWaterSessions(sessions: List<WaterSession>) {
        saveRecords("water_sessions", sessions.map { session -> JSONObject()
            .put("sessionId", session.sessionId).put("device", writeDevice(session.device)).put("skuId", session.skuId)
            .put("phase", session.phase.name).put("startSent", session.startSent).put("messageId", session.messageId)
            .put("orderNo", session.orderNo).put("orderId", session.orderId).put("startedAt", session.startedAtMillis)
            .put("endedAt", session.endedAtMillis).put("finalized", session.finalized) })
        clearSession()
    }
    override fun saveLaundrySessions(sessions: List<LaundrySession>) {
        saveRecords("laundry_sessions", sessions.map { session -> JSONObject()
            .put("sessionId", session.sessionId).put("device", writeDevice(session.device)).put("skuId", session.skuId)
            .put("price", session.price).put("payMethod", session.payMethod).put("phase", session.phase.name)
            .put("createSent", session.createSent).put("orderNo", session.orderNo).put("orderId", session.orderId)
            .put("paymentSent", session.paymentSent).put("paid", session.paid).put("minutes", session.minutes)
            .put("startedAt", session.startedAtMillis).put("expectedEnd", session.expectedEndMillis)
            .put("finishedAt", session.finishedAtMillis).put("createdAt", session.createdAtMillis).put("payTypeName", session.payTypeName)
            .put("completionObservedAt", session.completionObservedAtMillis) })
        clearLaundrySession()
    }
    private fun records(key: String): List<JSONObject>? {
        val data = vault.read(key) ?: return null
        if (data.text("owner") != account()?.phone) return emptyList()
        return data.getJSONArray("sessions").objects()
    }
    private fun saveRecords(key: String, sessions: List<JSONObject>) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        vault.write(mapOf(key to JSONObject().put("owner", owner.phone).put("sessions", JSONArray(sessions)).toString()))
    }
    private fun writeDevice(d: LifeDevice): JSONObject = JSONObject().put("id", d.id).put("serial", d.serial).put("name", d.name).put("category", d.category)
            .put("routeShopId", d.routeShopId).put("orgId", d.orgId).put("shopId", d.shopId).put("imei", d.imei)
            .put("shopName", d.shopName).put("address", d.address).put("payment", d.payment).put("bluetooth", d.bluetooth)
            .put("supportsFinish", d.supportsFinish).put("error", d.error)
            .put("skus", JSONArray(d.skus.map { JSONObject().put("id", it.id).put("name", it.name).put("available", it.available)
                .put("price", it.price).put("feature", it.feature).put("minutes", it.minutes) }))
            .put("channels", JSONArray(d.channels.map { JSONObject().put("method", it.method).put("name", it.name)
                .put("available", it.available).put("needsOpen", it.needsOpen).put("remark", it.remark).put("link", it.link) }))
    private fun readDevice(device: JSONObject): LifeDevice = LifeDevice(device.getString("id"), device.getString("serial"),
        device.getString("name"), device.getString("category"), device.getString("routeShopId"), device.getString("orgId"),
        device.getString("shopId"), device.getString("imei"), device.getString("shopName"), device.getString("address"),
        device.getString("payment"), device.getBoolean("bluetooth"), device.getBoolean("supportsFinish"), device.text("error").ifBlank { null },
        device.getJSONArray("skus").objects().map { LifeSku(it.getString("id"), it.getString("name"), it.getBoolean("available"),
            it.text("price"), it.text("feature"), it.optInt("minutes").takeIf { minutes -> minutes > 0 }) }, device.getJSONArray("channels").objects().map {
            LifePayChannel(it.getString("method"), it.getString("name"), it.getBoolean("available"), it.getBoolean("needsOpen"),
                it.text("remark").ifBlank { null }, it.text("link").ifBlank { null }) })
}

private fun encryptedRecords(context: Context): LifeDeviceRecords {
    val vault = SecureVault(context, "encrypted_life_devices", "yktwx.life.devices.aes.v1")
    return object : LifeDeviceRecords {
        override fun read(key: String) = vault.read(key)
        override fun write(values: Map<String, String>) = vault.write(values)
        override fun remove(key: String) = vault.remove(key)
    }
}
