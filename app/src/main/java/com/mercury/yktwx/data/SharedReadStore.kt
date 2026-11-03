package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Account-scoped successful values and single-flight reads; a page never owns another page's job. */
internal class SharedReadStore(scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    data class Snapshot<T>(val value: T?, val readAt: Long, val pending: Boolean, val error: Throwable?) {
        fun fresh(now: Long = ReadCachePolicy.now()) = !pending && error == null && ReadCachePolicy.isFresh(readAt, now)
    }
    private class Flight(val job: Deferred<Any>, val valueRevision: Long, var readers: Int = 0)
    private class Entry(var stamp: Long) {
        var value: Any? = null
        var valueRevision = 0L
        var baseline: Any? = null
        var readAt = 0L
        var pending = stamp != 0L
        var error: Throwable? = null
        var flight: Flight? = null
    }
    private var identity: AccountIdentity? = null
    private val entries = LinkedHashMap<String, Entry>(16, .75f, true)
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    @Synchronized fun reset(owner: AccountIdentity?) {
        if (owner == identity) return
        entries.values.forEach { it.flight?.job?.cancel() }
        entries.clear()
        identity = owner
        mutableRevision.value++
    }

    private fun entry(key: String, stamp: Long): Entry = entries.getOrPut(key) { Entry(stamp) }.also {
        if (it.stamp != stamp) {
            it.flight?.job?.cancel()
            it.flight = null
            it.stamp = stamp
            it.baseline = it.value
            it.pending = stamp != 0L
            it.readAt = 0L
        }
        // Never evict a request still observed by a page.
        if (entries.size > 64) entries.entries.firstOrNull { item -> item.key != key && item.value.flight == null }
            ?.let { item -> entries.remove(item.key) }
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized fun <T> snapshot(owner: AccountIdentity?, key: String, stamp: Long): Snapshot<T> {
        if (owner == null || owner != identity) return Snapshot(null, 0, false, null)
        val entry = entry(key, stamp)
        return Snapshot(entry.value as T?, entry.readAt, entry.pending || entry.error != null && entry.stamp != 0L, entry.error)
    }

    /** A server-confirmed result from another endpoint supersedes an older concurrent read. */
    @Synchronized fun <T : Any> put(owner: AccountIdentity?, key: String, stamp: Long, value: T, readAt: Long) {
        if (owner == null || owner != identity) return
        val entry = entry(key, stamp)
        entry.value = value
        entry.valueRevision++
        entry.readAt = readAt
        entry.pending = false
        entry.error = null
        mutableRevision.value++
    }

    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> read(owner: AccountIdentity, key: String, stamp: Long, force: Boolean,
        now: () -> Long = ReadCachePolicy::now, currentStamp: () -> Long = { stamp }, waitForChange: Boolean = true, load: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val flight: Flight
        synchronized(this) {
            if (identity != owner) throw CancellationException("Account changed")
            val entry = entry(key, stamp)
            if (!force && !entry.pending && entry.error == null && ReadCachePolicy.isFresh(entry.readAt, now()))
                entry.value?.let { return it as T }
            flight = entry.flight ?: Flight(scope.async(start = CoroutineStart.LAZY) { load() }, entry.valueRevision).also { entry.flight = it }
            flight.readers++
        }
        try {
            val value = flight.job.await() as T
            if (currentStamp() != stamp) throw CancellationException("Resource invalidated")
            synchronized(this) {
                val entry = entries[key]
                if (identity == owner && entry?.flight === flight && entry.stamp == stamp) {
                    if (entry.valueRevision != flight.valueRevision && entry.value != null) return entry.value as T
                    if (!waitForChange) entry.pending = false
                    if (entry.pending) {
                        if (entry.baseline != null && entry.baseline != value) entry.pending = false
                        else if (entry.baseline == null) entry.baseline = value
                    }
                    entry.value = value
                    entry.error = null
                    entry.readAt = if (entry.pending) 0L else now()
                    mutableRevision.value++
                }
            }
            return value
        } catch (e: Exception) {
            if (e !is CancellationException) synchronized(this) {
                entries[key]?.takeIf { identity == owner && it.flight === flight && it.stamp == stamp }?.let {
                    it.error = e; it.readAt = 0L; mutableRevision.value++
                }
            }
            throw e
        } finally {
            synchronized(this) {
                flight.readers--
                if (flight.readers == 0) {
                    if (entries[key]?.flight === flight) entries[key]?.flight = null
                    if (!flight.job.isCompleted) flight.job.cancel()
                }
            }
        }
    }
}
