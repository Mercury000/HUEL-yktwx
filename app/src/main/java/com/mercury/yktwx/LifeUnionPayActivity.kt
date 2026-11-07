package com.mercury.yktwx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.lifecycle.lifecycleScope
import com.mercury.yktwx.data.life.LifePaymentLaunch
import com.unionpay.UPPayAssistEx
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class LifeUnionPayResult(val requestId: Long, val status: String?)

/** Each SDK activity result carries the original request id, including after recreation. */
internal class LifeUnionPayContract : ActivityResultContract<LifePaymentLaunch, LifeUnionPayResult?>() {
    override fun createIntent(context: Context, input: LifePaymentLaunch): Intent =
        Intent(context, LifeUnionPayActivity::class.java)
            .putExtra(LifeUnionPayActivity.RequestId, input.id)
            .putExtra(LifeUnionPayActivity.TransactionNumber, input.payload)

    override fun parseResult(resultCode: Int, intent: Intent?): LifeUnionPayResult? {
        if (intent == null || !intent.hasExtra(LifeUnionPayActivity.RequestId)) return null
        return LifeUnionPayResult(intent.getLongExtra(LifeUnionPayActivity.RequestId, -1),
            intent.getStringExtra(LifeUnionPayActivity.PaymentResult))
    }
}

/** Bridges the SDK's legacy request code to ActivityResultRegistry without persisting its TN. */
open class LifeUnionPayActivity : ComponentActivity() {
    private var sdkStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { complete(null) }
        // The SDK may still be active after recreation; never submit the same TN again here.
        if (savedInstanceState != null) {
            sdkStarted = savedInstanceState.getBoolean(SdkStarted)
            if (!sdkStarted) complete(null)
            return
        }
        val tn = intent.getStringExtra(TransactionNumber)
        if (!intent.hasExtra(RequestId) || tn.isNullOrBlank()) {
            complete("launch_failed")
            return
        }
        try {
            if (startSdk(tn) != 0) complete("launch_failed")
        } catch (_: Exception) {
            complete("launch_failed")
        } catch (_: LinkageError) {
            complete("launch_failed")
        }
        // startPay returns 0 even when routing only shows an unavailable-app toast.
        // Bound that case, and refuse any delayed SDK launch after this bridge finishes.
        lifecycleScope.launch {
            delay(15_000)
            if (!sdkStarted) complete("launch_failed")
        }
    }

    protected open fun startSdk(tn: String): Int = UPPayAssistEx.startPay(this, null, null, tn, "00")

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if (requestCode == SdkRequestCode && (isFinishing || isDestroyed)) return
        super.startActivityForResult(intent, requestCode, options)
        if (requestCode == SdkRequestCode) sdkStarted = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(SdkStarted, sdkStarted)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SdkRequestCode) complete(data?.getStringExtra("pay_result"))
    }

    private fun complete(status: String?) {
        if (isFinishing) return
        setResult(Activity.RESULT_OK, Intent().putExtra(RequestId, intent.getLongExtra(RequestId, -1))
            .putExtra(PaymentResult, status))
        finish()
    }

    internal companion object {
        const val RequestId = "life.unionpay.request_id"
        const val TransactionNumber = "life.unionpay.tn"
        const val PaymentResult = "life.unionpay.result"
        const val SdkRequestCode = 10
        const val SdkStarted = "life.unionpay.started"
    }
}
