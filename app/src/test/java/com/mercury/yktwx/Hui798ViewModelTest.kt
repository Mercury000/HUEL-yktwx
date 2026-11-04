package com.mercury.yktwx

import android.app.Application
import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Hui798ViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private val clock = 1_700_000_000_000L
    private val device = Hui798Device("123456789012345", "用水设备")
    private val account = Hui798Account("13800000000", "fixture-token")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }
    private class Store(var savedAccount: Hui798Account?, var saved: List<Hui798Session> = emptyList()) : Hui798Store {
        override fun account() = savedAccount
        override fun saveAccount(account: Hui798Account) { if (savedAccount != null && savedAccount!!.phone != account.phone) saved = emptyList(); savedAccount = account }
        override fun clearAccount() { savedAccount = null }
        override fun sessions() = saved
        override fun saveSessions(sessions: List<Hui798Session>) { saved = sessions }
    }
    private inner class Api : Hui798Service {
        var reading = Hui798Reading(99, null)
        var starts = 0; var ends = 0; var reads = 0
        var lostStart = false; var lostEnd = false; var holdEnd = false
        var authFailure = false
        var captchaGate: CompletableDeferred<Unit>? = null
        val captchaKeys = mutableListOf<String>()
        var smsKey = ""
        override suspend fun captcha(key: String): ByteArray {
            captchaKeys.add(key)
            captchaGate?.let { withContext(NonCancellable) { it.await() } }
            return ByteArrayOutputStream().also { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }
        override suspend fun sendCode(phone: String, imageCode: String, key: String) { smsKey = key }
        override suspend fun login(phone: String, code: String) = Hui798Account(phone, "new-token")
        override suspend fun device(id: String, token: String) = device.copy(id = id)
        override suspend fun status(id: String, token: String): Hui798Reading { reads++; if (authFailure) throw Hui798Exception("fixture expired", true); return reading }
        override suspend fun start(id: String, token: String) { starts++; reading = Hui798Reading(1, clock); if (lostStart) throw IOException("fixture lost response") }
        override suspend fun end(id: String, token: String) { ends++; if (!holdEnd) reading = Hui798Reading(99, null); if (lostEnd) throw IOException("fixture lost response") }
    }
    private fun model(api: Api, store: Store): Hui798ViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return Hui798ViewModel(app, api, store, dispatcher) { clock }.also { models.put("model-${System.identityHashCode(it)}", it) }
    }
    private fun running() = Hui798Session(device, WaterPhase.Running, clock, true, clock, clock, sessionId = "fixture-session")
    @Test fun duplicatedStartAndStopWaitForDeviceConfirmationRatherThanCommandAcceptance() = runTest(dispatcher) {
        val api = Api(); val store = Store(account); val model = model(api, store); runCurrent()
        model.pageVisible(true); model.openDevice(device.id); runCurrent()
        model.startWater(); model.startWater(); runCurrent()
        assertEquals(1, api.starts); assertEquals(WaterPhase.Running, model.state.value.session?.phase)
        api.holdEnd = true
        model.stopWater(); model.stopWater(); runCurrent()
        assertEquals(1, api.ends); assertEquals(WaterPhase.Stopping, model.state.value.session?.phase)
        assertNull(model.state.value.session?.endedAtMillis)
        api.reading = Hui798Reading(99, null); advanceTimeBy(1000); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.session?.phase); assertEquals(clock, store.saved.single().endedAtMillis)
    }
    @Test fun lostEndResponseIsRecoveredByReadingWithoutRepeatingTheCommand() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock); lostEnd = true }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent(); model.openSession("fixture-session")
        model.stopWater(); runCurrent(); assertEquals(WaterPhase.Stopping, model.state.value.session?.phase)
        model.refreshState(); runCurrent()
        assertEquals(1, api.ends); assertEquals(WaterPhase.Ended, model.state.value.session?.phase)
    }
    @Test fun lostStartDoesNotAdoptAnUnidentifiedRunOrReplayStartOnRetry() = runTest(dispatcher) {
        val api = Api().apply { lostStart = true }; val model = model(api, Store(account)); runCurrent()
        model.pageVisible(true); model.openDevice(device.id); runCurrent(); model.startWater(); runCurrent()
        assertEquals(WaterPhase.Starting, model.state.value.session?.phase); assertFalse(model.state.value.session!!.startAcknowledged)
        model.refreshState(); runCurrent()
        assertEquals(1, api.starts); assertTrue(model.state.value.confirmedSessions.isEmpty()); assertNotNull(model.state.value.error)
    }
    @Test fun foreignSessionCannotBeStoppedAndMissingNetworkDoesNotEraseLocalProgress() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock + 10_000) }
        val store = Store(account, listOf(running())); val model = model(api, store); runCurrent(); model.openSession("fixture-session")
        model.stopWater(); runCurrent()
        assertEquals(0, api.ends); assertTrue(model.state.value.confirmedSessions.isEmpty()); assertEquals(listOf(running()), store.saved)
        assertNotNull(model.state.value.error)
    }
    @Test fun foregroundPollingUpdatesStoredProgressAndCompletionCanBeRemoved() = runTest(dispatcher) {
        val api = Api().apply { reading = Hui798Reading(1, clock) }; val store = Store(account, listOf(running()))
        val model = model(api, store); runCurrent(); model.environment(true); runCurrent()
        assertEquals(setOf("fixture-session"), model.state.value.confirmedSessions)
        model.environment(false); val reads = api.reads; advanceTimeBy(5000); runCurrent(); assertEquals(reads, api.reads)
        api.reading = Hui798Reading(99, null); model.environment(true); runCurrent()
        assertEquals(WaterPhase.Ended, model.state.value.sessions.single().phase)
        model.dismissSession("fixture-session"); runCurrent(); assertTrue(store.saved.isEmpty()); assertTrue(model.state.value.sessions.isEmpty())
    }
    @Test fun expiryLeavesProgressOwnedByTheDisconnectedPlatform() = runTest(dispatcher) {
        val api = Api().apply { authFailure = true }; val store = Store(account, listOf(running())); val model = model(api, store)
        runCurrent(); model.environment(true); runCurrent()
        assertNull(store.savedAccount); assertFalse(model.state.value.connected); assertEquals(listOf(running()), model.state.value.sessions)
        assertFalse(model.state.value.loginVisible)
    }
    @Test fun refreshedCaptchaUsesOnlyItsMatchingKeyEvenWhenTheOldImageArrivesLate() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>(); val api = Api().apply { captchaGate = gate }; val model = model(api, Store(null)); runCurrent()
        model.connectAccount(); runCurrent(); val oldKey = api.captchaKeys.single()
        api.captchaGate = null; model.refreshCaptcha(); runCurrent()
        assertNotNull(model.state.value.captcha); assertNotEquals(oldKey, api.captchaKeys.last())
        gate.complete(Unit); runCurrent()
        model.phoneChanged(account.phone); model.imageCodeChanged("aB12"); model.sendCode(); runCurrent()
        assertEquals(api.captchaKeys.last(), api.smsKey); assertEquals(60, model.state.value.codeSeconds)
        model.dismissLogin(); advanceTimeBy(60_000); runCurrent()
    }
    @Test fun inlineLoginDoesNotStartOrOpenADeviceUntilExplicitlyRequested() = runTest(dispatcher) {
        val api = Api(); val model = model(api, Store(null)); runCurrent(); model.connectAccount(); runCurrent()
        model.phoneChanged(account.phone); model.codeChanged("123456"); model.login(); runCurrent()
        assertTrue(model.state.value.connected); assertFalse(model.state.value.loginVisible)
        assertNull(model.state.value.device); assertEquals(0, api.starts); assertEquals(0, api.reads)
    }
}
