package com.mercury.yktwx

import android.content.Context
import android.os.Looper
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.components.CameraXDeviceCameraBackend
import com.mercury.yktwx.ui.components.DeviceCameraBackend
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CameraXDeviceCameraBackendTest {
    private class Events : DeviceCameraBackend.Events {
        var errors = 0
        override fun previewReady() {}
        override fun flashAvailable(available: Boolean) {}
        override fun scanned(code: String) {}
        override fun failed(message: String) { errors++ }
    }
    @Test fun previewComposesWithTheAnimatedAndClippedPage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backend = CameraXDeviceCameraBackend(context)
        val view = backend.createPreviewView(context) as PreviewView
        assertEquals(PreviewView.ImplementationMode.COMPATIBLE, view.implementationMode)
        assertEquals(PreviewView.ScaleType.FILL_CENTER, view.scaleType)
        backend.close()
    }
    @Test fun aProviderFutureCompletingAfterExitCannotBindOrReportAnOldError() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var completion: CallbackToFutureAdapter.Completer<ProcessCameraProvider>
        val future = CallbackToFutureAdapter.getFuture<ProcessCameraProvider> { completion = it; "pending camera provider" }
        val backend = CameraXDeviceCameraBackend(context) { future }
        val events = Events()
        backend.start(events)
        backend.close()
        completion.setException(IllegalStateException("provider initialization failed after exit"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, events.errors)
    }
    @Test fun providerFailureOfTheCurrentOwnerIsReportedForRetry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        lateinit var completion: CallbackToFutureAdapter.Completer<ProcessCameraProvider>
        val future = CallbackToFutureAdapter.getFuture<ProcessCameraProvider> { completion = it; "pending camera provider" }
        val backend = CameraXDeviceCameraBackend(context) { future }
        val events = Events()
        backend.start(events)
        completion.setException(IllegalStateException("current provider failure"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, events.errors)
        backend.close()
    }
}
