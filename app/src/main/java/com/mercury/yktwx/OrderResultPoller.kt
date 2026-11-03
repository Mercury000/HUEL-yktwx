package com.mercury.yktwx

import com.mercury.yktwx.data.RechargeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Polls read-only order status. Completed queries retain their budget across foreground pauses. */
internal class OrderResultPoller(
    private val scope: CoroutineScope,
    private val delayMillis: Long,
    private val timeoutMillis: Long? = null,
) {
    data class Outcome(val phase: RechargePhase?, val message: String?)
    private var completed = 0
    private var sequence = 0L
    private var queryJob: Job? = null
    private var deadline: Job? = null

    fun start(newRound: Boolean, isCurrent: () -> Boolean, onJob: (Job) -> Unit,
        query: suspend () -> RechargeResult, onResult: (Outcome) -> Unit,
        onError: (Exception) -> Unit, onTimeout: () -> Unit = {}): Job {
        queryJob?.takeIf { it.isActive }?.let { return it }
        if (newRound) { completed = 0; deadline?.cancel(); deadline = null }
        val run = ++sequence
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (completed < QueryLimit) {
                try {
                    if (completed > 0) delay(delayMillis)
                    if (run != sequence || !isCurrent()) return@launch
                    val response = query()
                    if (run != sequence || !isCurrent()) return@launch
                    completed++
                    if (response.paid || completed == QueryLimit) {
                        stop()
                        onResult(classify(response))
                        return@launch
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (run == sequence && isCurrent()) { stop(); onError(e) }
                    return@launch
                }
            }
        }
        queryJob = job
        onJob(job)
        if (timeoutMillis != null && deadline?.isActive != true) {
            deadline = scope.launch {
                delay(timeoutMillis)
                if (isCurrent()) {
                    val pending = queryJob
                    stop()
                    onTimeout()
                    pending?.cancel()
                }
            }
        }
        job.start()
        return job
    }

    fun stop() { sequence++; deadline?.cancel(); deadline = null }
    fun reset() { stop(); completed = 0; queryJob?.cancel(); queryJob = null }

    private fun classify(result: RechargeResult): Outcome = when {
        result.paid -> Outcome(RechargePhase.Paid, null)
        result.serverStatus == 0 -> Outcome(RechargePhase.Failed, result.message)
        result.serverStatus == 2 -> Outcome(RechargePhase.TimedOut, result.message)
        else -> Outcome(null, result.message ?: "订单状态异常")
    }

    private companion object { const val QueryLimit = 3 }
}
