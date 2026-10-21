package com.mercury.yktwx.data

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/** Legacy encrypted records; the foreground recharge flow does not restore old orders. */
class SecureRechargeStore(context: Context) : RechargeStore {
    private val vault = SecureVault(context.applicationContext, "encrypted_recharge")
    private fun key(username: String) = "pending_" + MessageDigest.getInstance("SHA-256")
        .digest(username.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    override fun pending(username: String): PendingRecharge? = vault.read(key(username), strict = true)?.let {
        if (it.rechargeText("username") != username) throw YktException("充值记录账户不匹配")
        PendingRecharge(username, it.getString("target"), it.getString("fee"), it.getLong("amount"), it.getLong("created"),
            it.rechargeText("order").ifBlank { null }, it.rechargeText("pay").ifBlank { null })
    }
    override fun save(pending: PendingRecharge) {
        vault.write(key(pending.username), JSONObject().put("username", pending.username).put("target", pending.targetId)
            .put("fee", pending.feeItemId).put("amount", pending.amountCents).put("created", pending.createdAtMillis)
            .put("order", pending.orderId).put("pay", pending.payId).toString())
    }
    override fun clear(username: String) = vault.remove(key(username))
}
