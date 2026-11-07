package com.mercury.yktwx.ui.components

/** Hand camera ownership over only after Camera2 delivers onClosed, including cancelled opens. */
internal object DeviceCameraOpenQueue {
    private val waiting = ArrayDeque<Lease>()

    class Lease internal constructor(internal val start: (Lease) -> Unit) {
        fun release() {
            val next = synchronized(waiting) {
                if (waiting.firstOrNull() !== this) return
                waiting.removeFirst()
                waiting.firstOrNull()
            }
            next?.start?.invoke(next)
        }
    }

    fun acquire(start: (Lease) -> Unit) {
        val lease = Lease(start)
        val ready = synchronized(waiting) {
            waiting.addLast(lease)
            waiting.size == 1
        }
        if (ready) start(lease)
    }
}
