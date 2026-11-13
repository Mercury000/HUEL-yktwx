package com.mercury.yktwx.data.life

import android.content.Context
import com.mercury.yktwx.data.SecureVault
import com.mercury.yktwx.data.StoredRecordJson

/** One encrypted, checked atomic record binds credentials, region and pending operations to their owner. */
class HellobikeAccountStore internal constructor(private val records: LifeDeviceRecords) : HellobikeStore {
    constructor(context: Context) : this(SecureVault(context, "encrypted_hellobike", "yktwx.hellobike.aes.v1").let { vault ->
        val prefs = context.getSharedPreferences("encrypted_hellobike", Context.MODE_PRIVATE)
        object : LifeDeviceRecords {
            override fun read(key: String): kotlinx.serialization.json.JsonObject? {
                val existed = prefs.contains(key)
                val value = vault.read(key)
                if (existed && value == null) throw HellobikeException("本机骑行记录无法读取，请重新登录并在哈啰核对订单", storageFailure = true)
                return value
            }
            override fun write(values: Map<String, String>) = vault.write(values)
            override fun remove(key: String) = vault.remove(key)
        }
    })
    @Synchronized override fun load(): HellobikeStoredState {
        val data = records.read("state") ?: return HellobikeStoredState()
        try {
            return StoredRecordJson.decode<HellobikeStoredState>(data).also { value ->
                require(value.account == null || value.account.userGuid == value.ownerGuid && value.account.token.isNotBlank())
                require(value.sessions.isEmpty() || value.ownerGuid.isNotBlank())
                require(value.retainedSessions.keys.none(String::isBlank))
                (value.retainedSessions.values + listOf(value.sessions)).forEach { sessions ->
                    require(sessions.map { it.sessionId }.distinct().size == sessions.size)
                    require(sessions.map { it.rideGuid }.distinct().size == sessions.size)
                    require(sessions.all { it.sessionId.isNotBlank() && it.rideGuid.isNotBlank() && (it.region == null || it.region.valid) })
                }
            }
        } catch (_: Exception) { throw HellobikeException("无法读取哈啰账号或骑行记录，请重新登录后核对订单", storageFailure = true) }
    }
    private fun write(value: HellobikeStoredState) = records.write(mapOf("state" to StoredRecordJson.encode(value).toString()))
    @Synchronized override fun saveClientId(clientId: String) { require(clientId.isNotBlank()); write(load().copy(clientId = clientId)) }
    @Synchronized override fun saveRegion(region: HellobikeRegion) { require(region.valid); write(load().copy(region = region)) }
    @Synchronized override fun saveAccount(account: HellobikeAccount) {
        require(account.userGuid.isNotBlank() && account.token.isNotBlank() && account.phone.matches(Regex("1[0-9]{10}")))
        val old = load()
        val retained = if (old.ownerGuid.isNotBlank() && old.ownerGuid != account.userGuid)
            old.retainedSessions + (old.ownerGuid to old.sessions.filterNot { it.dismissible }) else old.retainedSessions
        write(old.copy(account = account, ownerGuid = account.userGuid, maskedPhone = account.maskedPhone,
            sessions = if (old.ownerGuid == account.userGuid) old.sessions else retained[account.userGuid].orEmpty(),
            retainedSessions = retained - account.userGuid))
    }
    @Synchronized override fun saveSessions(ownerGuid: String, sessions: List<HellobikeSession>) {
        val old = load()
        if (ownerGuid.isBlank() || old.ownerGuid != ownerGuid || old.account?.let { it.userGuid != ownerGuid } == true)
            throw HellobikeException("哈啰骑行记录与账户不一致，请重新登录", storageFailure = true)
        write(old.copy(sessions = sessions))
    }
    @Synchronized override fun clearAccount() { write(load().copy(account = null)) }
    @Synchronized override fun removeAccount() {
        val old = load()
        val retained = if (old.ownerGuid.isNotBlank()) old.retainedSessions + (old.ownerGuid to old.sessions.filterNot { it.dismissible }) else old.retainedSessions
        write(HellobikeStoredState(clientId = old.clientId, region = old.region, retainedSessions = retained, recoveryRequired = old.recoveryRequired))
    }
    @Synchronized override fun recoverUnreadable(clientId: String) {
        write(HellobikeStoredState(clientId = clientId, recoveryRequired = true))
    }
    @Synchronized override fun acknowledgeRecovery() { write(load().copy(recoveryRequired = false)) }
}
