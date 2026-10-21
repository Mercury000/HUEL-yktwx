package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
class RechargeViewModelTest {
    private val vault = MemoryVault()
    private val service = FakeRechargeService()
    // Any unexpected authentication/payment request fails locally, never reaches the network.
    private val repository = AccountRepository(YktApi { error("Unexpected network request") }, vault, service)
    private val model = RechargeViewModel(ApplicationProvider.getApplicationContext<Application>(), repository, 0L)
    private val store = ViewModelStore().apply { put("recharge", model) }

    @After fun cleanup() {
        store.clear()
        service.configurationGate.complete(Unit)
        service.creationGate.complete(Unit)
        service.resultGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun invalidAmountsAndPreviewCannotCreateOrders() {
        open()
        listOf(0L, -1L, 50_001L).forEach { amount ->
            model.submit(amount)
            assertNotNull(model.state.value.error)
            assertNull(model.state.value.pending)
        }
        assertEquals(0, service.creations)
        model.open(vault.username, "fake-account", true)
        await { model.state.value.visible }
        model.submit(1000)
        assertEquals(0, service.creations)
        assertNull(model.state.value.pending)
    }

    @Test fun repeatedClicksCreateAndLaunchOnlyOneOrderWithExactCents() {
        open()
        service.creationGate = CompletableDeferred()
        model.submit(1234)
        assertTrue(model.state.value.loading)
        model.submit(1234)
        await { service.creations == 1 }
        model.submit(5000)
        service.creationGate.complete(Unit)
        await { !model.state.value.loading }
        model.submit(5000)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
        assertEquals(1234L, service.submittedCents)
        assertEquals(1234L, model.state.value.pending?.amountCents)
        assertEquals(RechargePhase.AwaitingPayment, model.state.value.phase)
        assertEquals(0, service.resultQueries)
    }

    @Test fun ambiguousCreationIsNotRetriedOrReportedAsSuccess() {
        open()
        service.creationFailure = NetworkFailure()
        model.submit(1000)
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Unknown, model.state.value.phase)
        assertNull(model.state.value.pending?.orderId)
        model.submit(1000)
        model.checkResult()
        assertEquals(1, service.creations)
        assertEquals(0, service.launches)
        assertEquals(0, service.resultQueries)
    }

