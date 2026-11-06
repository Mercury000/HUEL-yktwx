package com.mercury.yktwx

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [PaymentMotionObserverTest.CountingSensorManager::class])
class PaymentMotionObserverTest {
    @Implements(SensorManager::class)
    class CountingSensorManager : ShadowSensorManager() {
        var registrations = 0
        var unregistrations = 0

        @Implementation
        public override fun registerListener(listener: SensorEventListener, sensor: Sensor, rate: Int): Boolean {
            registrations++
            return super.registerListener(listener, sensor, rate)
        }

        @Implementation
        public override fun unregisterListener(listener: SensorEventListener) {
            unregistrations++
            super.unregisterListener(listener)
        }
    }

    private fun sensor(): Pair<PaymentMotionObserver, CountingSensorManager> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val shadow = Shadow.extract<CountingSensorManager>(manager)
        shadow.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GAME_ROTATION_VECTOR))
        return PaymentMotionObserver(context) {} to shadow
    }

    @Test fun resumedLifecycleReplayRegistersOnceAndCanRestartAfterStopping() {
        val (sensor, shadow) = sensor()
        val owner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        val observer = LifecycleEventObserver { _, _ ->
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) assertTrue(sensor.start())
            else sensor.stop()
        }
        owner.lifecycle.addObserver(observer)
        assertTrue(sensor.start())
        assertEquals(1, shadow.registrations)
        assertEquals(0, shadow.unregistrations)
        owner.lifecycle.currentState = Lifecycle.State.CREATED
        assertEquals(1, shadow.unregistrations)
        owner.lifecycle.currentState = Lifecycle.State.RESUMED
        assertEquals(2, shadow.registrations)
        assertEquals(1, shadow.unregistrations)
        owner.lifecycle.currentState = Lifecycle.State.DESTROYED
        assertEquals(2, shadow.unregistrations)
        assertTrue(shadow.listeners.isEmpty())
    }

    @Test fun failedRegistrationCanBeRetried() {
        val (sensor, shadow) = sensor()
        shadow.setForceListenersToFail(true)
        assertFalse(sensor.start())
        shadow.setForceListenersToFail(false)
        assertTrue(sensor.start())
        assertTrue(sensor.start())
        assertEquals(2, shadow.registrations)
        assertEquals(0, shadow.unregistrations)
        sensor.stop()
        assertEquals(1, shadow.unregistrations)
        assertTrue(shadow.listeners.isEmpty())
    }
}
