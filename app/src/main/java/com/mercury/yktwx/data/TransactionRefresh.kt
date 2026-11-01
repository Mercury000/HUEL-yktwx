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
    private val values = LinkedHashMap<String, Any>(16, .75f, true)
    private val baselines = mutableMapOf<String, Any>()
    private val updated = mutableSetOf<String>()
    private var retries = 0
    private var visible = false
    private var timer: Job? = null

    init { require(delaysMillis.all { it > 0 }) }

    fun invalidate(date: LocalDate = LocalDate.now()) {
        revision++
        sequence++
        invalidated = true
        transactionDate = date
        baselines.clear()
        baselines.putAll(values)
        updated.clear()
        retries = 0
        pause()
    }

    fun clear() {
        revision++
        sequence++
        invalidated = false
        transactionDate = null
        values.clear(); baselines.clear(); updated.clear()
        retries = 0
        pause()
    }

    fun affects(period: BillDateFilter): Boolean = transactionDate?.let {
        !it.isBefore(period.start) && !it.isAfter(period.end)
    } == true

    fun pending(key: String): Boolean = invalidated && key !in updated

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

    fun observe(read: Read, key: String, value: Any) {
        if (!accepts(read)) return
        if (pending(key)) {
            val baseline = baselines[key]
            if (baseline != null && baseline != value) updated += key
            // Without a pre-transaction live value, the first response cannot prove progress.
            else if (baseline == null) baselines[key] = value
        }
        values[key] = value
        while (values.size > MaxKeys) {
            val oldest = values.keys.first()
            values.remove(oldest); baselines.remove(oldest); updated.remove(oldest)
        }
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
