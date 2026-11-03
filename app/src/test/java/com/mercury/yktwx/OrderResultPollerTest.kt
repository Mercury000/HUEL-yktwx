package com.mercury.yktwx

import com.mercury.yktwx.data.NetworkFailure
import com.mercury.yktwx.data.RechargeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OrderResultPollerTest {
    @Test fun interruptedReadsDoNotConsumeTheCompletedQueryBudgetOnResume() = runTest {
        val poller = OrderResultPoller(backgroundScope, 10)
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val outcomes = mutableListOf<OrderResultPoller.Outcome>()
        fun start(newRound: Boolean) = poller.start(newRound, { true }, {}, query = {
            requests++
            if (requests == 2) gate.await()
            RechargeResult(false, 0)
        }, onResult = { outcomes += it }, onError = { throw it })
        val first = start(true)
        runCurrent()
        advanceTimeBy(10); runCurrent()
        assertEquals(2, requests)
        first.cancel(); runCurrent()
        start(false)
        advanceTimeBy(30); runCurrent()
        assertEquals(4, requests)
        assertEquals(RechargePhase.Failed, outcomes.single().phase)
        advanceTimeBy(100); runCurrent()
        assertEquals(4, requests)
    }

    @Test fun requestErrorsDoNotBecomePaymentFailuresAndAnExplicitRetryStartsANewBudget() = runTest {
        val poller = OrderResultPoller(backgroundScope, 10)
        var errors = 0
        val outcomes = mutableListOf<OrderResultPoller.Outcome>()
        poller.start(true, { true }, {}, query = { throw NetworkFailure() },
            onResult = { outcomes += it }, onError = { errors++ })
        runCurrent()
        assertEquals(1, errors)
        assertTrue(outcomes.isEmpty())
        poller.start(true, { true }, {}, query = { RechargeResult(true) },
            onResult = { outcomes += it }, onError = { throw it })
        runCurrent()
        assertEquals(RechargePhase.Paid, outcomes.single().phase)
    }

    @Test fun resettingTheAttemptRejectsAnUncooperativeLateResponse() = runTest {
        val poller = OrderResultPoller(backgroundScope, 10)
        val gate = CompletableDeferred<Unit>()
        val outcomes = mutableListOf<OrderResultPoller.Outcome>()
        poller.start(true, { true }, {}, query = {
            withContext(NonCancellable) { gate.await() }
            RechargeResult(true)
        }, onResult = { outcomes += it }, onError = { throw it })
        runCurrent()
        poller.reset()
        poller.start(true, { true }, {}, query = { RechargeResult(false, 2) },
            onResult = { outcomes += it }, onError = { throw it })
        gate.complete(Unit)
        advanceTimeBy(30); runCurrent()
        assertEquals(RechargePhase.TimedOut, outcomes.single().phase)
    }

    @Test fun theIndependentDeadlineIsRetainedAcrossPauseAndResume() = runTest {
        val poller = OrderResultPoller(backgroundScope, 10, 50)
        val gate = CompletableDeferred<Unit>()
        var active = true
        var timeouts = 0
        val outcomes = mutableListOf<OrderResultPoller.Outcome>()
        fun start(newRound: Boolean) = poller.start(newRound, { active }, {}, query = {
            gate.await(); RechargeResult(true)
        }, onResult = { outcomes += it }, onError = { throw it }, onTimeout = { timeouts++; active = false })
        val first = start(true)
        runCurrent()
        advanceTimeBy(20)
        first.cancel(); runCurrent()
        start(false)
        advanceTimeBy(30); runCurrent()
        assertEquals(1, timeouts)
        gate.complete(Unit); runCurrent()
        assertTrue(outcomes.isEmpty())
    }

    @Test fun unknownServerStatusesRemainUnknownAfterTheBoundedReadRound() = runTest {
        val poller = OrderResultPoller(backgroundScope, 10)
        var requests = 0
        val outcomes = mutableListOf<OrderResultPoller.Outcome>()
        poller.start(true, { true }, {}, query = { requests++; RechargeResult(false, 99, "unknown") },
            onResult = { outcomes += it }, onError = { throw it })
        advanceTimeBy(30); runCurrent()
        assertEquals(3, requests)
        assertNull(outcomes.single().phase)
        assertEquals("unknown", outcomes.single().message)
    }
}
