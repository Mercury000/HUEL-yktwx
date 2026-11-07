package com.mercury.yktwx.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.alipay.sdk.app.PayTask
import com.mercury.yktwx.LifeUnionPayContract
import com.mercury.yktwx.data.life.LifePaymentLaunch

/** SDK parameters are transient; only the backend confirms the order payment. */
@Composable
internal fun LifePaymentEffect(request: LifePaymentLaunch?, enabled: Boolean,
    launchPayment: (Long, suspend () -> String?) -> Unit, claimPayment: (Long) -> Boolean,
    paymentReturned: (Long, String?) -> Unit) {
    val activity = LocalContext.current.activity()
    var unionPayRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    val unionPay = rememberLauncherForActivityResult(LifeUnionPayContract()) { result ->
        val requestId = result?.requestId ?: unionPayRequestId
        if (requestId != null) {
            if (unionPayRequestId == requestId) unionPayRequestId = null
            paymentReturned(requestId, result?.status)
        }
    }
    LaunchedEffect(request?.id, enabled) {
        val launch = request ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        when (launch.method) {
            "13" -> launchPayment(launch.id) {
                if (activity == null) null
                else runCatching { PayTask(activity).payV2(launch.payload, true)["resultStatus"] }.getOrNull()
            }
            "52" -> if (claimPayment(launch.id)) {
                unionPayRequestId = launch.id
                try { unionPay.launch(launch) }
                catch (_: Exception) {
                    unionPayRequestId = null
                    paymentReturned(launch.id, "launch_failed")
                }
            }
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
