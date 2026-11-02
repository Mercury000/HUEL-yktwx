package com.mercury.yktwx

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
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
    @After fun cleanup() { models.clear(); listeners.cancel(); Dispatchers.resetMain() }

    @Test fun automaticSwitchDoesNotDisableManualChecks() = runTest(dispatcher) {
        val model = model()
        model.configure(automatic = false, allowed = true, inForeground = true)
        runCurrent()
        assertEquals(0, count)
        model.checkManually(); runCurrent()
        assertEquals(1, count)
        model.presentIfAllowed(true)
        assertTrue(model.state.value.visible)
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

    @Test fun disablingAutomaticChecksCancelsTheRequestAndRejectsItsLateResponse() = runTest(dispatcher) {
        val gate = CompletableDeferred<UpdateCheckResult>()
        val model = model { withContext(NonCancellable) { gate.await() } }
        model.configure(true, true, true); runCurrent()
        assertFalse("Automatic checks have no visible loading", model.state.value.checking)
        model.configure(false, true, true)
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

    @Test fun invalidCachedDataIsIgnoredAndNoticesRespectTheAutomaticSwitchAndPreview() = runTest(dispatcher) {
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
}
