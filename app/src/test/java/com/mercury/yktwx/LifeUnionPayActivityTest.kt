package com.mercury.yktwx

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.life.LifePaymentLaunch
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
class LifeUnionPayActivityTest {
    @Test fun contractPassesTnUnchangedAndReturnsOriginalRequestIdentity() {
        val contract = LifeUnionPayContract()
        val request = LifePaymentLaunch(7, "wash-52", "202611071234567890123", "52")
        val intent = contract.createIntent(ApplicationProvider.getApplicationContext(), request)
        assertEquals(LifeUnionPayActivity::class.java.name, intent.component?.className)
        assertEquals(request.payload, intent.getStringExtra(LifeUnionPayActivity.TransactionNumber))
        assertEquals(request.id, intent.getLongExtra(LifeUnionPayActivity.RequestId, -1))
        assertEquals(LifeUnionPayResult(7, "success"), contract.parseResult(Activity.RESULT_OK,
            Intent().putExtra(LifeUnionPayActivity.RequestId, 7L)
                .putExtra(LifeUnionPayActivity.PaymentResult, "success")))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, null))
    }

    @Test fun recreationDoesNotRelaunchTnAndSdkCallbackPreservesRequestId() {
        val contract = LifeUnionPayContract()
        val intent = contract.createIntent(ApplicationProvider.getApplicationContext(),
            LifePaymentLaunch(17, "wash-52", "202611071234567890123", "52"))
        val controller = Robolectric.buildActivity(LifeUnionPayActivity::class.java, intent)
            .create(Bundle().apply { putBoolean(LifeUnionPayActivity.SdkStarted, true) })
        val activity = controller.get()
        val shadow = shadowOf(activity)
        assertNull(shadow.nextStartedActivityForResult)
        assertFalse(activity.isFinishing)
        val sdkIntent = Intent(activity, Activity::class.java)
        activity.startActivityForResult(sdkIntent, LifeUnionPayActivity.SdkRequestCode)
        shadow.receiveResult(sdkIntent, Activity.RESULT_OK, Intent().putExtra("pay_result", "cancel"))
        assertTrue(activity.isFinishing)
        assertEquals(LifeUnionPayResult(17, "cancel"), contract.parseResult(shadow.resultCode, shadow.resultIntent))
        assertFalse(shadow.resultIntent.hasExtra(LifeUnionPayActivity.TransactionNumber))
        controller.destroy()
    }

    @Test fun interruptedRoutingReturnsUnknownWithoutReplayingPayment() {
        val contract = LifeUnionPayContract()
        val controller = Robolectric.buildActivity(LifeUnionPayActivity::class.java,
            contract.createIntent(ApplicationProvider.getApplicationContext(),
                LifePaymentLaunch(19, "wash-52", "202611071234567890123", "52"))).create(Bundle())
        val shadow = shadowOf(controller.get())
        assertTrue(controller.get().isFinishing)
        assertNull(shadow.nextStartedActivityForResult)
        assertEquals(LifeUnionPayResult(19, null), contract.parseResult(shadow.resultCode, shadow.resultIntent))
        controller.destroy()
    }

    @Test fun acceptedSdkCallWithoutAnActivityTimesOutAndCannotLaunchLater() {
        val contract = LifeUnionPayContract()
        val controller = Robolectric.buildActivity(UnionPayWithoutPaymentApp::class.java,
            contract.createIntent(ApplicationProvider.getApplicationContext(),
                LifePaymentLaunch(23, "wash-52", "202611071234567890123", "52")))
            .create().start().resume()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(15))
        val shadow = shadowOf(activity)
        assertTrue(activity.isFinishing)
        assertEquals(LifeUnionPayResult(23, "launch_failed"), contract.parseResult(shadow.resultCode, shadow.resultIntent))
        activity.startActivityForResult(Intent(activity, Activity::class.java), LifeUnionPayActivity.SdkRequestCode, null)
        assertNull(shadow.nextStartedActivityForResult)
        controller.pause().stop().destroy()
    }
}

/** Models the vendor's accepted call followed by an unavailable-app toast and no callback. */
class UnionPayWithoutPaymentApp : LifeUnionPayActivity() {
    override fun startSdk(tn: String): Int = 0
}
