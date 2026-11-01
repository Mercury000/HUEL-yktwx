package com.mercury.yktwx.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionRefreshTest {
    @Test fun failedUpdatedEndpointsKeepProgressAndRecoverIndependently() = runTest {
        val refresh = TransactionRefresh(this, needsRefresh = { false }, isReading = { false }, retry = {})
        val baseline = refresh.started()
        refresh.observe(baseline, "summary", 0)
        refresh.observe(baseline, "counts", 0)
        refresh.invalidate()
        val changed = refresh.started()
        refresh.observe(changed, "summary", 100)
        refresh.observe(changed, "counts", 1)
        val failure = refresh.started()
        refresh.record<Int>(failure, "summary", Result.failure(IllegalStateException()))
        refresh.failed(failure, "counts")
        assertFalse(refresh.pending("summary"))
        assertTrue(refresh.needsTransactionRead("summary"))
        assertEquals(0L, refresh.cacheReadAt("summary", 1000L))

        val recovery = refresh.started()
        refresh.record(recovery, "summary", Result.success(100))
        assertFalse(refresh.needsRead("summary"))
        assertEquals(1000L, refresh.cacheReadAt("summary", 1000L))
        assertTrue(refresh.needsRead("counts"))
        refresh.failed(failure, "summary")
        assertFalse(refresh.needsRead("summary"))

        refresh.clear()
        refresh.failed(recovery, "summary")
        assertFalse(refresh.needsRead("summary"))
        assertFalse(refresh.needsRead("counts"))
    }

    @Test fun clearingAFailureDoesNotConfirmAnUnchangedOrMissingBaseline() = runTest {
        val refresh = TransactionRefresh(this, needsRefresh = { false }, isReading = { false }, retry = {})
        val before = refresh.started()
        refresh.failed(before, "balance")
        assertTrue(refresh.needsRead("balance"))
        assertFalse(refresh.needsTransactionRead("balance"))
        refresh.invalidate()
        val read = refresh.started()
        refresh.succeeded(read, "balance")
        assertTrue(refresh.pending("balance"))
        refresh.observe(read, "balance", 100)
        assertTrue(refresh.needsRead("balance"))
        assertEquals(0L, refresh.cacheReadAt("balance", 1000L))
        assertEquals(1000L, refresh.cacheReadAt("balance", 1000L, affected = false))
        refresh.failed(read, "balance")
        assertEquals(0L, refresh.cacheReadAt("balance", 1000L, affected = false))
        refresh.observe(read, "balance", 200)
        assertFalse(refresh.needsRead("balance"))
    }

    @Test fun recoveringAnUpdatedEndpointStopsRetriesWithoutRepeatingOtherEndpoints() = runTest {
        var requests = 0
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.needsTransactionRead("balance") },
            isReading = { false }, retry = {
                requests++
                val read = refresh.started()
                refresh.observe(read, "balance", 200)
                refresh.finished(read)
            })
        refresh.observe(refresh.started(), "balance", 100)
        refresh.invalidate(); refresh.setVisible(true)
        refresh.observe(refresh.started(), "balance", 200)
        val failure = refresh.started()
        refresh.failed(failure, "balance"); refresh.finished(failure)
        advanceUntilIdle()
        assertEquals(1, requests)
        assertFalse(refresh.needsRead("balance"))
    }

    @Test fun unchangedResponsesExhaustPollingWithoutBecomingFreshEvenAfterSixtySeconds() = runTest {
        var requests = 0
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.pending("bills") }, isReading = { false }, retry = {
            requests++
            val read = refresh.started()
            refresh.observe(read, "bills", listOf("old"))
            refresh.finished(read)
        })
        refresh.observe(refresh.started(), "bills", listOf("old"))
        refresh.invalidate()
        refresh.setVisible(true)
        val initial = refresh.started()
        refresh.observe(initial, "bills", listOf("old"))
        refresh.finished(initial)
        advanceTimeBy(1_999); runCurrent(); assertEquals(0, requests)
        advanceTimeBy(1); runCurrent(); assertEquals(1, requests)
        advanceTimeBy(4_999); runCurrent(); assertEquals(1, requests)
        advanceTimeBy(1); runCurrent(); assertEquals(2, requests)
        advanceTimeBy(10_000); runCurrent(); assertEquals(3, requests)
        advanceTimeBy(60_000); runCurrent(); assertEquals(3, requests)
        assertTrue(refresh.pending("bills"))
        refresh.setVisible(false); refresh.setVisible(true)
        val returned = refresh.started()
        refresh.observe(returned, "bills", listOf("old", "recharge"))
        refresh.finished(returned)
        advanceUntilIdle()
        assertFalse(refresh.pending("bills"))
        assertEquals(3, requests)
    }

    @Test fun hidingPausesTimersAndReturningPreservesTheUsedBudget() = runTest {
        var requests = 0
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.pending("bills") }, isReading = { false }, retry = {
            requests++
            val read = refresh.started()
            refresh.observe(read, "bills", 1)
            refresh.finished(read)
        })
        refresh.observe(refresh.started(), "bills", 1)
        refresh.invalidate(); refresh.setVisible(true)
        val initial = refresh.started()
        refresh.observe(initial, "bills", 1); refresh.finished(initial)
        advanceTimeBy(1_000); refresh.setVisible(false)
        advanceTimeBy(60_000); runCurrent(); assertEquals(0, requests)
        refresh.setVisible(true)
        val returned = refresh.started()
        refresh.observe(returned, "bills", 1); refresh.finished(returned)
        advanceTimeBy(2_000); runCurrent(); assertEquals(1, requests)
        refresh.setVisible(false); refresh.setVisible(true)
        val again = refresh.started()
        refresh.observe(again, "bills", 1); refresh.finished(again)
        advanceTimeBy(4_999); runCurrent(); assertEquals(1, requests)
        advanceTimeBy(1); runCurrent(); assertEquals(2, requests)
        advanceUntilIdle(); assertEquals(3, requests)
    }

    @Test fun aChangedListCannotConfirmDelayedSummaryAndCounts() = runTest {
        lateinit var refresh: TransactionRefresh
        var requests = 0
        val keys = listOf("list", "summary", "counts")
        refresh = TransactionRefresh(this, needsRefresh = { keys.any(refresh::pending) }, isReading = { false }, retry = {
            requests++
            val read = refresh.started()
            refresh.observe(read, "summary", 1000L)
            refresh.observe(read, "counts", 1)
            refresh.finished(read)
        })
        val before = refresh.started()
        refresh.observe(before, "list", emptyList<String>())
        refresh.observe(before, "summary", 0L); refresh.observe(before, "counts", 0)
        refresh.invalidate(); refresh.setVisible(true)
        val first = refresh.started()
        refresh.observe(first, "list", listOf("recharge"))
        refresh.observe(first, "summary", 0L); refresh.observe(first, "counts", 0)
        refresh.finished(first)
        assertFalse(refresh.pending("list"))
        assertTrue(refresh.pending("summary")); assertTrue(refresh.pending("counts"))
        advanceUntilIdle()
        assertEquals(1, requests)
        assertFalse(keys.any(refresh::pending))
    }

    @Test fun lateResponsesCannotClearANewerTransactionOrReplaceItsBaseline() = runTest {
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.pending("balance") }, isReading = { false }, retry = {})
        refresh.observe(refresh.started(), "balance", 1000L)
        refresh.invalidate()
        val old = refresh.started()
        refresh.invalidate()
        refresh.observe(old, "balance", 2000L)
        assertFalse(refresh.accepts(old)); assertTrue(refresh.pending("balance"))
        val current = refresh.started()
        refresh.observe(current, "balance", 1000L)
        assertTrue(refresh.pending("balance"))
        refresh.observe(current, "balance", 3000L)
        assertFalse(refresh.pending("balance"))
    }

    @Test fun manualReadsCancelScheduledDuplicatesAndIgnoreOlderReadCompletions() = runTest {
        var requests = 0
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.pending("balance") }, isReading = { false }, retry = { requests++ })
        refresh.observe(refresh.started(), "balance", 1000L)
        refresh.invalidate(); refresh.setVisible(true)
        val automatic = refresh.started()
        refresh.observe(automatic, "balance", 1000L); refresh.finished(automatic)
        advanceTimeBy(1_000)
        val manual = refresh.started()
        refresh.observe(automatic, "balance", 2000L); refresh.finished(automatic)
        assertTrue(refresh.pending("balance"))
        refresh.observe(manual, "balance", 2000L); refresh.finished(manual)
        advanceUntilIdle(); assertEquals(0, requests)
    }

    @Test fun missingBaselinesAndDifferentQueriesCannotInventConfirmation() = runTest {
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { false }, isReading = { false }, retry = {})
        refresh.observe(refresh.started(), "october", 100L)
        refresh.invalidate(LocalDate.of(2026, 10, 31))
        val read = refresh.started()
        refresh.observe(read, "september", 200L)
        assertTrue(refresh.pending("october")); assertTrue(refresh.pending("september"))
        assertTrue(refresh.affects(BillDateFilter.month(java.time.YearMonth.of(2026, 10))))
        assertFalse(refresh.affects(BillDateFilter.month(java.time.YearMonth.of(2026, 9))))
        refresh.observe(read, "september", 200L)
        assertTrue(refresh.pending("september"))
    }

    @Test fun logoutCancelsRetriesAndRevokesAllReadTokens() = runTest {
        var requests = 0
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.pending("bills") }, isReading = { false }, retry = { requests++ })
        refresh.invalidate(); refresh.setVisible(true)
        val read = refresh.started()
        refresh.finished(read)
        refresh.clear()
        refresh.observe(read, "bills", listOf("old-account"))
        advanceUntilIdle()
        assertFalse(refresh.accepts(read)); assertFalse(refresh.pending("bills"))
        assertEquals(0, requests)
    }

    @Test fun anInFlightReadDoesNotSpendTheRetryBudget() = runTest {
        var reading = true
        var requests = 0
        lateinit var refresh: TransactionRefresh
        refresh = TransactionRefresh(this, needsRefresh = { refresh.pending("bills") }, isReading = { reading }, retry = {
            requests++
            val read = refresh.started()
            refresh.observe(read, "bills", 2); refresh.finished(read)
        })
        refresh.observe(refresh.started(), "bills", 1)
        refresh.invalidate(); refresh.setVisible(true)
        val read = refresh.started()
        refresh.observe(read, "bills", 1); refresh.finished(read)
        advanceTimeBy(6_000); runCurrent(); assertEquals(0, requests)
        reading = false
        advanceUntilIdle()
        assertEquals(1, requests); assertFalse(refresh.pending("bills"))
    }
}
