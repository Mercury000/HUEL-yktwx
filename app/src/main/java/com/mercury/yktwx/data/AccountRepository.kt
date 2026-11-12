package com.mercury.yktwx.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Reads share authenticated snapshots; session changes and non-replayable operations stay exclusive. */
class AccountRepository internal constructor(private val service: YktService, private val vault: Vault, private val rechargeService: RechargeService, private val feeService: FeeService, private val cardService: CardService, private val profileService: ProfileService, private val displayCache: DisplayCache? = null, private val readNow: () -> Long = ReadCachePolicy::now,
    internal val overviewReads: SharedReadStore, internal val cardReads: SharedReadStore,
    internal val dataChanges: AccountDataChanges) : SessionAuthority {
    internal fun identity(username: String): AccountIdentity? = sessionState.value.let {
        AccountIdentity(username, it.generation).takeIf { _ -> it.username == username }
    }
    private fun publishSession(state: SessionState) {
        val identity = state.username?.let { AccountIdentity(it, state.generation) }
        overviewReads.reset(identity)
        cardReads.reset(identity)
        dataChanges.reset(state)
        mutableSession.value = state
    }
    private val mutex = Mutex()
    private val mutableSession = MutableStateFlow(SessionState())
    override val sessionState = mutableSession.asStateFlow()
    @Volatile private var accountGeneration = 0L
    private var sessionRevision = 0L
    private var unverifiedSessionRevision: Long? = null
    private class AccountSnapshot(val credentials: Credentials?, val session: Session?)
    @Volatile private var cachedAccount: AccountSnapshot? = null
    private class SessionLease(val username: String, val session: Session, val generation: Long,
        val revision: Long, val freshlyLoggedIn: Boolean)
    suspend fun hasCredentials() = mutex.withLock { account().credentials != null }
    suspend fun username() = mutex.withLock { account().credentials?.username.orEmpty() }
    override suspend fun restoreSession(): SessionState = mutex.withLock { account(); sessionState.value }
    /** UI guards fail closed before restoration and never touch disk or Android Keystore. */
    fun ownsAccount(username: String) = username.isNotBlank() && cachedAccount?.credentials?.username == username
    /** Local display must not wait for a slow token renewal. Recheck revocation after disk IO. */
    internal suspend fun <T> cachedDisplay(username: String, key: String, codec: DisplayCodec<T>): T? {
        currentCoroutineContext().ensureActive()
        val generation = accountGeneration
        if (displayCache == null || !ownsAccount(username)) return null
        val cached = withContext(Dispatchers.IO) { displayCache.read(username, key, codec) }
        return cached.takeIf { generation == accountGeneration && ownsAccount(username) }
    }
    /** Serialize disk mutations with identity revocation; no late write can survive logout. */
    internal suspend fun <T> saveDisplay(username: String, key: String, value: T, codec: DisplayCodec<T>) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!ownsAccount(username)) return@withLock
        withContext(Dispatchers.IO) { displayCache?.write(username, key, value, codec) }
        Unit
    }
    internal suspend fun removeDisplay(username: String, key: String) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (ownsAccount(username)) withContext(Dispatchers.IO) { displayCache?.remove(username, key) }
        Unit
    }
    /** All snapshot initialization and mutations are serialized by the session mutex. */
    private suspend fun account(): AccountSnapshot = cachedAccount ?: onVaultThread {
        val credentials = credentials()
        AccountSnapshot(credentials, if (credentials != null) session() else null)
    }.also {
        cachedAccount = it
        publishSession(SessionState(restored = true, username = it.credentials?.username, generation = accountGeneration))
    }
    suspend fun login(username: String, password: String): PaymentBatch = mutex.withLock {
        account()
        val credentials = Credentials(username.trim(), password)
        val session = try { service.login(credentials) }
        catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            clearCredentials((e as? YktException)?.sessionEndReason() ?: SessionEndReason.LoginFailed)
            throw e
        }
        currentCoroutineContext().ensureActive()
        // Persistence and the in-memory identity must agree even if the caller is cancelled.
        withContext(NonCancellable) {
            onVaultThread { saveAccount(credentials, session) }
            cachedAccount = AccountSnapshot(credentials, session)
            accountGeneration++
            sessionRevision++; unverifiedSessionRevision = sessionRevision
            publishSession(SessionState(restored = true, username = credentials.username, generation = accountGeneration))
        }
        currentCoroutineContext().ensureActive()
        // Don't re-login if the newly issued token is rejected; present a login error instead.
        try { service.payment(session.accessToken).also { unverifiedSessionRevision = null } }
        catch (e: YktException) {
            currentCoroutineContext().ensureActive()
            if (e.isAuthenticationFailure()) clearCredentials(e.sessionEndReason())
            throw e
        }
    }
    suspend fun refresh(onAccount: suspend (PaymentAccount) -> Unit = {}): PaymentBatch = withSession { service.payment(it, onAccount) }
    suspend fun paymentAccount(username: String): PaymentAccount = withSession(expectedUsername = username) {
        service.paymentAccount(it)
    }
    private fun cardStamp() = dataChanges.state.value.versions[AccountResource.Data.Cards] ?: 0L
    internal fun cardSnapshot(username: String, account: String): SharedReadStore.Snapshot<CampusCard> =
        cardReads.snapshot(identity(username), "card:$account", cardStamp())
    private fun publishCards(owner: AccountIdentity?, cards: List<CampusCard>, stamp: Long) {
        if (stamp != cardStamp()) return
        cards.forEach { card -> cardReads.put(owner, "card:${card.account}", stamp, card, if (card.status >= 0) readNow() else 0L) }
    }
    suspend fun paymentCard(username: String, account: String, force: Boolean = false): CampusCard {
        restoreSession()
        val owner = identity(username) ?: throw AuthenticationRequired()
        return cardReads.read(owner, "card:$account", cardStamp(), force, now = readNow,
            currentStamp = ::cardStamp, waitForChange = false) {
            withSession(expectedUsername = username) { service.paymentCard(it, account) }.also {
                if (it.account != account || it.status < 0) throw YktException("校园卡状态数据异常")
            }
        }
    }
    suspend fun personalProfile(username: String): PersonalProfile = withSession(expectedUsername = username) { profileService.profile(it) }
    suspend fun homeIdentity(username: String, sno: String): HomeCardIdentity? = withSession(expectedUsername = username) { profileService.homeIdentity(it, sno) }
    suspend fun profileAvatar(path: String): ByteArray? = profileService.avatar(path)
    suspend fun dormRoom(username: String, config: FeeConfiguration,
        otherConfiguration: (suspend () -> FeeConfiguration?)? = null,
        onUpdate: suspend (DormRoom) -> Unit = {}): DormRoom? = withSession(expectedUsername = username) {
        DormRoomLoader(feeService).load(it, config, otherConfiguration, onUpdate)
    }
    internal suspend fun <T> withSession(retryAuthentication: Boolean = true, expectedUsername: String? = null,
        block: suspend (String) -> T): T {
        currentCoroutineContext().ensureActive()
        // Consumed captcha queries and mutations cannot be replayed or overlap account changes.
        if (!retryAuthentication) return mutex.withLock {
            val lease = acquireSession(expectedUsername)
            try { withContext(Dispatchers.Default) { block(lease.session.accessToken) }.also { unverifiedSessionRevision = null } }
            catch (e: YktException) {
                if (!e.isAuthenticationFailure()) throw e
                currentCoroutineContext().ensureActive()
                if (e !is AuthenticationRequired || lease.freshlyLoggedIn) clearCredentials(e.sessionEndReason()) else clearSession()
                throw e
            }
        }
        var lease = mutex.withLock { acquireSession(expectedUsername) }
        val result = try {
            withContext(Dispatchers.Default) { block(lease.session.accessToken) }
        } catch (e: YktException) {
            if (!e.isAuthenticationFailure()) throw e
            if (e !is AuthenticationRequired) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    requireOwner(lease)
                    if (sessionRevision == lease.revision) clearCredentials(e.sessionEndReason())
                }
                throw e
            }
            lease = mutex.withLock {
                currentCoroutineContext().ensureActive()
                requireOwner(lease)
                val current = cachedAccount?.session
                // A late failure must reuse, rather than clear, a newer authenticated session.
                if (sessionRevision != lease.revision || current?.accessToken != lease.session.accessToken) {
                    acquireSession(lease.username)
                } else {
                    if (lease.freshlyLoggedIn) { clearCredentials(SessionEndReason.Authentication); throw e }
                    clearSession()
                    acquireSession(lease.username)
                }
            }
            try { withContext(Dispatchers.Default) { block(lease.session.accessToken) } }
            catch (retry: YktException) {
                if (!retry.isAuthenticationFailure()) throw retry
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    requireOwner(lease)
                    if (sessionRevision == lease.revision &&
                        cachedAccount?.session?.accessToken == lease.session.accessToken) clearCredentials(retry.sessionEndReason())
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
        val snapshot = account()
        val credentials = snapshot.credentials ?: throw AuthenticationRequired()
        if (expectedUsername != null && credentials.username != expectedUsername) throw YktException("充值账户已切换，请重新打开充值")
        var session = snapshot.session
        if (session == null || !session.isFresh()) {
            session = try { service.login(credentials) }
            catch (e: InvalidCredentials) { currentCoroutineContext().ensureActive(); clearCredentials(SessionEndReason.InvalidCredentials); throw e }
            catch (e: CaptchaRequired) { currentCoroutineContext().ensureActive(); clearCredentials(SessionEndReason.CaptchaRequired); throw e }
            catch (e: AuthenticationRequired) { currentCoroutineContext().ensureActive(); clearCredentials(SessionEndReason.Authentication); throw e }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                onVaultThread { saveSession(session) }
                cachedAccount = AccountSnapshot(credentials, session)
                sessionRevision++; unverifiedSessionRevision = sessionRevision
            }
        }
        return SessionLease(credentials.username, session, accountGeneration, sessionRevision,
            unverifiedSessionRevision == sessionRevision)
    }
    private fun requireOwner(lease: SessionLease) {
        val credentials = cachedAccount?.credentials ?: throw AuthenticationRequired()
        if (accountGeneration != lease.generation || credentials.username != lease.username)
            throw YktException("账户已切换，请重新打开页面")
    }
    private suspend fun clearSession() = withContext(NonCancellable) {
        cachedAccount = AccountSnapshot(cachedAccount?.credentials, null)
        sessionRevision++; unverifiedSessionRevision = null
        onVaultThread { clearSession() }
    }
    private suspend fun clearCredentials(reason: SessionEndReason) = withContext(NonCancellable) {
        val previousOwner = cachedAccount?.credentials?.username
        accountGeneration++; sessionRevision++; unverifiedSessionRevision = null
        cachedAccount = AccountSnapshot(null, null)
        val revoked = SessionState(restored = true, generation = accountGeneration, clearing = true, endReason = reason)
        publishSession(revoked)
        try {
            try { if (previousOwner != null) withContext(Dispatchers.IO) { displayCache?.clear(previousOwner) } }
            finally { onVaultThread { clear() } }
        } finally {
            publishSession(revoked.copy(clearing = false))
        }
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
    internal data class BillOverview(
        val summary: SharedReadStore.Snapshot<BillSummary>,
        val counts: SharedReadStore.Snapshot<BillCounts>,
        private val checkedAt: Long,
    ) {
        val pending get() = summary.pending || counts.pending
        val summaryFresh get() = summary.fresh(checkedAt)
        val countsFresh get() = counts.fresh(checkedAt)
        val fresh get() = summaryFresh && countsFresh
    }
    private fun overviewStamp(period: BillDateFilter): Long {
        val today = java.time.LocalDate.now()
        if (today.isBefore(period.start) || today.isAfter(period.end)) return 0L
        val versions = dataChanges.state.value.versions
        return maxOf(versions[AccountResource.Data.IncomeBills] ?: 0L, versions[AccountResource.Data.ExpenseBills] ?: 0L)
    }
    internal fun billOverview(username: String, period: BillDateFilter): BillOverview {
        val owner = identity(username)
        val stamp = overviewStamp(period)
        return BillOverview(overviewReads.snapshot(owner, DisplayKeys.summary(period.timeFrom, period.timeTo), stamp),
            overviewReads.snapshot(owner, DisplayKeys.counts(period.timeFrom, period.timeTo), stamp), readNow())
    }
    internal suspend fun readBillSummary(username: String, period: BillDateFilter, force: Boolean = false): BillSummary {
        restoreSession()
        val owner = identity(username) ?: throw AuthenticationRequired()
        val key = DisplayKeys.summary(period.timeFrom, period.timeTo)
        return overviewReads.read(owner, key, overviewStamp(period), force, now = readNow, currentStamp = { overviewStamp(period) }) {
            billSummary(period.timeFrom, period.timeTo, username).also { saveDisplay(username, key, it, DisplayCodecs.Summary) }
        }
    }
    internal suspend fun readBillCounts(username: String, period: BillDateFilter, force: Boolean = false): BillCounts {
        restoreSession()
        val owner = identity(username) ?: throw AuthenticationRequired()
        val key = DisplayKeys.counts(period.timeFrom, period.timeTo)
        return overviewReads.read(owner, key, overviewStamp(period), force, now = readNow, currentStamp = { overviewStamp(period) }) {
            billCounts(period.timeFrom, period.timeTo, username).also { saveDisplay(username, key, it, DisplayCodecs.Counts) }
        }
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
        withSession(retryAuthentication = false, expectedUsername = username) {
            val identity = identity(username)
            try { feeService.setFeeRoom(it, project, room) }
            finally { dataChanges.invalidate(identity, setOf(AccountResource.Data.Rooms)) }
        }
    suspend fun createFee(username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long) =
        withSession(retryAuthentication = false, expectedUsername = username) { feeService.createFee(it, username, config, query, amountCents) }
    suspend fun prepareFeeAccount(order: PendingRecharge, method: FeeMethod) =
        withSession(expectedUsername = order.username) { feeService.prepareFeeAccount(it, order, method) }
    suspend fun payFeeAccount(order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) =
        withSession(retryAuthentication = false, expectedUsername = order.username) { feeService.payFeeAccount(it, order, method, preparation, type, password) }
    suspend fun launchFeeWechat(order: PendingRecharge, method: FeeMethod) =
        withSession(retryAuthentication = false, expectedUsername = order.username) { feeService.launchFeeWechat(it, order, method) }
    suspend fun feeResult(order: PendingRecharge) = withSession(expectedUsername = order.username) { feeService.feeResult(it, order) }
    suspend fun passwordConfiguration(username: String) = withSession(expectedUsername = username) {
        val owner = identity(username)
        val stamp = cardStamp()
        cardService.passwordConfiguration(it).also { configuration -> publishCards(owner, configuration.cards, stamp) }
    }
    suspend fun modifyCardPassword(username: String, card: CampusCard, oldPassword: String, newPassword: String, confirmation: String) =
        withSession(retryAuthentication = false, expectedUsername = username) {
            cardService.modifyCardPassword(it, card, oldPassword, newPassword, confirmation)
            // Clear under the same session lock before a queued refresh can re-login with old credentials.
            clearCredentials(SessionEndReason.PasswordChanged)
        }
    suspend fun cardConfiguration(username: String) = withSession(expectedUsername = username) {
        val owner = identity(username)
        val stamp = cardStamp()
        cardService.cardConfiguration(it).also { configuration -> publishCards(owner, configuration.cards, stamp) }
    }
    suspend fun changeCardState(username: String, card: CampusCard, action: CampusCardAction, password: String) =
        withSession(retryAuthentication = false, expectedUsername = username) {
            val identity = identity(username)
            try {
                cardService.changeCardState(it, card, action, password).also { cards ->
                    dataChanges.invalidate(identity, setOf(AccountResource.Data.Cards, AccountResource.Data.Profile))
                    publishCards(identity, cards, cardStamp())
                }
            } catch (e: CardOperationUnconfirmed) {
                dataChanges.invalidate(identity, setOf(AccountResource.Data.Cards, AccountResource.Data.Profile))
                throw e
            }
        }
    suspend fun recipientCaptcha(username: String) = withSession(expectedUsername = username) { cardService.recipientCaptcha(it) }
    suspend fun rechargeRecipients(username: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String) =
        withSession(retryAuthentication = false, expectedUsername = username) { cardService.rechargeRecipients(it, type, value, captcha, code) }
    suspend fun recipientRechargeConfiguration(username: String, recipient: RechargeRecipient) =
        withSession(expectedUsername = username) { cardService.recipientRechargeConfiguration(it, recipient) }
    override suspend fun logout() = mutex.withLock {
        account()
        clearCredentials(SessionEndReason.Logout)
    }

}
