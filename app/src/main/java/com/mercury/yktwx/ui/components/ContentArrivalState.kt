package com.mercury.yktwx.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** One-shot entrances share event timing and consumed identities across page recreation. */
internal class ContentArrivalState private constructor(
    private var event: Long,
    private var startedAt: Long,
    private val consumed: MutableSet<String>,
) {
    constructor() : this(-1, 0, mutableSetOf())

    fun beginEvent(event: Long) {
        if (this.event == event) return
        this.event = event
        startedAt = SystemClock.elapsedRealtime()
        consumed.clear()
    }

    /** Claim when the child is composed, including children deferred by size layouts. */
    fun claim(id: String): Boolean = consumed.add(id)

    fun claim(event: Long, id: String, windowMillis: Long = 450L): Boolean =
        this.event == event && SystemClock.elapsedRealtime() - startedAt in 0L until windowMillis && claim(id)

    fun retain(ids: Collection<String>) { consumed.retainAll(ids.toSet()) }
    fun forget(id: String) { consumed.remove(id) }

    companion object {
        val Saver = listSaver<ContentArrivalState, Any>(
            save = { listOf(it.event, it.startedAt) + it.consumed.toList() },
            restore = { saved ->
                ContentArrivalState(saved[0] as Long, saved[1] as Long,
                    saved.drop(2).mapTo(mutableSetOf()) { it as String })
            },
        )
    }
}

@Composable
internal fun rememberContentArrivalState(event: Long? = null): ContentArrivalState =
    rememberSaveable(saver = ContentArrivalState.Saver) { ContentArrivalState() }
        .also { if (event != null) it.beginEvent(event) }
