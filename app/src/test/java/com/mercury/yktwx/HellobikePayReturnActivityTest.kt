package com.mercury.yktwx

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HellobikePayReturnActivityTest {
    @Test fun signingReturnOnlyClosesTheReturnActivity() {
        for (path in listOf("success", "fail")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("yktwx://hellobike-pay/paysign/$path"))
            Robolectric.buildActivity(HellobikePayReturnActivity::class.java, intent).use { activity ->
                activity.create()
                assertTrue(activity.get().isFinishing)
            }
        }
    }
}