    @Test fun wechatReturnNeedsServerConfirmationBeforeSuccess() {
        prepareOrder()
        service.resultGate = CompletableDeferred()
        model.wechatLaunched()
        assertEquals(RechargePhase.AwaitingPayment, model.state.value.phase)
        assertEquals(0, service.resultQueries)
        model.onPaused()
        model.onBackground()
        model.onForeground()
        model.onResumed(true)
        await { service.resultQueries == 1 }
        assertEquals(RechargePhase.Checking, model.state.value.phase)
        assertTrue(model.state.value.loading)
        service.resultGate.complete(Unit)
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Paid, model.state.value.phase)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
        assertEquals(1, service.resultQueries)
    }

    @Test fun unconfirmedResultsExhaustReadBudgetWithoutRepeatingPayment() {
        prepareOrder()
        service.resultStatus = -1
        model.checkResult()
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Unknown, model.state.value.phase)
        assertEquals(3, service.resultQueries)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
    }

    @Test fun failedQueriesStayUnknownInsteadOfClaimingPaidOrUnpaid() {
        prepareOrder()
        service.resultFailure = NetworkFailure()
        model.checkResult()
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Unknown, model.state.value.phase)
        assertEquals(3, service.resultQueries)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
    }

    @Test fun dismissedCreationCannotLaunchOrOverwriteReopenedAttempt() {
        open()
        service.creationGate = CompletableDeferred()
        model.submit(1000)
        await { service.creations == 1 }
        model.dismiss()
        assertFalse(model.canLaunchWechat())
        model.open(vault.username, "fake-account", false)
        service.creationGate.complete(Unit)
        await { service.creationResponses == 1 && model.state.value.visible && !model.state.value.loading }
        assertEquals(RechargePhase.Amount, model.state.value.phase)
        assertNull(model.state.value.pending)
        assertNull(model.state.value.error)
        assertEquals(0, service.launches)
    }

    @Test fun oldAccountResultCannotOverwriteNewAccountAttempt() {
        prepareOrder()
        service.resultGate = CompletableDeferred()
        model.checkResult()
        await { service.resultQueries == 1 }
        vault.username = "other-user"
        model.accountChanged(vault.username)
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.pending)
        service.configurationGate = CompletableDeferred()
        model.open(vault.username, "fake-account", false)
        service.resultGate.complete(Unit)
        await { service.resultResponses == 1 && service.configurations == 2 }
        // Hold the new configuration response so it cannot conceal a stale state update.
        assertEquals(RechargePhase.Amount, model.state.value.phase)
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.pending)
        assertNull(model.state.value.error)
        service.configurationGate.complete(Unit)
        await { model.state.value.visible && !model.state.value.loading }
        assertEquals(RechargePhase.Amount, model.state.value.phase)
        assertEquals(1, service.creations)
    }

    @Test fun ownerIsCheckedAgainAfterAnInFlightSuccessfulResponse() {
        prepareOrder()
        service.resultGate = CompletableDeferred()
        model.checkResult()
        await { service.resultQueries == 1 }
        vault.username = "other-user"
        // Even before the UI delivers accountChanged, the old owner's result must be rejected.
        service.resultGate.complete(Unit)
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Unknown, model.state.value.phase)
        assertEquals(1, service.resultQueries)
        assertFalse(model.canLaunchWechat())
    }

    private fun open() {
        model.onForeground()
        model.onResumed(true)
        model.open(vault.username, "fake-account", false)
        await { model.state.value.visible && !model.state.value.loading }
    }

    private fun prepareOrder() {
        open()
        model.submit(1000)
        await { model.state.value.phase == RechargePhase.AwaitingPayment && !model.state.value.loading }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for recharge state")
    }

    private class MemoryVault : Vault {
        var username = "test-user"
        override fun credentials() = Credentials(username, "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) { username = value.username }
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() { username = "" }
    }

    private class FakeRechargeService : RechargeService {
        var configurations = 0
        var creations = 0
        var launches = 0
        var resultQueries = 0
        var creationResponses = 0
        var resultResponses = 0
        var submittedCents: Long? = null
        var creationFailure: Exception? = null
        var resultFailure: Exception? = null
        var resultStatus = 1
        var configurationGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var creationGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var resultGate = CompletableDeferred<Unit>().apply { complete(Unit) }

        override suspend fun rechargeConfiguration(token: String, preferredTarget: String?): RechargeConfiguration {
            configurations++
            configurationGate.await()
            return RechargeConfiguration("401", "fake-account", "统一账户", 0, listOf(1000, 5000), 1, 50_000, true, false)
        }

        override suspend fun createRecharge(token: String, config: RechargeConfiguration, amountCents: Long): RechargeOrder {
            creations++
            submittedCents = amountCents
            // Deliberately return late even after cancellation, like a non-cooperative transport.
            withContext(NonCancellable) { creationGate.await() }
            creationResponses++
            creationFailure?.let { throw it }
            return RechargeOrder("FAKE-ORDER", "81")
        }

        override suspend fun launchRecharge(token: String, pending: PendingRecharge): WechatRechargeLaunch {
            launches++
            return WechatRechargeLaunch("weixin://wap/pay?prepayid=fake-prepay")
        }

        override suspend fun rechargeResult(token: String, pending: PendingRecharge): RechargeResult {
            resultQueries++
            withContext(NonCancellable) { resultGate.await() }
            resultResponses++
            resultFailure?.let { throw it }
            return RechargeResult(resultStatus == 1, pending.amountCents, null, resultStatus)
        }
    }
}
