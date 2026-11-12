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
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateSourceSelectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val bytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + "apk-selection-fixture".toByteArray()
    private val release = UpdateRelease(10, "1.5.0", "更新", "https://github.com/release", sha256 = sha256(bytes),
        size = bytes.size.toLong(), packageName = context.packageName,
        urls = (1..5).map { "https://cdn$it.example.com/app.apk" } + "https://github.com/app.apk")
    private fun candidate() = PackageInfo().apply {
        packageName = context.packageName
        setLongVersionCode(10)
        signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1)))) }
    }
    @Before fun setup() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.setLongVersionCode(9)
        installed.signingInfo = candidate().signingInfo
        shadowOf(context.packageManager).installPackage(installed)
    }
    private fun stalledStream(closed: CountDownLatch, entered: CompletableDeferred<Unit>? = null) = object : InputStream() {
        override fun read(): Int = error("Use buffered reads")
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            entered?.complete(Unit)
            closed.await(2, TimeUnit.SECONDS)
            throw IOException("stalled fixture closed")
        }
    }

    @Test fun fastApkBeatsTheFirstStalledSourceAndHttp200HtmlIsRejected() = runBlocking {
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        val slowClosed = CountDownLatch(1)
        val slowEntered = CountDownLatch(1)
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { url ->
            requests.computeIfAbsent(url.host) { AtomicInteger() }.incrementAndGet()
            object : HttpFixture(url) {
                override fun statusCode(): Int {
                    if (url.host.startsWith("cdn1")) slowEntered.countDown()
                    return if (url.host.startsWith("cdn5") || url.host == "github.com") 503 else 200
                }
                override fun contentLength() = when {
                    url.host.startsWith("cdn3") -> 999L
                    url.host.startsWith("cdn4") -> -1L
                    else -> bytes.size.toLong()
                }
                override fun bodyStream() = when {
                    url.host.startsWith("cdn1") -> stalledStream(slowClosed)
                    url.host.startsWith("cdn4") -> ByteArrayInputStream("<html>error page</html>".toByteArray())
                    else -> {
                        slowEntered.await(1, TimeUnit.SECONDS)
                        ByteArrayInputStream(bytes)
                    }
                }
                override fun close() { if (url.host.startsWith("cdn1")) slowClosed.countDown() }
            }
        })
        val file = installer.apk(release)
        file.delete()
        val states = mutableListOf<UpdateDownloadState>()
        val started = System.nanoTime()
        installer.download(release) {
            states += it
            if (it.phase == UpdateDownloadPhase.Verifying) {
                val temporary = file.parentFile!!.listFiles()!!.single { file -> file.name.endsWith(".download") }
                shadowOf(context.packageManager).setPackageArchiveInfo(temporary.absolutePath, candidate())
            }
        }
        assertTrue("A stalled first URL must not delay a ready alternative", System.nanoTime() - started < 2_000_000_000L)
        assertEquals(UpdateDownloadPhase.SelectingSource, states.first().phase)
        assertEquals("One prefix probe and one full transfer use the ready APK source", 2,
            requests.getValue("cdn2.example.com").get())
        assertEquals("The cancelled slow source must not start a full transfer", 1,
            requests.getValue("cdn1.example.com").get())
        assertEquals(1, states.count { it.phase == UpdateDownloadPhase.Downloading && it.percent == 0 })
        assertTrue(file.isFile)
        file.delete()
        Unit
    }

    @Test fun sourceSelectionDeadlineCancelsStalledBodiesAndLeavesNoDownloadFile() = runBlocking {
        val closed = ConcurrentHashMap<String, CountDownLatch>()
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { url ->
            val gate = closed.computeIfAbsent(url.host) { CountDownLatch(1) }
            object : HttpFixture(url) {
                override fun contentLength() = bytes.size.toLong()
                override fun bodyStream() = stalledStream(gate)
                override fun close() { gate.countDown() }
            }
        }, sourceSelectionMillis = 120)
        installer.apk(release).delete()
        val started = System.nanoTime()
        val error = assertThrows(IOException::class.java) {
            runBlocking { withTimeout(2_000) { installer.download(release) {} } }
        }
        assertEquals("暂时没有可用的下载来源，请检查网络后重试", error.message)
        assertTrue("Selection is bounded rather than waiting for six serial read timeouts",
            System.nanoTime() - started < 1_500_000_000L)
        withTimeout(1_000) { while (closed.values.any { it.count != 0L }) delay(10) }
        assertFalse(installer.apk(release).exists())
        assertFalse(installer.apk(release).parentFile!!.listFiles()!!.any { it.name.endsWith(".download") })
    }

    @Test fun cachedApkIsRehashedWithoutProbingTheNetwork() = runBlocking {
        val requests = AtomicInteger()
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { requests.incrementAndGet(); error("No network expected") })
        val file = installer.apk(release)
        file.writeBytes(bytes)
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, candidate())
        assertEquals(file, installer.download(release) {})
        assertEquals(0, requests.get())
        file.delete()
        Unit
    }

    @Test fun signedManifestIncompatibilityIsRejectedBeforeAnySourceProbe() = runBlocking {
        val requests = AtomicInteger()
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { requests.incrementAndGet(); error("No network expected") })
        installer.apk(release).delete()
        for ((invalid, message) in listOf(
            release.copy(signerSha256 = sha256(byteArrayOf(2))) to "安装包签名与当前应用不一致",
            release.copy(minSdk = 99) to "当前系统不支持此更新，请重新检查",
            release.copy(packageName = "other.app") to "安装包包名不匹配",
            release.copy(versionCode = 9) to "安装包版本不匹配",
        )) {
            val error = assertThrows(UpdateApkIdentityFailure::class.java) { runBlocking { installer.download(invalid) {} } }
            assertEquals(message, error.message)
        }
        assertEquals(0, requests.get())
    }

    @Test fun parentCancellationStopsSelectionWithoutStartingAFullTransfer() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val closed = ConcurrentHashMap<String, CountDownLatch>()
        val requests = ConcurrentHashMap<String, AtomicInteger>()
        val installer = AppUpdateInstaller(context, fixtureUpdateHttp { url ->
            requests.computeIfAbsent(url.host) { AtomicInteger() }.incrementAndGet()
            val gate = closed.computeIfAbsent(url.host) { CountDownLatch(1) }
            object : HttpFixture(url) {
                override fun contentLength() = bytes.size.toLong()
                override fun bodyStream() = stalledStream(gate, entered)
                override fun close() { gate.countDown() }
            }
        })
        installer.apk(release).delete()
        val job = launch { installer.download(release) {} }
        try {
            withTimeout(1_000) { entered.await() }
            withTimeout(1_000) { job.cancelAndJoin() }
            withTimeout(1_000) { while (closed.values.any { it.count != 0L }) delay(10) }
            assertTrue(job.isCancelled)
            assertTrue(requests.values.all { it.get() == 1 })
            assertFalse(installer.apk(release).exists())
        } finally { job.cancel() }
    }
}
