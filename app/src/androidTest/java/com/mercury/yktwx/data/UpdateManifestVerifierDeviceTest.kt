package com.mercury.yktwx.data

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mercury.yktwx.MainActivity
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/** Runs on Android's actual crypto providers and shares the Python publisher's fixtures. */
@RunWith(AndroidJUnit4::class)
class UpdateManifestVerifierDeviceTest {
    private fun fixture(name: String) = InstrumentationRegistry.getInstrumentation().context.assets
        .open("publisher/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun pythonPublisherSignaturesVerifyOnAndroid() {
        val verifier = UpdateManifestVerifier(fixture("public-key.txt").trim())
        val (pointer, _) = verifier.payload(fixture("latest.json"))
        val manifest = fixture("manifest.json")
        val (release, _) = verifier.manifest(manifest, "beta")
        assertEquals(100001, release.versionCode)
        assertEquals(sha256(manifest.toByteArray()), pointer.stringValue("manifestSha256"))
    }

    @Test fun changedPayloadAndInvalidSignaturesAreRejectedOnAndroid() {
        val verifier = UpdateManifestVerifier(fixture("public-key.txt").trim())
        val original = parseObject(fixture("manifest.json"))
        val payload = Base64.getDecoder().decode(original.stringValue("payload"))
        val changed = Base64.getEncoder().encodeToString(payload + byteArrayOf(0x20))
        assertThrows(IllegalArgumentException::class.java) {
            verifier.payload(original.withField("payload", JsonPrimitive(changed)).toString())
        }
        val signature = Base64.getEncoder().encodeToString(ByteArray(64))
        assertThrows(IllegalArgumentException::class.java) {
            verifier.payload(original.withField("signature", JsonPrimitive(signature)).toString())
        }
    }

    @Test fun malformedPublicKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UpdateManifestVerifier(Base64.getEncoder().encodeToString(ByteArray(44)))
        }
    }

    @Test fun applicationGraphCanCreateMainActivityWithAndroidProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .putExtra("ui_preview", true)).use { scenario ->
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
}
