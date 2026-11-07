package com.mercury.yktwx

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import com.mercury.yktwx.data.life.LifePaymentLaunch
import com.mercury.yktwx.ui.components.LifePaymentEffect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifePaymentEffectTest {
    @get:Rule val compose = createComposeRule()
    private data class Launch(val code: Int, val contract: ActivityResultContract<*, *>, val input: LifePaymentLaunch)

    @Test fun sdkContractsRouteOnceAndMissingResultRetainsTheOriginalRequestId() {
        val launches = mutableListOf<Launch>()
        val returns = mutableListOf<Pair<Long, String?>>()
        val claimed = mutableSetOf<Long>()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?) {
                launches += Launch(requestCode, contract, input as LifePaymentLaunch)
            }
        }
        val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        var request by mutableStateOf(LifePaymentLaunch(7, "wash-13", "test-signed-payload"))
        var enabled by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                LifePaymentEffect(request, enabled, { claimed.add(it) }, { id, status -> returns += id to status })
            }
        }
        compose.runOnIdle {
            assertEquals(1, launches.size)
            assertTrue(launches.single().contract is LifeAlipayContract)
            assertEquals("test-signed-payload", launches.single().input.payload)
            enabled = false
        }
        compose.runOnIdle { enabled = true }
        compose.runOnIdle {
            assertEquals(1, launches.size)
            registry.dispatchResult(launches.single().code, Activity.RESULT_CANCELED, null)
            assertEquals(listOf(7L to null), returns)
            request = LifePaymentLaunch(8, "wash-52", "202611071234567890123", "52")
        }
        compose.runOnIdle {
            assertEquals(2, launches.size)
            assertTrue(launches.last().contract is LifeUnionPayContract)
            assertEquals(request, launches.last().input)
            registry.dispatchResult(launches.last().code, Activity.RESULT_OK,
                Intent().putExtra(LifeUnionPayActivity.RequestId, 8L)
                    .putExtra(LifeUnionPayActivity.PaymentResult, "success"))
            assertEquals(listOf(7L to null, 8L to "success"), returns)
        }
    }
}
