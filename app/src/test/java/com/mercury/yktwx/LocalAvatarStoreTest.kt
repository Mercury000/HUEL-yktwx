package com.mercury.yktwx

import android.graphics.Bitmap
import android.graphics.Color
import com.mercury.yktwx.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalAvatarStoreTest {
    private val directory = Files.createTempDirectory("local-avatar-test").toFile()
    private val store = LocalAvatarStore(directory)
    @After fun cleanup() { directory.deleteRecursively() }
    private fun image(color: Int) = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).let {
        it.eraseColor(color)
        AvatarImage.encode(it, AvatarCrop()).also { _ -> it.recycle() }
    }

    @Test fun restartAndAccountIsolationPreserveOnlyTheChosenImage() {
        val first = image(Color.RED)
        val second = image(Color.BLUE)
        store.write("../fake-owner", first); store.write("other-owner", second)
        store.write("preview:../fake-owner", second)
        val restarted = LocalAvatarStore(directory)
        assertArrayEquals(first, restarted.read("../fake-owner"))
        assertArrayEquals(second, restarted.read("other-owner"))
        assertNull(restarted.read("unknown-owner"))
        assertEquals(3, directory.listFiles()!!.size)
        assertTrue(directory.listFiles()!!.all { it.length() <= LocalAvatarStore.MaxBytes && !it.name.contains("owner") })
        restarted.remove("../fake-owner")
        assertNull(restarted.read("../fake-owner"))
        assertArrayEquals(second, restarted.read("other-owner"))
        assertArrayEquals(second, restarted.read("preview:../fake-owner"))
    }

    @Test fun cancelledReplacementRetainsThePreviousFile() {
        val first = image(Color.RED)
        store.write("fake-owner", first)
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            store.write("fake-owner", image(Color.BLUE)) { throw kotlinx.coroutines.CancellationException() }
        }
        assertArrayEquals(first, store.read("fake-owner"))
    }

    @Test fun corruptOrOversizedFilesAreDiscardedWithoutReadingFullContents() {
        store.write("fake-owner", image(Color.RED))
        directory.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.read("fake-owner"))
        assertTrue(directory.listFiles()!!.isEmpty())
        val valid = image(Color.RED)
        store.write("fake-owner", valid)
        directory.listFiles()!!.single().writeBytes(valid.copyOf(30))
        assertNull(store.read("fake-owner"))
        assertTrue(directory.listFiles()!!.isEmpty())
        store.write("fake-owner", image(Color.RED))
        directory.listFiles()!!.single().writeBytes(ByteArray(LocalAvatarStore.MaxBytes + 1))
        assertNull(store.read("fake-owner"))
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
