package com.mercury.yktwx

import com.mercury.yktwx.data.accountRepositoryFixture
import com.mercury.yktwx.ui.usageConsentFixture
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.splashscreen.SplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.ui.UsageConsent
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@dagger.hilt.android.testing.HiltAndroidTest
@dagger.hilt.android.testing.UninstallModules(com.mercury.yktwx.di.RepositoryModule::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = dagger.hilt.android.testing.HiltTestApplication::class, sdk = [35], qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi",
    instrumentedPackages = ["androidx.core.splashscreen"], shadows = [StartupSplashShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageConsentStartupTest {
    @get:Rule(order = 0) val hilt = dagger.hilt.android.testing.HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var requests = 0
    private var clears = 0
    private var credentials: Credentials? = null
    private var session: Session? = null
    private var restorationGate: CountDownLatch? = null
    @Volatile private var restorationStarted = false
    private val vault = object : Vault {
        override fun credentials(): Credentials? {
            restorationStarted = true
            restorationGate?.let { check(it.await(10, TimeUnit.SECONDS)) }
            return credentials
        }
        override fun session() = session
        override fun saveCredentials(value: Credentials) { credentials = value }
        override fun saveSession(value: Session) { session = value }
        override fun clearSession() { session = null }
        override fun clear() { clears++; credentials = null; session = null }
    }
    private val service = object : YktService by FakeAccountService() {
        override suspend fun login(credentials: Credentials): Session { requests++; return Session("fictional-token", Long.MAX_VALUE) }
        override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
            requests++
            return PaymentBatch(listOf("12345678901234567890"), 60,
                PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 0))
        }
        override suspend fun paymentAccount(token: String): PaymentAccount {
            requests++
            return PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 0)
        }
        override suspend fun paymentCard(token: String, account: String): CampusCard {
            requests++
            return CampusCard(account, "校园卡", "fictional-user", false, false, 0)
        }
    }
    @dagger.hilt.android.testing.BindValue
    internal lateinit var repository: AccountRepository

    @Before fun prepare() {
        repository = accountRepositoryFixture(service, vault)
        app.getSharedPreferences("usage_consent", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().putBoolean("automatic_updates", false).commit()
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        shadowOf(connectivity).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        hilt.inject()
    }
    @After fun cleanup() {
        restorationGate?.countDown()
        scenario?.close()
    }
    private fun launch(waitForRestoration: Boolean = true): MainActivity {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var activity: MainActivity
        scenario!!.onActivity { activity = it }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            if (waitForRestoration) !ViewModelProvider(activity)[PaymentViewModel::class.java].state.value.restoringLogin
            else restorationStarted
        }
        compose.waitForIdle()
        return activity
    }
    private fun accept() {
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            usageConsentFixture(app).accepted && compose.onAllNodesWithTag("usage-notice-body").fetchSemanticsNodes().isEmpty()
        }
    }
    private fun storedLoginWithOldNotice() {
        credentials = Credentials("202600000001", "fictional-password")
        session = Session("fictional-token", Long.MAX_VALUE)
        app.getSharedPreferences("usage_consent", Context.MODE_PRIVATE).edit().putString("accepted_hash", "previous-body-hash").commit()
    }

    @Test fun firstStartupAutomaticallyRequiresAgreementBeforeLoginOrBusinessInitialization() {
        val activity = launch()
        val payments = ViewModelProvider(activity)[PaymentViewModel::class.java]
        compose.onNodeWithContentDescription("开始设置").assertIsDisplayed().performClick()
        compose.onNodeWithTag("usage-consent-page").assertIsDisplayed()
        compose.onNodeWithText("登录校园一卡通").assertDoesNotExist()
        compose.onNodeWithText("上滑并阅读").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("拒绝并退出").assertIsDisplayed().assertIsEnabled()
        assertFalse(ViewModelProvider(activity)[BillViewModel::class.java].state.value.loaded)
        assertEquals(0, requests)
        accept()
        compose.onNodeWithTag("usage-notice-body").assertDoesNotExist()
        compose.onNodeWithTag("oobe-accounts").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        assertTrue(usageConsentFixture(app).setupPending)
        assertTrue(payments.state.value.needsLogin)
        assertEquals(0, requests)
    }

    @Test fun rejectingFirstRunNoticeExitsAndShowsTheSamePageOnReopen() {
        val activity = launch()
        compose.onNodeWithContentDescription("开始设置").performClick()
        compose.onNodeWithTag("usage-consent-page").assertIsDisplayed()
        compose.onNodeWithText("拒绝并退出").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(usageConsentFixture(app).accepted)
        scenario!!.close()
        launch()
        compose.onNodeWithContentDescription("开始设置").performClick()
        compose.onNodeWithTag("usage-consent-page").assertIsDisplayed()
        assertEquals(0, requests)
    }

    @Test fun accountSetupBackClosesTheDrawerBeforeReturningToTheAcceptedNotice() {
        val activity = launch()
        compose.onNodeWithContentDescription("开始设置").performClick()
        accept()

        compose.onNodeWithTag("oobe-campus-row").performClick()
        compose.onNodeWithTag("oobe-login-drawer").assertIsDisplayed()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("oobe-login-drawer").assertDoesNotExist()
        compose.onNodeWithTag("oobe-accounts").assertIsDisplayed()

        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithTag("usage-consent-page").assertIsDisplayed()
        compose.onNodeWithText("继续").assertIsEnabled().performClick()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("usage-consent-page").assertIsDisplayed()
        assertFalse(activity.isFinishing)
        assertTrue(usageConsentFixture(app).accepted)
        assertTrue(usageConsentFixture(app).setupPending)
    }

    @Test fun unfinishedSetupResumesAndRealCampusLoginUnlocksAppearanceWithoutOptionalAccounts() {
        launch()
        compose.onNodeWithContentDescription("开始设置").performClick()
        accept()
        scenario!!.close()
        val activity = launch()
        val payments = ViewModelProvider(activity)[PaymentViewModel::class.java]
        compose.onNodeWithTag("oobe-accounts").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        val bikeRow = compose.onNodeWithTag("oobe-life-row-${LifeProvider.Hellobike.tag}")
        compose.waitUntil(5_000) { hasClickAction().matches(bikeRow.fetchSemanticsNode()) }
        compose.onNodeWithText("稍后可在「我的 → 应用设置 → 生活服务账户」登录").assertIsDisplayed()
        bikeRow.performClick()
        compose.onNodeWithTag("hellobike-login-sheet").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-login-phone").performTextInput("00000000000")
        compose.onNodeWithTag("hellobike-send-code").assertIsEnabled().performClick()
        compose.onNodeWithText("请输入正确的手机号").assertIsDisplayed()
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("hellobike-login-sheet").assertDoesNotExist()
        compose.onNodeWithText("下一步").assertIsNotEnabled()
        compose.onNodeWithTag("oobe-campus-row").performClick()
        compose.onNodeWithTag("oobe-login-drawer").assertIsDisplayed()
        compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
        compose.onNodeWithText("密码").performTextInput("fictional-password")
        compose.onNodeWithText("登录").assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !payments.state.value.needsLogin
        }
        compose.onNodeWithTag("oobe-login-drawer").assertDoesNotExist()
        compose.onNodeWithTag("oobe-campus-row-label", useUnmergedTree = true).assertTextEquals("已登录")
        assertNotNull(credentials)
        assertNull(payments.state.value.code)
        assertFalse(ViewModelProvider(activity)[BillViewModel::class.java].state.value.loaded)
        compose.onNodeWithText("下一步").assertIsEnabled().performClick()
        compose.onNodeWithText("选择外观").assertIsDisplayed()
        compose.onNodeWithTag("oobe-balance-card").assertIsDisplayed()
        val floating = activity.settings.floatingBottomBar.value
        compose.onNode(hasText("悬浮导航") and hasClickAction()).performScrollTo().performClick()
        compose.waitUntil(5_000) { activity.settings.floatingBottomBar.value != floating }
        compose.onNodeWithText("完成设置").performClick()
        compose.onNodeWithText("设置完成").assertIsDisplayed()
        compose.onNodeWithText("进入财小通").performClick()
        compose.waitUntil(5_000) { !usageConsentFixture(app).setupPending && !activity.consent.setupPending }
        compose.onNodeWithTag("oobe-accounts").assertDoesNotExist()
        scenario!!.close()
        val reopened = launch()
        compose.onNodeWithContentDescription("开始设置").assertDoesNotExist()
        compose.onNodeWithTag("oobe-accounts").assertDoesNotExist()
        compose.onNodeWithTag("payment-code-card").assertIsDisplayed()
        listOf("life-login-notice", "hui798-login-notice", "hellobike-login-notice").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        assertEquals(!floating, reopened.settings.floatingBottomBar.value)
        assertTrue(usageConsentFixture(app).accepted)
        assertEquals(0, clears)
    }

    @Test fun changedNoticeKeepsLoginAndDefersRequestsAndCodesUntilAgreementSurvivesRecreation() {
        storedLoginWithOldNotice()
        val activity = launch()
        val payments = ViewModelProvider(activity)[PaymentViewModel::class.java]
        compose.onNodeWithTag("consent-main-shell").assertExists()
        compose.onNodeWithTag("usage-consent-page").assertDoesNotExist()
        compose.onNodeWithText("上滑并阅读").assertIsNotEnabled()
        assertEquals("使用须知已更新，请重新阅读并同意后使用。", ShadowToast.getTextOfLatestToast())
        assertFalse(payments.state.value.needsLogin)
        assertEquals("202600000001", payments.state.value.username)
        assertEquals(0, clears)
        assertEquals(0, requests)
        assertNull(payments.state.value.code)
        compose.onNodeWithContentDescription("付款二维码").assertDoesNotExist()
        assertTrue(activity.viewModelStore.keys().none {
            activity.viewModelStore[it] is LifeDevicesViewModel || activity.viewModelStore[it] is Hui798ViewModel
        })
        scenario!!.recreate()
        compose.onNodeWithTag("consent-main-shell").assertExists()
        compose.onNodeWithTag("usage-notice-paragraph-0").assertIsDisplayed()
        assertFalse(usageConsentFixture(app).accepted)
        assertEquals(0, requests)
        assertEquals(0, clears)
        accept()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); payments.state.value.code != null }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
        assertFalse(payments.state.value.needsLogin)
        assertEquals("202600000001", credentials?.username)
        assertEquals(0, clears)
        assertTrue(requests > 0)
        scenario!!.recreate()
        compose.onNodeWithTag("usage-notice-body").assertDoesNotExist()
        assertTrue(usageConsentFixture(app).accepted)
    }

    @Test fun confirmingChangedNoticeWhileLocalLoginIsStillRestoringEventuallyEntersThePaymentPage() {
        storedLoginWithOldNotice()
        restorationGate = CountDownLatch(1)
        val activity = launch(waitForRestoration = false)
        val payments = ViewModelProvider(activity)[PaymentViewModel::class.java]
        compose.onNodeWithText("上滑并阅读").assertIsDisplayed().assertIsNotEnabled()
        assertTrue(payments.state.value.restoringLogin)
        assertEquals(0, requests)
        accept()
        assertTrue(payments.state.value.restoringLogin)
        assertEquals(0, requests)
        compose.runOnIdle { restorationGate!!.countDown() }
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            !payments.state.value.restoringLogin && payments.state.value.code != null
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("付款二维码").assertIsDisplayed()
        assertFalse(payments.state.value.needsLogin)
        assertEquals("202600000001", payments.state.value.username)
        assertEquals(0, clears)
        assertTrue(requests > 0)
    }

    @Test fun rejectingUpdatedNoticeExitsAndReopeningRequiresAgreementWithoutTouchingPlatformRecords() {
        storedLoginWithOldNotice()
        val panggui = app.getSharedPreferences("encrypted_life_devices", Context.MODE_PRIVATE)
        val hui798 = app.getSharedPreferences("encrypted_hui798", Context.MODE_PRIVATE)
        panggui.edit().putString("account", "fictional-encrypted-account").putString("water_sessions", "fictional-encrypted-progress").commit()
        hui798.edit().putString("account", "fictional-encrypted-account").putString("sessions", "fictional-encrypted-progress").commit()
        val pangguiBefore = panggui.all.toMap()
        val huiBefore = hui798.all.toMap()
        val activity = launch()
        compose.onNodeWithText("不同意").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(usageConsentFixture(app).accepted)
        assertEquals("previous-body-hash", app.getSharedPreferences("usage_consent", Context.MODE_PRIVATE).getString("accepted_hash", null))
        scenario!!.close()
        val reopened = launch()
        compose.onNodeWithText("上滑并阅读").assertIsNotEnabled()
        assertEquals(0, requests)
        assertEquals(0, clears)
        assertEquals("202600000001", credentials?.username)
        assertEquals(pangguiBefore, panggui.all)
        assertEquals(huiBefore, hui798.all)
        compose.runOnIdle { reopened.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); reopened.isFinishing }
        assertTrue(reopened.isFinishing)
        assertFalse(usageConsentFixture(app).accepted)
    }
}

@Implements(SplashScreen::class, isInAndroidSdk = false)
class StartupSplashShadow {
    @Implementation
    fun setKeepOnScreenCondition(condition: SplashScreen.KeepOnScreenCondition) = Unit
}
