package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FeeRoomConfirmationTest {
    private fun room(id: String) = FeeRoomBinding.from(listOf(FeeLevel("room", 1, "房间")),
        mapOf("room" to id), mapOf("room" to "示例房间"))!!
    private fun config(binding: FeeRoomBinding?, enabled: Boolean = true, invalid: Boolean = false) =
        FeeConfiguration(FeeProject.Wenbei,
            RechargeConfiguration("411", "", listOf(100), 1, 10000, true, false), enabled, binding, invalid)

    @Test fun lostSaveAndUnbindResponsesAreConfirmedWithExactlyOneWriteAndOneRead() = runBlocking {
        for (target in listOf(room("fake-room"), null)) {
            var writes = 0
            var reads = 0
            val failure = NetworkFailure()
            val result = confirmFeeRoomBinding(target,
                write = { writes++; throw failure },
                read = { reads++; config(target) }, isCurrent = { true })
            assertTrue(result.bindingKnown)
            assertTrue(result.confirmed)
            assertSame(target, result.binding)
            assertSame(failure, result.writeFailure)
            assertEquals(1, writes)
            assertEquals(1, reads)
        }
    }

    @Test fun mismatchKeepsTheObservedBindingAndWriteFailureWithoutRetrying() = runBlocking {
        val original = room("old")
        val target = room("new")
        val failure = YktException("保存被拒绝")
        var writes = 0
        val result = confirmFeeRoomBinding(target, { writes++; throw failure }, { config(original) }, { true })
        assertTrue(result.bindingKnown)
        assertFalse(result.confirmed)
        assertSame(original, result.binding)
        assertSame(failure, result.writeFailure)
        assertEquals(1, writes)
    }

    @Test fun disabledAndMalformedConfigurationsCannotConfirmEvenAnEmptyBinding() = runBlocking {
        for (configuration in listOf(config(null, enabled = false), config(null, invalid = true))) {
            val result = confirmFeeRoomBinding(null, {}, { configuration }, { true })
            assertFalse(result.bindingKnown)
            assertFalse(result.confirmed)
        }
    }

    @Test fun failedConfirmationReadPropagatesWithoutReplayingTheWrite() = runBlocking {
        var writes = 0
        var reads = 0
        val failure = NetworkFailure()
        try {
            confirmFeeRoomBinding(null, { writes++ }, { reads++; throw failure }, { true })
            fail("A failed read cannot confirm the write")
        } catch (actual: NetworkFailure) { assertSame(failure, actual) }
        assertEquals(1, writes)
        assertEquals(1, reads)
    }

    @Test fun cancellationDuringWriteSkipsConfirmation() = runBlocking {
        var reads = 0
        val cancelled = CancellationException("cancelled")
        try {
            confirmFeeRoomBinding(null, { throw cancelled }, { reads++; config(null) }, { true })
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertEquals(0, reads)
    }

    @Test fun revokedOperationsDoNotReadOrPublishAnOldAccountResult() = runBlocking {
        for (revokeAt in 0..2) {
            var current = revokeAt != 0
            var writes = 0
            var reads = 0
            try {
                confirmFeeRoomBinding(null,
                    write = { writes++; if (revokeAt == 1) current = false },
                    read = { reads++; current = false; config(null) }, isCurrent = { current })
                fail("A revoked operation cannot publish its result")
            } catch (_: CancellationException) { }
            assertEquals(if (revokeAt == 0) 0 else 1, writes)
            assertEquals(if (revokeAt == 2) 1 else 0, reads)
        }
    }
}
