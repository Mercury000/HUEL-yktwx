package com.mercury.yktwx

import com.mercury.yktwx.data.cardServiceApiFixture
import com.mercury.yktwx.data.feeApiFixture
import com.mercury.yktwx.data.rechargeApiFixture
import com.mercury.yktwx.data.yktApiFixture
import com.mercury.yktwx.data.accountRepositoryFixture
import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionLifecycleTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val owner = "fake-owner"
    private val card = CampusCard("fake-card", "校园卡", owner, false, false, 0)
    private val account = PaymentAccount(card.account, "pay-account", "1", "账户", 10000)
    private val config = RechargeConfiguration("fee", "target", listOf(1000), null, null, true, false)
    private val cache by lazy { DisplayCache(folder.root, { bytes, _ -> bytes }, { bytes, _ -> bytes }) }
    private var logins = 0
    private var writes = 0
    private var feeFailure: YktException? = null
    private var writeFailure: YktException? = null
    private var profileEntered = false
    private var profileReturned = false
    private var holdProfile = false
    private val profileGate = CompletableDeferred<Unit>()
    private val vault = object : Vault {
        var credentials: Credentials? = Credentials(owner, "password")
        var token: Session? = Session("restored-token", Long.MAX_VALUE)
        override fun credentials() = credentials
        override fun session() = token
        override fun saveCredentials(value: Credentials) { credentials = value }
        override fun saveSession(value: Session) { token = value }
        override fun clearSession() { token = null }
        override fun clear() { credentials = null; token = null }
    }
    private val service = object : YktService by yktApiFixture({ error("Unexpected live request") }) {
        override suspend fun login(credentials: Credentials): Session {
            logins++
            return Session("new-token-$logins", Long.MAX_VALUE)
        }
        override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit) =
            PaymentBatch(listOf("00000000000000000000"), 60, account)
        override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(100, 200)
        override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = type
    }
    private val fees = object : FeeService by feeApiFixture({ error("Unexpected live fee request") }) {
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            feeFailure?.let { throw it }
            return FeeConfiguration(project, config)
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int,
            selections: Map<String, String>, detail: Boolean) = FeeQuery(display = listOf("余额" to "100"))
        override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) {
            writes++
            writeFailure?.let { throw it }
        }
    }
    private val cards = object : CardService by cardServiceApiFixture({ error("Unexpected live card request") }) {
        override suspend fun cardConfiguration(token: String) = CardServiceConfiguration(listOf(card), false, false)
        override suspend fun passwordConfiguration(token: String) = CardPasswordConfiguration(listOf(card))
    }
    private val recharge = object : RechargeService by rechargeApiFixture({ error("Unexpected live recharge request") }) {
        override suspend fun rechargeConfiguration(token: String, preferredTarget: String?) = config
    }
    private val profiles = object : ProfileService {
        override suspend fun profile(token: String): PersonalProfile {
            if (holdProfile) {
                profileEntered = true
                withContext(NonCancellable) { profileGate.await() }
                profileReturned = true
            }
            return PersonalProfile("旧资料", owner, "部门", "专业", null)
        }
        override suspend fun avatar(path: String): ByteArray? = null
    }
    private val repository by lazy {
        accountRepositoryFixture(service, vault, rechargeService = recharge, feeService = fees,
            cardService = cards, profileService = profiles, displayCache = cache)
    }
    private fun <T : androidx.lifecycle.ViewModel> keep(key: String, model: T): T = model.also { store.put(key, it) }
    private val payment by lazy { keep("payment", paymentViewModelFixture(app, repository)) }
    private val bills by lazy { keep("bill", billViewModelFixture(app, repository, initialEnvironment = ReadEnvironment.Inactive)) }
    private val mine by lazy { keep("my", myViewModelFixture(app, repository)) }
    private val hall by lazy { keep("hall", hallViewModelFixture(app, repository, initialEnvironment = ReadEnvironment.Inactive)) }
    private val fee by lazy { keep("fee", feeViewModelFixture(app, repository)) }
    private val topUp by lazy { keep("recharge", rechargeViewModelFixture(app, repository)) }
    private val cardModel by lazy { keep("card", cardServiceViewModelFixture(app, repository)) }
    private val password by lazy { keep("password", cardPasswordViewModelFixture(app, repository)) }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for session propagation")
    }
    private fun changeSession(change: suspend () -> Unit) {
        // Keep the paused main looper running while a cache writer releases the session mutex.
        val result = CoroutineScope(Dispatchers.Default).async { withTimeout(5000) { change() } }
        await { result.isCompleted }
        runBlocking { result.await() }
    }
    @After fun cleanup() { profileGate.complete(Unit); store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    private fun openAll() {
        val bill = PreviewData.billState()
        runBlocking {
            repository.restoreSession()
            repository.saveDisplay(owner, DisplayKeys.Profile, profiles.profile("unused"), DisplayCodecs.Profile)
            repository.saveDisplay(owner, DisplayKeys.bills(bill.dateFilter, null, null, null, ""),
                BillPageData(bill.items, 1, 1), DisplayCodecs.Page)
            repository.saveDisplay(owner, DisplayKeys.Network, FeeQuery(display = listOf("套餐" to "缓存")), DisplayCodecs.Query)
        }
        payment.initialize(); bills.initialize()
        // These pages bind directly from the authority, without YktApp or accountChanged calls.
        mine; hall
        await { !payment.state.value.restoringLogin && bills.state.value.items.isNotEmpty() &&
            mine.state.value.profile != null && hall.state.value.network != null }
        payment.onForeground()
        await { payment.state.value.code != null }
        fee.open(FeeProject.Network, owner, false)
        topUp.open(owner, null, false)
        cardModel.open(CampusCardAction.ManageState, owner, false)
        password.open(owner, false)
        await { fee.state.value.config != null && topUp.state.value.visible &&
            cardModel.state.value.visible && password.state.value.visible }
    }

    private fun assertCleared() {
        await { payment.state.value.needsLogin && !payment.state.value.busy &&
            bills.state.value.needsLogin && mine.state.value.profile == null && hall.state.value.network == null &&
            fee.state.value.config == null && !topUp.state.value.visible &&
            !cardModel.state.value.visible && !password.state.value.visible }
        assertNull(payment.state.value.code)
        assertNull(payment.state.value.account)
        assertTrue(bills.state.value.items.isEmpty())
        assertNull(mine.state.value.summary)
        assertTrue(hall.state.value.rooms.isEmpty())
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun feeAuthenticationFailureRevokesEveryPageAndDialogWithoutAnActiveUi() {
        openAll()
        feeFailure = CaptchaRequired()
        fee.open(FeeProject.Network, owner, false)
        assertCleared()
        assertEquals(SessionEndReason.CaptchaRequired, repository.sessionState.value.endReason)
        assertNull(vault.credentials())
    }

    @Test fun nonReplayableTokenRejectionKeepsTheAccountAndAllItsDisplays() {
        openAll()
        val before = repository.sessionState.value
        writeFailure = AuthenticationRequired()
        try {
            changeSession { repository.setFeeRoom(owner, FeeProject.Zhengdong, null) }
            fail("Expected token rejection")
        } catch (_: AuthenticationRequired) { }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, repository.sessionState.value)
        assertEquals(1, writes)
        assertEquals(0, logins)
        assertNotNull(vault.credentials()); assertNull(vault.session())
        assertNotNull(payment.state.value.code)
        assertTrue(bills.state.value.items.isNotEmpty())
        assertNotNull(mine.state.value.profile); assertNotNull(hall.state.value.network)
        assertTrue(topUp.state.value.visible && cardModel.state.value.visible && password.state.value.visible)
    }

    @Test fun revokedProfileReadCannotRepopulateAnyCacheWithItsLateResponse() {
        openAll()
        holdProfile = true
        mine.refresh()
        await { profileEntered }
        changeSession { repository.logout() }
        assertCleared()
        profileGate.complete(Unit)
        await { profileReturned }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(mine.state.value.profile)
        assertNull(mine.state.value.avatar)
        assertTrue(folder.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun sameAccountLoginClosesOldDialogsEvenIfCollectorsSkipSignedOut() {
        openAll()
        val before = repository.sessionState.value
        changeSession { repository.login(owner, "changed-password") }
        await { !topUp.state.value.visible && !cardModel.state.value.visible &&
            !password.state.value.visible && fee.state.value.config == null }
        assertEquals(owner, repository.sessionState.value.username)
        assertTrue(repository.sessionState.value.generation > before.generation)
        assertEquals("changed-password", vault.credentials()!!.password)
        assertFalse(payment.state.value.needsLogin)
    }
}
