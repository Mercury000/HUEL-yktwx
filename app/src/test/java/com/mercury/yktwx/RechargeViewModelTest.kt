package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
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
        service.launchGate.complete(Unit)
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
        assertEquals(RechargePhase.Amount, model.state.value.phase)
        assertNull(model.state.value.pending?.orderId)
        model.checkResult()
        assertEquals(1, service.creations)
        assertEquals(0, service.launches)
        assertEquals(0, service.resultQueries)
        model.finish()
        open()
        service.creationFailure = RechargePaymentRejected("金额大于每笔交易允许的最大金额800元")
        model.submit(1000)
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Failed, model.state.value.phase)
        assertEquals("金额大于每笔交易允许的最大金额800元", model.state.value.error)
        assertNull(model.state.value.pending?.orderId)
        model.checkResult(); model.submit(1000)
        assertEquals(2, service.creations)
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

    @Test fun serverStatusesAndMessagesControlResultsWithoutRepeatingPayment() {
        prepareOrder()
        service.resultStatus = -1
        service.resultMessage = "后端订单提示"
        model.checkResult()
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Checking, model.state.value.phase)
        assertEquals("后端订单提示", model.state.value.error)
        assertEquals(3, service.resultQueries)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
        for (status in listOf(0, 2)) {
            service.resultStatus = status
            model.checkResult()
            await { !model.state.value.loading }
            assertEquals(if (status == 0) RechargePhase.Failed else RechargePhase.TimedOut, model.state.value.phase)
            assertEquals("后端订单提示", model.state.value.error)
            assertEquals(1, service.creations)
        }
    }

    @Test fun queryFailureShowsRequestErrorAndAllowsReadOnlyRetry() {
        prepareOrder()
        service.resultFailure = NetworkFailure()
        model.checkResult()
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Checking, model.state.value.phase)
        assertEquals(NetworkFailure().message, model.state.value.error)
        assertEquals(1, service.resultQueries)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
        service.resultFailure = null
        model.checkResult()
        await { model.state.value.phase == RechargePhase.Paid }
        assertEquals(2, service.resultQueries)
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
        assertEquals(RechargePhase.Checking, model.state.value.phase)
        assertEquals(1, service.resultQueries)
        assertFalse(model.canLaunchWechat())
    }

    private fun open() {
        model.onForeground()
        model.onResumed(true)
        model.open(vault.username, "fake-account", false)
        await { model.state.value.visible && !model.state.value.loading }
    }

    @Test fun othersRechargeUsesSameCreationLaunchAndResultFlowWithRecipientMetadata() {
        val recipient = RechargeRecipient("other-card", "other-target", "示例同学", "202600000002", "统一账户")
        val config = RechargeConfiguration("401", recipient.targetId, listOf(1000), 1, 50000, true, false, recipient)
        model.onForeground()
        model.onResumed(true)
        model.openOthers(vault.username, config, false)
        assertTrue(model.state.value.visible)
        assertEquals(0, service.configurations)
        model.submit(1000)
        await { model.state.value.phase == RechargePhase.AwaitingPayment && !model.state.value.loading }
        assertEquals(1, service.creations); assertEquals(1, service.launches)
        assertSame(recipient, model.state.value.pending!!.recipient)
        assertEquals("other-target", model.state.value.pending!!.targetId)
        model.checkResult()
        await { model.state.value.phase == RechargePhase.Paid }
        assertEquals(1, service.resultQueries)
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
        var launchFailure: Exception? = null
        var resultFailure: Exception? = null
        var resultStatus = 1
        var resultMessage: String? = null
        var configurationGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var creationGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var resultGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var launchGate = CompletableDeferred<Unit>().apply { complete(Unit) }

        override suspend fun rechargeConfiguration(token: String, preferredTarget: String?): RechargeConfiguration {
            configurations++
            configurationGate.await()
            return RechargeConfiguration("401", "fake-account", listOf(1000, 5000), 1, 50_000, true, false)
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
            launchGate.await()
            launchFailure?.let { throw it }
            return WechatRechargeLaunch("weixin://wap/pay?prepayid=fake-prepay")
        }

        override suspend fun rechargeResult(token: String, pending: PendingRecharge): RechargeResult {
            resultQueries++
            withContext(NonCancellable) { resultGate.await() }
            resultResponses++
            resultFailure?.let { throw it }
            return RechargeResult(resultStatus == 1, resultStatus, resultMessage)
        }
    }
    @Test fun explicitLostRejectionEndsOwnAndOthersRechargeWithoutLaunchOrQuery() {
        for (other in listOf(false, true)) {
            if (other) {
                val recipient = RechargeRecipient("other-card", "other-target", "示例", "123456", "统一账户")
                model.openOthers(vault.username, RechargeConfiguration("401", "other-target", listOf(1000), 1, 50_000, true, false, recipient), false)
            } else open()
            service.launchFailure = RechargePaymentRejected("账户已挂失")
            val previousCreations = service.creations
            model.submit(1000)
            await { !model.state.value.loading && model.state.value.phase == RechargePhase.Failed }
            assertEquals("账户已挂失", model.state.value.error)
            assertFalse(model.canLaunchWechat())
            assertNull(runBlocking { withTimeoutOrNull(50) { model.events.first() } })
            assertEquals("FAKE-ORDER", model.state.value.pending?.orderId)
            model.onForeground(); model.onResumed(true); model.onWindowFocusChanged(true)
            model.checkResult(); model.submit(1000)
            assertEquals(0, service.resultQueries)
            assertEquals(previousCreations + 1, service.creations)
            model.finish()
            assertNull(model.state.value.pending)
        }
    }

    @Test fun launchRequestFailureReturnsToAmountWithoutAutomaticQuery() {
        open()
        service.launchFailure = NetworkFailure()
        model.submit(1000)
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Amount, model.state.value.phase)
        assertNull(model.state.value.pending)
        assertEquals(NetworkFailure().message, model.state.value.error)
        assertFalse(model.canLaunchWechat())
        model.checkResult()
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
        assertEquals(0, service.resultQueries)
    }

    @Test fun paymentParameterFetchIsNotYetAwaitingExternalPayment() {
        open()
        service.launchGate = CompletableDeferred()
        service.launchFailure = RechargePaymentRejected("账户已挂失")
        model.submit(1000)
        await { service.launches == 1 }
        assertTrue(model.state.value.loading)
        assertEquals(RechargePhase.Creating, model.state.value.phase)
        assertFalse(model.canLaunchWechat())
        service.launchGate.complete(Unit)
        await { model.state.value.phase == RechargePhase.Failed }
        assertEquals(0, service.resultQueries)
    }

    @Test fun requestErrorReturnsToAmountWithoutLosingOfficialText() {
        open()
        service.launchFailure = YktException("新的业务提示")
        model.submit(1000)
        await { !model.state.value.loading }
        assertEquals(RechargePhase.Amount, model.state.value.phase)
        assertEquals("新的业务提示", model.state.value.error)
        assertNull(model.state.value.pending)
        assertEquals(0, service.resultQueries)
    }

    @Test fun nonExplicitFailureDoesNotPermanentlyDisableReadOnlyOrderChecks() {
        prepareOrder()
        model.launchFailed()
        assertEquals(RechargePhase.Failed, model.state.value.phase)
        model.checkResult()
        await { model.state.value.phase == RechargePhase.Paid }
        assertEquals(1, service.resultQueries)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
    }

    @Test fun unpaidResultCanBeRecheckedWithoutRepeatingCreationOrPayment() {
        prepareOrder()
        service.resultStatus = 0
        model.checkResult()
        await { !model.state.value.loading && model.state.value.phase == RechargePhase.Failed }
        service.resultStatus = 1
        model.checkResult()
        await { model.state.value.phase == RechargePhase.Paid }
        assertEquals(4, service.resultQueries)
        assertEquals(1, service.creations)
        assertEquals(1, service.launches)
    }

}
