package com.mercury.yktwx.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.alipay.sdk.app.PayTask
import com.mercury.yktwx.data.life.LifeAlipayLaunch

/** Signed SDK payload stays in memory; only the backend confirms the order payment. */
@Composable
internal fun LifeAlipayEffect(request: LifeAlipayLaunch?, enabled: Boolean, launchPayment: (Long, suspend () -> String?) -> Unit) {
    val activity = LocalContext.current.activity()
    LaunchedEffect(request?.id, enabled) {
        val launch = request ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        launchPayment(launch.id) {
            if (activity == null) null
            else runCatching { PayTask(activity).payV2(launch.payload, true)["resultStatus"] }.getOrNull()
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
