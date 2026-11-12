package com.mercury.yktwx.analytics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.NetworkFailure
import com.mercury.yktwx.data.InvalidCredentials
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.UsageConsent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppAnalyticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val backend = RecordingAnalyticsBackend()
    private val analytics = AppAnalytics(backend)
    @After fun cleanup() { AppAnalytics.instance = null }

    @Test fun authorizedStartupInitializesWithoutForegroundOrBusinessEvents() {
        analytics.initialize(agreed = true)
        analytics.initialize(agreed = true)
        analytics.pageVisible(AnalyticsPage.BillList)
        analytics.installHandoff()
        assertEquals(1, backend.initializations)
        assertEquals(0, backend.resumes)
        assertFalse(analytics.active.value)
        assertNull(analytics.submit(AnalyticsFeature.RechargeSelf))
        assertTrue(backend.events.isEmpty())

        analytics.resume(context, agreed = true)
        assertEquals(1, backend.initializations)
        assertEquals(1, backend.resumes)
        assertTrue(analytics.active.value)
        assertNotNull(analytics.begin())
    }

    @Test fun startupWithoutConsentWaitsForAcceptance() {
        analytics.initialize(agreed = false)
        analytics.resume(context, agreed = false)
        assertEquals(0, backend.initializations)
        assertFalse(analytics.active.value)
        assertNull(analytics.begin())

        analytics.resume(context, agreed = true)
        assertEquals(1, backend.initializations)
        assertEquals(1, backend.resumes)
        assertTrue(analytics.active.value)
    }

    @Test fun revokingStartupConsentDisablesBeforeAnyForeground() {
        analytics.initialize(agreed = true)
        analytics.initialize(agreed = false)
        analytics.initialize(agreed = true)
        analytics.resume(context, agreed = true)
        assertEquals(1, backend.initializations)
        assertEquals(1, backend.disables)
        assertEquals(0, backend.resumes)
        assertFalse(analytics.active.value)
        assertNull(analytics.begin())
        assertTrue(backend.events.isEmpty())
    }

    @Test fun consentAndPreviewGateInitializationAndEveryEvent() {
        analytics.resume(context, false)
        analytics.resume(context, true, preview = true)
        analytics.submit(AnalyticsFeature.RechargeSelf)
        analytics.pageVisible(AnalyticsPage.BillList)
        analytics.installHandoff()
        assertNull(analytics.begin())
        assertEquals(0, backend.initializations)
        assertTrue(backend.events.isEmpty())
        analytics.resume(context, true)
        analytics.resume(context, true)
        assertEquals(1, backend.initializations)
        assertEquals(1, backend.resumes)
        assertTrue(analytics.active.value)
    }

    @Test fun missingConfigurationCannotManufactureEvents() {
        backend.available = false
        analytics.resume(context, true)
        assertFalse(analytics.active.value)
        assertNull(analytics.submit(AnalyticsFeature.PangguiWater))
        assertEquals(0, backend.resumes)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun pageRecompositionAndPaymentRedisplayDoNotManufactureCodeFetches() {
        analytics.resume(context, true)
        analytics.pageVisible(AnalyticsPage.Payment)
        repeat(4) { analytics.paymentCodeStatus(true) }
        analytics.paymentCodeStatus(false)
        analytics.paymentCodeStatus(true)
        analytics.pause(context)
        analytics.pageVisible(null)
        analytics.resume(context, true)
        analytics.pageVisible(AnalyticsPage.Payment)
        analytics.paymentCodeStatus(true)
        analytics.pageVisible(AnalyticsPage.BillList)
        repeat(4) { analytics.pageVisible(AnalyticsPage.BillList) }
        analytics.pageVisible(AnalyticsPage.BillStats)
        analytics.pageVisible(AnalyticsPage.Payment)
        analytics.paymentCodeStatus(true)
        assertEquals(listOf("bill_list_viewed", "bill_stats_viewed"), backend.events.map { it.first })
    }

    @Test fun successfulCodeFetchesCountEachRequestButConsumeEachAttemptOnce() {
        analytics.resume(context, true)
        val first = analytics.begin()
        repeat(3) { analytics.paymentCodeFetched(first) }
        analytics.paymentCodeFetched(analytics.begin())
        assertEquals(listOf("payment_code_fetched", "payment_code_fetched"), backend.events.map { it.first })
        assertTrue(backend.events.all { it.second.isEmpty() })
    }

    @Test fun codeFetchesRequireAuthorizationAtRequestStartAndRejectRevokedAttempts() {
        analytics.paymentCodeFetched(analytics.begin())
        analytics.resume(context, true, preview = true)
        analytics.paymentCodeFetched(analytics.begin())
        analytics.resume(context, true)
        val attempt = analytics.begin()
        analytics.revoke()
        analytics.paymentCodeFetched(attempt)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun continuousCodeFaultIsCountedOnceUntilExplicitRetryOrRecovery() {
        analytics.resume(context, true)
        analytics.pageVisible(AnalyticsPage.Payment)
        repeat(3) { analytics.paymentCodeStatus(false, AnalyticsError.Network) }
        analytics.retryPaymentCode()
        analytics.paymentCodeStatus(false, AnalyticsError.Timeout)
        analytics.paymentCodeStatus(true)
        analytics.paymentCodeStatus(false, AnalyticsError.Business)
        assertEquals(3, backend.events.count { it.first == "operation_failed" })
        assertEquals(0, backend.events.count { it.first == "payment_code_fetched" })
    }

    @Test fun callbacksFromBeforeRevocationAndPreviewCannotReport() {
        analytics.resume(context, true)
        val first = analytics.begin()
        analytics.resume(context, true, preview = true)
        analytics.loginResult(first, AnalyticsPlatform.Campus)
        analytics.resume(context, true)
        val second = analytics.begin()
        analytics.revoke()
        analytics.downloadResult(second)
        analytics.resume(context, true)
        assertNull(analytics.begin())
        assertEquals(1, backend.disables)
        assertTrue(backend.events.isEmpty())
    }

    @Test fun consentRevocationStopsTheSharedCollectorImmediately() = runBlocking {
        AppAnalytics.instance = analytics
        val consent = UsageConsent(context)
        consent.accept()
        analytics.resume(context, consent.accepted)
        val attempt = analytics.submit(AnalyticsFeature.RechargeSelf)
        consent.revoke()
        analytics.operationFailed(attempt, AnalyticsFeature.RechargeSelf, AnalyticsError.Auth)
        assertFalse(consent.accepted)
        assertFalse(analytics.active.value)
        assertEquals(listOf("recharge_self_submitted"), backend.events.map { it.first })
    }

    @Test fun preflightFailureAndDuplicateTerminalCallbacksAreExcluded() {
        analytics.resume(context, true)
        val attempt = analytics.begin()
        analytics.operationFailed(attempt, AnalyticsFeature.Hui798Water, AnalyticsError.Business)
        assertTrue(backend.events.isEmpty())
        repeat(3) { analytics.submitted(attempt, AnalyticsFeature.Hui798Water) }
        repeat(3) { analytics.operationFailed(attempt, AnalyticsFeature.Hui798Water, AnalyticsError.Network) }
        assertEquals(listOf("hui798_water_start_requested", "operation_failed"), backend.events.map { it.first })
    }

    @Test fun backgroundCannotStartEventsButCanFinishAnAuthorizedDownload() {
        analytics.resume(context, true)
        val attempt = analytics.begin()
        analytics.pause(context)
        assertNull(analytics.submit(AnalyticsFeature.Network))
        analytics.downloadResult(attempt)
        analytics.downloadResult(attempt, AnalyticsError.Network)
        assertEquals(listOf("update_download_result" to mapOf("outcome" to "success")), backend.events.toList())
    }

    @Test fun updateDiscoveryIsDeduplicatedForAContinuousForeground() {
        analytics.resume(context, true)
        repeat(3) { analytics.updateAvailable(analytics.begin(), "1.2.0", "stable") }
        analytics.pause(context)
        analytics.resume(context, true)
        analytics.updateAvailable(analytics.begin(), "1.2.0", "stable")
        analytics.updateAvailable(analytics.begin(), "https://private.invalid", "stable")
        analytics.updateAvailable(analytics.begin(), "1.2.0", "private-channel")
        assertEquals(1, backend.events.size)
        analytics.background()
        analytics.updateAvailable(analytics.begin(), "1.2.0", "stable")
        assertEquals(2, backend.events.size)
    }

    @Test fun catalogueContainsOnlyTheNineteenDesignedEventsAndBoundedProperties() {
        analytics.resume(context, true)
        AnalyticsFeature.entries.filter { it.event != null }.forEach { analytics.submit(it) }
        analytics.pageVisible(AnalyticsPage.Payment)
        analytics.paymentCodeFetched(analytics.begin())
        analytics.paymentCodeStatus(true)
        analytics.pageVisible(AnalyticsPage.BillList)
        analytics.pageVisible(AnalyticsPage.BillStats)
        analytics.filterApplied(AnalyticsFilter.Search)
        analytics.loginResult(analytics.begin(), AnalyticsPlatform.Panggui, AnalyticsError.Auth)
        analytics.operationFailed(analytics.submit(AnalyticsFeature.Utility), AnalyticsFeature.Utility, AnalyticsError.Business)
        analytics.updateAvailable(analytics.begin(), "1.2.0-beta.1", "beta")
        analytics.downloadResult(analytics.begin(), AnalyticsError.Integrity)
        analytics.installHandoff()
        assertEquals(19, backend.events.map { it.first }.toSet().size)
        val allowed = setOf("filter_kind", "platform", "outcome", "error_kind", "feature", "target_version", "update_channel")
        assertTrue(backend.events.all { (_, fields) -> fields.keys.all { it in allowed } })
    }

    @Test fun errorsUseTypesInsteadOfSensitiveExceptionMessages() {
        assertEquals(AnalyticsError.Network, AnalyticsError.from(NetworkFailure("private-account-and-token")))
        assertEquals(AnalyticsError.Auth, AnalyticsError.from(InvalidCredentials()))
        assertEquals(AnalyticsError.Timeout, AnalyticsError.from(SocketTimeoutException("private-url")))
        assertThrows(CancellationException::class.java) { AnalyticsError.from(CancellationException()) }
        analytics.resume(context, true)
        analytics.loginResult(analytics.begin(), AnalyticsPlatform.Campus, AnalyticsError.from(NetworkFailure("private-account-and-token")))
        assertFalse(backend.events.toString().contains("private"))
        assertTrue(UsageNotice.text.contains("友盟+ U-App 统计 SDK"))
    }

    @Test fun statisticsFailureDoesNotEscapeIntoBusinessCode() {
        analytics.resume(context, true)
        backend.failEvents = true
        analytics.submit(AnalyticsFeature.RechargeSelf)
        analytics.downloadResult(analytics.begin())
    }
}
