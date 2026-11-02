package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Test

class ReadGateTest {
    @Test fun readsRequireVisibilityAndKnownOnlineState() {
        val gate = ReadGate()
        assertTrue(gate.canRead)

        gate.update(visible = false, foreground = true, online = true, epoch = 0)
        assertFalse(gate.canRead)
        assertEquals(ReadDecision.Reason.Hidden, (gate.decide("query", ReadTrigger.Visible) as ReadDecision.Reject).reason)

        gate.update(visible = true, foreground = false, online = true, epoch = 0)
        assertFalse(gate.canRead)

        gate.update(visible = true, foreground = true, online = false, epoch = 0)
        assertFalse(gate.canRead)
        assertEquals(ReadDecision.Reason.Offline, (gate.decide("query", ReadTrigger.Pull) as ReadDecision.Reject).reason)

        gate.update(visible = true, foreground = true, online = null, epoch = 0)
        assertTrue(gate.canRead)
    }

    @Test fun unknownOnlineStateStillAllowsARead() {
        val gate = ReadGate()
        gate.update(visible = true, foreground = true, online = null, epoch = 0)
        val decision = gate.decide("query", ReadTrigger.Visible)
        assertTrue(decision is ReadDecision.Start)
        assertEquals(1, (decision as ReadDecision.Start).attempt)
    }

    @Test fun singleFlightRejectsASecondStartForTheSameQuery() {
        val gate = ReadGate()
        val decision = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", decision)
        assertEquals(ReadDecision.InFlight, gate.decide("query", ReadTrigger.Pull))
        assertTrue(gate.decide("other", ReadTrigger.Visible) is ReadDecision.Start)
        gate.finished("query", decision, failure = null)
        assertTrue(gate.decide("query", ReadTrigger.Visible) is ReadDecision.Start)
    }

    @Test fun automaticReadsGetTwoAttemptsPerEpochAndManualReadsResetTheBudget() {
        val gate = ReadGate()
        val first = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        assertEquals(1, first.attempt)
        gate.started("query", first)
        gate.finished("query", first, ReadFailureKind.Network)
        assertTrue(gate.canAutoRetry("query"))
        assertEquals(600L, gate.retryDelayMillis("query"))

        val second = gate.decide("query", ReadTrigger.Retry) as ReadDecision.Start
        assertEquals(2, second.attempt)
        gate.started("query", second)
        gate.finished("query", second, ReadFailureKind.Network)
        assertFalse(gate.canAutoRetry("query"))
        assertEquals(ReadDecision.Reason.Backoff, (gate.decide("query", ReadTrigger.Visible) as ReadDecision.Reject).reason)

        val manual = gate.decide("query", ReadTrigger.Pull) as ReadDecision.Start
        assertEquals(ReadTrigger.Pull, manual.trigger)
        gate.started("query", manual)
        gate.finished("query", manual, ReadFailureKind.Network)
        assertTrue(gate.canAutoRetry("query"))
        val afterManualRetry = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        assertEquals(2, afterManualRetry.attempt)
    }

    @Test fun nonNetworkFailuresDoNotAutoRetryOrRecover() {
        val gate = ReadGate()
        val decision = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", decision)
        gate.finished("query", decision, ReadFailureKind.Other)
        assertFalse(gate.canAutoRetry("query"))
        assertFalse(gate.canRecover("query", 0))
        assertEquals(ReadFailureKind.Other, gate.failure("query"))
    }

    @Test fun epochClearsTheBudgetButNotInFlightWork() {
        val gate = ReadGate()
        val decision = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", decision)
        val changed = gate.update(visible = true, foreground = true, online = false, epoch = 0)
        assertFalse(changed)
        assertEquals(ReadDecision.Reason.Offline, (gate.decide("query", ReadTrigger.Retry) as ReadDecision.Reject).reason)
        assertTrue(gate.update(visible = true, foreground = true, online = true, epoch = 1))
        assertEquals(1L, gate.currentEpoch)
        assertEquals(ReadDecision.InFlight, gate.decide("query", ReadTrigger.Visible))
    }

    @Test fun recoveryBelongsToTheFailedQueryAndOnlyRunsOncePerEpoch() {
        val gate = ReadGate()
        gate.update(visible = true, foreground = true, online = true, epoch = 0)
        val old = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", old)

        assertFalse(gate.update(visible = true, foreground = true, online = false, epoch = 0))
        assertTrue(gate.update(visible = true, foreground = true, online = true, epoch = 1))
        assertEquals(1L, gate.currentEpoch)

        gate.finished("query", old, ReadFailureKind.Network)
        assertTrue(gate.canRecover("query", 1))
        assertFalse(gate.canRecover("other", 1))
        assertFalse(gate.canRecover("query", 0))

        val recovery = gate.decide("query", ReadTrigger.Reconnect) as ReadDecision.Start
        gate.started("query", recovery)
        assertFalse(gate.canRecover("query", 1))
        assertEquals(ReadDecision.InFlight, gate.decide("query", ReadTrigger.Reconnect))
    }

    @Test fun aRetryStartingAfterReconnectConsumesTheNewEpochBudget() {
        val gate = ReadGate()
        gate.update(visible = true, foreground = true, online = true, epoch = 0)
        // A decision taken before local preparation belongs to epoch 0.
        val prepared = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        assertEquals(0L, prepared.epoch)

        assertFalse(gate.update(visible = true, foreground = true, online = false, epoch = 0))
        assertTrue(gate.update(visible = true, foreground = true, online = true, epoch = 1))
        assertEquals(1L, gate.currentEpoch)

        // The request actually starts in epoch 1, so it already used this epoch's first attempt.
        gate.started("query", prepared)
        gate.finished("query", prepared, ReadFailureKind.Network)
        assertFalse(gate.canRecover("query", 1))
        assertTrue(gate.canAutoRetry("query"))
    }

    @Test fun resetAndClearForgetFailuresAndInFlightWork() {
        val gate = ReadGate()
        val decision = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", decision)
        gate.finished("query", decision, ReadFailureKind.Network)
        assertNotNull(gate.failure("query"))

        gate.reset("query")
        assertNull(gate.failure("query"))
        assertTrue(gate.decide("query", ReadTrigger.Visible) is ReadDecision.Start)
    }

    @Test fun clearForgetsEveryQueryButKeepsTheEnvironment() {
        val gate = ReadGate()
        gate.update(visible = true, foreground = true, online = true, epoch = 4)
        val decision = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", decision)
        gate.finished("query", decision, ReadFailureKind.Network)

        gate.clear()
        assertNull(gate.failure("query"))
        assertEquals(4L, gate.currentEpoch)
        assertTrue(gate.decide("query", ReadTrigger.Reconnect) is ReadDecision.Start)
    }

    @Test fun onlyTheMonitorCanAdvanceAnEpoch() {
        val gate = ReadGate()
        gate.update(true, true, false, 4)
        assertFalse(gate.update(true, true, true, 4))
        assertEquals(4L, gate.currentEpoch)
        assertTrue(gate.update(true, true, true, 5))
        assertEquals(5L, gate.currentEpoch)
    }

    @Test fun anOldCompletionCannotReleaseItsReplacement() {
        val gate = ReadGate()
        val old = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", old)
        gate.reset("query")
        val replacement = gate.decide("query", ReadTrigger.Visible) as ReadDecision.Start
        gate.started("query", replacement)
        gate.finished("query", old, null)
        assertTrue(gate.isReading("query"))
        gate.finished("query", replacement, null)
        assertFalse(gate.isReading("query"))
    }
}
