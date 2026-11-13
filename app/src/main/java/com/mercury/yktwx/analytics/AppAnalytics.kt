package com.mercury.yktwx.analytics

import kotlinx.serialization.json.*
import kotlinx.serialization.SerializationException
import com.mercury.yktwx.data.*
import android.content.Context
import com.mercury.yktwx.data.life.Hui798Exception
import com.mercury.yktwx.data.life.HellobikeException
import com.mercury.yktwx.data.life.LifeDeviceException
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AnalyticsFeature(val id: String, val event: String?) {
    PaymentCode("payment_code", null),
    RechargeSelf("recharge_self", "recharge_self_submitted"),
    RechargeOther("recharge_other", "recharge_other_submitted"),
    Electricity("fee_electricity", "fee_electricity_submitted"),
    Water("fee_water", "fee_water_submitted"),
    Network("fee_network", "fee_network_submitted"),
    Utility("fee_utility", "fee_utility_submitted"),
    PangguiWater("panggui_water", "panggui_water_start_requested"),
    PangguiShower("panggui_shower", "panggui_shower_start_requested"),
    PangguiLaundry("panggui_laundry", "panggui_laundry_pay_submitted"),
    Hui798Water("hui798_water", "hui798_water_start_requested"),
    HellobikeRide("hellobike_ride", "hellobike_unlock_requested"),
    HellobikePayment("hellobike_payment", "hellobike_pay_submitted"),
}

internal enum class AnalyticsPlatform(val id: String) { Campus("campus"), Panggui("panggui"), Hui798("hui798"), Hellobike("hellobike") }
internal enum class AnalyticsPage { Payment, BillList, BillStats }
internal enum class AnalyticsFilter(val id: String) {
    Search("search"), Type("type"), Amount("amount"), Date("date"), Combined("combined"), Clear("clear")
}
internal enum class AnalyticsError(val id: String) {
    Offline("offline"), Network("network"), Timeout("timeout"), Auth("auth"), Business("business"),
    Parse("parse"), Integrity("integrity"), Permission("permission"), External("external"), Unknown("unknown");

    companion object {
        fun from(error: Throwable): AnalyticsError {
            if (error is CancellationException) throw error
            val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
            return when {
                causes.any { it is SocketTimeoutException } -> Timeout
                causes.any { it.isAuthenticationFailure() || it is LifeDeviceException && it.loginExpired || it is Hui798Exception && it.loginExpired || it is HellobikeException && it.loginExpired } -> Auth
                causes.any { it is HellobikeException && it.storageFailure } -> Integrity
                causes.any { it is SecurityException } -> Permission
                causes.any { it is SerializationException } -> Parse
                causes.any { it is NetworkFailure || it is IOException } -> Network
                causes.any { it is YktException || it is LifeDeviceException && it.businessRejected || it is Hui798Exception && it.rejected || it is HellobikeException && (it.rejected || it.officialRequired) } -> Business
                else -> Unknown
            }
        }
    }
}

internal class AnalyticsAttempt internal constructor(internal val authorization: Long) {
    internal var finished = false
    internal var submitted = false
}

internal interface AnalyticsBackend {
    fun initialize(): Boolean
    fun resume(context: Context)
    fun pause(context: Context)
    fun disable()
    fun event(id: String, properties: Map<String, String>)
}

internal class AppAnalytics internal constructor(private val backend: AnalyticsBackend) {
    private val activeState = MutableStateFlow(false)
    val active = activeState.asStateFlow()
    private var initialized = false
    private var disabled = false
    private var accepted = false
    private var foreground = false
    private var authorization = 0L
    private var page: AnalyticsPage? = null
    private var codeFailure = false
    private val updates = mutableSetOf<Pair<String, String>>()

    /** Initialize before Activity creation on authorized cold starts, without starting foreground events. */
    @Synchronized fun initialize(agreed: Boolean) {
        if (!agreed) {
            if (accepted) revoke()
            return
        }
        if (disabled) return
        accepted = true
        if (!initialized) initialized = runCatching { backend.initialize() }.getOrDefault(false)
    }

    @Synchronized fun resume(context: Context, agreed: Boolean, preview: Boolean = false) {
        if (!agreed) {
            if (accepted) revoke()
            return
        }
        if (preview) {
            pause(context)
            accepted = false
            authorization++
            return
        }
        if (disabled) return
        initialize(agreed)
        if (initialized && !foreground) {
            foreground = runCatching { backend.resume(context) }.isSuccess
            activeState.value = foreground
        }
    }

