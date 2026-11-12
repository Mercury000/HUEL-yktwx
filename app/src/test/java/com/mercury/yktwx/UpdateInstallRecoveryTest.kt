package com.mercury.yktwx

import com.mercury.yktwx.data.fixtureUpdateHttp
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateInstallRecoveryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val bytes = "verified-install-recovery-fixture".toByteArray()
    private val release = UpdateRelease(10, "1.5.0", "更新说明", "https://github.com/release",
        sha256 = sha256(bytes), size = bytes.size.toLong(), packageName = app.packageName,
        urls = (1..5).map { "https://cdn$it.example.com/app.apk" } + "https://github.com/app.apk")
    private val installer = AppUpdateInstaller(app, fixtureUpdateHttp { error("Verified APK must be reused") }) {
        Uri.parse("content://${app.packageName}.updates/verified.apk")
    }
    private val launched = mutableListOf<Intent>()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        val installed = app.packageManager.getPackageInfo(app.packageName, 0)
        installed.setLongVersionCode(9)
        installed.signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1)))) }
        shadowOf(app.packageManager).installPackage(installed)
        val candidate = PackageInfo().apply {
            packageName = app.packageName
            setLongVersionCode(10)
            signingInfo = installed.signingInfo
        }
        val file = installer.apk(release)
        file.writeBytes(bytes)
        shadowOf(app.packageManager).setPackageArchiveInfo(file.absolutePath, candidate)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
    }

    @After fun cleanup() {
        models.clear()
        Dispatchers.resetMain()
    }

    private fun model(candidate: UpdateRelease = release, now: () -> Long = System::currentTimeMillis): AppUpdateViewModel {
        val prefs = app.getSharedPreferences("installation-recovery", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return AppUpdateViewModel(app, updateStoreFixture(prefs, "recovery"), {
            UpdateCheckResult(UpdateCheckReason.UpdateAvailable, candidate)
        }, installer, now).also { models.put("recovery", it) }
    }

    private fun waitForPhase(model: AppUpdateViewModel, phase: UpdateDownloadPhase) {
        dispatcher.scheduler.runCurrent()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (model.state.value.download.phase != phase && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            Thread.yield()
        }
        dispatcher.scheduler.runCurrent()
        assertEquals(phase, model.state.value.download.phase)
    }

    @Test fun authorizationReturnBeforeResumeWaitsThenInstallsAndCancellationCanRetry() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.NeedsAuthorization)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, launched.single().action)
        model.configure(true, true, false)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        model.authorizationReturned(launched::add)
        runCurrent()
        assertEquals(1, launched.size)
        model.configure(true, true, true)
        waitForPhase(model, UpdateDownloadPhase.Installing)
        val intent = launched.last()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals(2, launched.size)
        model.installationReturned(Activity.RESULT_CANCELED)
        assertEquals(UpdateDownloadPhase.Ready, model.state.value.download.phase)
        assertNull(model.state.value.download.error)
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.Installing)
        assertEquals(3, launched.size)
    }

    @Test fun deniedAuthorizationStaysRecoverableAndChangedApkIsNeverInstalled() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.NeedsAuthorization)
        model.authorizationReturned(launched::add)
        assertEquals("请允许安装此来源的应用后重试", model.state.value.download.error)
        assertEquals(UpdateDownloadPhase.NeedsAuthorization, model.state.value.download.phase)
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.NeedsAuthorization)
        assertEquals(2, launched.size)
        installer.apk(release).writeBytes(ByteArray(bytes.size))
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        model.authorizationReturned(launched::add)
        waitForPhase(model, UpdateDownloadPhase.Failed)
        assertFalse(installer.apk(release).exists())
        assertFalse(launched.any { it.action == Intent.ACTION_VIEW })
    }

    @Test fun installerLaunchFailureOffersInstallationRetryAndReusesVerifiedBytes() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        model.downloadAndInstall { throw android.content.ActivityNotFoundException() }
        waitForPhase(model, UpdateDownloadPhase.Failed)
        assertTrue(model.state.value.download.retryInstallation)
        assertEquals("无法打开安装界面，请重试", model.state.value.download.error)
        assertTrue(installer.apk(release).exists())
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.Installing)
        assertEquals(Intent.ACTION_VIEW, launched.single().action)
    }

    @Test fun immediateInstallerHandoffCallbackDoesNotReportFailure() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        model.downloadAndInstall { intent ->
            launched.add(intent)
            model.installationReturned(Activity.RESULT_CANCELED)
        }
        waitForPhase(model, UpdateDownloadPhase.Ready)
        assertEquals(1, launched.size)
        assertNull(model.state.value.download.error)
        assertTrue(installer.apk(release).exists())
    }

    @Test fun explicitInstallerFailureCanRetryAndSuccessClearsTheUpdate() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.Installing)
        // A late authorization callback must not consume the installer's result.
        model.authorizationReturned(launched::add)
        assertEquals(UpdateDownloadPhase.Installing, model.state.value.download.phase)
        model.installationReturned(Activity.RESULT_FIRST_USER)
        assertEquals(UpdateDownloadPhase.Failed, model.state.value.download.phase)
        assertEquals("系统安装失败，请重试", model.state.value.download.error)
        assertTrue(model.state.value.download.retryInstallation)
        assertTrue(installer.apk(release).exists())
        model.downloadAndInstall(launched::add)
        waitForPhase(model, UpdateDownloadPhase.Installing)
        model.installationReturned(Activity.RESULT_OK)
        assertNull(model.state.value.release)
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.noticeVisible)
        assertNull(model.state.value.download.error)
    }

    @Test fun metadataExpiringDuringVerificationCannotLaunchTheInstaller() = runTest(dispatcher) {
        var time = 1_000_000L
        val model = model(release.copy(expiresAt = java.time.Instant.ofEpochMilli(time + 1000))) { time }
        model.configure(true, true, true); runCurrent()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        model.downloadAndInstall(launched::add)
        time += 1000
        waitForPhase(model, UpdateDownloadPhase.Failed)
        assertEquals("更新信息已过期，请重新检查", model.state.value.download.error)
        assertTrue(launched.isEmpty())
    }
}
