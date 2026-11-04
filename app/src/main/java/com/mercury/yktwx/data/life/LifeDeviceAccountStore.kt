package com.mercury.yktwx.data.life

import android.content.Context
import com.mercury.yktwx.data.SecureVault
import org.json.JSONArray
import org.json.JSONObject

/** Independent platform records, backed by the app's existing Keystore encryption. */
class LifeDeviceAccountStore(context: Context) : LifeDeviceStore {
    private val vault = SecureVault(context, "encrypted_life_devices", "yktwx.life.devices.aes.v1")
    override fun account(): LifeAccount? = vault.read("account")?.let {
        val phone = it.text("phone")
        val token = it.text("token")
        if (phone.isBlank() || token.isBlank()) null else LifeAccount(phone, token)
    }
    override fun saveAccount(account: LifeAccount) {
        if (account().let { it != null && it.phone != account.phone }) { clearSession(); clearLaundrySession() }
        vault.write(mapOf("account" to JSONObject().put("phone", account.phone).put("token", account.token).toString()))
    }
    override fun clearAccount() { vault.remove("account") }
    override fun session(): WaterSession? = runCatching {
        val data = vault.read("water") ?: return null
        val owner = account() ?: return null
        if (data.text("owner") != owner.phone) return null
        val device = data.getJSONObject("device")
        WaterSession(
            readDevice(device),
            data.getString("skuId"), WaterPhase.valueOf(data.getString("phase")), data.getBoolean("startSent"),
            data.text("messageId"), data.text("orderNo"), data.text("orderId"),
            data.optLong("startedAt").takeIf { it > 0 }, data.optLong("endedAt").takeIf { it > 0 }, data.optBoolean("finalized"))
    }.getOrNull()
    override fun saveSession(session: WaterSession) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        val device = writeDevice(session.device)
        vault.write(mapOf("water" to JSONObject().put("owner", owner.phone).put("device", device).put("skuId", session.skuId)
            .put("phase", session.phase.name).put("startSent", session.startSent).put("messageId", session.messageId)
            .put("orderNo", session.orderNo).put("orderId", session.orderId).put("startedAt", session.startedAtMillis)
            .put("endedAt", session.endedAtMillis).put("finalized", session.finalized).toString()))
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
            data.optLong("expectedEnd").takeIf { it > 0 }, data.optLong("finishedAt").takeIf { it > 0 })
    }.getOrNull()
    override fun saveLaundrySession(session: LaundrySession) {
        val owner = account() ?: throw LifeDeviceException("请先登录胖乖生活", true)
        vault.write(mapOf("laundry" to JSONObject().put("owner", owner.phone).put("device", writeDevice(session.device))
            .put("skuId", session.skuId).put("price", session.price).put("payMethod", session.payMethod).put("phase", session.phase.name)
            .put("createSent", session.createSent).put("orderNo", session.orderNo).put("orderId", session.orderId)
            .put("paymentSent", session.paymentSent).put("paid", session.paid).put("minutes", session.minutes)
            .put("startedAt", session.startedAtMillis).put("expectedEnd", session.expectedEndMillis)
            .put("finishedAt", session.finishedAtMillis).toString()))
    }
    override fun clearLaundrySession() { vault.remove("laundry") }
    private fun writeDevice(d: LifeDevice): JSONObject = JSONObject().put("id", d.id).put("serial", d.serial).put("name", d.name).put("category", d.category)
            .put("routeShopId", d.routeShopId).put("orgId", d.orgId).put("shopId", d.shopId).put("imei", d.imei)
            .put("shopName", d.shopName).put("address", d.address).put("payment", d.payment).put("bluetooth", d.bluetooth)
            .put("supportsFinish", d.supportsFinish).put("error", d.error)
            .put("skus", JSONArray(d.skus.map { JSONObject().put("id", it.id).put("name", it.name).put("available", it.available)
                .put("price", it.price).put("feature", it.feature) }))
            .put("channels", JSONArray(d.channels.map { JSONObject().put("method", it.method).put("name", it.name)
                .put("available", it.available).put("needsOpen", it.needsOpen).put("remark", it.remark).put("link", it.link) }))
    private fun readDevice(device: JSONObject): LifeDevice = LifeDevice(device.getString("id"), device.getString("serial"),
        device.getString("name"), device.getString("category"), device.getString("routeShopId"), device.getString("orgId"),
        device.getString("shopId"), device.getString("imei"), device.getString("shopName"), device.getString("address"),
        device.getString("payment"), device.getBoolean("bluetooth"), device.getBoolean("supportsFinish"), device.text("error").ifBlank { null },
        device.getJSONArray("skus").objects().map { LifeSku(it.getString("id"), it.getString("name"), it.getBoolean("available"),
            it.text("price"), it.text("feature")) }, device.getJSONArray("channels").objects().map {
            LifePayChannel(it.getString("method"), it.getString("name"), it.getBoolean("available"), it.getBoolean("needsOpen"),
                it.text("remark").ifBlank { null }, it.text("link").ifBlank { null }) })
}
