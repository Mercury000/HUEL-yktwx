package com.mercury.yktwx.data

/** Pages that own network reads. Shared by the shell and every ViewModel. */
enum class ReadPage { Payment, Hall, Bill, My, Profile, Account }

/** Account identity and query parameters, without relying on concatenated string formats. */
internal data class ReadQuery(val owner: String, val resource: Resource, val parameters: List<Any?> = emptyList()) {
    enum class Resource { Payment, Account, Motion, Hall, Profile, BillList, BillStats, BillPage }
    override fun toString() = "ReadQuery($resource)"
}

/**
 * One immutable snapshot of the shell's read context. The shell publishes it; ViewModels never
 * probe connectivity themselves. [page] is the page that is currently allowed to read, or null
 * while an overlay, dialog or background lifecycle blocks reads.
 */
data class ReadEnvironment(
    val page: ReadPage?,
    val foreground: Boolean,
    val online: Boolean?,
    val epoch: Long,
    val paymentVisible: Boolean = false,
    val paymentCovered: Boolean = false,
    val accountVisible: Boolean = false,
    /** Increments on every foreground resume so a coalesced stop/start still re-evaluates reads. */
    val resumeEpoch: Long = 0,
) {
    companion object {
        /** Production reads wait for the shell to publish its first environment. */
        val Inactive = ReadEnvironment(null, false, null, 0)
        internal fun standalone(page: ReadPage) = ReadEnvironment(page, true, null, 0,
            paymentVisible = page == ReadPage.Payment, accountVisible = page == ReadPage.Account)
    }
}

enum class ReadTrigger(val automatic: Boolean) {
    Initial(true),
    Visible(true),
    Reconnect(true),
    Retry(true),
    Pull(false),
    Action(false),
    Transaction(false),
    Motion(false),
}

internal sealed interface ReadDecision {
    class Start(
        val epoch: Long,
        val attempt: Int,
        val trigger: ReadTrigger,
    ) : ReadDecision

    data object InFlight : ReadDecision

    data class Reject(val reason: Reason) : ReadDecision

    enum class Reason { Offline, Hidden, Backoff }
}

/**
 * Per-ViewModel read policy: visibility, online state, single-flight and a bounded automatic
 * retry budget. It deliberately does not know how a page loads data; callers own their jobs and
 * call [started]/[finished] around the actual network request.
 *
 * Consecutive failed automatic reads get at most [maxAutoAttempts] attempts per recovery cycle.
 * Success clears that budget so normal periodic reads can continue. A manual read resets it;
 * the controller also opens a new cycle on an actual page/foreground activation. A reconnect
 * never starts a second read while one is in flight; if that
 * request fails, the caller can spend the remaining automatic attempt. This guarantees that
 * every offline -> online transition eventually tries again, without an unbounded request loop.
 */
