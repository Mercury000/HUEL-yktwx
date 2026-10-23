package com.mercury.yktwx

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Optional foreground-only sensor input. Missing hardware simply leaves the payment page unchanged. */
internal class PaymentMotionObserver(context: Context, private val onEvent: (PaymentTurnDetector.Event) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val detector = PaymentTurnDetector()
    private val matrix = FloatArray(9)
    private var registered = false

    fun start(): Boolean {
        stop()
        val manager = manager ?: return false
        val sensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return false
        registered = try { manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME) }
        catch (_: RuntimeException) { false }
        return registered
    }
    fun stop() {
        if (registered) manager?.unregisterListener(this)
        registered = false; detector.reset()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.values.size < 3) return
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Third matrix column maps local +Z (out of the screen) to the reference frame.
        detector.sample(matrix[2].toDouble(), matrix[5].toDouble(), matrix[8].toDouble(), event.timestamp / 1_000_000)
            ?.let(onEvent)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
