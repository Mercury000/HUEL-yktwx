package com.mercury.yktwx.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Owns the lifetime of display reads, including local preparation, network attempts and retries.
 * All methods run on the owning ViewModel's dispatcher. Local display restoration has its own
 * lifetime; joining it here never consumes a network attempt or cancels it on a page change.
 *
 * A slot is reserved before launching, while a network lease is acquired only by startNetwork().
 * Request identity is reference identity: a cancelled request cannot finish its replacement.
 * Page code owns results, query freshness and transaction convergence, not retry/waiting jobs.
 */
internal class ReadController(
    private val scope: CoroutineScope,
    private val pages: Set<ReadPage>,
    initialEnvironment: ReadEnvironment,
    private val reevaluate: () -> Unit,
    retryDelaysMillis: List<Long> = listOf(600L),
) {
    private val gate = ReadGate(retryDelaysMillis = retryDelaysMillis)
    private val requests = LinkedHashMap<Any, Request>()
    private val retries = HashMap<Any, Job>()
    private val pendingActions = LinkedHashMap<Any, () -> Unit>()
    private var pendingReevaluation = false
    private var idleCallback: Job? = null
    private var revision = 0L
    private var recoveryRevision = 0L
    var environment: ReadEnvironment = initialEnvironment
        private set

    init { update(initialEnvironment) }

    val canRead get() = gate.canRead
    val isVisible get() = gate.isVisible
    val currentEpoch get() = gate.currentEpoch
    val onlineOrNull get() = environment.online

    /** Updates policy atomically before the ViewModel applies presentation side effects. */
    fun update(next: ReadEnvironment) {
        val previous = environment
        val wasVisible = gate.isVisible
        environment = next
        val visible = next.foreground && next.page in pages
        val epochChanged = gate.update(visible, next.foreground, next.online, next.epoch)
        val resumed = next.foreground && (!previous.foreground || next.resumeEpoch > previous.resumeEpoch)
        val activated = visible && (!wasVisible || next.page != previous.page) || resumed
        if (activated) gate.recoverNetworkFailures()
        if (epochChanged || activated) recoveryRevision++
        if (!canRead) {
            retries.values.forEach { it.cancel() }
            retries.clear()
            pendingReevaluation = false
            pendingActions.clear()
            idleCallback?.cancel(); idleCallback = null
        } else if (epochChanged || activated) {
            retries.values.forEach { it.cancel() }
            retries.clear()
            pendingReevaluation = requests.isNotEmpty()
        }
    }

    fun isReading(query: Any): Boolean = requests[query]?.job?.isActive == true

    /** Admission before page code changes loading state; launch still checks the same policy. */
    fun canStart(query: Any, trigger: ReadTrigger, replace: Boolean = false,
        afterCurrent: () -> Unit = reevaluate): Boolean {
        if (!canRead) return false
        if (replace) cancel(query)
        if (query in requests) {
            if (trigger.automatic) pendingReevaluation = true
            else if (trigger == ReadTrigger.Pull || trigger == ReadTrigger.Action) pendingActions[query] = afterCurrent
            return false
        }
        if (trigger.automatic && query in retries) return false
        return gate.decide(query, trigger) is ReadDecision.Start
    }

    fun reevaluateWhenIdle() { pendingReevaluation = requests.isNotEmpty() && canRead }

    /** Manual triggers merge with active work; a different query can be explicitly cancelled. */
    fun launch(query: Any, trigger: ReadTrigger, retry: () -> Unit,
        block: suspend Request.() -> Unit): Job? {
        if (!canRead) return null
        requests[query]?.let {
            // Re-evaluate after completion rather than replaying a closure for an obsolete query.
            if (trigger.automatic || trigger == ReadTrigger.Pull || trigger == ReadTrigger.Action) pendingReevaluation = true
            return it.job
        }
        if (trigger.automatic && query in retries) return null
        if (gate.decide(query, trigger) !is ReadDecision.Start) return null
        retries.remove(query)?.cancel()
        pendingActions.remove(query)
        val request = Request(query, trigger, revision, retry)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { request.block() }
            finally { finish(request) }
        }
        request.job = job
        requests[query] = request
        job.start()
        return job
    }

    /** Reconnect never bypasses the epoch already used by the current query. */
    fun recover(query: Any, recoveryId: Long, retry: () -> Unit) {
        if (canRead && gate.canRecover(query, recoveryId)) retry()
    }

    fun cancel(query: Any) {
        pendingActions.remove(query)
        retries.remove(query)?.cancel()
        requests.remove(query)?.job?.cancel()
        gate.reset(query)
        if (requests.isEmpty()) {
            pendingReevaluation = false
            if (pendingActions.isEmpty()) { idleCallback?.cancel(); idleCallback = null }
        }
    }

    fun clear() {
        revision++
        pendingReevaluation = false
        pendingActions.clear()
        idleCallback?.cancel(); idleCallback = null
        retries.values.forEach { it.cancel() }
        retries.clear()
        val old = requests.values.toList()
        requests.clear()
        old.forEach { it.job.cancel() }
        gate.clear()
    }

    private fun finish(request: Request) {
        if (requests[request.query] !== request || request.revision != revision) return
        requests.remove(request.query)
        val lease = request.lease
        if (lease != null) gate.finished(request.query, lease, request.failure, cancelled = !request.completed)
        val networkFailed = request.completed && request.failure == ReadFailureKind.Network
        val currentRecovery = request.networkRecoveryRevision == recoveryRevision
        if (networkFailed && currentRecovery && request.trigger.automatic && canRead && gate.canAutoRetry(request.query)) {
            val retryRevision = revision
            retries[request.query] = scope.launch {
                delay(gate.retryDelayMillis(request.query))
                retries.remove(request.query)
                if (revision == retryRevision && canRead && gate.canAutoRetry(request.query)) request.retry()
            }
        }
        if (requests.isEmpty() && pendingActions.isNotEmpty()) {
            pendingReevaluation = false
            val finishedRevision = revision
            whenIdle {
                // Keep actions cancellable until execution, including the yielded completion gap.
                pendingActions.toMap().forEach { (query, action) ->
                    if (revision == finishedRevision && canRead && pendingActions.remove(query) === action) action()
                }
            }
        } else if (requests.isEmpty() && pendingReevaluation) {
            pendingReevaluation = false
            // Keep completed failures visible. Network failures in this epoch follow backoff;
            // A failed request spanning a reconnect/foreground return gets a fresh evaluation.
            if (canRead && (request.failure == null || networkFailed && !currentRecovery)) {
                whenIdle(reevaluate)
            }
        }
    }

    private fun whenIdle(action: () -> Unit) {
        idleCallback?.cancel()
        val finishedRevision = revision
        idleCallback = scope.launch {
            yield() // The completed Job must be inactive before page freshness checks.
            idleCallback = null
            if (revision == finishedRevision && canRead && requests.isEmpty()) action()
        }
    }

    inner class Request internal constructor(
        internal val query: Any,
        internal val trigger: ReadTrigger,
        internal val revision: Long,
        internal val retry: () -> Unit,
    ) {
        internal lateinit var job: Job
        internal var lease: ReadDecision.Start? = null
        internal var networkRecoveryRevision = -1L
        internal var completed = false
        internal var failure: ReadFailureKind? = null
        val epoch get() = lease?.epoch ?: currentEpoch

        /** Call after local preparation, immediately before the first network operation. */
        suspend fun startNetwork(): Boolean {
            currentCoroutineContext().ensureActive()
            if (requests[query] !== this || this.revision != this@ReadController.revision) return false
            check(lease == null) { "A display read can start its network attempt only once" }
            val decision = gate.decide(query, trigger)
            if (decision !is ReadDecision.Start) return false
            lease = decision
            networkRecoveryRevision = recoveryRevision
            gate.started(query, decision)
            return true
        }

        /** Only accepted business results finish a lease; cancellation is handled automatically. */
        fun complete(failure: ReadFailureKind?) {
            this.failure = failure
            completed = true
        }
    }
}
