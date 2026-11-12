package com.mercury.yktwx.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadControllerTest {
    private fun online(epoch: Long = 0) = ReadEnvironment(ReadPage.My, true, true, epoch)

    @Test fun foregroundReturnRestoresExhaustedNetworkBudgetWithoutAReconnect() = runTest {
        var calls = 0
        lateinit var reads: ReadController
        fun request(trigger: ReadTrigger = ReadTrigger.Visible) {
            reads.launch("query", trigger, { request(ReadTrigger.Retry) }) {
                assertTrue(startNetwork()); calls++; complete(ReadFailureKind.Network)
            }
        }
        reads = ReadController(this, setOf(ReadPage.My), online(), {})
        request(); advanceUntilIdle()
        assertEquals(2, calls)
        repeat(3) { reads.update(online()); request() }
        advanceUntilIdle(); assertEquals(2, calls)
        reads.update(online().copy(foreground = false, page = null))
        reads.update(online().copy(resumeEpoch = 1))
        request(); advanceUntilIdle()
        assertEquals(4, calls)
        assertEquals(0L, reads.currentEpoch)
        repeat(3) { reads.update(online().copy(resumeEpoch = 1)); request() }
        advanceUntilIdle(); assertEquals(4, calls)
    }

    @Test fun pageReturnRecoversExhaustedNetworkFailuresButRepeatedVisibleEmissionsStayBounded() = runTest {
        var calls = 0
        lateinit var reads: ReadController
        fun request(trigger: ReadTrigger = ReadTrigger.Visible) {
            reads.launch("query", trigger, { request(ReadTrigger.Retry) }) {
                assertTrue(startNetwork()); calls++; complete(ReadFailureKind.Network)
            }
        }
        reads = ReadController(this, setOf(ReadPage.My, ReadPage.Profile), online(), {})
        request(); advanceUntilIdle(); assertEquals(2, calls)
        reads.update(online().copy(page = ReadPage.Hall))
        request(); runCurrent(); assertEquals(2, calls)
        reads.update(online()); request(); advanceUntilIdle(); assertEquals(4, calls)
        repeat(3) { reads.update(online()); request() }
        advanceUntilIdle(); assertEquals(4, calls)
        // Details can share a ViewModel with the parent and still constitute a new page entry.
        reads.update(online().copy(page = ReadPage.Profile)); request()
        advanceUntilIdle(); assertEquals(6, calls)
        repeat(3) { reads.update(online().copy(page = ReadPage.Profile)); request() }
        advanceUntilIdle(); assertEquals(6, calls)
        assertEquals(0L, reads.currentEpoch)
    }

    @Test fun failedRequestSpanningForegroundReturnReevaluatesOnceWithoutOldBackoff() = runTest {
        val network = CompletableDeferred<Unit>()
        var reevaluations = 0
        var retries = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        reads.launch("query", ReadTrigger.Visible, { retries++ }) {
            assertTrue(startNetwork()); network.await(); complete(ReadFailureKind.Network)
        }
        runCurrent()
        // Compose can coalesce stop/start; the resume sequence still carries the transition.
        reads.update(online().copy(resumeEpoch = 1))
        network.complete(Unit); runCurrent()
        assertEquals(1, reevaluations)
        advanceUntilIdle(); assertEquals(0, retries)
        assertTrue(reads.canStart("query", ReadTrigger.Visible))
    }

    @Test fun localPreparationSpanningForegroundUsesTheNewBudgetAndNormalBackoff() = runTest {
        val local = CompletableDeferred<Unit>()
        var reevaluations = 0
        var retries = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        reads.launch("query", ReadTrigger.Visible, { retries++ }) {
            local.await(); assertTrue(startNetwork()); complete(ReadFailureKind.Network)
        }
        runCurrent(); reads.update(online().copy(resumeEpoch = 1))
        local.complete(Unit); runCurrent()
        assertEquals(0, reevaluations)
        advanceTimeBy(599); runCurrent(); assertEquals(0, retries)
        advanceTimeBy(1); runCurrent(); assertEquals(1, retries)
    }

    @Test fun productionWaitsForEnvironmentAndUnknownNetworkCanRead() = runTest {
        var calls = 0
        val reads = ReadController(this, setOf(ReadPage.My), ReadEnvironment.Inactive, {})
        assertNull(reads.launch("query", ReadTrigger.Pull, {}) { calls++ })
        reads.update(online().copy(online = null))
        reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); calls++; complete(null)
        }
        runCurrent()
        assertEquals(1, calls)
        reads.update(online().copy(page = ReadPage.Hall))
        assertFalse(reads.canStart("query", ReadTrigger.Pull))
    }

    @Test fun preparationReservesAQueryButOnlyTheActualNetworkStartConsumesAnAttempt() = runTest {
        val local = CompletableDeferred<Unit>()
        var calls = 0
        var reevaluations = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        val original = reads.launch("query", ReadTrigger.Visible, { calls++ }) {
            local.await()
            assertTrue(startNetwork()); calls++; complete(ReadFailureKind.Network)
        }
        runCurrent()
        assertSame(original, reads.launch("query", ReadTrigger.Visible, {}) { fail("Duplicate preparation") })
        reads.update(online().copy(online = false))
        reads.update(online(1))
        local.complete(Unit); runCurrent()
        assertEquals(1, calls)
        assertEquals(0, reevaluations)
        reads.recover("query", 1) { fail("Already started in this epoch") }
        advanceTimeBy(599); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(1); runCurrent(); assertEquals(2, calls)
    }

    @Test fun aReconnectWaitsForOldNetworkWorkThenReevaluatesTheCurrentPage() = runTest {
        val network = CompletableDeferred<Unit>()
        var reevaluations = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); network.await(); complete(ReadFailureKind.Network)
        }
        runCurrent()
        reads.update(online().copy(online = false))
        reads.update(online(1)); runCurrent()
        assertEquals(0, reevaluations)
        network.complete(Unit); runCurrent()
        assertEquals(1, reevaluations)
        reads.clear(); advanceUntilIdle()
        assertEquals(1, reevaluations)
    }

    @Test fun successAllowsPeriodicReadsButTwoAutomaticFailuresStopUntilManualReadOrReconnect() = runTest {
        var calls = 0
        lateinit var reads: ReadController
        fun request(trigger: ReadTrigger, failure: ReadFailureKind?) {
            reads.launch("query", trigger, { request(ReadTrigger.Retry, ReadFailureKind.Network) }) {
                assertTrue(startNetwork()); calls++; complete(failure)
            }
        }
        reads = ReadController(this, setOf(ReadPage.My), online(), {})
        repeat(3) { request(ReadTrigger.Visible, null); runCurrent() }
        assertEquals(3, calls)
        request(ReadTrigger.Visible, ReadFailureKind.Network); advanceUntilIdle()
        assertEquals(5, calls)
        assertFalse(reads.canStart("query", ReadTrigger.Visible))
        request(ReadTrigger.Pull, null); runCurrent()
        assertEquals(6, calls)
        assertTrue(reads.canStart("query", ReadTrigger.Visible))
    }

    @Test fun cancellationAndLateCompletionCannotClearTheNewRequestOrRetryForAnotherOwner() = runTest {
        val oldNetwork = CompletableDeferred<Unit>()
        val newNetwork = CompletableDeferred<Unit>()
        var retries = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        reads.launch("query", ReadTrigger.Visible, { retries++ }) {
            assertTrue(startNetwork())
            withContext(NonCancellable) { oldNetwork.await() }
            complete(ReadFailureKind.Network)
        }
        runCurrent(); reads.clear()
        reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); newNetwork.await(); complete(null)
        }
        runCurrent(); oldNetwork.complete(Unit); runCurrent()
        assertTrue(reads.isReading("query"))
        newNetwork.complete(Unit); advanceUntilIdle()
        assertFalse(reads.isReading("query")); assertEquals(0, retries)
    }

    @Test fun aHiddenPageDoesNotReceiveQueuedReevaluationOrRetries() = runTest {
        val network = CompletableDeferred<Unit>()
        var work = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { work++ })
        reads.launch("query", ReadTrigger.Visible, { work++ }) {
            assertTrue(startNetwork()); network.await(); complete(ReadFailureKind.Network)
        }
        runCurrent(); reads.update(online(1)); reads.update(online(1).copy(page = null))
        network.complete(Unit); advanceUntilIdle()
        assertEquals(0, work)
    }

    @Test fun foregroundResumeReevaluatesWithoutResettingTheConnectionBudget() = runTest {
        val network = CompletableDeferred<Unit>()
        var reevaluations = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); network.await(); complete(null)
        }
        runCurrent(); reads.update(online().copy(resumeEpoch = 1))
        network.complete(Unit); runCurrent()
        assertEquals(1, reevaluations)
        assertEquals(0L, reads.currentEpoch)
    }

    @Test fun transactionChecksHaveTheirOwnBoundAndDoNotAcquireAutomaticRetries() = runTest {
        var retries = 0
        var calls = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        repeat(4) {
            reads.launch("query", ReadTrigger.Transaction, { retries++ }) {
                assertTrue(startNetwork()); calls++; complete(ReadFailureKind.Network)
            }
            runCurrent()
        }
        advanceUntilIdle()
        assertEquals(4, calls); assertEquals(0, retries)
    }

    @Test fun automaticActivationRespectsBackoffAndManualReadCancelsTheQueuedRetry() = runTest {
        var calls = 0
        var retries = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        reads.launch("query", ReadTrigger.Visible, { retries++ }) {
            assertTrue(startNetwork()); calls++; complete(ReadFailureKind.Network)
        }
        runCurrent()
        repeat(3) {
            assertFalse(reads.canStart("query", ReadTrigger.Visible))
            assertNull(reads.launch("query", ReadTrigger.Visible, {}) { fail("Bypassed backoff") })
        }
        advanceTimeBy(599); runCurrent()
        assertEquals(0, retries)
        assertTrue(reads.canStart("query", ReadTrigger.Pull))
        reads.launch("query", ReadTrigger.Pull, {}) {
            assertTrue(startNetwork()); calls++; complete(null)
        }
        advanceUntilIdle()
        assertEquals(2, calls); assertEquals(0, retries)
    }

    @Test fun repeatedExplicitActionsCoalesceUntilAllCurrentWorkFinishes() = runTest {
        val firstNetwork = CompletableDeferred<Unit>()
        val secondNetwork = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        reads.launch("first", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); firstNetwork.await(); complete(null)
        }
        reads.launch("second", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); secondNetwork.await(); complete(null)
        }
        runCurrent()
        assertFalse(reads.canStart("first", ReadTrigger.Action) { actions += "old" })
        assertFalse(reads.canStart("first", ReadTrigger.Pull) { actions += "latest" })
        firstNetwork.complete(Unit); runCurrent()
        assertTrue(actions.isEmpty())
        secondNetwork.complete(Unit); runCurrent()
        assertEquals(listOf("latest"), actions)
    }

    @Test fun aQueuedActionCanBeCancelledAfterTheNetworkFinishesBeforeItsCallbackRuns() = runTest {
        val network = CompletableDeferred<Unit>()
        var actions = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        val job = reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); network.await(); complete(null)
        }!!
        runCurrent()
        assertFalse(reads.canStart("query", ReadTrigger.Action) { actions++ })
        job.invokeOnCompletion { reads.cancel("query") }
        network.complete(Unit); advanceUntilIdle()
        assertEquals(0, actions)
    }

    @Test fun hidingAndReturningCannotResurrectAnAlreadyQueuedAction() = runTest {
        val network = CompletableDeferred<Unit>()
        var actions = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        val job = reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork()); network.await(); complete(null)
        }!!
        runCurrent()
        assertFalse(reads.canStart("query", ReadTrigger.Pull) { actions++ })
        job.invokeOnCompletion {
            reads.update(online().copy(page = null))
            reads.update(online())
        }
        network.complete(Unit); advanceUntilIdle()
        assertEquals(0, actions)
    }

    @Test fun explicitReplacementKeepsItsLeaseWhenTheCancelledRequestFinishesLate() = runTest {
        val oldNetwork = CompletableDeferred<Unit>()
        val replacementNetwork = CompletableDeferred<Unit>()
        var retries = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), {})
        reads.launch("query", ReadTrigger.Visible, { retries++ }) {
            assertTrue(startNetwork())
            withContext(NonCancellable) { oldNetwork.await() }
            complete(ReadFailureKind.Network)
        }
        runCurrent()
        assertTrue(reads.canStart("query", ReadTrigger.Action, replace = true))
        reads.launch("query", ReadTrigger.Action, {}) {
            assertTrue(startNetwork()); replacementNetwork.await(); complete(null)
        }
        runCurrent(); oldNetwork.complete(Unit); runCurrent()
        assertTrue(reads.isReading("query"))
        replacementNetwork.complete(Unit); advanceUntilIdle()
        assertFalse(reads.isReading("query")); assertEquals(0, retries)
    }

    @Test fun authenticationAndBusinessFailuresDoNotScheduleNetworkRetries() = runTest {
        var retries = 0
        var reevaluations = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        for (failure in listOf(ReadFailureKind.Authentication, ReadFailureKind.Other)) {
            reads.launch(failure, ReadTrigger.Visible, { retries++ }) {
                assertTrue(startNetwork()); complete(failure)
            }
            assertFalse(reads.canStart(failure, ReadTrigger.Visible))
        }
        advanceUntilIdle()
        assertEquals(0, retries); assertEquals(0, reevaluations)
    }

    @Test fun repeatedTransactionTriggersMergeWithoutReplayingTheCurrentRound() = runTest {
        val network = CompletableDeferred<Unit>()
        var reevaluations = 0
        val reads = ReadController(this, setOf(ReadPage.My), online(), { reevaluations++ })
        val job = reads.launch("query", ReadTrigger.Transaction, {}) {
            assertTrue(startNetwork()); network.await(); complete(ReadFailureKind.Network)
        }
        runCurrent()
        assertFalse(reads.canStart("query", ReadTrigger.Transaction) { fail("Replayed transaction round") })
        assertSame(job, reads.launch("query", ReadTrigger.Transaction, {}) { fail("Duplicate transaction") })
        network.complete(Unit); advanceUntilIdle()
        assertEquals(0, reevaluations)
    }

    @Test fun automaticDuplicatesReevaluateFreshnessAfterPublishedResultsFinishSaving() = runTest {
        val cacheWrite = CompletableDeferred<Unit>()
        var reevaluations = 0
        lateinit var reads: ReadController
        reads = ReadController(this, setOf(ReadPage.My), online(), {
            assertFalse(reads.isReading("query"))
            reevaluations++
        })
        reads.launch("query", ReadTrigger.Visible, {}) {
            assertTrue(startNetwork())
            cacheWrite.await() // Results can become stale while their cache write is still active.
            complete(null)
        }
        runCurrent()
        repeat(3) { assertFalse(reads.canStart("query", ReadTrigger.Visible)) }
        cacheWrite.complete(Unit); advanceUntilIdle()
        assertEquals(1, reevaluations)
    }
}
