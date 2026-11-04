package com.mercury.yktwx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.components.prepareWeChatQrModels
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeChatQrModelsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun packagedModelsAreCopiedWithTheirPinnedContents() {
        val directory = prepareWeChatQrModels(context)
        assertEquals("0.1.0", directory.name)
        val hashes = mapOf(
            "detect.caffemodel" to "cc49b8c9babaf45f3037610fe499df38c8819ebda29e90ca9f2e33270f6ef809",
            "detect.prototxt" to "e8acfc395caf443a47f15686a9b9207b36cb8f7e6ceb8fbaf6466665e68a9466",
            "sr.caffemodel" to "e5d36889d8e6ef2f1c1f515f807cec03979320ac81792cd8fb927c31fd658ae3",
            "sr.prototxt" to "8ae41acba97e8b4a8e741ee350481e49b8e01d787193f470a4c95ee1c02d5b61"
        )
        for ((name, expected) in hashes) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(directory, name).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(name, expected, hash)
        }
    }

    @Test fun truncatedModelIsRepairedBeforeNativeInitialization() {
        val directory = prepareWeChatQrModels(context)
        val model = File(directory, "detect.caffemodel")
        val original = model.readBytes()
        model.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(directory, prepareWeChatQrModels(context))
        assertArrayEquals(original, model.readBytes())
        assertFalse(File(directory, "detect.caffemodel.new").exists())
    }
}
