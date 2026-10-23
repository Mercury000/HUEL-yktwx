package com.mercury.yktwx

import kotlin.math.acos
import kotlin.math.sqrt

/** Recognizes a complete screen-away/screen-back cycle, independent of hand and rotation axis. */
internal class PaymentTurnDetector {
    enum class Event { Started, Returned, Cancelled }
    private data class Direction(val x: Double, val y: Double, val z: Double) {
        fun angle(other: Direction) = Math.toDegrees(acos((x * other.x + y * other.y + z * other.z).coerceIn(-1.0, 1.0)))
    }
    private var reference: Direction? = null
    private var stableAnchor: Direction? = null
    private var stableSince = 0L
    private var last: Direction? = null
    private var lastTime = -1L
    private var startedAt: Long? = null
    private var awaySince: Long? = null
    private var shown = false
    private var maximumAngle = 0.0
    private var returnSince: Long? = null

    fun reset() {
        reference = null; stableAnchor = null; last = null; lastTime = -1
        startedAt = null; awaySince = null; shown = false; maximumAngle = 0.0; returnSince = null
    }

    /** timestampMillis is monotonic sensor time; input is the screen's outward unit normal. */
    fun sample(x: Double, y: Double, z: Double, timestampMillis: Long): Event? {
        val length = sqrt(x * x + y * y + z * z)
        if (!length.isFinite() || length < 0.0001 || timestampMillis <= lastTime) return null
        val direction = Direction(x / length, y / length, z / length)
        val speed = last?.angle(direction)?.times(1000.0)?.div((timestampMillis - lastTime).coerceAtLeast(1)) ?: 0.0
        last = direction; lastTime = timestampMillis
        val origin = reference
        if (origin == null) {
            if (stable(direction, timestampMillis, 200)) reference = direction
            return null
        }
        val angle = origin.angle(direction)
        if (startedAt == null) {
            if (angle >= 40.0) {
                startedAt = timestampMillis; maximumAngle = angle; stableAnchor = null
                awaySince = timestampMillis.takeIf { angle >= 65.0 }
                return Event.Started
            }
            // Rebase only on a genuinely stable modest posture adjustment, never follow a turn frame by frame.
            if (angle in 8.0..35.0 && stable(direction, timestampMillis, 600)) {
                reference = direction; stableAnchor = null
            } else if (angle < 8.0) stableAnchor = null
            return null
        }
        if (timestampMillis - startedAt!! > 15_000) { reset(); return Event.Cancelled }
        maximumAngle = maxOf(maximumAngle, angle)
        if (!shown) {
            if (angle >= 65.0) {
                if (awaySince == null) awaySince = timestampMillis
                if (timestampMillis - awaySince!! >= 100) shown = true
            } else awaySince = null
            if (!shown && angle <= 25.0) { reset(); return Event.Cancelled }
        }
        val returnAngle = (maximumAngle * 0.25).coerceIn(25.0, 35.0)
        if (shown && angle <= returnAngle && speed <= 120.0) {
            if (returnSince == null) returnSince = timestampMillis
            if (timestampMillis - returnSince!! >= 100) {
                // A new reference and a new complete turn are required for another event.
                reset(); return Event.Returned
            }
        } else returnSince = null
        return null
    }

    private fun stable(direction: Direction, time: Long, duration: Long): Boolean {
        val anchor = stableAnchor
        if (anchor == null || anchor.angle(direction) > 6.0) {
            stableAnchor = direction; stableSince = time; return false
        }
        return time - stableSince >= duration
    }
}
