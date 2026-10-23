package com.mercury.yktwx.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mercury.yktwx.PaymentMotionObserver
import com.mercury.yktwx.PaymentTurnDetector
import com.mercury.yktwx.PaymentViewModel

@Composable
internal fun PaymentMotionEffect(enabled: Boolean, username: String, model: PaymentViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(enabled, username, lifecycle, model) {
        val sensor = PaymentMotionObserver(context) { event ->
            when (event) {
                PaymentTurnDetector.Event.Started -> model.paymentTurnStarted()
                PaymentTurnDetector.Event.Returned -> model.paymentTurnReturned()
                PaymentTurnDetector.Event.Cancelled -> model.cancelPaymentDetection()
            }
        }
        fun update() {
            if (enabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                model.setPaymentMotionActive(sensor.start())
            } else {
                sensor.stop(); model.setPaymentMotionActive(false)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose {
            lifecycle.removeObserver(observer)
            sensor.stop(); model.setPaymentMotionActive(false)
        }
    }
}
