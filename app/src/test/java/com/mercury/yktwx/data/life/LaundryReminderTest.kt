package com.mercury.yktwx.data.life

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class LaundryReminderTest {
    private val device = LifeDevice("77", "serial", "77", "00", "org", "org", "shop", "imei",
        "学校", "", "before", false, false, null, emptyList(), emptyList())
    private val session = LaundrySession(device, "clean", "0.00", "2", LaundryPhase.Ended)
    private fun time(value: String) = LocalDateTime.parse(value).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    @Test fun deadlineIsThreeOnTheFollowingCalendarDateIncludingEarlyMorningAndYearRollover() {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            for (completed in listOf("2026-12-31T01:00:00", "2026-12-31T12:00:00", "2026-12-31T23:59:59")) {
                assertEquals(time("2027-01-01T03:00:00"),
                    session.copy(finishedAtMillis = time(completed)).completionReminderDeadlineMillis())
            }
        } finally { TimeZone.setDefault(originalZone) }
    }

    @Test fun missingServerFinishUsesOneObservationWithoutInventingActualFinishAndServerTimeTakesPriority() {
        val observed = session.withCompletionObservation(time("2026-11-07T20:00:00"))
        assertNull(observed.finishedAtMillis)
        assertEquals(time("2026-11-08T03:00:00"), observed.completionReminderDeadlineMillis())
        assertEquals(observed, observed.withCompletionObservation(time("2026-11-09T10:00:00")))
        assertEquals(time("2026-11-07T03:00:00"),
            observed.copy(finishedAtMillis = time("2026-11-06T20:00:00")).completionReminderDeadlineMillis())
    }

    @Test fun estimatedFinishAndReminderFieldsNeverExpireUnfinishedOrClosedOrders() {
        for (phase in LaundryPhase.entries.filter { it != LaundryPhase.Ended }) {
            assertNull(session.copy(phase = phase, finishedAtMillis = 1, expectedEndMillis = 1,
                completionObservedAtMillis = 1).completionReminderDeadlineMillis())
        }
    }
}
