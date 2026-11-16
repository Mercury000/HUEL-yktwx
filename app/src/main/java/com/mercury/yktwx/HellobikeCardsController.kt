package com.mercury.yktwx

import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class HellobikeCardsState(
    val loading: Boolean = false, val cards: List<HellobikeUserCard> = emptyList(), val cardsError: String? = null,
    val nextPages: Map<Int, Int> = emptyMap(), val audit: HellobikeStudentAudit? = null, val auditError: String? = null,
    val products: List<HellobikeCampusProduct> = emptyList(), val productsTitle: String = "校园骑行卡", val productsError: String? = null,
    val purchase: HellobikeCardPurchase? = null, val paymentVisible: Boolean = false, val busy: Boolean = false,
    val options: List<HellobikePayOption> = emptyList(), val method: String? = null, val paymentError: String? = null,
    val feedback: LifePaymentFeedback? = null, val paymentLaunch: LifePaymentLaunch? = null, val paymentInFlight: Boolean = false,
    val ordinaryExpanded: Boolean = false, val ordinaryLoading: Boolean = false, val ordinaryLoaded: Boolean = false,
    val ordinaryProducts: List<HellobikeCampusProduct> = emptyList(), val ordinaryError: String? = null,
    val ordinaryLocationError: String? = null, val ordinaryLocationPermissionRequired: Boolean = false,
    val ordinaryLocationLoading: Boolean = false,
    val cardsLoading: Boolean = false, val auditLoading: Boolean = false, val productsLoading: Boolean = false,
)

