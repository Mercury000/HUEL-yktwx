package com.mercury.yktwx

import com.mercury.yktwx.ui.components.DeviceQrFrameBuffer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class DeviceQrFrameBufferTest {
    @Test fun densePlaneRespectsPositionAndReusesStorageForTheNextFrame() {
        val copier = DeviceQrFrameBuffer()
        val source = ByteBuffer.wrap(byteArrayOf(99, 99, 1, 2, 3, 4, 5, 6, 99)).apply {
            position(2)
            limit(8)
        }
        val first = copier.copy(source, 3, 2, 3, 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), first)
        assertEquals(2, source.position())
        assertEquals(8, source.limit())
        val second = copier.copy(ByteBuffer.wrap(byteArrayOf(6, 5, 4, 3, 2, 1)), 3, 2, 3, 1)
        assertSame(first, second)
        assertArrayEquals(byteArrayOf(6, 5, 4, 3, 2, 1), second)
    }

    @Test fun paddedReadOnlyDirectPlaneDoesNotRequirePaddingAfterItsLastRow() {
        val source = ByteBuffer.allocateDirect(8).apply {
            put(byteArrayOf(1, 2, 3, 99, 99, 4, 5, 6))
            flip()
        }.asReadOnlyBuffer()
        val frame = DeviceQrFrameBuffer().copy(source, 3, 2, 5, 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frame)
        assertEquals(0, source.position())
    }

    @Test fun pixelStrideAndRowStrideAreBothRespectedAfterAnOffset() {
        val source = ByteBuffer.wrap(byteArrayOf(99, 99, 1, 99, 2, 99, 3, 99, 99, 4, 99, 5, 99, 6))
            .apply { position(2) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), DeviceQrFrameBuffer().copy(source, 3, 2, 7, 2))
        assertEquals(2, source.position())
    }

    @Test fun resolutionChangesResizeStorageWithoutStalePixels() {
        val copier = DeviceQrFrameBuffer()
        val large = copier.copy(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6)), 3, 2, 3, 1)
        val small = copier.copy(ByteBuffer.wrap(byteArrayOf(8, 9)), 2, 1, 2, 1)
        assertNotSame(large, small)
        assertArrayEquals(byteArrayOf(8, 9), small)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), large)
    }

    @Test fun copiedPixelsSurviveReuseOfTheCameraPlane() {
        val source = ByteBuffer.allocateDirect(6).apply {
            put(byteArrayOf(1, 2, 3, 4, 5, 6))
            flip()
        }
        val frame = DeviceQrFrameBuffer().copy(source, 3, 2, 3, 1)
        source.put(byteArrayOf(9, 9, 9, 9, 9, 9))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frame)
    }

    @Test fun truncatedAndImpossiblePlanesAreRejectedBeforeCopying() {
        val copier = DeviceQrFrameBuffer()
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5))
        assertThrows(IllegalArgumentException::class.java) { copier.copy(source, 3, 2, 3, 1) }
        assertThrows(IllegalArgumentException::class.java) { copier.copy(source, 3, 2, 2, 1) }
        assertThrows(IllegalArgumentException::class.java) { copier.copy(source, Int.MAX_VALUE, 2, Int.MAX_VALUE, 1) }
        assertEquals(0, source.position())
    }
}
