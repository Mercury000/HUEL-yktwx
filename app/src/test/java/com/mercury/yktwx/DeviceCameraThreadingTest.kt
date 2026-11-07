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
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowCameraCaptureSessionImpl
import org.robolectric.shadows.ShadowCameraCharacteristics
import org.robolectric.shadows.ShadowCameraDeviceImpl
import org.robolectric.shadows.ShadowCameraManager
import org.robolectric.shadows.ShadowSurfaceTexture
import org.robolectric.shadows.StreamConfigurationMapBuilder
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        private var openLooper: Looper? = null
        private val idQueries = AtomicInteger()
        private val characteristicsQueries = LinkedBlockingQueue<String>()
        private val requests = LinkedBlockingQueue<Pair<Looper, CaptureRequest>>()
        private val openCalls = AtomicInteger()
        private val configureCalls = AtomicInteger()
        private var holdOpened = false
        private var holdClosed = false
        private val openedCallbacks = LinkedBlockingQueue<() -> Unit>()
        private val closedCallbacks = LinkedBlockingQueue<() -> Unit>()
    }

    @Implements(CameraManager::class)
    class RecordingManager : ShadowCameraManager() {
        @RealObject private lateinit var real: CameraManager
        @Implementation fun openCamera(id: String, callback: CameraDevice.StateCallback, handler: Handler) {
            openLooper = Looper.myLooper()
            openCalls.incrementAndGet()
            val forwarding = object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (holdOpened) openedCallbacks.add { handler.post { callback.onOpened(device) } }
                    else callback.onOpened(device)
                }
                override fun onDisconnected(device: CameraDevice) = callback.onDisconnected(device)
                override fun onError(device: CameraDevice, error: Int) = callback.onError(device, error)
                override fun onClosed(device: CameraDevice) {
                    if (holdClosed) closedCallbacks.add { handler.post { callback.onClosed(device) } }
                    else callback.onClosed(device)
                }
            }
            Shadow.directlyOn<Unit, CameraManager>(real, CameraManager::class.java, "openCamera",
                ReflectionHelpers.ClassParameter.from(String::class.java, id),
                ReflectionHelpers.ClassParameter.from(CameraDevice.StateCallback::class.java, forwarding),
                ReflectionHelpers.ClassParameter.from(Handler::class.java, handler))
        }
        @Implementation public override fun getCameraIdList(): Array<String> {
            idQueries.incrementAndGet()
            queryLooper = Looper.myLooper()
            queryEntered.countDown()
            check(queryGate.await(5, TimeUnit.SECONDS))
            return super.getCameraIdList()
        }
        @Implementation public override fun getCameraCharacteristics(cameraId: String): CameraCharacteristics {
            characteristicsQueries.add(cameraId)
            return super.getCameraCharacteristics(cameraId)
        }
    }

    @Implements(className = "android.hardware.camera2.impl.CameraDeviceImpl", isInAndroidSdk = false)
    class RecordingDevice : ShadowCameraDeviceImpl() {
        @Implementation public override fun createCaptureSession(outputs: List<Surface>,
            callback: CameraCaptureSession.StateCallback, handler: Handler) {
            configureLooper = Looper.myLooper()
            configureCalls.incrementAndGet()
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
    private var flashAvailable: Boolean? = null

    @Before fun setup() {
        queryEntered = CountDownLatch(1); queryGate = CountDownLatch(0)
        configureEntered = CountDownLatch(1); configureGate = CountDownLatch(0)
        deviceClosed = CountDownLatch(1)
        queryLooper = null; configureLooper = null; closeLooper = null; openLooper = null
        requests.clear()
        openCalls.set(0); configureCalls.set(0); holdOpened = false; holdClosed = false
        openedCallbacks.clear(); closedCallbacks.clear()
        idQueries.set(0); characteristicsQueries.clear()
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadow.extract<RecordingManager>(context.getSystemService(CameraManager::class.java))
            .addCamera("0", characteristics(CameraCharacteristics.LENS_FACING_BACK))
        camera = DeviceCamera(context, {}, {}, {
            flashCallbacks++
            flashLooper = Looper.myLooper()
            flashAvailable = it
        })
        camera.attach(TextureView(context))
        surface = SurfaceTexture(0)
    }

    private fun characteristics(facing: Int, flash: Boolean = true): CameraCharacteristics {
        val characteristics = ShadowCameraCharacteristics.newCameraCharacteristics()
        val values = Shadow.extract<ShadowCameraCharacteristics>(characteristics)
        values.set(CameraCharacteristics.LENS_FACING, facing)
        values.set(CameraCharacteristics.SENSOR_ORIENTATION, 90)
        values.set(CameraCharacteristics.FLASH_INFO_AVAILABLE, flash)
        values.set(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE, Range(1f, 8f))
        values.set(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            StreamConfigurationMapBuilder.newBuilder().addOutputSize(Size(1280, 720))
                .addOutputSize(ImageFormat.YUV_420_888, Size(1280, 720)).build())
        return characteristics
    }

    @After fun cleanup() {
        queryGate.countDown(); configureGate.countDown()
        holdOpened = false; holdClosed = false
        while (true) (openedCallbacks.poll() ?: break).invoke()
        while (true) (closedCallbacks.poll() ?: break).invoke()
        camera.dispose()
        camera.onSurfaceTextureDestroyed(surface)
        ReflectionHelpers.getField<Handler>(camera, "cameraWorker").looper.thread.join(5000)
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(5, TimeUnit.SECONDS))
    private fun nextRequest(): Pair<Looper, CaptureRequest> =
        requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "Camera never started its repeating request" }

    private fun drainCameraWorker(camera: DeviceCamera) {
        val drained = CountDownLatch(1)
        ReflectionHelpers.getField<Handler>(camera, "cameraWorker").post { drained.countDown() }
        await(drained)
    }

    @Test fun preparingDuringNavigationOpensHardwareOnWorkerAndWaitsForPreviewSurface() {
        holdOpened = true
        camera.prepare()
        camera.prepare()
        val opened = requireNotNull(openedCallbacks.poll(5, TimeUnit.SECONDS))
        opened()
        drainCameraWorker(camera)
        assertEquals(1, idQueries.get())
        // The fake device queries its characteristics once as well.
        assertEquals(listOf("0", "0"), characteristicsQueries.toList())
        assertEquals(1, openCalls.get())
        assertNotSame(Looper.getMainLooper(), openLooper)
        assertSame(queryLooper, openLooper)
        assertEquals(0, configureCalls.get())
        assertTrue(requests.isEmpty())
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        nextRequest()
        assertEquals(1, idQueries.get())
        assertEquals(1, openCalls.get())
        assertEquals(1, configureCalls.get())
    }

    @Test fun surfaceReadyBeforeWarmedHardwareConfiguresOnceWithoutReopening() {
        holdOpened = true
        camera.prepare()
        val opened = requireNotNull(openedCallbacks.poll(5, TimeUnit.SECONDS))
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        drainCameraWorker(camera)
        assertEquals(1, openCalls.get())
        assertEquals(0, configureCalls.get())
        assertTrue(requests.isEmpty())
        opened()
        nextRequest()
        assertEquals(1, openCalls.get())
        assertEquals(1, configureCalls.get())
    }

    @Test fun reopenWaitsForCancelledOpenAndItsClosedCallbackBeforeOpeningNextCamera() {
        holdOpened = true; holdClosed = true
        // Exit during navigation, before there is even a preview surface to destroy.
        camera.prepare()
        val opened = requireNotNull(openedCallbacks.poll(5, TimeUnit.SECONDS))
        camera.dispose()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val replacement = DeviceCamera(context, {}, {}, {})
        val replacementSurface = SurfaceTexture(0)
        try {
            replacement.attach(TextureView(context))
            replacement.onSurfaceTextureAvailable(replacementSurface, 1080, 2400)
            drainCameraWorker(replacement)
            assertEquals("A cancelled pending open still owns the hardware", 1, openCalls.get())
            opened()
            val closed = requireNotNull(closedCallbacks.poll(5, TimeUnit.SECONDS))
            drainCameraWorker(replacement)
            assertEquals("close() is asynchronous; wait for onClosed", 1, openCalls.get())
            assertTrue(requests.isEmpty())
            assertTrue(ReflectionHelpers.getField<Handler>(camera, "cameraWorker").looper.thread.isAlive)
            holdOpened = false; holdClosed = false
            closed()
            nextRequest()
            assertEquals(2, openCalls.get())
            val oldThread = ReflectionHelpers.getField<Handler>(camera, "cameraWorker").looper.thread
            oldThread.join(5000)
            assertFalse(oldThread.isAlive)
        } finally {
            replacement.dispose()
            replacement.onSurfaceTextureDestroyed(replacementSurface)
            ReflectionHelpers.getField<Handler>(replacement, "cameraWorker").looper.thread.join(5000)
        }
    }

    @Test fun exitAfterHardwareWarmupClosesItWithoutEverCreatingAStream() {
        holdOpened = true
        camera.prepare()
        requireNotNull(openedCallbacks.poll(5, TimeUnit.SECONDS)).invoke()
        drainCameraWorker(camera)
        camera.dispose()
        await(deviceClosed)
        val cameraThread = ReflectionHelpers.getField<Handler>(camera, "cameraWorker").looper.thread
        cameraThread.join(5000)
        assertFalse(cameraThread.isAlive)
        assertSame(openLooper, closeLooper)
        assertEquals(0, configureCalls.get())
        assertTrue(requests.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, flashCallbacks)
    }

    @Test fun startupAndControlsUseCameraWorkerWhileUiCallbacksUseMain() {
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        val (looper, initial) = nextRequest()
        assertNotSame(Looper.getMainLooper(), looper)
        assertSame(looper, queryLooper)
        assertSame(looper, configureLooper)
        assertEquals(1, idQueries.get())
        // Robolectric also queries once internally when it constructs the fake opened device.
        assertEquals(listOf("0", "0"), characteristicsQueries.toList())
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

    @Test fun rearSelectionReusesItsCharacteristicsAndStopsAtFirstRearCamera() {
        val manager = Shadow.extract<RecordingManager>(ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(CameraManager::class.java))
        manager.removeCamera("0")
        manager.addCamera("0", characteristics(CameraCharacteristics.LENS_FACING_FRONT, flash = false))
        manager.addCamera("1", characteristics(CameraCharacteristics.LENS_FACING_BACK))
        manager.addCamera("2", characteristics(CameraCharacteristics.LENS_FACING_BACK, flash = false))
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        nextRequest()
        assertEquals(1, idQueries.get())
        assertEquals(listOf("0", "1", "1"), characteristicsQueries.toList())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(true, flashAvailable)
    }

    @Test fun noRearCameraFallsBackToFirstCameraWithoutQueryingListAgain() {
        val manager = Shadow.extract<RecordingManager>(ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(CameraManager::class.java))
        manager.removeCamera("0")
        manager.addCamera("0", characteristics(CameraCharacteristics.LENS_FACING_FRONT, flash = false))
        manager.addCamera("1", characteristics(CameraCharacteristics.LENS_FACING_EXTERNAL))
        camera.onSurfaceTextureAvailable(surface, 1080, 2400)
        nextRequest()
        assertEquals(1, idQueries.get())
        assertEquals(listOf("0", "1", "0"), characteristicsQueries.toList())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(false, flashAvailable)
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