/** Account detail operations share the ViewModel's account and encrypted store, never a ride session. */
internal class HellobikeCardsController(
    private val scope: CoroutineScope, private val api: HellobikeService, private val store: HellobikeStore,
    private val io: CoroutineDispatcher, private val account: () -> HellobikeAccount?,
    private val region: () -> HellobikeRegion?, private val location: HellobikeLocationService,
    private val canSubmit: () -> Boolean, private val expired: suspend (Exception, HellobikeAccount) -> Unit,
    private val now: () -> Long,
    private val requestLocationPermission: () -> Unit = {},
) {
    private val mutable = MutableStateFlow(HellobikeCardsState())
    val state = mutable.asStateFlow()
    private var foreground = false
    private var visible = false
    private var generation = 0L
    private var readTask: Job? = null
    private var ordinaryTask: Job? = null
    private var ordinaryGeneration = 0L
    private var resolvingLocation = false
    private var operation: Job? = null
    private var position: HellobikePosition? = null
    private var selectedRegion: HellobikeRegion? = null
    private var launchOwner: HellobikeAccount? = null
    private var launchId: Long? = null
    private var claimed = false
    private var counter = 0L
    val occupied get() = state.value.busy || state.value.paymentInFlight
    val canRequestOrdinaryLocation get() = foreground && visible && account() != null && state.value.ordinaryExpanded

    suspend fun stopAndJoin() {
        generation++; ordinaryGeneration++; resolvingLocation = false
        val tasks = listOfNotNull(readTask, ordinaryTask, operation)
        tasks.forEach { it.cancel() }; tasks.joinAll()
        readTask = null; ordinaryTask = null; operation = null
    }
    fun bind(purchase: HellobikeCardPurchase?) {
        generation++; ordinaryGeneration++; resolvingLocation = false
        readTask?.cancel(); ordinaryTask?.cancel(); operation?.cancel()
        position = null; selectedRegion = null; launchOwner = null; launchId = null; claimed = false
        // Restored IDs are only a query handle. Historical local result flags never
        // become a fresh cloud payment or cancellation status.
        mutable.value = HellobikeCardsState(purchase = purchase?.copy(paid = false, paymentCancelled = false))
        if (foreground && visible && account() != null) refresh()
    }
    fun environment(active: Boolean) {
        if (foreground == active) return
        foreground = active
        if (active && visible) { refresh(); if (!claimed && state.value.purchase?.attempt != null && !state.value.purchase!!.paid) checkPayment() }
        else if (!active) stopVisibleWork()
    }
    fun pageVisible(active: Boolean) {
        if (visible == active) return
        visible = active
        if (active && foreground) { refresh(); if (state.value.purchase?.attempt != null && !claimed && !state.value.purchase!!.paid) checkPayment() }
        else if (!active) { stopVisibleWork(); dismissPayment() }
    }
    private fun stopVisibleWork() {
        generation++; ordinaryGeneration++; resolvingLocation = false
        readTask?.cancel(); ordinaryTask?.cancel(); operation?.cancel()
        if (!claimed) { launchOwner = null; launchId = null }
        mutable.update { it.copy(loading = false, ordinaryLoading = false, busy = false, paymentLaunch = null,
            ordinaryLocationLoading = false,
            cardsLoading = false, auditLoading = false, productsLoading = false,
            paymentInFlight = claimed, feedback = it.feedback?.copy(loading = false)) }
    }
    private suspend fun ensure(owner: HellobikeAccount, request: Long) {
        currentCoroutineContext().ensureActive()
        if (account() != owner || request != generation) throw CancellationException("Account detail changed")
    }
    private suspend fun readFailure(e: Exception, owner: HellobikeAccount): String {
        expired(e, owner)
        return e.message?.takeIf(String::isNotBlank) ?: "连接失败，请检查网络后重试"
    }
    fun refresh() {
        val owner = account() ?: return
        if (!foreground || !visible || state.value.loading || state.value.busy) return
        readTask?.cancel()
        ordinaryGeneration++; ordinaryTask?.cancel(); ordinaryTask = null
        resolvingLocation = true
        val request = ++generation
        mutable.update { it.copy(loading = true, cardsLoading = true, auditLoading = true, productsLoading = true,
            ordinaryLocationLoading = true,
            cardsError = null, auditError = null, productsError = null, ordinaryLoaded = false,
            ordinaryLoading = it.ordinaryExpanded, ordinaryError = null) }
        readTask = scope.launch {
            try {
                var locationError: String? = null
                var permissionRequired = false
                val point = try { location.current() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    permissionRequired = e is HellobikeLocationPermissionRequired
                    locationError = if (permissionRequired) "定位未授权，骑行卡可能不全" else "未获取到位置，骑行卡可能不全"
                    null
                }
                ensure(owner, request)
                var area = region()
                if (point != null) {
                    try { val resolved = api.region(point); ensure(owner, request); area = resolved }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { locationError = "暂无法确认地区，骑行卡可能不全" }
                }
                ensure(owner, request); position = point; selectedRegion = area
                resolvingLocation = false
                mutable.update { it.copy(ordinaryLoading = false, ordinaryLocationLoading = false,
                    ordinaryLocationError = locationError, ordinaryLocationPermissionRequired = permissionRequired) }
                if (state.value.ordinaryExpanded) expandOrdinary(true)
                try {
                    val audit = api.studentAudit(owner); ensure(owner, request)
                    mutable.update { it.copy(audit = audit, auditLoading = false, productsLoading = audit.studentApproved,
                        products = if (audit.studentApproved) it.products else emptyList()) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { val message = readFailure(e, owner); ensure(owner, request); mutable.update { it.copy(audit = null, auditError = message, products = emptyList(), auditLoading = false, productsLoading = false) } }
                val cards = mutableListOf<HellobikeUserCard>()
                val next = mutableMapOf<Int, Int>()
                val errors = mutableListOf<String>()
                for (type in 1..2) {
                    try {
                        val page = api.userCards(owner, selectedRegion, position, type, 0); ensure(owner, request)
                        cards += page.cards
                        if (page.hasNext) next[type] = 1
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { errors += readFailure(e, owner); ensure(owner, request) }
                }
                mutable.update { it.copy(cards = cards.distinctBy { c -> c.businessType to c.guid }, nextPages = next, cardsLoading = false,
                    cardsError = errors.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")) }
                if (state.value.audit?.studentApproved == true) {
                    try {
                        val products = api.campusProducts(owner, selectedRegion, position); ensure(owner, request)
                        mutable.update { it.copy(products = products.products, productsTitle = products.title, productsLoading = false) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { val message = readFailure(e, owner); ensure(owner, request); mutable.update { it.copy(products = emptyList(), productsError = message, productsLoading = false) } }
                }
            } finally {
                if (request == generation) {
                    if (resolvingLocation) {
                        resolvingLocation = false
                        mutable.update { it.copy(ordinaryLoading = false, ordinaryLocationLoading = false) }
                    }
                    mutable.update { it.copy(loading = false, cardsLoading = false, auditLoading = false, productsLoading = false) }
                }
            }
        }
    }
    fun loadMore() {
        val owner = account() ?: return
        if (!foreground || !visible || state.value.loading || state.value.busy || state.value.nextPages.isEmpty()) return
        val request = generation
        val pages = state.value.nextPages
        mutable.update { it.copy(loading = true, cardsError = null) }
        readTask = scope.launch {
            try {
                for ((type, index) in pages) {
                    val page = api.userCards(owner, selectedRegion, position, type, index); ensure(owner, request)
                    mutable.update { it.copy(cards = (it.cards + page.cards).distinctBy { c -> c.businessType to c.guid },
                        nextPages = if (page.hasNext) it.nextPages + (type to index + 1) else it.nextPages - type) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { val message = readFailure(e, owner); ensure(owner, request); mutable.update { it.copy(cardsError = message) } }
            finally { if (request == generation) mutable.update { it.copy(loading = false) } }
        }
    }
    fun expandOrdinary(expanded: Boolean = !state.value.ordinaryExpanded) {
        mutable.update { it.copy(ordinaryExpanded = expanded) }
        if (expanded && resolvingLocation) {
            mutable.update { it.copy(ordinaryLoading = true) }
            return
        }
        if (!expanded || state.value.ordinaryLoaded || state.value.ordinaryLoading || !foreground || !visible) return
        val owner = account() ?: return
        val request = generation
        val ordinaryRequest = ++ordinaryGeneration
        val area = selectedRegion ?: region()
        val point = position
        mutable.update { it.copy(ordinaryLoading = true, ordinaryError = null) }
        ordinaryTask = scope.launch {
            try {
                val products = api.ordinaryProducts(owner, area, point)
                ensureOrdinary(owner, request, ordinaryRequest)
                mutable.update { it.copy(ordinaryProducts = products, ordinaryLoaded = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureOrdinary(owner, request, ordinaryRequest)
                val message = readFailure(e, owner); ensureOrdinary(owner, request, ordinaryRequest)
                mutable.update { it.copy(ordinaryError = message) }
            }
            finally { if (request == generation && ordinaryRequest == ordinaryGeneration) mutable.update { it.copy(ordinaryLoading = false) } }
        }
    }
    private suspend fun ensureOrdinary(owner: HellobikeAccount, request: Long, ordinaryRequest: Long) {
        ensure(owner, request)
        if (ordinaryRequest != ordinaryGeneration) throw CancellationException("Card location changed")
    }
    fun requestOrdinaryLocation() {
        if (!canRequestOrdinaryLocation || occupied || resolvingLocation) return
        if (state.value.ordinaryLocationPermissionRequired) requestLocationPermission() else refresh()
    }
    private suspend fun save(owner: HellobikeAccount, purchase: HellobikeCardPurchase?, request: Long) {
        ensure(owner, request)
        withContext(NonCancellable) {
            withContext(io) { store.saveCardPurchase(owner.userGuid, purchase?.copy(paid = false, paymentCancelled = false)) }
            if (account() == owner) mutable.update { it.copy(purchase = purchase) }
        }
        ensure(owner, request)
    }
    private fun runOperation(block: suspend (HellobikeAccount, Long) -> Unit) {
        val owner = account() ?: return
        if (!foreground || !visible || occupied || !canSubmit()) return
        val request = generation
        mutable.update { it.copy(busy = true, paymentError = null) }
        operation = scope.launch {
            try { block(owner, request) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = readFailure(e, owner); ensure(owner, request)
                mutable.update { it.copy(paymentError = message, feedback = if (it.purchase?.attempt != null)
                    LifePaymentFeedback("campus-card", RechargePhase.Checking, detail = message) else null) }
            } finally { if (request == generation) mutable.update { it.copy(busy = false, feedback = it.feedback?.copy(loading = false)) } }
        }
    }
    fun choose(productKey: String) {
        val product = (state.value.products + state.value.ordinaryProducts).firstOrNull { it.key == productKey } ?: return
        if (!product.campus && resolvingLocation) return
        if (product.campus && state.value.audit?.studentApproved != true || !product.purchasable) return
        if (state.value.purchase?.let { !it.paid && it.attempt != null } == true) {
            showPurchase(); return
        }
        readTask?.cancel(); mutable.update { it.copy(loading = false, cardsLoading = false, auditLoading = false, productsLoading = false) }
        runOperation { owner, request ->
            mutable.update { it.copy(paymentVisible = true, options = emptyList(), feedback = null, method = null) }
            val purchase = try { api.preOrderCard(owner, product, selectedRegion, position) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if ((e as? HellobikeException)?.rejected == true) throw e
                throw HellobikeException((e.message ?: "预下单结果暂未确认") + "\n下单结果未返回，请先核对原订单。", loginExpired = (e as? HellobikeException)?.loginExpired == true)
            }
            save(owner, purchase, request)
            val options = api.cardPayChannels(owner, purchase); ensure(owner, request)
            mutable.update { it.copy(options = options, method = initialMethod(options)) }
        }
    }
    fun showPurchase() {
        val purchase = state.value.purchase ?: return
        mutable.update { it.copy(paymentVisible = true) }
        if (purchase.paid) mutable.update { it.copy(feedback = LifePaymentFeedback("campus-card", RechargePhase.Paid)) }
        else if (purchase.attempt != null) checkPayment()
        else runOperation { owner, request ->
            val options = api.cardPayChannels(owner, purchase); ensure(owner, request)
            mutable.update { it.copy(options = options, feedback = null, method = initialMethod(options)) }
        }
    }
    fun dismissPayment() { mutable.update { it.copy(paymentVisible = false) } }
    /** Cancelling a cashier is not a remote order cancellation. An uncertain dispatch stays queryable. */
    fun cancelPayment() {
        if (occupied) return
        val purchase = state.value.purchase
        if (purchase?.attempt != null && !purchase.paid &&
            (!purchase.paymentCancelled || state.value.feedback?.phase != RechargePhase.Failed)) { dismissPayment(); return }
        runOperation { owner, request ->
            save(owner, null, request)
            mutable.update { it.copy(paymentVisible = false, options = emptyList(), method = null, feedback = null, paymentError = null) }
        }
    }
    fun continueCancelledPayment() {
        val purchase = state.value.purchase?.takeIf { it.paymentCancelled && !it.paid } ?: return
        runOperation { owner, request ->
            query(owner, request)
            if (state.value.purchase?.paid == true) return@runOperation
            // The SDK explicitly cancelled this invocation. Retry the same server order;
            // never create a replacement order for an unconfirmed payment.
            val resumed = purchase.copy(attempt = null, paymentCancelled = false)
            val options = api.cardPayChannels(owner, resumed); ensure(owner, request)
            save(owner, resumed, request)
            mutable.update { it.copy(options = options, feedback = null, method = initialMethod(options)) }
        }
    }
    private fun initialMethod(options: List<HellobikePayOption>): String? =
        (options.firstOrNull { it.channel.available == true && it.defaultSelected }
            ?: options.firstOrNull { it.channel.available == true })?.selectionId
    fun selectMethod(method: String) {
        if (!occupied && state.value.options.any { it.selectionId == method })
            mutable.update { it.copy(method = method) }
    }
    fun pay() {
        val purchase = state.value.purchase ?: return
        val option = state.value.options.firstOrNull { it.selectionId == state.value.method && it.channel.available == true } ?: return
        if (purchase.attempt != null || purchase.paid || !state.value.paymentVisible) return
        runOperation { owner, request ->
            val orders = purchase.orders
            val attempt = HellobikePaymentAttempt(orders, option.channel.method, now(), option.extension, displayAmount = option.amount)
            save(owner, purchase.copy(attempt = attempt), request)
            mutable.update { it.copy(feedback = LifePaymentFeedback("campus-card", RechargePhase.Creating, loading = true)) }
            val component = api.payCard(owner, purchase, orders, option); ensure(owner, request)
            save(owner, purchase.copy(attempt = attempt.copy(orders = component.orders, channel = component.channel, extension = component.extension)), request)
            if (component.channel == "003") query(owner, request, polling = true)
            else {
                val method = component.channel.hellobikeBridgeMethod() ?: throw HellobikeException("返回的支付方式需要在哈啰处理，请查询原订单")
                val payload = component.payload?.takeIf(String::isNotBlank) ?: throw HellobikeException("支付凭据未返回，请查询原订单")
                val id = ++counter
                launchOwner = owner; launchId = id; claimed = false
                mutable.update { it.copy(paymentInFlight = true, paymentLaunch = LifePaymentLaunch(id, orders.first().guid, payload, method),
                    feedback = LifePaymentFeedback("campus-card", RechargePhase.AwaitingPayment)) }
            }
        }
    }
    fun claimPayment(id: Long): Boolean {
        if (!foreground || account() != launchOwner || launchId != id || claimed || state.value.paymentLaunch?.id != id) return false
        claimed = true; mutable.update { it.copy(paymentLaunch = null) }; return true
    }
    fun paymentReturned(id: Long, status: String?) {
        if (id != launchId || account() != launchOwner) return
        launchId = null; launchOwner = null; claimed = false
        mutable.update { it.copy(busy = true, paymentInFlight = false, paymentLaunch = null, feedback = LifePaymentFeedback("campus-card", RechargePhase.Checking,
            detail = if (status == "launch_failed") "未能打开支付应用，请查询本笔结果" else "正在核对本笔付款结果")) }
        val cancelled = status == "6001" || status.equals("cancel", true)
        val owner = account() ?: return
        val request = generation
        operation = scope.launch {
            try {
                if (cancelled) state.value.purchase?.let { save(owner, it.copy(paymentCancelled = true), request) }
                if (foreground && visible) query(owner, request, polling = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = readFailure(e, owner); ensure(owner, request)
                mutable.update { it.copy(paymentError = message, feedback = LifePaymentFeedback("campus-card", RechargePhase.Checking, detail = message)) }
            } finally { if (request == generation) mutable.update { it.copy(busy = false, feedback = it.feedback?.copy(loading = false)) } }
        }
    }
    fun checkPayment() {
        if (state.value.purchase?.attempt == null || state.value.purchase?.paid == true) return
        runOperation { owner, request -> query(owner, request) }
    }
    private suspend fun query(owner: HellobikeAccount, request: Long, polling: Boolean = false) {
        val purchase = state.value.purchase ?: return
        val attempt = purchase.attempt ?: return
        mutable.update { it.copy(feedback = LifePaymentFeedback("campus-card", RechargePhase.Checking, loading = true)) }
        // BasePayModule.w uses the designated order, or componentData.orders[0].
        // It does not add an all-orders-success requirement to this cashier result.
        val order = attempt.orders.firstOrNull() ?: return
        var paid = false
        for (index in 0 until if (polling) 4 else 1) {
            if (index > 0) delay(3000)
            paid = try { api.cardPaymentPaid(owner, order, attempt.extension) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!polling || (e as? HellobikeException)?.loginExpired == true || index == 3) throw e
                false
            }
            ensure(owner, request)
            if (paid) break
        }
        if (paid) {
            save(owner, purchase.copy(paid = true, paymentCancelled = false), request)
            mutable.update { it.copy(feedback = LifePaymentFeedback("campus-card", RechargePhase.Paid, detail = "付款已确认，正在刷新我的卡")) }
            val cards = mutableListOf<HellobikeUserCard>(); val next = mutableMapOf<Int, Int>()
            try {
                for (type in 1..2) {
                    val page = api.userCards(owner, selectedRegion ?: region(), position, type, 0); ensure(owner, request)
                    cards += page.cards; if (page.hasNext) next[type] = 1
                }
                mutable.update { it.copy(cards = cards.distinctBy { c -> c.businessType to c.guid }, nextPages = next, cardsError = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { val message = readFailure(e, owner); ensure(owner, request); mutable.update { it.copy(cardsError = message) } }
        } else mutable.update { it.copy(feedback = LifePaymentFeedback("campus-card",
            if (purchase.paymentCancelled) RechargePhase.Failed else RechargePhase.Checking,
            detail = if (purchase.paymentCancelled) "支付应用已取消本次付款，原订单尚未确认支付成功" else "原订单尚未确认支付成功，请稍后查询")) }
    }
}
