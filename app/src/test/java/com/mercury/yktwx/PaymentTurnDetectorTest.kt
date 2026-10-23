package com.mercury.yktwx

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class PaymentTurnDetectorTest {
    private val detector = PaymentTurnDetector()
    private var time = 0L
    private val events = mutableListOf<PaymentTurnDetector.Event>()
    private fun pose(angle: Double, duration: Int = 240, aroundX: Boolean = false) {
        repeat(duration / 20) {
            time += 20
            val radians = Math.toRadians(angle)
            detector.sample(if (aroundX) 0.0 else sin(radians), if (aroundX) sin(radians) else 0.0, cos(radians), time)
                ?.let { events += it }
        }
    }
    private fun turn(sign: Int = 1, aroundX: Boolean = false) {
        pose(0.0)
        for (angle in 0..140 step 5) pose(angle.toDouble() * sign, 20, aroundX)
        pose(140.0 * sign, 160, aroundX)
        for (angle in 140 downTo 0 step 5) pose(angle.toDouble() * sign, 20, aroundX)
        pose(0.0)
    }
    @Test fun naturalTurnReturnsExactlyOnce() {
        turn()
        assertEquals(listOf(PaymentTurnDetector.Event.Started, PaymentTurnDetector.Event.Returned), events)
        pose(0.0, 2000)
        assertEquals(1, events.count { it == PaymentTurnDetector.Event.Returned })
    }
    @Test fun eitherHandAndEitherAxisWork() {
        turn(-1, true)
        assertEquals(1, events.count { it == PaymentTurnDetector.Event.Returned })
    }
    @Test fun modestAdjustmentsAndScreenPlaneRotationsDoNotTrigger() {
        pose(0.0)
        pose(20.0); pose(-15.0); pose(0.0)
        assertTrue(events.isEmpty())
    }
    @Test fun briefSweepAndIncompleteTurnDoNotTrigger() {
        pose(0.0); pose(45.0, 20); pose(80.0, 20); pose(0.0)
        assertFalse(events.contains(PaymentTurnDetector.Event.Returned))
    }
    @Test fun scannerAdjustmentsAreNotMistakenForReturn() {
        pose(0.0); pose(80.0); pose(110.0); pose(75.0); pose(95.0)
        assertFalse(events.contains(PaymentTurnDetector.Event.Returned))
        pose(0.0)
        assertEquals(1, events.count { it == PaymentTurnDetector.Event.Returned })
    }
    @Test fun slowTurnDoesNotDragTheReferenceAlong() {
        pose(0.0)
        for (angle in 0..100 step 5) pose(angle.toDouble(), 180)
        for (angle in 100 downTo 0 step 5) pose(angle.toDouble(), 180)
        pose(0.0)
        assertEquals(1, events.count { it == PaymentTurnDetector.Event.Returned })
    }
    @Test fun newFullTurnCanTriggerWithoutALongCooldown() {
        turn(); turn(-1)
        assertEquals(2, events.count { it == PaymentTurnDetector.Event.Returned })
    }
    @Test fun timedOutTurnAndResetCannotReturn() {
        pose(0.0); pose(100.0); pose(100.0, 16000); pose(0.0)
        assertFalse(events.contains(PaymentTurnDetector.Event.Returned))
        detector.reset(); events.clear(); pose(0.0)
        assertTrue(events.isEmpty())
    }
    @Test fun nonFiniteAndDuplicateSamplesAreIgnored() {
        assertNull(detector.sample(Double.NaN, 0.0, 1.0, 20))
        pose(0.0)
        assertNull(detector.sample(1.0, 0.0, 0.0, time))
        assertTrue(events.isEmpty())
    }
}
