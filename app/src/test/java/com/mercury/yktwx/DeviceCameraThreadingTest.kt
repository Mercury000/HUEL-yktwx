package com.mercury.yktwx

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.components.DeviceCamera
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraDeviceImpl
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.shadows.ShadowSurfaceTexture
import org.robolectric.shadows.StreamConfigurationMapBuilder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [DeviceCameraThreadingTest.RecordingManager::class,
    DeviceCameraThreadingTest.RecordingDevice::class, DeviceCameraThreadingTest.RecordingSession::class,
    DeviceCameraThreadingTest.RecordingTexture::class])
class DeviceCameraThreadingTest {
    companion object {
        private var queryEntered = CountDownLatch(1)
        private var queryGate = CountDownLatch(0)
        private var configureEntered = CountDownLatch(1)
        private var configureGate = CountDownLatch(0)
        private var deviceClosed = CountDownLatch(1)
        private var queryLooper: Looper? = null
        private var configureLooper: Looper? = null
        private var closeLooper: Looper? = null
        private val requests = LinkedBlockingQueue<Pair<Looper, CaptureRequest>>()
    }

    @Implements(CameraManager::class)
    class RecordingManager : ShadowCameraManager() {
        @Implementation public override fun getCameraIdList(): Array<String> {
            queryLooper = Looper.myLooper()
            queryEntered.countDown()
            check(queryGate.await(5, TimeUnit.SECONDS))
            return super.getCameraIdList()
        }
    }

    @Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
    class RecordingDevice : ShadowCameraDeviceImpl() {
        @Implementation public override fun createCaptureSession(outputs: List<Surface>,
            callback: CameraCaptureSession.StateCallback, handler: Handler) {
            configureLooper = Looper.myLooper()
            configureEntered.countDown()
            check(configureGate.await(5, TimeUnit.SECONDS))
            super.createCaptureSession(outputs, callback, handler)
        }

        @Implementation public override fun close() {
            closeLooper = Looper.myLooper()
            super.close()
            deviceClosed.countDown()
        }
    }

    @Implements(className = "android.hardware.camera2.impl.CameraCaptureSessionImpl", isInAndroidSdk = false)
    class RecordingSession : ShadowCameraCaptureSessionImpl() {
        @Implementation public override fun setRepeatingRequest(request: CaptureRequest,
            callback: CameraCaptureSession.CaptureCallback?, handler: Handler?): Int {
            requests.add(requireNotNull(Looper.myLooper()) to request)
            return super.setRepeatingRequest(request, callback, handler)
        }
    }

    // Robolectric's legacy SurfaceTexture has no native released-state tracking.
    @Implements(SurfaceTexture::class)
    class RecordingTexture : ShadowSurfaceTexture() {
        private var released = false
        @Implementation fun release() { released = true }
        @Implementation fun isReleased(): Boolean = released
    }

    private lateinit var camera: DeviceCamera
    private lateinit var surface: SurfaceTexture
    private var flashCallbacks = 0
    private var flashLooper: Looper? = null

    @Before fun setup() {
        queryEntered = CountDownLatch(1); queryGate = CountDownLatch(0)
        configureEntered = CountDownLatch(1); configureGate = CountDownLatch(0)
        deviceClosed = CountDownLatch(1)
        queryLooper = null; configureLooper = null; closeLooper = null
        requests.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val values = Shadow.extract<ShadowCameraCharacteristics>(characteristics)
        values.set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        values.set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        values.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, true)
        values.set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, Range(1f, 8f))
        values.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            StreamConfigurationMapBuilder.newBuilder().addOutputSize(Size(1280, 720))
                .addOutputSize(ImageFormat.YUV_420_888, Size(1280, 720)).build())
        Shadow.extract<RecordingManager>(context.getSystemService(CameraManager::class.java))
            .addCamera("0", characteristics)
        camera = DeviceCamera(context, {}, {}, {
            flashCallbacks++
            flashLooper = Looper.myLooper()
        })
        camera.attach(TextureView(context))
        surface = SurfaceTexture(0)
    }

    @After fun cleanup() {
        queryGate.countDown(); configureGate.countDown()
        camera.dispose()
        camera.onSurfaceTextureDestroyed(surface)
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))
    private fun nextRequest(): Pair<Looper, CaptureRequest> =
        requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "Camera never started its repeating request" }

    @Test fun startupAndControlsUseCameraWorkerWhileUiCallbacksUseMain() {
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        val (looper, initial) = nextRequest()
        assertNotSame(Looper.getMainLooper(), looper)
        assertSame(looper, queryLooper)
        assertSame(looper, configureLooper)
        assertEquals(CaptureRequest.FLASH_MODE_OFF, initial.get(CaptureRequest.FLASH_MODE))
        assertEquals(1f, initial.get(CaptureRequest.CONTROL_ZOOM_RATIO)!!, 0f)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, flashCallbacks)
        assertSame(Looper.getMainLooper(), flashLooper)

        camera.torch(true)
        val (torchLooper, torch) = nextRequest()
        assertSame(looper, torchLooper)
        assertEquals(CaptureRequest.FLASH_MODE_TORCH, torch.get(CaptureRequest.FLASH_MODE))
        camera.zoomBy(3f)
        val (zoomLooper, zoom) = nextRequest()
        assertSame(looper, zoomLooper)
        assertEquals(3f, zoom.get(CaptureRequest.CONTROL_ZOOM_RATIO)!!, 0f)
        camera.toggleQuickZoom()
        assertEquals(1f, nextRequest().second.get(CaptureRequest.CONTROL_ZOOM_RATIO)!!, 0f)
        camera.dispose()
        await(deviceClosed)
        assertSame(looper, closeLooper)
    }

    @Test fun destroyingSurfaceDuringBlockedStartupReturnsImmediatelyAndCancelsUiUpdates() {
        queryGate = CountDownLatch(1)
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        await(queryEntered)
        assertNotSame(Looper.getMainLooper(), queryLooper)
        assertFalse(camera.onSurfaceTextureDestroyed(surface))
        assertFalse(surface.isReleased)
        camera.dispose()
        queryGate.countDown()
        // The worker closes and releases the surface without making the main looper wait.
        val thread = queryLooper!!.thread
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertTrue(surface.isReleased)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, flashCallbacks)
        assertEquals(1L, configureEntered.count)
    }

    @Test fun torchRequestedBeforeSurfaceAvailabilityIsAppliedToFirstCapture() {
        camera.torch(true)
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        assertEquals(CaptureRequest.FLASH_MODE_TORCH, nextRequest().second.get(CaptureRequest.FLASH_MODE))
    }

    @Test fun disposeDuringSessionConfigurationSuppressesPreviewAndClosesOnWorker() {
        configureGate = CountDownLatch(1)
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        await(configureEntered)
        camera.dispose()
        assertFalse(camera.onSurfaceTextureDestroyed(surface))
        assertFalse(surface.isReleased)
        configureGate.countDown()
        await(deviceClosed)
        configureLooper!!.thread.join(5000)
        assertFalse(configureLooper!!.thread.isAlive)
        assertTrue(surface.isReleased)
        assertTrue(requests.isEmpty())
        assertSame(configureLooper, closeLooper)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, flashCallbacks)
    }
}
