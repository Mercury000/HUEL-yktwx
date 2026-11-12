package com.mercury.yktwx

import com.mercury.yktwx.data.HttpFixture
import com.mercury.yktwx.data.fixtureUpdateHttp

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.analytics.AppAnalytics
import com.mercury.yktwx.analytics.RecordingAnalyticsBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val prefs = app.getSharedPreferences("update-test", Context.MODE_PRIVATE)
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private var time = 1_000_000L
    private var count = 0
    private val events = mutableListOf<UpdateFeedback>()
    private val listeners = CoroutineScope(SupervisorJob() + dispatcher)
    private val update = UpdateCheckResult(UpdateCheckReason.UpdateAvailable,
        UpdateRelease(4, "1.2.0", "修复与优化", "https://cxt.303889.xyz/download/"))
    private fun model(key: String = "v3", check: suspend () -> UpdateCheckResult = { count++; update }) =
        AppUpdateViewModel(app, UpdateCheckStore(prefs, key), check) { time }
            .also { model ->
                models.put("model-${System.nanoTime()}", model)
                listeners.launch(start = CoroutineStart.UNDISPATCHED) { model.messages.collect { events += it } }
            }
    @Before fun setup() { prefs.edit().clear().commit(); Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); listeners.cancel(); Dispatchers.resetMain(); AppAnalytics.instance = null }

    @Test fun analyticsDeduplicatesAValidatedVersionAcrossRepeatedChecks() = runTest(dispatcher) {
        val statistics = RecordingAnalyticsBackend()
        statistics.start(app)
        val model = model()
        model.configure(false, true, true)
        repeat(3) { model.checkManually(); runCurrent() }
        assertEquals(listOf("update_available"), statistics.events.map { it.first })
        assertEquals(mapOf("target_version" to "1.2.0", "update_channel" to "stable"), statistics.events.single().second)
    }

    @Test fun disablingRemindersKeepsSilentAndManualChecksEnabled() = runTest(dispatcher) {
        val model = model()
        model.configure(reminders = false, allowed = true, inForeground = true)
        runCurrent()
        assertEquals(1, count)
        assertEquals(update.release, model.state.value.release)
        assertFalse(model.state.value.noticeVisible)
        assertFalse(model.state.value.visible)
        assertTrue(events.isEmpty())
        model.checkManually(); runCurrent()
        assertEquals(2, count)
        model.presentIfAllowed(true)
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.noticeVisible)
    }

    @Test fun changingChannelCancelsTheOldCheckAndChecksImmediatelyWithRemindersDisabled() = runTest(dispatcher) {
        val pending = CompletableDeferred<UpdateCheckResult>()
        val model = model { count++; if (count == 1) pending.await() else update }
        model.configure(true, true, true)
        runCurrent()
        assertEquals(1, count)
        model.configure(false, true, true, "beta")
        runCurrent()
        assertEquals(2, count)
        assertFalse(model.state.value.checking)
        pending.complete(UpdateCheckResult(UpdateCheckReason.CurrentOrNewer))
        runCurrent()
        assertEquals(update.release, model.state.value.release)
    }

    @Test fun automaticChecksAreThrottledAcrossForegroundsAndRestartsButNewInstallVersionChecksAgain() = runTest(dispatcher) {
        val first = model()
        first.configure(true, true, true); runCurrent()
        first.configure(true, true, false)
        first.configure(true, true, true); runCurrent()
        val restarted = model()
        restarted.configure(true, true, true); runCurrent()
        assertEquals(1, count)
        model("v4").configure(true, true, true); runCurrent()
        assertEquals(2, count)
        time += UpdateCheckStore.DAY - 1
        restarted.configure(true, true, false)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(2, count)
        time++
        restarted.configure(true, true, true); runCurrent()
        assertEquals(3, count)
    }

    @Test fun disabledRemindersPreserveTheCheckCooldownAcrossForegroundsAndRestarts() = runTest(dispatcher) {
        val first = model()
        first.configure(false, true, true); runCurrent()
        first.configure(false, true, false)
        first.configure(false, true, true); runCurrent()
        val restarted = model()
        restarted.configure(false, true, true); runCurrent()
        assertEquals(1, count)
        time += UpdateCheckStore.DAY
        restarted.configure(false, true, true); runCurrent()
        assertEquals(2, count)
        assertFalse(restarted.state.value.noticeVisible)
        assertFalse(restarted.state.value.visible)
    }

    @Test fun automaticFailureRetriesAfterOneHourAcrossRestartsAndSuccessRestoresDailyInterval() = runTest(dispatcher) {
        var fail = true
        val check: suspend () -> UpdateCheckResult = {
            count++
            if (fail) throw java.io.IOException("offline")
            UpdateCheckResult(UpdateCheckReason.CurrentOrNewer)
        }
        model(check = check).configure(true, true, true); runCurrent()
        assertEquals(1, count)
        assertTrue(events.isEmpty())
        time += UpdateCheckStore.HOUR - 1
        val restarted = model(check = check)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(1, count)
        fail = false
        time++
        restarted.configure(true, true, false)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(2, count)
        assertTrue(events.isEmpty())
        time += UpdateCheckStore.DAY - 1
        restarted.configure(true, true, false)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(2, count)
        time++
        restarted.configure(true, true, false)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(3, count)
    }

    @Test fun failureCooldownStartsWhenTheRequestFailsAndRepeatedFailuresStayHourly() = runTest(dispatcher) {
        val gate = CompletableDeferred<UpdateCheckResult>()
        val first = model { count++; gate.await() }
        first.configure(true, true, true); runCurrent()
        time += UpdateCheckStore.HOUR / 2
        gate.completeExceptionally(java.io.IOException("offline")); runCurrent()
        assertTrue(events.isEmpty())
        time += UpdateCheckStore.HOUR - 1
        val restarted = model { count++; error("offline") }
        restarted.configure(true, true, true); runCurrent()
        assertEquals(1, count)
        time++
        restarted.configure(true, true, false)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(2, count)
        time += UpdateCheckStore.HOUR
        restarted.configure(true, true, false)
        restarted.configure(true, true, true); runCurrent()
        assertEquals(3, count)
        assertTrue(events.isEmpty())
    }

    @Test fun manualTapJoinsAutomaticRequestAndReceivesItsError() = runTest(dispatcher) {
        val gate = CompletableDeferred<UpdateCheckResult>()
        val model = model { count++; gate.await() }
        model.configure(true, true, true); runCurrent()
        model.checkManually(); model.checkManually()
        assertEquals(1, count)
        gate.completeExceptionally(java.io.IOException("offline")); runCurrent()
        assertEquals(listOf(UpdateFeedback.Network), events)
        assertFalse(model.state.value.checking)
    }

    @Test fun automaticFailureStaysSilentAndManualRetryBypassesCooldown() = runTest(dispatcher) {
        val model = model { count++; error("offline") }
        model.configure(true, true, true); runCurrent()
        assertTrue(events.isEmpty())
        model.checkManually(); runCurrent()
        assertEquals(2, count)
        assertEquals(listOf(UpdateFeedback.Failed), events)
    }

    @Test fun disablingRemindersKeepsThePendingCheckAndManualResult() = runTest(dispatcher) {
        val gate = CompletableDeferred<UpdateCheckResult>()
        val model = model { count++; gate.await() }
        model.configure(true, true, true); runCurrent()
        assertFalse("Automatic checks have no visible loading", model.state.value.checking)
        model.configure(false, true, true)
        model.checkManually(); runCurrent()
        assertEquals(1, count)
        assertTrue(model.state.value.checking)
        gate.complete(update); runCurrent()
        assertEquals(update.release, model.state.value.release)
        assertFalse(model.state.value.noticeVisible)
        model.presentIfAllowed(true)
        assertTrue(model.state.value.visible)
        model.configure(true, true, true)
        model.configure(false, true, true)
        assertTrue("Turning off reminders does not close manually opened details", model.state.value.visible)
    }

    @Test fun revokingNetworkConsentCancelsTheRequestAndRejectsItsLateResponse() = runTest(dispatcher) {
        val gate = CompletableDeferred<UpdateCheckResult>()
        val model = model { withContext(NonCancellable) { gate.await() } }
        model.configure(true, true, true); runCurrent()
        assertFalse("Automatic checks have no visible loading", model.state.value.checking)
        model.configure(false, false, true)
        gate.complete(update); runCurrent()
        assertFalse(model.state.value.checking)
        assertNull(model.state.value.release)
        model.presentIfAllowed(true)
        assertFalse(model.state.value.visible)
    }

    @Test fun automaticResultsOnlyShowANoticeAndDetailsRequireATap() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        assertTrue(model.state.value.noticeVisible)
        assertTrue(events.isEmpty())
        model.presentIfAllowed(false)
        assertFalse(model.state.value.visible)
        model.presentIfAllowed(true)
        assertFalse(model.state.value.visible)
        model.openDetails()
        assertTrue(model.state.value.visible)
        model.presentIfAllowed(false)
        assertFalse("Details close when leaving a page with an update entry", model.state.value.visible)
        model.configure(true, true, false)
        model.openDetails()
        model.presentIfAllowed(true)
        assertFalse(model.state.value.visible)
        model.configure(true, true, true)
        model.presentIfAllowed(true)
        assertFalse(model.state.value.visible)
        model.openDetails()
        assertTrue(model.state.value.visible)
        model.dismiss(); model.presentIfAllowed(true)
        assertFalse(model.state.value.visible)
        assertTrue("Closing details keeps the notice available", model.state.value.noticeVisible)
    }

    @Test fun dismissedNoticesStayHiddenAcrossRestartsFor24HoursButManualChecksStillShowDetails() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        model.dismissNotice()
        assertFalse(model.state.value.noticeVisible)
        val restarted = model()
        restarted.configure(true, true, true); runCurrent()
        assertEquals(update.release, restarted.state.value.release)
        assertFalse(restarted.state.value.noticeVisible)
        time += UpdateCheckStore.DAY - 1
        restarted.configure(true, true, true); runCurrent()
        assertFalse(restarted.state.value.noticeVisible)
        restarted.checkManually(); runCurrent()
        restarted.presentIfAllowed(true)
        assertTrue(restarted.state.value.visible)
        assertFalse(restarted.state.value.noticeVisible)
        restarted.dismiss()
        time++
        restarted.configure(true, true, true); runCurrent()
        assertTrue(restarted.state.value.noticeVisible)
        assertFalse(restarted.state.value.visible)
    }

    @Test fun aDifferentReleaseIsNotHiddenByThePreviousDismissal() = runTest(dispatcher) {
        var result = update
        val model = model { result }
        model.configure(true, true, true); runCurrent()
        model.dismissNotice()
        result = update.copy(release = update.release!!.copy(versionCode = 5, versionName = "1.3.0"))
        model.checkManually(); runCurrent()
        assertTrue(model.state.value.noticeVisible)
    }

    @Test fun cachedReleaseSurvivesRestartAndFailureButACurrentResultClearsIt() = runTest(dispatcher) {
        model().configure(true, true, true); runCurrent()
        var fail = true
        val restarted = model {
            count++
            if (fail) throw java.io.IOException("offline")
            UpdateCheckResult(UpdateCheckReason.CurrentOrNewer)
        }
        restarted.configure(true, true, true); runCurrent()
        assertEquals(1, count)
        assertEquals(update.release, restarted.state.value.release)
        assertTrue(restarted.state.value.noticeVisible)
        restarted.checkManually(); runCurrent()
        assertEquals(update.release, restarted.state.value.release)
        assertTrue(restarted.state.value.noticeVisible)
        fail = false
        restarted.checkManually(); runCurrent()
        assertNull(restarted.state.value.release)
        assertFalse(restarted.state.value.noticeVisible)
        assertNull(model().state.value.release)
        assertNull(model("v4").state.value.release)
    }

    @Test fun invalidCachedDataIsIgnoredAndNoticesRespectTheReminderSwitchAndPreview() = runTest(dispatcher) {
        prefs.edit().putString("v3:release", "invalid json").commit()
        val model = model()
        assertNull(model.state.value.release)
        model.configure(true, true, true); runCurrent()
        assertTrue(model.state.value.noticeVisible)
        model.configure(false, true, true)
        assertFalse(model.state.value.noticeVisible)
        model.configure(true, true, true)
        assertTrue(model.state.value.noticeVisible)
        model.configure(true, false, true)
        assertFalse(model.state.value.noticeVisible)
    }

    @Test fun ordinaryReleaseExpiringInTheRunningProcessIsClearedAfterAFailedManualCheck() = runTest(dispatcher) {
        var fail = false
        val expiring = update.copy(release = update.release!!.copy(expiresAt = java.time.Instant.ofEpochMilli(time + 1000)))
        val model = model { if (fail) throw java.io.IOException("offline") else expiring }
        model.configure(true, true, true); runCurrent()
        assertTrue(model.state.value.noticeVisible)
        time += 1000
        fail = true
        model.checkManually(); runCurrent()
        assertNull(model.state.value.release)
        assertFalse(model.state.value.noticeVisible)
        assertFalse(model.state.value.visible)
        assertEquals(listOf(UpdateFeedback.Network), events)
    }

    @Test fun manualDialogsWaitForAPageWithAnUpdateEntry() = runTest(dispatcher) {
        val model = model()
        model.configure(false, true, true)
        model.checkManually(); runCurrent()
        model.presentIfAllowed(false)
        assertFalse(model.state.value.visible)
        model.presentIfAllowed(true)
        assertTrue(model.state.value.visible)
    }

    @Test fun previewAndSignedOutScreensDoNotMakeRequests() = runTest(dispatcher) {
        val model = model()
        model.configure(true, false, true)
        model.checkManually(); runCurrent()
        assertEquals(0, count)
        assertEquals(listOf(UpdateFeedback.Preview), events)
        model.configure(true, true, false)
        model.checkManually(); runCurrent()
        assertEquals(0, count)
    }

    @Test fun missingReleasesAndIncompatibleDevicesDoNotClaimToBeCurrent() = runTest(dispatcher) {
        for ((reason, feedback) in listOf(UpdateCheckReason.CurrentOrNewer to UpdateFeedback.Current,
            UpdateCheckReason.NoPublishedRelease to UpdateFeedback.NoRelease,
            UpdateCheckReason.NoCompatibleRelease to UpdateFeedback.Incompatible)) {
            val model = model { UpdateCheckResult(reason) }
            model.configure(false, true, true)
            model.checkManually(); runCurrent()
            assertEquals(feedback, events.last())
            assertNull(model.state.value.release)
        }
    }

    @Test fun skipSuppressesAutomaticBannerButManualChecksStillShowDetails() = runTest(dispatcher) {
        val model = model()
        model.configure(true, true, true); runCurrent()
        model.openDetails(); model.skip()
        assertFalse(model.state.value.noticeVisible)
        assertFalse(model.state.value.visible)
        model.checkManually(); runCurrent(); model.presentIfAllowed(true)
        assertTrue(model.state.value.visible)
        assertEquals(2, count)
    }

    @Test fun forcedUpdatesBlockEveryDismissalAndSurviveCheckFailureAndSwitchChanges() = runTest(dispatcher) {
        var fail = false
        val forced = update.copy(release = update.release!!.copy(versionCode = 10, maxForcedVersionCode = 9))
        val model = model { if (fail) throw java.io.IOException("offline") else forced }
        model.configure(true, true, true); runCurrent()
        assertTrue(model.state.value.required)
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.noticeVisible)
        model.dismiss(); model.ignore(); model.skip(); model.dismissNotice(); model.presentIfAllowed(false)
        assertTrue(model.state.value.visible)
        model.configure(false, true, true)
        assertTrue(model.state.value.visible)
        fail = true
        model.checkManually(); runCurrent()
        assertTrue(model.state.value.required)
        assertTrue(model.state.value.visible)
        model.configure(false, true, false)
        assertFalse(model.state.value.visible)
        model.configure(false, true, true)
        assertTrue(model.state.value.visible)
    }

    @Test fun disabledRemindersStillDiscoverAndEnforceANewForcedUpdate() = runTest(dispatcher) {
        val forced = update.copy(release = update.release!!.copy(versionCode = 10, maxForcedVersionCode = 9))
        val model = model { count++; forced }
        assertNull(model.state.value.release)
        model.configure(false, true, true); runCurrent()
        assertEquals(1, count)
        assertTrue(model.state.value.required)
        assertTrue(model.state.value.visible)
        assertFalse(model.state.value.noticeVisible)
        model.dismiss(); model.ignore(); model.skip(); model.dismissNotice()
        assertTrue(model.state.value.visible)
    }

    @Test fun manualRecheckKeepsAnActiveDownloadDialogOpenForTheSameApk() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val finish = java.util.concurrent.CountDownLatch(1)
        val installer = AppUpdateInstaller(app, fixtureUpdateHttp { url ->
            object : HttpFixture(url) {
                override fun statusCode(): Int {
                    started.complete(Unit)
                    finish.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    throw java.io.IOException("offline fixture")
                }
                override fun close() { finish.countDown() }

            }
        })
        val result = update.copy(release = update.release!!.copy(versionCode = 10, sha256 = "a".repeat(64), size = 100,
            urls = (1..5).map { "https://cdn$it.example.com/app.apk" } + "https://github.com/app.apk"))
        val checked = CompletableDeferred<UpdateCheckResult>()
        val model = AppUpdateViewModel(app, UpdateCheckStore(prefs, "download-review"), {
            count++
            if (count == 1) result else checked.await()
        }, installer) { time }
        models.put("download-review", model)
        try {
            model.configure(true, true, true); runCurrent()
            model.openDetails()
            model.downloadAndInstall { fail("A blocked fake download cannot open the installer") }
            runCurrent()
            withContext(Dispatchers.IO) { withTimeout(2_000) { started.await() } }
            assertTrue(model.state.value.download.busy)
            model.checkManually(); runCurrent()
            assertTrue(model.state.value.visible)
            checked.complete(result); runCurrent()
            assertTrue(model.state.value.download.busy)
            assertTrue(model.state.value.visible)
            assertEquals(2, count)
            model.configure(false, true, true)
            assertTrue("Disabling update reminders must keep the active download visible", model.state.value.visible)
            model.configure(false, false, true)
            runCurrent()
            assertFalse("Cancelled downloads must leave a usable retry action", model.state.value.download.busy)
            assertEquals(UpdateDownloadPhase.Failed, model.state.value.download.phase)
        } finally { models.clear(); finish.countDown() }
    }
}