    @Synchronized fun pause(context: Context) {
        if (foreground) runCatching { backend.pause(context) }
        foreground = false
        activeState.value = false
    }

    @Synchronized fun background() { updates.clear() }

    @Synchronized fun revoke() {
        accepted = false
        foreground = false
        activeState.value = false
        authorization++
        page = null
        codeFailure = false
        updates.clear()
        if (initialized) {
            disabled = true
            runCatching { backend.disable() }
        }
    }

    @Synchronized fun begin(): AnalyticsAttempt? =
        if (accepted && foreground && initialized && !disabled) AnalyticsAttempt(authorization) else null

    @Synchronized fun submit(feature: AnalyticsFeature): AnalyticsAttempt? {
        val attempt = begin() ?: return null
        submitted(attempt, feature)
        return attempt
    }

    @Synchronized fun submitted(attempt: AnalyticsAttempt?, feature: AnalyticsFeature) {
        if (attempt == null || attempt.submitted || !valid(attempt)) return
        attempt.submitted = true
        feature.event?.let { send(it) }
    }

    @Synchronized fun pageVisible(value: AnalyticsPage?) {
        if (begin() == null || page == value) return
        page = value
        codeFailure = false
        when (value) {
            AnalyticsPage.BillList -> send("bill_list_viewed")
            AnalyticsPage.BillStats -> send("bill_stats_viewed")
            else -> Unit
        }
    }

    @Synchronized fun paymentCodeFetched(attempt: AnalyticsAttempt?) {
        if (consume(attempt)) send("payment_code_fetched")
    }

    @Synchronized fun paymentCodeStatus(valid: Boolean, failure: AnalyticsError? = null) {
        if (page != AnalyticsPage.Payment || begin() == null) return
        if (valid) {
            codeFailure = false
        } else if (failure != null && !codeFailure) {
            codeFailure = true
            send("operation_failed", mapOf("feature" to AnalyticsFeature.PaymentCode.id, "error_kind" to failure.id))
        }
    }

    @Synchronized fun retryPaymentCode() { codeFailure = false }

    @Synchronized fun filterApplied(kind: AnalyticsFilter) {
        if (begin() != null) send("bill_filter_applied", mapOf("filter_kind" to kind.id))
    }

    @Synchronized fun loginResult(attempt: AnalyticsAttempt?, platform: AnalyticsPlatform, error: AnalyticsError? = null) {
        if (!consume(attempt)) return
        send("login_result", mapOf("platform" to platform.id, "outcome" to if (error == null) "success" else "failure") +
            (error?.let { mapOf("error_kind" to it.id) } ?: emptyMap()))
    }

    @Synchronized fun operationFailed(attempt: AnalyticsAttempt?, feature: AnalyticsFeature, error: AnalyticsError) {
        if (attempt?.submitted == true && consume(attempt)) send("operation_failed", mapOf("feature" to feature.id, "error_kind" to error.id))
    }

    @Synchronized fun updateAvailable(attempt: AnalyticsAttempt?, version: String, channel: String) {
        if (!consume(attempt) || !foreground || channel !in setOf("stable", "beta") ||
            !version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-beta(?:\\.[0-9]+)?)?"))) return
        if (updates.add(version to channel)) send("update_available", mapOf("target_version" to version, "update_channel" to channel))
    }

    @Synchronized fun downloadResult(attempt: AnalyticsAttempt?, error: AnalyticsError? = null) {
        if (!consume(attempt)) return
        send("update_download_result", mapOf("outcome" to if (error == null) "success" else "failure") +
            (error?.let { mapOf("error_kind" to it.id) } ?: emptyMap()))
    }

    @Synchronized fun installHandoff() { if (begin() != null) send("update_install_handoff") }

    private fun consume(attempt: AnalyticsAttempt?): Boolean {
        if (attempt == null || !valid(attempt)) return false
        attempt.finished = true
        return true
    }

    private fun valid(attempt: AnalyticsAttempt) =
        !attempt.finished && accepted && !disabled && attempt.authorization == authorization

    private fun send(event: String, properties: Map<String, String> = emptyMap()) {
        runCatching { backend.event(event, properties) }
    }

}

internal fun FeeProject.analyticsFeature() = when (this) {
    FeeProject.Network -> AnalyticsFeature.Network
    FeeProject.FamilyWater -> AnalyticsFeature.Water
    FeeProject.Commercial -> AnalyticsFeature.Utility
    else -> AnalyticsFeature.Electricity
}
