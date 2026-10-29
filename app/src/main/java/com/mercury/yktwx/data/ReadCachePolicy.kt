package com.mercury.yktwx.data

import android.os.SystemClock

/** Successful reads share a short reuse window; disk snapshots still require a live read. */
internal object ReadCachePolicy {
    const val WindowMillis = 60_000L
    fun now(): Long = SystemClock.elapsedRealtime()
    fun isFresh(readAt: Long, now: Long = now()): Boolean =
        readAt > 0 && now - readAt in 0 until WindowMillis
}

/** Bounded memory snapshots retain stale content for display without extending its freshness. */
internal class RecentReadCache<K, V>(private val limit: Int = 8) {
    data class Snapshot<V>(val value: V, val readAt: Long)
    private val entries = LinkedHashMap<K, Snapshot<V>>(limit, .75f, true)
    operator fun get(key: K): Snapshot<V>? = entries[key]
    fun put(key: K, value: V, readAt: Long) {
        entries[key] = Snapshot(value, readAt)
        while (entries.size > limit) entries.remove(entries.keys.first())
    }
    fun invalidate() { entries.replaceAll { _, snapshot -> snapshot.copy(readAt = 0) } }
    fun invalidate(key: K) { entries[key]?.let { entries[key] = it.copy(readAt = 0) } }
    fun remove(key: K) { entries.remove(key) }
    fun clear() { entries.clear() }
}
