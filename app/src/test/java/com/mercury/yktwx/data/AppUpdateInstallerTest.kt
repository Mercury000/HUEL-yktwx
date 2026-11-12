package com.mercury.yktwx.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateInstallerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = "real-apk-byte-fixture".toByteArray()
    private val release = UpdateRelease(10, "1.5.0", "更新", "https://github.com/release", sha256 = sha256(bytes),
        size = bytes.size.toLong(), packageName = context.packageName,
        urls = (1..5).map { "https://cdn$it.example.com/app.apk" } + "https://github.com/app.apk")
    private fun info(packageName: String = context.packageName, version: Long = 10, signature: ByteArray = byteArrayOf(1)): PackageInfo = PackageInfo().apply {
        this.packageName = packageName; setLongVersionCode(version)
        signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(signature))) }
    }
    @Before fun setUp() {
        val installed = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PROVIDERS or android.content.pm.PackageManager.GET_META_DATA)
        installed.setLongVersionCode(9)
        installed.signingInfo = info(version = 9).signingInfo
        shadowOf(context.packageManager).installPackage(installed)
    }
    @Test fun firstBadHashFallsBackAndVerifiedFileIsRehashedBeforeInstall() = runBlocking {
        var requests = 0
        val installer = AppUpdateInstaller(context, UpdateHttp { url ->
            requests++
            object : HttpsURLConnection(url) {
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = bytes.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(if (requests == 1) "wrong-apk-byte-data!!".toByteArray() else bytes)
                override fun disconnect() {}
                override fun connect() {}
                override fun usingProxy() = false
                override fun getCipherSuite() = "test"
                override fun getLocalCertificates(): Array<Certificate>? = null
                override fun getServerCertificates(): Array<Certificate> = emptyArray()
            }
        })
        val file = installer.apk(release)
        file.delete()
        val temporary = file.resolveSibling(file.name + ".download")
        shadowOf(context.packageManager).setPackageArchiveInfo(temporary.absolutePath, info())
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info())
        val states = mutableListOf<UpdateDownloadState>()
        assertEquals(file, installer.download(release) { states += it })
        assertEquals(2, requests)
        assertFalse(temporary.exists())
        assertTrue(states.any { it.phase == UpdateDownloadPhase.Verifying })
        installer.verify(release)
        file.writeBytes(ByteArray(bytes.size))
        assertThrows(Exception::class.java) { runBlocking { installer.verify(release) } }
        assertFalse(file.exists())
    }
    @Test fun wrongPackageVersionOrSignerCannotBeInstalledAndDeletesFile() {
        val installer = AppUpdateInstaller(context)
        val file = installer.apk(release)
        for ((candidate, message) in listOf(info(packageName = "other.app") to "安装包包名不匹配",
            info(version = 11) to "安装包版本不匹配", info(signature = byteArrayOf(2)) to "安装包签名与当前应用不一致")) {
            file.writeBytes(bytes)
            shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, candidate)
            val error = assertThrows(Exception::class.java) { runBlocking { installer.verify(release) } }
            assertEquals(message, error.message)
            assertFalse(file.exists())
        }
    }
    @Test fun authorizationUsesCurrentPackageAndDoesNotDownloadAgainWhenFileMatches() = runBlocking {
        var network = 0
        val installer = AppUpdateInstaller(context, UpdateHttp { network++; error("Must reuse verified bytes") })
        val file = installer.apk(release)
        file.writeBytes(bytes)
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info())
        assertEquals(file, installer.download(release) {})
        assertEquals(0, network)
        installer.verify(release)
        assertEquals("package:${context.packageName}", installer.authorizationIntent().data.toString())
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(installer.authorized())
        // AndroidX FileProvider compares slash paths; real content URI behavior needs an Android device,
        // since the Windows Robolectric filesystem uses backslashes. Verify the permission boundary here.
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.updates",
            android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertEquals(com.mercury.yktwx.R.xml.update_file_paths,
            provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
    }
}
