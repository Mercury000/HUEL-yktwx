package com.mercury.yktwx.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * A read-only convergence round. An unchanged successful response is still unconfirmed;
 * retry exhaustion pauses polling, rather than granting that response normal cache freshness.
 * Values are compared only within the same query key, independently for each endpoint.
 * Observed changes end data refreshes, never establish a transaction's payment status.
 */
internal class TransactionRefresh(
    private val scope: CoroutineScope,
    private val delaysMillis: List<Long> = DefaultDelaysMillis,
    private val needsRefresh: () -> Boolean,
    private val isReading: () -> Boolean,
    private val retry: () -> Unit,
) {
    data class Read(val revision: Long, val sequence: Long)

    private var revision = 0L
    private var sequence = 0L
    private var invalidated = false
    private var transactionDate: LocalDate? = null
    private data class Endpoint(
        var value: Any? = null,
        var baseline: Any? = null,
        var updated: Boolean = false,
        var failed: Boolean = false,
    )
    private val endpoints = LinkedHashMap<String, Endpoint>(16, .75f, true)
    private var retries = 0
    private var visible = false
    private var timer: Job? = null

    init { require(delaysMillis.all { it > 0 }) }

    fun invalidate(date: LocalDate = LocalDate.now()) {
        revision++
        sequence++
        invalidated = true
        transactionDate = date
        endpoints.values.forEach { it.baseline = it.value; it.updated = false }
        retries = 0
        pause()
    }

    fun clear() {
        revision++
        sequence++
        invalidated = false
        transactionDate = null
        endpoints.clear()
        retries = 0
        pause()
    }

    fun affects(period: BillDateFilter): Boolean = transactionDate?.let {
        !it.isBefore(period.start) && !it.isAfter(period.end)
    } == true

    fun pending(key: String): Boolean = invalidated && endpoints[key]?.updated != true

    /** A read failure does not undo observed progress, but still requires recovery. */
    fun needsRead(key: String): Boolean = pending(key) || endpoints[key]?.failed == true

    fun needsTransactionRead(key: String): Boolean = invalidated && needsRead(key)

    /** Unaffected periods can use successful reads even while another period is pending. */
    fun cacheReadAt(key: String, readAt: Long, affected: Boolean = true): Long =
        if (endpoints[key]?.failed == true || affected && pending(key)) 0L else readAt

    fun setVisible(value: Boolean) {
        visible = value
        if (!value) pause()
    }

    fun pause() { timer?.cancel(); timer = null }

    fun started(): Read {
        pause()
        return Read(revision, ++sequence)
    }

    fun accepts(read: Read): Boolean = read.revision == revision && read.sequence == sequence

    private fun endpoint(key: String): Endpoint {
        val state = endpoints.getOrPut(key) { Endpoint() }
        while (endpoints.size > MaxKeys) endpoints.remove(endpoints.keys.first())
        return state
    }

    fun failed(read: Read, key: String) {
        if (accepts(read)) endpoint(key).failed = true
    }

    /** Configuration-only success can clear a failure without claiming that readings changed. */
    fun succeeded(read: Read, key: String) {
        if (accepts(read)) endpoint(key).failed = false
    }

    fun <T : Any> record(read: Read, key: String, result: Result<T>) {
        result.fold({ observe(read, key, it) }, { failed(read, key) })
    }

    fun observe(read: Read, key: String, value: Any) {
        if (!accepts(read)) return
        val state = endpoint(key)
        if (invalidated && !state.updated) {
            val baseline = state.baseline
            if (baseline != null && baseline != value) state.updated = true
            // Without a pre-transaction live value, the first response cannot prove progress.
            else if (baseline == null) state.baseline = value
        }
        state.value = value
        state.failed = false
    }

    fun finished(read: Read) {
        if (!accepts(read) || !visible || !needsRefresh() || retries >= delaysMillis.size) return
        pause()
        val wait = delaysMillis[retries]
        timer = scope.launch {
            delay(wait)
            timer = null
            if (!accepts(read) || !visible || !needsRefresh()) return@launch
            if (isReading()) { finished(read); return@launch }
            retries++
            retry()
        }
    }

    companion object {
        val DefaultDelaysMillis = listOf(2_000L, 5_000L, 10_000L)
        private const val MaxKeys = 32
    }
}
