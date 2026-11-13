package com.mercury.yktwx

import com.mercury.yktwx.data.appPreferencesRepositoryFixture
import com.mercury.yktwx.ui.themeSettingsFixture
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.components.DashboardTopBar
import top.yukonga.miuix.kmp.basic.Scaffold
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.pages.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.HttpFixture
import com.mercury.yktwx.data.fixtureHttp
import com.mercury.yktwx.data.fixtureLifeDeviceApi
import com.mercury.yktwx.data.fixtureHui798Api
import kotlinx.coroutines.Dispatchers
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File
import java.io.ByteArrayInputStream
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeAccountsUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val preferences = com.mercury.yktwx.data.MemoryPreferences()
    private val models = ViewModelStore()
    private val preferenceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    private fun settings() = themeSettingsFixture(appPreferencesRepositoryFixture(
        preferences, preferenceScope))
    @After fun cleanupPreferences() { models.clear(); preferenceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    private fun capture(name: String) {
        val file = File("../tools/life-accounts-ui/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hellobikeWithoutARidingOrderStaysLoggedInAfterAutomaticAndManualChecks() {
        var code = 301
        val api = HellobikeApi(fixtureHttp { url -> object : HttpFixture(url) {
            override fun bodyStream() = ByteArrayInputStream("""{"code":$code,"msg":"无骑行中订单","data":null}""".toByteArray())
        } })
        val store = BikeRecords().store()
        val bike = HellobikeViewModel(api, store, FakeBikeLocation(), io = Dispatchers.Unconfined,
            analytics = AnalyticsFixtures.from(compose.activity.application)).also { models.put("bike", it) }
        compose.setContent {
            val state by bike.state.collectAsState()
            val connection by bike.accountConnection.collectAsState()
            LaunchedEffect(Unit) { bike.initialize(preview = false); bike.environment(true) }
            LifeAccountsSettingsCard(listOf(ManagedLifeAccount(LifeProvider.Hellobike, connection, restoring = state.restoring)),
                active = true, onClick = {})
        }
        compose.waitForIdle()
        compose.onNodeWithText("已登录").assertIsDisplayed()
        compose.onNodeWithText("检查失败").assertDoesNotExist()
        compose.onNodeWithText("无骑行中订单").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(LifeAccountStatus.Verified, bike.accountConnection.value.status)
            assertNull(bike.accountConnection.value.error)
            assertNull(bike.state.value.error)
            assertTrue(bike.state.value.sessions.isEmpty())
            assertNotNull(store.load().account)
            code = 0
            bike.checkAccount(force = true)
        }
        compose.waitForIdle()
        compose.onNodeWithText("已登录").assertIsDisplayed()
        compose.onNodeWithText("检查失败").assertDoesNotExist()
        compose.runOnIdle { assertNull(bike.accountConnection.value.error); assertNull(bike.state.value.error) }
    }

    @Test fun officialExpiryResponsesShowAllLoginBannersAndExpiredSettingsRatherThanCheckFailure() {
        fun reply(url: URL, code: Int) = object : HttpFixture(url) {
            override fun bodyStream() = ByteArrayInputStream("""{"code":$code,"msg":"登录信息已失效，请重新登录"}""".toByteArray())
        }
        val pgStore = LifeDeviceAccountStore(BikeRecords()).apply { saveAccount(LifeAccount(fixtureBikeAccount.phone, "test-token")) }
        val huiStore = Hui798AccountStore(BikeRecords()).apply { saveAccount(Hui798Account(fixtureBikeAccount.phone, "test-token", "test-user")) }
        val bikeStore = BikeRecords().store()
        val app = compose.activity.application
        val pg = lifeDevicesViewModelFixture(app, fixtureLifeDeviceApi("test-secret", { reply(it, 126) }), pgStore, Dispatchers.Unconfined)
            .also { models.put("pg", it) }
        val hui = hui798ViewModelFixture(app, fixtureHui798Api("https://fixture.invalid/api", "test-salt", { reply(it, -99) }), huiStore, Dispatchers.Unconfined)
            .also { models.put("hui", it) }
        val bike = HellobikeViewModel(HellobikeApi(fixtureHttp { reply(it, 103) }), bikeStore, FakeBikeLocation(),
            io = Dispatchers.Unconfined, analytics = AnalyticsFixtures.from(app)).also { models.put("bike", it) }
        val settings = settings()
        LifeProvider.entries.forEach(settings::dismissLifeLoginNotice)
        var showSettings by mutableStateOf(false)
        var login: LifeProvider? = null
        compose.setContent {
            val pgState by pg.state.collectAsState()
            val huiState by hui.state.collectAsState()
            val bikeState by bike.state.collectAsState()
            val pgConnection by pg.accountConnection.collectAsState()
            val huiConnection by hui.accountConnection.collectAsState()
            val bikeConnection by bike.accountConnection.collectAsState()
            LaunchedEffect(Unit) { bike.initialize(preview = false); bike.environment(true) }
            if (showSettings) SettingsFixture(listOf(
                ManagedLifeAccount(LifeProvider.Panggui, pgConnection),
                ManagedLifeAccount(LifeProvider.Hui798, huiConnection),
                ManagedLifeAccount(LifeProvider.Hellobike, bikeConnection)), onLogin = { login = it })
            else CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    PaymentPage(PreviewData.paymentState(false), settings, 0.dp, {},
                        lifeLoginRequired = !pgState.restoring && pgState.connectedPhone.isBlank(),
                        lifeLoginExpired = pgConnection.status == LifeAccountStatus.Expired,
                        hui798LoginRequired = !huiState.restoring && !huiState.connected,
                        hui798LoginExpired = huiConnection.status == LifeAccountStatus.Expired,
                        hellobikeLoginRequired = !bikeState.restoring && !bikeState.connected,
                        hellobikeLoginExpired = bikeConnection.status == LifeAccountStatus.Expired,
                        onHellobikeLogin = { login = LifeProvider.Hellobike })
                }
            }
        }
        compose.waitUntil(10_000) { listOf(pg.accountConnection.value, hui.accountConnection.value, bike.accountConnection.value)
            .all { it.status == LifeAccountStatus.Expired } }
        compose.onNodeWithTag("life-login-notice").assertIsDisplayed()
        compose.onNodeWithTag("hui798-login-notice").assertIsDisplayed()
        compose.onNodeWithTag("hellobike-login-notice").assertIsDisplayed().performClick()
        compose.onNodeWithText("胖乖生活登录失效，请重新登录").assertIsDisplayed()
        compose.onNodeWithText("慧生活798登录失效，请重新登录").assertIsDisplayed()
        compose.onNodeWithText("哈啰登录失效，请重新登录").assertIsDisplayed()
        listOf("life-login-notice", "hui798-login-notice", "hellobike-login-notice").forEach {
            compose.onNodeWithTag("$it-dismiss").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(LifeProvider.Hellobike, login)
            assertNull(pgStore.account()); assertNull(huiStore.account()); assertNull(bikeStore.load().account)
            assertFalse(pg.state.value.loginVisible); assertFalse(hui.state.value.loginVisible); assertFalse(bike.state.value.loginVisible)
            login = null; showSettings = true
        }
        compose.onAllNodesWithText("登录失效").assertCountEquals(3)
        compose.onAllNodesWithText("登录信息已失效，请重新登录").assertCountEquals(2)
        compose.onNodeWithText("检查失败").assertDoesNotExist()
        compose.onNodeWithTag("hellobike-account-row").performClick()
        compose.runOnIdle { assertEquals(LifeProvider.Hellobike, login) }
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
    }

    @Test fun expiredBannersUpdateOnPaymentFailureAndClearWhenAccountsRecover() {
        val settings = settings()
        LifeProvider.entries.forEach(settings::dismissLifeLoginNotice)
        var expired by mutableStateOf(false)
        val logins = mutableListOf<LifeProvider>()
        val tags = listOf("life-login-notice", "hui798-login-notice", "hellobike-login-notice")
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(ColorSchemeMode.Light) {
                PaymentPage(PreviewData.paymentState(false).copy(code = null, account = null,
                    error = "付款码暂不可用", busy = false), settings, 0.dp, {},
                    lifeLoginRequired = true, hui798LoginRequired = true, hellobikeLoginRequired = true,
                    lifeLoginExpired = expired, hui798LoginExpired = expired, hellobikeLoginExpired = expired,
                    onLifeLogin = { logins += LifeProvider.Panggui },
                    onHui798Login = { logins += LifeProvider.Hui798 },
                    onHellobikeLogin = { logins += LifeProvider.Hellobike })
            }
        } }
        tags.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.runOnIdle { expired = true }
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed().performClick() }
        compose.runOnIdle {
            assertEquals(listOf(LifeProvider.Panggui, LifeProvider.Hui798, LifeProvider.Hellobike), logins)
            assertTrue(settings.pangguiLoginNoticeDismissed.value)
            assertTrue(settings.hui798LoginNoticeDismissed.value)
            assertTrue(settings.hellobikeLoginNoticeDismissed.value)
            expired = false
        }
        compose.mainClock.advanceTimeBy(1000)
        tags.forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.runOnIdle { expired = true }
        tags.forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
    }

    @Test fun paymentDismissesOnlyChosenReminderAndKeepsSettingsLoginAvailable() {
        val settings = settings()
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) {
            AppTheme(ColorSchemeMode.Light) {
                PaymentPage(PreviewData.paymentState(false), settings, 0.dp, {}, lifeLoginRequired = true, hui798LoginRequired = true)
            }
        } }
        compose.onNodeWithTag("payment-code-card").assertIsDisplayed()
        compose.onNodeWithTag("life-login-notice-dismiss").performClick()
        compose.waitForIdle()
        assertTrue(settings.pangguiLoginNoticeDismissed.value)
        assertFalse(settings.hui798LoginNoticeDismissed.value)
        assertEquals("可在设置 → 生活服务账户中登录", ShadowToast.getTextOfLatestToast())
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("life-login-notice").assertDoesNotExist()
        compose.onNodeWithTag("hui798-login-notice").assertIsDisplayed()
        compose.onNodeWithTag("payment-code-card").assertIsDisplayed()
        assertTrue(settings().pangguiLoginNoticeDismissed.value)
        capture("payment-light")
    }

    @Composable
    private fun SettingsFixture(accounts: List<ManagedLifeAccount>, dark: Boolean = false,
        onCheck: (LifeProvider) -> Unit = {}, onLogin: (LifeProvider) -> Unit = {},
        onRemove: (LifeProvider) -> Unit = {}) {
        AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
            Scaffold(topBar = { DashboardTopBar("设置") }) { padding ->
                AppSettingsContent(Modifier.fillMaxSize(), PaddingValues(top = padding.calculateTopPadding(), bottom = 28.dp),
                    settings(), AppUpdateUiState(), {}, {}, accounts, true, {}, onCheck, onLogin, onRemove)
            }
        }
    }

    @Test @Config(qualifiers = "+zh-rCN")
    fun settingsContainsBothAccountsAndAllowsExitDuringServiceRecovery() {
        val accounts = listOf(
            ManagedLifeAccount(LifeProvider.Hui798, LifeAccountConnection("138****0000", LifeAccountStatus.Verified), restoring = true),
            ManagedLifeAccount(LifeProvider.Panggui, LifeAccountConnection()))
        var logins = 0; var removed: LifeProvider? = null
        compose.setContent { SettingsFixture(accounts, onLogin = { logins++ }, onRemove = { removed = it }) }
        compose.onNodeWithText("生活服务账户", substring = false).assertIsDisplayed()
        compose.onNodeWithText("通用", substring = false).assertIsDisplayed()
        compose.onNodeWithText("应用更新", substring = false).assertIsDisplayed()
        compose.onNodeWithTag("panggui-account-row").performClick()
        assertEquals(1, logins)
        capture("settings-light")
        compose.onNodeWithText("全部检查").assertDoesNotExist()
        compose.onNodeWithText("管理账户").assertDoesNotExist()
        compose.onNodeWithTag("hui798-account-row").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("life-account-logout-dialog").assertIsDisplayed()
        compose.onNodeWithText("退出慧生活798账户").assertIsDisplayed()
        compose.onNodeWithText("暂时无法退出").assertDoesNotExist()
        compose.onNodeWithText("查看进度").assertDoesNotExist()
        compose.onNodeWithTag("life-account-exit").assertIsEnabled()
        compose.onNodeWithText("更换账户").assertDoesNotExist()
        capture("account-logout-recovering")
        compose.onNodeWithTag("life-account-exit").performClick()
        assertEquals(LifeProvider.Hui798, removed)
    }

    @Test @Config(qualifiers = "+zh-rCN")
    fun accountExitRequiresConfirmationAndDismissesDialogAfterRemoval() {
        var account by mutableStateOf(ManagedLifeAccount(LifeProvider.Hui798,
            LifeAccountConnection("138****0000", LifeAccountStatus.Verified)))
        var removed: LifeProvider? = null
        compose.setContent { SettingsFixture(listOf(account), onRemove = {
            removed = it; account = account.copy(connection = LifeAccountConnection())
        }) }
        compose.onNodeWithTag("hui798-account-row").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("life-account-logout-dialog").assertIsDisplayed()
        compose.onNodeWithText("退出慧生活798账户").assertIsDisplayed()
        compose.onNodeWithText("退出后需重新登录。").assertIsDisplayed()
        compose.onNodeWithText("重新登录").assertDoesNotExist()
        assertNull(removed)
        capture("account-logout-light")
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertNull(removed)
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
        compose.onNodeWithTag("hui798-account-row").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("life-account-exit").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(LifeProvider.Hui798, removed)
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
        compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
        compose.onNodeWithText("未登录").assertIsDisplayed()
    }

    @Test fun settingsPullRefreshAndExpiredLoginWorkInDarkLargeText() {
        val accounts = listOf(
            ManagedLifeAccount(LifeProvider.Hui798, LifeAccountConnection("138****0000", LifeAccountStatus.Expired)),
            ManagedLifeAccount(LifeProvider.Panggui, LifeAccountConnection("139****0026", LifeAccountStatus.Unverified,
                error = "网络连接失败，暂时无法确认登录状态。")))
        var checked: LifeProvider? = null; var loggedIn: LifeProvider? = null
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                SettingsFixture(accounts, dark = true, onCheck = { checked = it }, onLogin = { loggedIn = it })
            }
        }
        compose.onNodeWithText("登录失效").assertIsDisplayed()
        compose.onNodeWithText("检查失败").assertIsDisplayed()
        compose.onNodeWithText("网络连接失败，暂时无法确认登录状态。").assertIsDisplayed()
        compose.onNodeWithTag("settings-page-list").performTouchInput {
            swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
        }
        compose.runOnIdle { assertEquals(LifeProvider.Panggui, checked) }
        capture("settings-dark-large")
        compose.onNodeWithTag("hui798-account-row").performClick()
        compose.mainClock.advanceTimeBy(500)
        assertEquals(LifeProvider.Hui798, loggedIn)
        compose.onNodeWithTag("life-account-logout-dialog").assertDoesNotExist()
        compose.onNodeWithTag("panggui-account-row").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("退出胖乖生活账户").assertIsDisplayed()
        compose.onNodeWithText("退出后需重新登录。").assertIsDisplayed()
        capture("account-logout-dark-large")
    }

    @Test fun accountExitKeepsFailureInDialogAndPreventsDuplicateConfirmations() {
        var account by mutableStateOf(ManagedLifeAccount(LifeProvider.Panggui,
            LifeAccountConnection("139****0026", LifeAccountStatus.Verified)))
        var removals = 0
        compose.setContent { SettingsFixture(listOf(account), onRemove = {
            removals++; account = account.copy(busy = true, error = null)
        }) }
        compose.onNodeWithTag("panggui-account-row").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("life-account-exit").performClick()
        compose.onNodeWithTag("life-account-exit").assertIsNotEnabled()
        compose.onNodeWithText("正在退出…").assertIsDisplayed()
        assertEquals(1, removals)
        compose.runOnIdle { account = account.copy(busy = false, error = "退出失败，请重试。") }
        compose.onNodeWithTag("life-account-logout-dialog").assertIsDisplayed()
        compose.onNodeWithText("退出失败，请重试。").assertIsDisplayed()
        compose.onNodeWithText("退出后需重新登录。").assertDoesNotExist()
        compose.onNodeWithTag("life-account-exit").assertIsEnabled().performClick()
        assertEquals(2, removals)
    }
}