internal class ReadGate(
    private val maxAutoAttempts: Int = 2,
    private val retryDelaysMillis: List<Long> = listOf(600L),
) {
    private var visible = true
    private var foreground = true
    private var online: Boolean? = null
    private var epoch = 0L
    private val attempts = HashMap<Any, Int>()
    private val startedEpoch = HashMap<Any, Long>()
    private val failures = HashMap<Any, ReadFailureKind>()
    private val inFlight = HashMap<Any, ReadDecision.Start>()

    val isVisible get() = visible && foreground
    val isOnline get() = online != false
    val currentEpoch get() = epoch
    val canRead get() = isVisible && isOnline
    val onlineOrNull get() = online

    /**
     * Publishes a new environment. Returns true when the connectivity epoch changed and resets
     * the automatic failure budget. The monitor is the sole producer of epochs.
     */
    fun update(visible: Boolean, foreground: Boolean, online: Boolean?, epoch: Long): Boolean {
        val nextEpoch = maxOf(epoch, this.epoch)
        val changed = nextEpoch != this.epoch
        this.visible = visible
        this.foreground = foreground
        this.online = online
        if (changed) {
            this.epoch = nextEpoch
            attempts.clear()
        }
        return changed
    }

    fun decide(query: Any, trigger: ReadTrigger): ReadDecision {
        if (!visible || !foreground) return ReadDecision.Reject(ReadDecision.Reason.Hidden)
        if (online == false) return ReadDecision.Reject(ReadDecision.Reason.Offline)
        if (query in inFlight) return ReadDecision.InFlight
        if (trigger == ReadTrigger.Reconnect && startedEpoch[query] == epoch) {
            return ReadDecision.Reject(ReadDecision.Reason.Backoff)
        }
        val used = attempts[query] ?: 0
        if (trigger.automatic && used >= maxAutoAttempts) {
            return ReadDecision.Reject(ReadDecision.Reason.Backoff)
        }
        return ReadDecision.Start(epoch, used + 1, trigger)
    }

    fun started(query: Any, decision: ReadDecision.Start) {
        // The decision may have been taken before local preparation. The request itself starts
        // now, so it belongs to the current epoch and consumes this epoch's budget.
        val attempt = if (decision.epoch == epoch) decision.attempt else 1
        if (decision.trigger == ReadTrigger.Pull || decision.trigger == ReadTrigger.Action ||
            decision.trigger == ReadTrigger.Transaction || decision.trigger == ReadTrigger.Motion
        ) {
            // A manual request is a fresh budget, not a continuation of a failed automatic read.
            attempts[query] = 1
        } else {
            attempts[query] = maxOf(attempts[query] ?: 0, attempt)
        }
        startedEpoch[query] = epoch
        inFlight[query] = decision
    }

    fun finished(query: Any, decision: ReadDecision.Start, failure: ReadFailureKind?, cancelled: Boolean = false) {
        if (inFlight[query] != decision) return
        inFlight.remove(query)
        if (cancelled) return
        if (failure == null) {
            failures.remove(query)
            attempts[query] = 0
        } else {
            failures[query] = failure
        }
    }

    /** A reconnect may retry only a network failure that has not already started in this epoch. */
    fun canRecover(query: Any, recoveryId: Long): Boolean =
        recoveryId == epoch && failures[query] == ReadFailureKind.Network &&
            startedEpoch[query] != epoch && query !in inFlight

    fun canAutoRetry(query: Any): Boolean =
        failures[query] == ReadFailureKind.Network && (attempts[query] ?: 0) < maxAutoAttempts &&
            query !in inFlight

    /** True while the actual network request for [query] is running, excluding local preparation. */
    fun isReading(query: Any): Boolean = query in inFlight

    fun retryDelayMillis(query: Any): Long {
        val used = (attempts[query] ?: 1).coerceAtLeast(1)
        return retryDelaysMillis.getOrElse(used - 1) { retryDelaysMillis.lastOrNull() ?: 1_000L }
    }

    fun failure(query: Any): ReadFailureKind? = failures[query]

    /** A real page/foreground activation may recover transport failures without a reconnect. */
    fun recoverNetworkFailures() {
        attempts.keys.removeAll { failures[it] == ReadFailureKind.Network || it in inFlight }
    }

    fun reset(query: Any) {
        attempts.remove(query)
        startedEpoch.remove(query)
        failures.remove(query)
        inFlight.remove(query)
    }

    /** Keeps the current environment but forgets every query, e.g. after an account change. */
    fun clear() {
        attempts.clear()
        startedEpoch.clear()
        failures.clear()
        inFlight.clear()
    }
}

/** Combines region failures so the ViewModel can schedule at most one page-level retry. */
internal fun readFailureKind(vararg kinds: ReadFailureKind?): ReadFailureKind? = when {
    kinds.any { it == ReadFailureKind.Authentication } -> ReadFailureKind.Authentication
    kinds.any { it == ReadFailureKind.Network } -> ReadFailureKind.Network
    kinds.any { it == ReadFailureKind.Other } -> ReadFailureKind.Other
    else -> null
}
