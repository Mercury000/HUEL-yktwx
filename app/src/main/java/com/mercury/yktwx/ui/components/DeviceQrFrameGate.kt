package com.mercury.yktwx.ui.components

/** A single worker owns the reusable pixels until it releases its frame. */
internal class DeviceQrFrameGate(private val intervalMillis: Long = 120) {
    private var ready = false
    private var busy = false
    private var closed = false
    private var lastFrameAt: Long? = null

    @Synchronized
    fun start() { if (!closed) ready = true }

    @Synchronized
    fun tryAcquire(nowMillis: Long): Boolean {
        if (!ready || closed || busy) return false
        val previous = lastFrameAt
        if (previous != null && nowMillis - previous < intervalMillis) return false
        busy = true
        lastFrameAt = nowMillis
        return true
    }

    @Synchronized
    fun release() { busy = false }

    @Synchronized
    fun resetInterval() { lastFrameAt = null }

    @Synchronized
    fun close() { closed = true }
}
