package com.mercury.yktwx

import com.mercury.yktwx.ui.components.DeviceQrFrameBuffer
import com.mercury.yktwx.ui.components.DeviceQrFrameGate
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceQrFrameGateTest {
    @Test fun initializationDoesNotQueueFramesAndTheFirstReadyFrameIsImmediate() {
        val gate = DeviceQrFrameGate()
        assertFalse(gate.tryAcquire(0))
        assertFalse(gate.tryAcquire(500))
        gate.start()
        assertTrue(gate.tryAcquire(501))
    }

    @Test fun fastWorkerCanAcceptFreshFramesAt120MillisInsteadOf400Millis() {
        val gate = DeviceQrFrameGate().apply { start() }
        assertTrue(gate.tryAcquire(0))
        gate.release()
        assertFalse(gate.tryAcquire(119))
        assertTrue(gate.tryAcquire(120))
        gate.release()
        assertTrue(gate.tryAcquire(240))
    }

    @Test fun cameraCannotOverwritePixelsWhileASlowWorkerOwnsThem() {
        val gate = DeviceQrFrameGate().apply { start() }
        val buffer = DeviceQrFrameBuffer()
        val worker = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            assertTrue(gate.tryAcquire(0))
            val pixels = buffer.copy(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, 2, 2, 1)
            val result = worker.submit<ByteArray> {
                entered.countDown()
                try {
                    check(finish.await(5, TimeUnit.SECONDS))
                    pixels.copyOf()
                } finally {
                    gate.release()
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            for (now in listOf(120L, 400L, 800L)) assertFalse(gate.tryAcquire(now))
            finish.countDown()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), result.get(5, TimeUnit.SECONDS))
            assertTrue(gate.tryAcquire(801))
            assertSame(pixels, buffer.copy(ByteBuffer.wrap(byteArrayOf(4, 3, 2, 1)), 2, 2, 2, 1))
        } finally {
            finish.countDown()
            worker.shutdownNow()
        }
    }

    @Test fun invalidPlaneCanReleaseItsSlotSoTheNextFrameIsNotBlocked() {
        val gate = DeviceQrFrameGate().apply { start() }
        assertTrue(gate.tryAcquire(0))
        try {
            assertThrows(IllegalArgumentException::class.java) {
                DeviceQrFrameBuffer().copy(ByteBuffer.wrap(byteArrayOf(1)), 2, 2, 2, 1)
            }
        } finally {
            gate.release()
        }
        assertTrue(gate.tryAcquire(120))
    }

    @Test fun reopeningResetsTheIntervalWithoutTakingPixelsFromAnInFlightWorker() {
        val gate = DeviceQrFrameGate().apply { start() }
        assertTrue(gate.tryAcquire(100))
        gate.resetInterval()
        assertFalse(gate.tryAcquire(101))
        gate.release()
        assertTrue(gate.tryAcquire(102))
    }

    @Test fun disposalCannotBeUndoneByLateInitializationOrWorkerCompletion() {
        val gate = DeviceQrFrameGate().apply { start() }
        assertTrue(gate.tryAcquire(0))
        gate.close()
        gate.release()
        gate.start()
        gate.resetInterval()
        assertFalse(gate.tryAcquire(1000))
    }
}
