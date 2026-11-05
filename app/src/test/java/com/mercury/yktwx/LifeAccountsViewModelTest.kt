package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LifeAccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private var clock = 1_700_000_000_000L
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }

    private class PgStore(var owner: LifeAccount?) : LifeDeviceStore {
        var laundry = emptyList<LaundrySession>()
        override fun account() = owner
        override fun saveAccount(account: LifeAccount) { owner = account }
        override fun clearAccount() { owner = null }
        override fun session(): WaterSession? = null
        override fun saveSession(session: WaterSession) {}
        override fun clearSession() {}
        override fun laundrySessions() = laundry
        override fun saveLaundrySessions(sessions: List<LaundrySession>) { laundry = sessions }
        override fun removeAccount() { laundry = emptyList(); owner = null }
    }
    private class PgApi : LifeDeviceService by LifeDeviceApi("fixture", { error("Unexpected device request") }), LifeAccountService {
        var phone = "13800000000"
        var reads = 0
        var error: Exception? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun profile(token: String): LifeAccountProfile {
            reads++
            val result = LifeAccountProfile("fixture-user", phone)
            val failure = error
            gate?.let { withContext(NonCancellable) { it.await() } }
            failure?.let { throw it }
            return result
        }
        override suspend fun login(phone: String, code: String) = LifeAccount(phone, "new-token")
    }
    private fun pg(api: PgApi, store: PgStore) = LifeDevicesViewModel(app, api, store, dispatcher) { clock }
        .also { models.put("pg", it) }

    @Test fun pangguiChecksAtRestoreAndCachesSuccessfulReads() = runTest(dispatcher) {
        val api = PgApi(); val store = PgStore(LifeAccount("13800000000", "old-token")); val model = pg(api, store)
        runCurrent()
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        assertEquals("138****0000", model.accountConnection.value.phone)
        assertEquals(1, api.reads)
        model.checkAccount(); runCurrent(); assertEquals(1, api.reads)
        clock += 300_001; model.checkAccount(); runCurrent(); assertEquals(2, api.reads)
        model.checkAccount(force = true); runCurrent(); assertEquals(3, api.reads)
    }

    @Test fun networkFailureKeepsCredentialsButExpiredProfileDoesNotOpenLoginSheet() = runTest(dispatcher) {
        val api = PgApi().apply { error = IOException("offline") }
        val store = PgStore(LifeAccount("13800000000", "old-token")); val model = pg(api, store); runCurrent()
        assertEquals(LifeAccountStatus.Unverified, model.accountConnection.value.status)
        assertNotNull(store.owner)
        assertFalse(model.state.value.loginVisible)
        api.error = LifeDeviceException("expired", true)
        model.checkAccount(force = true); runCurrent()
        assertNull(store.owner)
        assertEquals(LifeAccountStatus.Expired, model.accountConnection.value.status)
        assertEquals("138****0000", model.accountConnection.value.phone)
        assertFalse(model.state.value.loginVisible)
        assertTrue(model.removeAccount()); runCurrent()
        assertEquals(LifeAccountStatus.NotLoggedIn, model.accountConnection.value.status)
    }

    @Test fun lateAccountReadCannotExpireAReplacementCredential() = runTest(dispatcher) {
        val api = PgApi().apply { error = LifeDeviceException("old expired", true); gate = CompletableDeferred() }
        val store = PgStore(LifeAccount("13800000000", "old-token")); val model = pg(api, store); runCurrent()
        assertTrue(model.manageLogin(change = true))
        model.phoneChanged("13900000001"); model.codeChanged("123456"); model.login(); runCurrent()
        assertEquals("13900000001", store.owner?.phone)
        api.gate!!.complete(Unit); runCurrent()
        assertEquals("new-token", store.owner?.token)
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        assertEquals("139****0001", model.accountConnection.value.phone)
    }

    @Test fun activityBlocksPangguiRemovalAndSwitchButAllowsOriginalAccountReauthentication() = runTest(dispatcher) {
        val api = PgApi(); val store = PgStore(LifeAccount("13800000000", "old-token"))
        val device = LifeDevice("fixture-device", "fixture-code", "洗衣机", "00", "s", "o", "s", "i", "", "", "before",
            false, false, null, emptyList(), emptyList())
        store.laundry = listOf(LaundrySession(device, "sku", "1.00", "2", phase = LaundryPhase.Running))
        val model = pg(api, store); runCurrent()
        assertFalse(model.manageLogin(change = true))
        assertFalse(model.removeAccount())
        assertTrue(model.manageLogin())
        model.phoneChanged("13900000001"); model.codeChanged("123456"); model.login(); runCurrent()
        assertEquals("old-token", store.owner?.token)
        assertTrue(model.state.value.loginError!!.contains("原账户"))
    }

    private class HuiStore(var owner: Hui798Account?) : Hui798Store {
        var records = emptyList<Hui798Session>()
        override fun account() = owner
        override fun saveAccount(account: Hui798Account) { owner = account }
        override fun clearAccount() { owner = null }
        override fun sessions() = records
        override fun saveSessions(sessions: List<Hui798Session>) { records = sessions }
    }
    private class HuiApi : Hui798Service by Hui798Api("https://fixture.invalid/api", "fixture", { error("Unexpected device request") }) {
        var failDevice = false
        var overviewError: Exception? = null
        val device = Hui798Device("fixture-device", "用水设备")
        override suspend fun overview(token: String): Hui798Overview {
            overviewError?.let { throw it }
            return Hui798Overview("fixture-user", listOf(device))
        }
        override suspend fun deviceUse(id: String, token: String): Hui798DeviceUse {
            if (failDevice) throw IOException("device offline")
            return Hui798DeviceUse(device, Hui798Reading(1, 1_700_000_000_000L), "fixture-user")
        }
    }
    private fun hui(api: HuiApi, store: HuiStore) = Hui798ViewModel(app, api, store, dispatcher) { clock }
        .also { models.put("hui", it) }

    @Test fun huiAccountVerificationSurvivesDeviceRecoveryFailure() = runTest(dispatcher) {
        val api = HuiApi().apply { failDevice = true }
        val store = HuiStore(Hui798Account("13800000000", "old-token", "fixture-user")); val model = hui(api, store); runCurrent()
        assertEquals(LifeAccountStatus.Verified, model.accountConnection.value.status)
        assertTrue(model.state.value.sessions.isEmpty())
        assertNotNull(model.state.value.error)
        assertNotNull(store.owner)
    }

    @Test fun huiActivityBlocksRemovalAndSwitchButAllowsLogin() = runTest(dispatcher) {
        val api = HuiApi(); val model = hui(api, HuiStore(Hui798Account("13800000000", "old-token", "fixture-user"))); runCurrent()
        assertTrue(model.hasActiveServices)
        assertFalse(model.removeAccount())
        assertFalse(model.manageLogin(change = true))
        // Reauthentication opens the existing captcha flow; its transport is not part of this fixture.
        assertTrue(model.manageLogin())
        runCurrent()
        assertTrue(model.state.value.loginVisible)
    }
}
