package com.mercury.yktwx.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.URL

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
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { url ->
            requests++
            object : HttpFixture(url) {
                override fun statusCode() = 200
                override fun contentLength() = bytes.size.toLong()
                override fun bodyStream() = ByteArrayInputStream(if (requests == 1) "wrong-apk-byte-data!!".toByteArray() else bytes)
                override fun close() {}

            }
        })
        val file = installer.apk(release)
        file.delete()
        lateinit var temporary: java.io.File
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info())
        val states = mutableListOf<UpdateDownloadState>()
        assertEquals(file, installer.download(release) {
            states += it
            if (it.phase == UpdateDownloadPhase.Verifying) {
                temporary = file.parentFile!!.listFiles()!!.single { candidate -> candidate.name.endsWith(".download") }
                shadowOf(context.packageManager).setPackageArchiveInfo(temporary.absolutePath, info())
            }
        })
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
    @Test fun cancellationCleansPartialFileAfterTheBodyWorkerClosesAndDoesNotTryAnotherCdn() = runBlocking {
        val reading = CompletableDeferred<Unit>()
        val stopped = java.util.concurrent.CountDownLatch(1)
        val releaseWorker = java.util.concurrent.CountDownLatch(1)
        var requests = 0
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { url ->
            requests++
            object : HttpFixture(url) {
                override fun bodyStream() = object : java.io.InputStream() {
                    var first = true
                    override fun read(): Int = error("Use buffered reads")
                    override fun read(target: ByteArray, offset: Int, length: Int): Int {
                        if (first) { first = false; target[offset] = 1; return 1 }
                        reading.complete(Unit)
                        stopped.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        releaseWorker.await(2, java.util.concurrent.TimeUnit.SECONDS)
                        throw java.io.IOException("Cancelled read")
                    }
                }
                override fun close() { stopped.countDown() }
            }
        })
        installer.apk(release).delete()
        val job = launch { installer.download(release) {} }
        try {
            withTimeout(2_000) { reading.await() }
            val temporary = installer.apk(release).parentFile!!.listFiles()!!.single { it.name.endsWith(".download") }
            withTimeout(1_000) { job.cancelAndJoin() }
            releaseWorker.countDown()
            withTimeout(2_000) { while (temporary.exists()) delay(10) }
            assertEquals(1, requests)
            assertFalse(installer.apk(release).exists())
        } finally { releaseWorker.countDown(); job.cancel() }
    }
    @Test fun authorizationUsesCurrentPackageAndDoesNotDownloadAgainWhenFileMatches() = runBlocking {
        var network = 0
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { network++; error("Must reuse verified bytes") })
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
