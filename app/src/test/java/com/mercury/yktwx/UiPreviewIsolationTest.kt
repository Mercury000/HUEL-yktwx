package com.mercury.yktwx

import com.mercury.yktwx.data.yktApiFixture
import com.mercury.yktwx.ui.usageConsentFixture
import com.mercury.yktwx.data.accountRepositoryFixture
import com.mercury.yktwx.analytics.AnalyticsFixtures
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.analytics.AppAnalytics
import com.mercury.yktwx.analytics.AnalyticsFeature
import com.mercury.yktwx.analytics.RecordingAnalyticsBackend
import com.mercury.yktwx.analytics.UmengAnalyticsBackend
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.ui.UsageConsent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class UiPreviewIsolationTest {
    private var credentialReads = 0
    private var networkRequests = 0
    private val vault = object : Vault {
        override fun credentials(): Credentials? { credentialReads++; return null }
        override fun session(): Session? = null
        override fun saveCredentials(value: Credentials) = error("Unexpected credential write")
        override fun saveSession(value: Session) = error("Unexpected session write")
        override fun clearSession() {}
        override fun clear() {}
    }
    private fun unexpectedNetwork(): Nothing { networkRequests++; error("Unexpected network request") }
    private val repository = accountRepositoryFixture(yktApiFixture({ unexpectedNetwork() }), vault,
        RechargeApi { unexpectedNetwork() }, FeeApi { unexpectedNetwork() },
        CardServiceApi { unexpectedNetwork() }, profileApiFixture(client = { unexpectedNetwork() }, imageClient = { unexpectedNetwork() }))
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private fun <T : ViewModel> keep(model: T): T = model.also { store.put(model.javaClass.name + store.keys().size, it) }

    @After fun cleanup() { store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun startupExtrasOnlyEnablePreviewInDebugAndPreviewBuilds() {
        val allowed = BuildConfig.BUILD_TYPE in setOf("debug", "preview")
        assertEquals(allowed, UiPreview.enabled)
        val intent = Intent().putExtra("ui_preview", true).putExtra("ui_preview_lost", true)
        assertEquals(allowed, UiPreview.requested(intent))
        assertEquals(allowed, UiPreview.lostRequested(intent))
        assertFalse(UiPreview.requested(Intent()))
        assertFalse(UiPreview.lostRequested(Intent()))
    }

    @Test fun analyticsSdkIsOnlyAvailableInReleaseBuilds() {
        val release = BuildConfig.BUILD_TYPE == "release"
        for (className in listOf("com.umeng.commonsdk.UMConfigure", "com.umeng.analytics.MobclickAgent")) {
            val available = runCatching { Class.forName(className, false, javaClass.classLoader) }.isSuccess
            assertEquals(className, release, available)
        }
        if (!release) {
            assertEquals("", BuildConfig.UMENG_APP_KEY)
            assertEquals("", BuildConfig.UMENG_CHANNEL)
            val analytics = AppAnalytics(UmengAnalyticsBackend(app))
            analytics.resume(app, agreed = true)
            assertFalse(analytics.active.value)
            assertNull(analytics.submit(AnalyticsFeature.RechargeSelf))
        }
    }

    @Test fun releaseApplicationInitializesFromCurrentPersistedConsentBeforeForeground() = runBlocking {
        val release = BuildConfig.BUILD_TYPE == "release"
        assertEquals("com.mercury.yktwx.YktApplication", app.javaClass.name)
        if (!release) return@runBlocking

        val prefs = app.getSharedPreferences("usage_consent", Application.MODE_PRIVATE)
        val backend = RecordingAnalyticsBackend()
        val analytics = AppAnalytics(backend)
        val application = app as YktApplication
        val originalAnalytics = application.analytics
        val originalConsent = application.consent
        application.analytics = analytics
        try {
            prefs.edit().clear().putString("accepted_hash", "outdated-notice").commit()
            application.consent = usageConsentFixture(app)
            app.onCreate()
            assertEquals(0, backend.initializations)

            usageConsentFixture(app).accept()
            application.consent = usageConsentFixture(app)
            app.onCreate()
            assertEquals(1, backend.initializations)
            assertEquals(0, backend.resumes)
            assertFalse(analytics.active.value)
            assertNull(analytics.begin())
            assertTrue(backend.events.isEmpty())

            analytics.resume(app, agreed = usageConsentFixture(app).accepted)
            assertEquals(1, backend.initializations)
            assertEquals(1, backend.resumes)
        } finally {
            application.analytics = originalAnalytics
            application.consent = originalConsent
            prefs.edit().clear().commit()
        }
    }

    @Test fun previewStartupCannotBypassLoginOrInjectProfileAndBillsInProduction() {
        val payment = keep(paymentViewModelFixture(app, repository))
        val bills = keep(billViewModelFixture(app, repository))
        val profile = keep(myViewModelFixture(app, repository))
        val hall = keep(hallViewModelFixture(app, repository))
        payment.initialize(uiPreview = true)
        bills.initialize(uiPreview = true)
        profile.accountChanged("owner", uiPreview = true)
        hall.accountChanged("owner", uiPreview = true)
        payment.onForeground()
        if (UiPreview.enabled) {
            assertFalse(payment.state.value.needsLogin)
            assertNotNull(payment.state.value.code)
            assertTrue(bills.state.value.loaded)
            assertTrue(bills.state.value.items.isNotEmpty())
            assertNotNull(profile.state.value.profile)
            assertTrue(hall.state.value.rooms.isNotEmpty())
            assertEquals(0, credentialReads)
        } else {
            await { !payment.state.value.restoringLogin && bills.state.value.needsLogin }
            assertTrue(payment.state.value.needsLogin)
            assertNull(payment.state.value.code)
            assertFalse(bills.state.value.loaded)
            assertTrue(bills.state.value.items.isEmpty())
            assertNull(profile.state.value.profile)
            assertTrue(hall.state.value.rooms.isEmpty())
            assertTrue(credentialReads > 0)
        }
        assertEquals(UiPreview.enabled, payment.state.value.preview)
        assertEquals(UiPreview.enabled, bills.state.value.preview)
        assertEquals(0, networkRequests)
    }

    @Test fun dialogPreviewFlagsAreIgnoredInProductionAndPreventWritesInPreview() {
        val recharge = keep(rechargeViewModelFixture(app, repository))
        val fee = keep(feeViewModelFixture(app, repository))
        val password = keep(cardPasswordViewModelFixture(app, repository))
        val card = keep(cardServiceViewModelFixture(app, repository))
        val recipient = keep(cardServiceViewModelFixture(app, repository))
        val unlost = keep(cardServiceViewModelFixture(app, repository))
        recharge.open("owner", null, preview = true)
        fee.open(FeeProject.Network, "owner", preview = true)
        password.open("owner", preview = true)
        card.open(CampusCardAction.ManageState, "owner", preview = true)
        recipient.open(CampusCardAction.OthersRecharge, "owner", preview = true)
        unlost.openUnlost("owner", "account", preview = true)
        await { !recharge.state.value.loading && !fee.state.value.loading && !password.state.value.loading &&
            !card.state.value.loading && !recipient.state.value.loading && !unlost.state.value.loading }
        assertEquals(UiPreview.enabled, recharge.state.value.preview)
        assertEquals(UiPreview.enabled, fee.state.value.preview)
        assertEquals(UiPreview.enabled, card.state.value.preview)
        assertEquals(UiPreview.enabled, recipient.state.value.preview)
        assertEquals(UiPreview.enabled, unlost.state.value.preview)
        if (UiPreview.enabled) {
            assertNotNull(recharge.state.value.config)
            assertNotNull(fee.state.value.query)
            assertTrue(password.state.value.visible)
            assertTrue(card.state.value.cards.isNotEmpty())
            assertTrue(unlost.state.value.cards.single().lost)
            recharge.submit(1000)
            fee.createWithMethod(1500, "TSM")
            password.submit("123456", "234567", "234567")
            card.changeCard("123456")
            unlost.changeCard("123456")
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(recharge.state.value.pending)
            assertNull(fee.state.value.order)
            assertEquals(0, credentialReads)
        } else {
            assertNull(recharge.state.value.config)
            assertNull(fee.state.value.query)
            assertFalse(password.state.value.visible)
            assertFalse(password.state.value.preview)
            assertTrue(card.state.value.cards.isEmpty())
            assertTrue(unlost.state.value.cards.isEmpty())
            assertTrue(credentialReads > 0)
        }
        assertEquals(0, networkRequests)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for state", condition())
    }
}
