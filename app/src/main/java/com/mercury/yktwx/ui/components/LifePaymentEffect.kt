package com.mercury.yktwx.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.mercury.yktwx.LifeAlipayContract
import com.mercury.yktwx.LifeUnionPayContract
import com.mercury.yktwx.data.life.LifePaymentLaunch

/** SDK parameters are transient; only the backend confirms the order payment. */
@Composable
internal fun LifePaymentEffect(request: LifePaymentLaunch?, enabled: Boolean,
    claimPayment: (Long) -> Boolean,
    paymentReturned: (Long, String?) -> Unit) {
    var alipayRequestId by rememberSaveable { mutableStateOf<Long?>(null) }
    val alipay = rememberLauncherForActivityResult(LifeAlipayContract()) { result ->
        val requestId = result?.requestId ?: alipayRequestId
        if (requestId != null) {
            if (alipayRequestId == requestId) alipayRequestId = null
            paymentReturned(requestId, result?.status)
        }
    }
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
            "13" -> if (claimPayment(launch.id)) {
                alipayRequestId = launch.id
                try { alipay.launch(launch) }
                catch (_: Exception) {
                    alipayRequestId = null
                    paymentReturned(launch.id, "launch_failed")
                }
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
