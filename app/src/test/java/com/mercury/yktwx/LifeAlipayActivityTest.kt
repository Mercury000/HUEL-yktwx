package com.mercury.yktwx

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.life.LifePaymentLaunch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifeAlipayActivityTest {
    private val contract = LifeAlipayContract()
    private fun intent() = contract.createIntent(ApplicationProvider.getApplicationContext(),
        LifePaymentLaunch(71, "wash-13", "test-signed-payload"))
    private fun activity() = Robolectric.buildActivity(AlipayWithoutSdkReturn::class.java, intent())
        .create().start().resume().also { ShadowLooper.shadowMainLooper().idle() }
    private fun result(activity: Activity): LifeAlipayResult? = shadowOf(activity).let {
        contract.parseResult(it.resultCode, it.resultIntent)
    }
    private fun tick(millis: Long) = ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(millis))

    @Test fun contractPreservesSignedPayloadAndOriginalRequestId() {
        val intent = intent()
        assertEquals(LifeAlipayActivity::class.java.name, intent.component?.className)
        assertEquals("test-signed-payload", intent.getStringExtra(LifeAlipayActivity.OrderInfo))
        assertEquals(71L, intent.getLongExtra(LifeAlipayActivity.RequestId, -1))
        assertEquals(LifeAlipayResult(71, "9000"), contract.parseResult(Activity.RESULT_OK,
            Intent().putExtra(LifeAlipayActivity.RequestId, 71L)
                .putExtra(LifeAlipayActivity.PaymentResult, "9000")))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
    }

    @Test fun stalledSdkTimesOutAndLateActivityLaunchesAreRejected() {
        val controller = activity(); val activity = controller.get()
        assertEquals(1, activity.calls)
        tick(14_999); assertFalse(activity.isFinishing)
        tick(1); assertTrue(activity.isFinishing)
        assertEquals(LifeAlipayResult(71, "launch_failed"), result(activity))
        activity.startActivity(Intent(activity, Activity::class.java))
        activity.startActivityForResult(Intent(activity, Activity::class.java), 1, null)
        val lateBinder = Thread { activity.startActivity(Intent(activity, Activity::class.java)) }
        lateBinder.start(); lateBinder.join(1000)
        assertFalse(lateBinder.isAlive)
        ShadowLooper.shadowMainLooper().idle()
        assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }

    @Test fun actualPaymentHandoffHasNoLaunchTimeoutAndReturningWithoutResultIsRecoverable() {
        val controller = activity(); val activity = controller.get()
        activity.startActivity(Intent(activity, Activity::class.java))
        controller.pause().stop()
        tick(60_000); assertFalse(activity.isFinishing)
        controller.start().resume()
        tick(LifeAlipayActivity.ReturnWaitMillis)
        assertTrue(activity.isFinishing)
        assertEquals(LifeAlipayResult(71, null), result(activity))
        controller.pause().stop().destroy()
    }

    @Test fun sdkResultWinsBeforeReturnGraceAndContainsNoOrderPayload() {
        val controller = activity(); val activity = controller.get()
        activity.status.complete("9000")
        ShadowLooper.shadowMainLooper().idle()
        assertTrue(activity.isFinishing)
        assertEquals(LifeAlipayResult(71, "9000"), result(activity))
        assertFalse(shadowOf(activity).resultIntent.hasExtra(LifeAlipayActivity.OrderInfo))
        controller.pause().stop().destroy()
    }

    @Test fun unobservedActivityDispatchStillTimesOutWhenTheBridgeRemainsVisible() {
        val controller = activity(); val activity = controller.get()
        activity.startActivity(Intent(activity, Activity::class.java))
        tick(LifeAlipayActivity.LaunchWaitMillis)
        assertTrue(activity.isFinishing)
        assertEquals(LifeAlipayResult(71, "launch_failed"), result(activity))
        controller.pause().stop().destroy()
    }

    @Test fun stalledTranslucentRoutingActivityIsClosedBeforeTheRequestIsReleased() {
        val controller = activity(); val activity = controller.get()
        val routeIntent = Intent().setClassName(activity.packageName, "com.alipay.sdk.app.APayEntranceActivity")
        activity.startActivity(routeIntent)
        controller.pause().stop()
        val route = Robolectric.buildActivity(AlipayRoutingOnly::class.java).create().start().resume()
        tick(LifeAlipayActivity.LaunchWaitMillis)
        assertTrue(route.get().isFinishing)
        assertTrue(activity.isFinishing)
        assertEquals(LifeAlipayResult(71, "launch_failed"), result(activity))
        route.pause().stop().destroy()
        controller.destroy()
    }
    @Test fun returningToTheTranslucentRouterWithoutSdkCallbackAlsoReleasesTheRequest() {
        val controller = activity(); val activity = controller.get()
        activity.startActivity(Intent().setClassName(activity.packageName, "com.alipay.sdk.app.APayEntranceActivity"))
        controller.pause().stop()
        val route = Robolectric.buildActivity(AlipayRoutingOnly::class.java).create().start().resume()
        route.pause().stop()
        tick(60_000)
        assertFalse(route.get().isFinishing); assertFalse(activity.isFinishing)
        route.start().resume()
        tick(LifeAlipayActivity.ReturnWaitMillis)
        assertTrue(route.get().isFinishing); assertTrue(activity.isFinishing)
        assertEquals(LifeAlipayResult(71, null), result(activity))
        route.pause().stop().destroy()
        controller.destroy()
    }

    @Test fun processRestoreDoesNotReplayPaymentAndBackReturnsUnknown() {
        val restored = Robolectric.buildActivity(AlipayWithoutSdkReturn::class.java, intent()).create(Bundle())
        assertTrue(restored.get().isFinishing)
        assertEquals(0, restored.get().calls)
        assertEquals(LifeAlipayResult(71, null), result(restored.get()))
        restored.destroy()
        val controller = activity()
        controller.get().onBackPressedDispatcher.onBackPressed()
        assertTrue(controller.get().isFinishing)
        assertEquals(LifeAlipayResult(71, null), result(controller.get()))
        controller.pause().stop().destroy()
    }

    @Test fun configurationRecreationBeforeRoutingNeverSubmitsAgain() {
        val controller = activity()
        controller.recreate()
        ShadowLooper.shadowMainLooper().idle()
        assertTrue(controller.get().isFinishing)
        assertEquals(LifeAlipayResult(71, null), result(controller.get()))
        controller.pause().stop().destroy()
    }
}

/** Isolates the vendor boundary: no real SDK, network or payment App is called. */
class AlipayWithoutSdkReturn : LifeAlipayActivity() {
    val status = CompletableDeferred<String?>()
    var calls = 0
    override val sdkDispatcher: CoroutineDispatcher get() = Dispatchers.Main.immediate
    override suspend fun paySdk(payload: String): String? { calls++; return status.await() }
    override fun isRoutingActivity(activity: Activity): Boolean = activity is AlipayRoutingOnly
}

class AlipayRoutingOnly : Activity()
