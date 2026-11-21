package com.mercury.yktwx

import android.content.Context
import android.view.View
import com.mercury.yktwx.ui.components.DeviceCameraBackend
import com.mercury.yktwx.ui.components.DeviceCameraPhase
import com.mercury.yktwx.ui.components.DeviceCameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeviceCameraSessionTest {
    private class Backend : DeviceCameraBackend {
        lateinit var events: DeviceCameraBackend.Events
        var starts = 0
        var closes = 0
        var recognition = 0
        var controls = 0
        var failStart = false
        override fun createPreviewView(context: Context): View = error("This test exercises ownership, not rendering")
        override fun start(events: DeviceCameraBackend.Events) { this.events = events; starts++; if (failStart) error("start failed") }
        override fun enableRecognition() { recognition++ }
        override fun torch(enabled: Boolean) { controls++ }
        override fun zoomBy(factor: Float) { controls++ }
        override fun toggleQuickZoom() { controls++ }
        override fun close() { closes++ }
    }
    @Test fun previewIsIndependentOfRecognitionAndSuccessfulFirstFrameCancelsStartupDeadline() = runTest {
        val backend = Backend()
        val owner = DeviceCameraSession(backend, {}, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        owner.start(); owner.start(); runCurrent()
        assertEquals(1, backend.starts)
        assertEquals(0, backend.recognition)
        backend.events.previewReady(); runCurrent()
        assertEquals(DeviceCameraPhase.Previewing, owner.state.value.phase)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(1, backend.recognition)
        advanceTimeBy(8_100); runCurrent()
        assertEquals(DeviceCameraPhase.Previewing, owner.state.value.phase)
        assertEquals(0, backend.closes)
        owner.close()
    }
    @Test fun fiftyCancelledOwnersCannotPublishIntoTheReplacementOrRunOldDeadlines() = runTest {
        val scans = mutableListOf<String>()
        val old = (1..50).map {
            val backend = Backend()
            val owner = DeviceCameraSession(backend, scans::add, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
            owner.start(); runCurrent(); owner.close()
            backend to owner
        }
        val current = Backend()
        val owner = DeviceCameraSession(current, scans::add, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        owner.start(); runCurrent()
        old.forEach { (backend, _) ->
            backend.events.failed("old failure"); backend.events.previewReady(); backend.events.scanned("old scan")
        }
        current.events.previewReady(); runCurrent()
        current.events.scanned("current scan"); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("current scan"), scans)
        assertEquals(DeviceCameraPhase.Previewing, owner.state.value.phase)
        assertTrue(old.all { (backend, oldOwner) -> backend.closes == 1 && backend.recognition == 0 && oldOwner.state.value.phase == DeviceCameraPhase.Closed })
        assertEquals(0, current.closes)
        owner.close()
    }
    @Test fun startupFailureClosesExactlyOnceAndLateFramesCannotReviveIt() = runTest {
        val backend = Backend()
        val owner = DeviceCameraSession(backend, {}, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        owner.start(); runCurrent()
        advanceTimeBy(8_100); runCurrent()
        assertEquals(DeviceCameraPhase.Failed, owner.state.value.phase)
        assertEquals(1, backend.closes)
        backend.events.previewReady(); backend.events.flashAvailable(true); runCurrent()
        owner.torch(true); owner.zoomBy(2f); owner.toggleQuickZoom(); owner.close()
        assertEquals(DeviceCameraPhase.Failed, owner.state.value.phase)
        assertEquals(0, backend.controls)
        assertEquals(1, backend.closes)
    }
    @Test fun recognitionPermissionIsStickyAcrossCancelledBackMotionAndCloseCancelsItsFallback() = runTest {
        val backend = Backend()
        val owner = DeviceCameraSession(backend, {}, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        owner.start(); runCurrent()
        owner.allowRecognition(true); owner.allowRecognition(false); owner.allowRecognition(true)
        assertEquals(1, backend.recognition)
        owner.close()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, backend.recognition)
        assertEquals(1, backend.closes)
    }
    @Test fun synchronousBindFailureCannotLeaveTheSessionStarting() = runTest {
        val backend = Backend().apply { failStart = true }
        val owner = DeviceCameraSession(backend, {}, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        owner.start(); runCurrent()
        assertEquals(DeviceCameraPhase.Failed, owner.state.value.phase)
        assertEquals(1, backend.closes)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, backend.recognition)
    }
}
