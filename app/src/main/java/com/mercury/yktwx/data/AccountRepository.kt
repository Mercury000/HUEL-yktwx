package com.mercury.yktwx.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All requests share one lock, so multiple expired-token callers cannot trigger a login storm. */
class AccountRepository(private val service: YktService, private val vault: Vault, private val rechargeService: RechargeService = RechargeApi()) {
    private val mutex = Mutex()
    fun hasCredentials() = vault.credentials() != null
    fun username() = vault.credentials()?.username.orEmpty()
    suspend fun login(username: String, password: String): PaymentBatch = mutex.withLock {
        val credentials = Credentials(username.trim(), password)
        val session = service.login(credentials)
        vault.saveCredentials(credentials); vault.saveSession(session)
        // Don't re-login if the newly issued token is rejected; present a login error instead.
        try { service.payment(session.accessToken) }
        catch (e: AuthenticationRequired) { vault.clear(); throw e }
    }
    suspend fun refresh(): PaymentBatch = withSession { service.payment(it) }
    internal suspend fun <T> withSession(retryAuthentication: Boolean = true, expectedUsername: String? = null, block: suspend (String) -> T): T = mutex.withLock {
        val credentials = vault.credentials() ?: throw AuthenticationRequired()
        if (expectedUsername != null && credentials.username != expectedUsername) throw YktException("充值账户已切换，请重新打开充值")
        var session = vault.session()
        var loggedIn = false
        suspend fun relogin(): Session {
            try { return service.login(credentials).also { vault.saveSession(it) } }
            catch (e: InvalidCredentials) { vault.clear(); throw e }
            catch (e: CaptchaRequired) { vault.clear(); throw e }
        }
        if (session == null || !session.isFresh()) { session = relogin(); loggedIn = true }
        try { block(session.accessToken) }
        catch (e: AuthenticationRequired) {
            vault.clearSession()
            if (!retryAuthentication) throw e
            if (loggedIn) { vault.clear(); throw e }
            val next = relogin()
            try { block(next.accessToken) }
            catch (e: AuthenticationRequired) { vault.clear(); throw e }
        }
    }
    suspend fun bills(
        current: Int,
        size: Int,
        timeFrom: String,
        timeTo: String,
        typeId: String?,
        amountFromCents: Long?,
        amountToCents: Long?,
        info: String?,
    ): BillPageData = withSession {
        service.bills(it, current, size, timeFrom, timeTo, typeId, amountFromCents, amountToCents, info)
    }
    suspend fun billSummary(timeFrom: String, timeTo: String): BillSummary = withSession {
        service.billSummary(it, timeFrom, timeTo)
    }
    suspend fun billTypes(): List<BillType> = withSession { service.billTypes(it) }
    suspend fun billCategories(type: Int, timeFrom: String, timeTo: String): List<BillCategory> = withSession {
        service.billCategories(it, type, timeFrom, timeTo)
    }
    suspend fun billDaily(type: Int, month: String): List<BillDailyPoint> = withSession {
        service.billDaily(it, type, month)
    }
    suspend fun billRanking(type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> = withSession {
        service.billRanking(it, type, timeFrom, timeTo, limit)
    }
    suspend fun rechargeConfiguration(preferredTarget: String?): RechargeConfiguration = withSession { rechargeService.rechargeConfiguration(it, preferredTarget) }
    suspend fun createRecharge(username: String, config: RechargeConfiguration, amountCents: Long): RechargeOrder = withSession(retryAuthentication = false, expectedUsername = username) { rechargeService.createRecharge(it, config, amountCents) }
    suspend fun launchRecharge(pending: PendingRecharge): WechatRechargeLaunch = withSession(retryAuthentication = false, expectedUsername = pending.username) { rechargeService.launchRecharge(it, pending) }
    suspend fun rechargeResult(pending: PendingRecharge): RechargeResult = withSession(expectedUsername = pending.username) { rechargeService.rechargeResult(it, pending) }
    suspend fun logout() = mutex.withLock { vault.clear() }

    companion object {
        @Volatile private var sharedInstance: AccountRepository? = null

        fun shared(context: Context): AccountRepository = sharedInstance ?: synchronized(this) {
            sharedInstance ?: AccountRepository(YktApi(), SecureVault(context.applicationContext)).also { sharedInstance = it }
        }
    }
}
