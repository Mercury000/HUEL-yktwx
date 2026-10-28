package com.mercury.yktwx.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads share authenticated snapshots; session changes and non-replayable operations stay exclusive. */
class AccountRepository(private val service: YktService, private val vault: Vault, private val rechargeService: RechargeService = RechargeApi(), private val feeService: FeeService = FeeApi(), private val cardService: CardService = CardServiceApi(), private val profileService: ProfileService = ProfileApi()) {
    private val mutex = Mutex()
    private var accountGeneration = 0L
    private var sessionRevision = 0L
    private var unverifiedSessionRevision: Long? = null
    private class SessionLease(val username: String, val session: Session, val generation: Long,
        val revision: Long, val freshlyLoggedIn: Boolean)
    fun hasCredentials() = vault.credentials() != null
    fun username() = vault.credentials()?.username.orEmpty()
    suspend fun login(username: String, password: String): PaymentBatch = mutex.withLock {
        val credentials = Credentials(username.trim(), password)
        val session = service.login(credentials)
        accountGeneration++
        onVaultThread { saveCredentials(credentials); saveSession(session) }
        sessionRevision++; unverifiedSessionRevision = sessionRevision
        // Don't re-login if the newly issued token is rejected; present a login error instead.
        try { service.payment(session.accessToken).also { unverifiedSessionRevision = null } }
        catch (e: AuthenticationRequired) { clearCredentials(); throw e }
    }
    suspend fun refresh(): PaymentBatch = withSession { service.payment(it) }
    suspend fun paymentAccount(username: String): PaymentAccount = withSession(expectedUsername = username) {
        service.paymentAccount(it)
    }
    suspend fun personalProfile(username: String): PersonalProfile = withSession(expectedUsername = username) { profileService.profile(it) }
    suspend fun homeIdentity(username: String, sno: String): HomeCardIdentity? = withSession(expectedUsername = username) { profileService.homeIdentity(it, sno) }
    suspend fun profileAvatar(path: String): ByteArray? = profileService.avatar(path)
    suspend fun dormRoom(username: String, config: FeeConfiguration): DormRoom? = withSession(expectedUsername = username) {
        DormMeterDiscovery(feeService).discover(it, config)
    }
    internal suspend fun <T> withSession(retryAuthentication: Boolean = true, expectedUsername: String? = null,
        block: suspend (String) -> T): T {
        currentCoroutineContext().ensureActive()
        // Consumed captcha queries and mutations cannot be replayed or overlap account changes.
        if (!retryAuthentication) return mutex.withLock {
            val lease = acquireSession(expectedUsername)
            try { block(lease.session.accessToken).also { unverifiedSessionRevision = null } }
            catch (e: AuthenticationRequired) { clearSession(); throw e }
        }
        var lease = mutex.withLock { acquireSession(expectedUsername) }
        val result = try {
            block(lease.session.accessToken)
        } catch (e: AuthenticationRequired) {
            lease = mutex.withLock {
                currentCoroutineContext().ensureActive()
                requireOwner(lease)
                val current = onVaultThread { session() }
                // A late failure must reuse, rather than clear, a newer authenticated session.
                if (sessionRevision != lease.revision || current?.accessToken != lease.session.accessToken) {
                    acquireSession(lease.username)
                } else {
                    if (lease.freshlyLoggedIn) { clearCredentials(); throw e }
                    clearSession()
                    acquireSession(lease.username)
                }
            }
            try { block(lease.session.accessToken) }
            catch (retry: AuthenticationRequired) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    requireOwner(lease)
                    if (sessionRevision == lease.revision &&
                        onVaultThread { session() }?.accessToken == lease.session.accessToken) clearCredentials()
                }
                throw retry
            }
        }
        currentCoroutineContext().ensureActive()
        mutex.withLock {
            requireOwner(lease)
            if (sessionRevision == lease.revision) unverifiedSessionRevision = null
        }
        return result
    }
    /** Caller holds the mutex only while validating identity and obtaining/renewing a token. */
    private suspend fun acquireSession(expectedUsername: String?): SessionLease {
        currentCoroutineContext().ensureActive()
        val credentials = onVaultThread { credentials() } ?: throw AuthenticationRequired()
        if (expectedUsername != null && credentials.username != expectedUsername) throw YktException("充值账户已切换，请重新打开充值")
        var session = onVaultThread { session() }
        if (session == null || !session.isFresh()) {
            session = try { service.login(credentials) }
            catch (e: InvalidCredentials) { currentCoroutineContext().ensureActive(); clearCredentials(); throw e }
            catch (e: CaptchaRequired) { currentCoroutineContext().ensureActive(); clearCredentials(); throw e }
            currentCoroutineContext().ensureActive()
            onVaultThread { saveSession(session) }
            sessionRevision++; unverifiedSessionRevision = sessionRevision
        }
        return SessionLease(credentials.username, session, accountGeneration, sessionRevision,
            unverifiedSessionRevision == sessionRevision)
    }
    private suspend fun requireOwner(lease: SessionLease) {
        val credentials = onVaultThread { credentials() } ?: throw AuthenticationRequired()
        if (accountGeneration != lease.generation || credentials.username != lease.username)
            throw YktException("账户已切换，请重新打开页面")
    }
    private suspend fun clearSession() {
        onVaultThread { clearSession() }
        sessionRevision++; unverifiedSessionRevision = null
    }
    private suspend fun clearCredentials() {
        accountGeneration++; sessionRevision++; unverifiedSessionRevision = null
        onVaultThread { clear() }
    }
    /** Android Keystore operations are synchronous Binder calls; never run them on a UI caller. */
    private suspend fun <T> onVaultThread(block: Vault.() -> T): T =
        withContext(Dispatchers.IO) { vault.block() }
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
    suspend fun billSummary(timeFrom: String, timeTo: String, username: String? = null): BillSummary = withSession(expectedUsername = username) {
        service.billSummary(it, timeFrom, timeTo)
    }
    suspend fun billCounts(timeFrom: String, timeTo: String, username: String? = null): BillCounts = withSession(expectedUsername = username) {
        token -> coroutineScope {
            val income = async { service.billCount(token, timeFrom, timeTo, 1) }
            val expenses = async { service.billCount(token, timeFrom, timeTo, 2) }
            BillCounts(income.await(), expenses.await())
        }
    }
    suspend fun billTypes(): List<BillType> = withSession { service.billTypes(it) }
    suspend fun billCategories(type: Int, timeFrom: String, timeTo: String): List<BillCategory> = withSession {
        service.billCategories(it, type, timeFrom, timeTo)
    }
    suspend fun billTrend(type: Int, filter: BillDateFilter): List<BillDailyPoint> = withSession {
        service.billTrend(it, type, filter)
    }
    suspend fun billRanking(type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> = withSession {
        service.billRanking(it, type, timeFrom, timeTo, limit)
    }
    suspend fun rechargeConfiguration(preferredTarget: String?): RechargeConfiguration = withSession { rechargeService.rechargeConfiguration(it, preferredTarget) }
    suspend fun createRecharge(username: String, config: RechargeConfiguration, amountCents: Long): RechargeOrder = withSession(retryAuthentication = false, expectedUsername = username) { rechargeService.createRecharge(it, config, amountCents) }
    suspend fun launchRecharge(pending: PendingRecharge): WechatRechargeLaunch = withSession(retryAuthentication = false, expectedUsername = pending.username) { rechargeService.launchRecharge(it, pending) }
    suspend fun rechargeResult(pending: PendingRecharge): RechargeResult = withSession(expectedUsername = pending.username) { rechargeService.rechargeResult(it, pending) }
    suspend fun feeConfiguration(username: String, project: FeeProject) = withSession(expectedUsername = username) { feeService.feeConfiguration(it, project) }
    suspend fun feeQuery(username: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean) =
        withSession(expectedUsername = username) { feeService.feeQuery(it, project, level, selections, detail) }
    suspend fun setFeeRoom(username: String, project: FeeProject, room: FeeRoomBinding?) =
        withSession(retryAuthentication = false, expectedUsername = username) { feeService.setFeeRoom(it, project, room) }
    suspend fun createFee(username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long) =
        withSession(retryAuthentication = false, expectedUsername = username) { feeService.createFee(it, username, config, query, amountCents) }
    suspend fun prepareFeeAccount(order: PendingRecharge, method: FeeMethod) =
        withSession(expectedUsername = order.username) { feeService.prepareFeeAccount(it, order, method) }
    suspend fun payFeeAccount(order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) =
        withSession(retryAuthentication = false, expectedUsername = order.username) { feeService.payFeeAccount(it, order, method, preparation, type, password) }
    suspend fun launchFeeWechat(order: PendingRecharge, method: FeeMethod) =
        withSession(retryAuthentication = false, expectedUsername = order.username) { feeService.launchFeeWechat(it, order, method) }
    suspend fun feeResult(order: PendingRecharge) = withSession(expectedUsername = order.username) { feeService.feeResult(it, order) }
    suspend fun passwordConfiguration(username: String) = withSession(expectedUsername = username) { cardService.passwordConfiguration(it) }
    suspend fun modifyCardPassword(username: String, card: CampusCard, oldPassword: String, newPassword: String, confirmation: String) =
        withSession(retryAuthentication = false, expectedUsername = username) {
            cardService.modifyCardPassword(it, card, oldPassword, newPassword, confirmation)
            // Clear under the same session lock before a queued refresh can re-login with old credentials.
            clearCredentials()
        }
    suspend fun cardConfiguration(username: String) = withSession(expectedUsername = username) { cardService.cardConfiguration(it) }
    suspend fun changeCardState(username: String, card: CampusCard, action: CampusCardAction, password: String) =
        withSession(retryAuthentication = false, expectedUsername = username) { cardService.changeCardState(it, card, action, password) }
    suspend fun recipientCaptcha(username: String) = withSession(expectedUsername = username) { cardService.recipientCaptcha(it) }
    suspend fun rechargeRecipients(username: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String) =
        withSession(retryAuthentication = false, expectedUsername = username) { cardService.rechargeRecipients(it, type, value, captcha, code) }
    suspend fun recipientRechargeConfiguration(username: String, recipient: RechargeRecipient) =
        withSession(expectedUsername = username) { cardService.recipientRechargeConfiguration(it, recipient) }
    suspend fun logout() = mutex.withLock { clearCredentials() }

    companion object {
        @Volatile private var sharedInstance: AccountRepository? = null

        fun shared(context: Context): AccountRepository = sharedInstance ?: synchronized(this) {
            sharedInstance ?: AccountRepository(YktApi(), SecureVault(context.applicationContext)).also { sharedInstance = it }
        }
    }
}
