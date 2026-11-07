package com.mercury.yktwx

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.alipay.sdk.app.PayTask
import com.mercury.yktwx.data.life.LifePaymentLaunch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

internal data class LifeAlipayResult(val requestId: Long, val status: String?)

internal class LifeAlipayContract : ActivityResultContract<LifePaymentLaunch, LifeAlipayResult?>() {
    override fun createIntent(context: Context, input: LifePaymentLaunch): Intent =
        Intent(context, LifeAlipayActivity::class.java)
            .putExtra(LifeAlipayActivity.RequestId, input.id)
            .putExtra(LifeAlipayActivity.OrderInfo, input.payload)

    override fun parseResult(resultCode: Int, intent: Intent?): LifeAlipayResult? {
        if (intent == null || !intent.hasExtra(LifeAlipayActivity.RequestId)) return null
        return LifeAlipayResult(intent.getLongExtra(LifeAlipayActivity.RequestId, -1),
            intent.getStringExtra(LifeAlipayActivity.PaymentResult))
    }
}

/** Retains a single SDK invocation and its result across Activity recreation. */
class LifeAlipaySession : ViewModel() {
    internal var started = false
    internal var dispatched = false
    internal var handedOff = false
    internal var routing = false
    internal var routingActivity: Activity? = null
    @Volatile internal var closed = false
    internal val result = MutableStateFlow<LifeAlipayResult?>(null)
    private var sdkJob: Job? = null

    internal fun start(requestId: Long, dispatcher: CoroutineDispatcher, execute: suspend () -> String?) {
        started = true
        sdkJob = viewModelScope.launch {
            val status = try { withContext(dispatcher) { execute() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { "launch_failed" }
            catch (_: LinkageError) { "launch_failed" }
            if (!closed) result.value = LifeAlipayResult(requestId, status)
        }
    }

    internal fun close() { closed = true; sdkJob?.cancel(); routingActivity = null }
}

/** Bounds routing stalls while allowing an opened payment UI to finish normally. */
open class LifeAlipayActivity : ComponentActivity() {
    private val payment: LifeAlipaySession by viewModels()
    private var orderInfo: String? = null
    private var returnWait: Job? = null
    private var resumedRouting: Activity? = null
    private val routingObserver = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (!payment.closed && payment.routing && isRoutingActivity(activity)) payment.routingActivity = activity
        }
        override fun onActivityPaused(activity: Activity) {
            if (activity === payment.routingActivity) {
                payment.handedOff = true
                resumedRouting = null
                returnWait?.cancel()
            }
        }
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) {
            if (activity !== payment.routingActivity) return
            resumedRouting = activity
            if (payment.handedOff && !payment.closed) {
                returnWait = lifecycleScope.launch {
                    delay(ReturnWaitMillis)
                    if (!payment.closed && resumedRouting === activity) {
                        activity.finish()
                        complete(null)
                    }
                }
            }
        }
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (activity === payment.routingActivity) {
                payment.routingActivity = null
                resumedRouting = null
                returnWait?.cancel()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { complete(null) }
        if (!intent.hasExtra(RequestId)) { complete("launch_failed"); return }
        // A fresh process cannot reconnect the SDK's in-memory callback; query the order instead.
        if (savedInstanceState != null && (!payment.started || !payment.dispatched)) {
            complete(null)
            return
        }
        orderInfo = intent.getStringExtra(OrderInfo)?.takeIf { it.isNotBlank() }
        if (orderInfo == null) { complete("launch_failed"); return }
        application.registerActivityLifecycleCallbacks(routingObserver)
        lifecycleScope.launch { payment.result.collect { it?.let { result -> complete(result.status) } } }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || payment.closed) return
        if (!payment.started) {
            val payload = orderInfo ?: return
            payment.start(intent.getLongExtra(RequestId, -1), sdkDispatcher) { paySdk(payload) }
            lifecycleScope.launch {
                delay(LaunchWaitMillis)
                if (!payment.handedOff) {
                    // The SDK's translucent forwarding Activity has no interactive payment UI.
                    payment.routingActivity?.finish()
                    complete("launch_failed")
                }
            }
        } else if (payment.dispatched) {
            // The user returned, but the SDK may have lost its result callback.
            returnWait = lifecycleScope.launch {
                delay(ReturnWaitMillis)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) complete(null)
            }
        }
    }

    override fun onPause() {
        returnWait?.cancel()
        if (payment.dispatched && !payment.routing) payment.handedOff = true
        super.onPause()
    }

    protected open suspend fun paySdk(payload: String): String? = runInterruptible {
        PayTask(this).payV2(payload, true)["resultStatus"]
    }

    protected open val sdkDispatcher: CoroutineDispatcher get() = Dispatchers.IO

    protected open fun isRoutingActivity(activity: Activity): Boolean =
        activity.javaClass.name == RoutingActivity

    override fun startActivity(intent: Intent, options: Bundle?) = dispatchSdkActivity(intent) {
        super.startActivity(intent, options)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) = dispatchSdkActivity(intent) {
        super.startActivityForResult(intent, requestCode, options)
    }

    private fun dispatchSdkActivity(intent: Intent, dispatch: () -> Unit) {
        // Binder callbacks can arrive off the main thread and after a routing timeout.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { dispatchSdkActivity(intent, dispatch) }
            return
        }
        if (payment.closed || isFinishing || isDestroyed) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { complete("launch_failed"); return }
        try {
            payment.routing = intent.component?.className == RoutingActivity
            dispatch()
            payment.dispatched = true
        } catch (_: Exception) { complete("launch_failed") }
    }

    private fun complete(status: String?) {
        if (isFinishing) return
        payment.close()
        setResult(Activity.RESULT_OK, Intent().putExtra(RequestId, intent.getLongExtra(RequestId, -1))
            .putExtra(PaymentResult, status))
        finish()
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(routingObserver)
        if (!isChangingConfigurations) payment.close()
        super.onDestroy()
    }

    internal companion object {
        const val RequestId = "life.alipay.request_id"
        const val OrderInfo = "life.alipay.order_info"
        const val PaymentResult = "life.alipay.result"
        const val LaunchWaitMillis = 15_000L
        const val ReturnWaitMillis = 1_000L
        private const val RoutingActivity = "com.alipay.sdk.app.APayEntranceActivity"
    }
}
