package com.mercury.yktwx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shared WeChat/translucent-window return handling; only the live attempt is tracked. */
internal class PaymentReturnObserver(
    private val scope: CoroutineScope,
    private val currentJob: () -> Job?,
    private val checking: () -> Boolean,
    private val onCheck: () -> Unit,
) {
    private var returnJob: Job? = null
    private var foreground = false
    private var resumed = false
    private var focused = false
    private var waiting = false
    private var left = false
    private var resumeCheck = false
    private var generation = 0L
    val canLaunch get() = foreground && resumed
    fun launched() { returnJob?.cancel(); waiting = true; left = false }
    fun consume() { waiting = false; left = false; resumeCheck = false }
    fun checkOnNextInteraction() { resumeCheck = true; checkReturn() }
    fun reset() { generation++; returnJob?.cancel(); returnJob = null; consume() }
    fun foreground() { foreground = true }
    fun resumed(hasFocus: Boolean) { resumed = true; focused = hasFocus; checkReturn() }
    fun paused() { resumed = false; lostInteraction() }
    fun focus(hasFocus: Boolean) { focused = hasFocus; if (hasFocus) checkReturn() else lostInteraction() }
    fun background() { foreground = false; lostInteraction() }
    private fun lostInteraction() {
        returnJob?.cancel()
        if (waiting) left = true
        if (checking()) { resumeCheck = true; currentJob()?.cancel() }
    }
    private fun needed() = resumeCheck || (waiting && left)
    private fun checkReturn() {
        if (!foreground || !resumed || !focused || !needed() || returnJob?.isActive == true) return
        val g = generation
        val oldJob = currentJob()
        returnJob = scope.launch {
            oldJob?.join()
            if (g == generation && foreground && resumed && focused && needed()) onCheck()
        }
    }
}
