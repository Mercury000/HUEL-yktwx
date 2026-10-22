package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
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
class FeeViewModelTest {
    private val fees = FakeFees()
    private val repository = AccountRepository(YktApi(), MemoryVault(), feeService = fees)
    private val model = FeeViewModel(ApplicationProvider.getApplicationContext<Application>(), repository, 0L)
    private val store = ViewModelStore().apply { put("fees", model) }

    @After fun cleanup() {
        fees.gate.complete(Unit)
        fees.resultGate.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun rejectionKeepsPasswordDialogAndOrderButDoesNotAutomaticallyRetry() {
        openPassword()
        val preparation = model.state.value.preparation
        fees.failure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":60005,\"success\":false,\"data\":null,\"msg\":\"账户密码错误\"}"))
        model.payAccount("demo-type", "123456")
        await { fees.payments == 1 && !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        assertEquals("账户密码错误", model.state.value.error)
        assertSame(preparation, model.state.value.preparation)
        assertEquals("FAKE-ORDER", model.state.value.order?.pending?.orderId)
        assertEquals(0, fees.resultQueries)
        assertEquals(1, fees.payments)
        fees.failure = null
        model.onForeground(); model.onResumed(true)
        model.payAccount("demo-type", "654321")
        await { model.state.value.resultPhase == RechargePhase.Paid }
        assertEquals(2, fees.payments)
        assertNull(model.state.value.preparation)
    }

    @Test fun server60050KeepsOfficialPasswordDialogWithoutInventingAnAction() {
        openPassword()
        fees.failure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":60050,\"success\":false,\"data\":null,\"msg\":\"账户余额不足！\"}"))
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        assertEquals("账户余额不足！", model.state.value.error)
        assertNotNull(model.state.value.preparation)
        assertEquals("FAKE-ORDER", model.state.value.order?.pending?.orderId)
        assertEquals(0, fees.resultQueries)
    }

    @Test fun expiryShowsFailureAndFinishRetainsTarget() {
        openPassword()
        fees.failure = FeeOrderExpired()
        model.payAccount("demo-type", "123456")
        await { model.state.value.resultPhase == RechargePhase.Failed }
        assertEquals("订单已过期，请重新发起缴费", model.state.value.error)
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNotNull(model.state.value.query?.thirdParty)
        assertNull(model.state.value.order)
    }

    @Test fun lostResponseOnlyQueriesAndPendingNeverBecomesFailure() {
        openPassword()
        model.onForeground(); model.onResumed(true)
        fees.failure = NetworkFailure()
        fees.resultStatus = 0
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading && model.state.value.resultPhase == RechargePhase.Unknown }
        assertEquals(1, fees.payments)
        assertEquals(3, fees.resultQueries)
        assertTrue(model.state.value.error.orEmpty().contains("确认前请勿重复缴费"))
        assertNull(model.state.value.preparation)
    }

    @Test fun preflightFailureAllowsManualRetryWithoutQueryingOrDroppingKeyboard() {
        openPassword()
        val preparation = model.state.value.preparation
        fees.failure = FeePaymentNotStarted("无法确认订单有效期，请稍后重试")
        model.payAccount("demo-type", "123456")
        await { fees.payments == 1 && !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        assertSame(preparation, model.state.value.preparation)
        assertEquals(0, fees.resultQueries)
    }

    @Test fun repeatedSubmissionAndLateResponseCannotChangeCanceledAttempt() {
        openPassword()
        fees.gate = CompletableDeferred()
        model.payAccount("demo-type", "123456")
        assertTrue(model.state.value.loading)
        model.payAccount("demo-type", "123456")
        await { fees.payments == 1 }
        model.cancelPayment()
        fees.gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNull(model.state.value.order)
        assertEquals(1, fees.payments)
        assertEquals(0, fees.resultQueries)
    }

    @Test fun accountQueryContinuesAcrossDialogFocusChanges() {
        openPassword()
        model.onForeground(); model.onResumed(true)
        fees.failure = NetworkFailure()
        fees.resultGate = CompletableDeferred()
        model.payAccount("demo-type", "123456")
        await { fees.resultQueries == 1 }
        model.onWindowFocusChanged(false)
        model.onPaused()
        model.onBackground()
        fees.resultGate.complete(Unit)
        await { model.state.value.resultPhase == RechargePhase.Paid }
        assertFalse(model.state.value.loading)
        assertEquals(1, fees.payments)
        assertEquals(1, fees.resultQueries)
    }

    @Test fun suspendedQueryEndsAtIndependentDeadlineWithoutReplayingPayment() {
        val shortModel = FeeViewModel(ApplicationProvider.getApplicationContext<Application>(), repository,
            checkDelayMillis = 0, checkTimeoutMillis = 100)
        store.put("short-fees", shortModel)
        shortModel.open(FeeProject.Network, "test-user", false)
        await { !shortModel.state.value.loading && shortModel.state.value.query != null }
        shortModel.create(100)
        await { shortModel.state.value.stage == FeeStage.Methods }
        shortModel.chooseMethod(fees.accountMethod)
        await { shortModel.state.value.stage == FeeStage.Password }
        fees.failure = NetworkFailure()
        fees.resultGate = CompletableDeferred()
        // No foreground event is required for an account query.
        shortModel.payAccount("demo-type", "123456")
        await { fees.resultQueries == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        await { shortModel.state.value.resultPhase == RechargePhase.Unknown }
        assertFalse(shortModel.state.value.loading)
        assertTrue(shortModel.state.value.error.orEmpty().contains("查询超时"))
        fees.resultGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RechargePhase.Unknown, shortModel.state.value.resultPhase)
        assertEquals(1, fees.payments)
    }

    private fun openPassword() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.create(100)
        await { model.state.value.stage == FeeStage.Methods }
        model.chooseMethod(fees.accountMethod)
        await { model.state.value.stage == FeeStage.Password }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for fee state")
    }
    private class MemoryVault : Vault {
        override fun credentials() = Credentials("test-user", "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private class FakeFees : FeeService {
        val accountMethod = FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)
        private val wechatMethod = FeeMethod("101", "TSM", "微信", false, 0)
        var payments = 0
        var resultQueries = 0
        var resultGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var failure: Exception? = null
        var resultStatus: Int? = 1
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        override suspend fun feeConfiguration(token: String, project: FeeProject) = FeeConfiguration(project, "测试缴费",
            RechargeConfiguration(project.id, "", "", 0, listOf(100), 1, 10000, true, false))
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean) =
            FeeQuery(thirdParty = if (detail) "{\"userId\":\"test-user\"}" else null)
        override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) = Unit
        override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long) =
            FeeOrder(PendingRecharge(username, query.thirdParty!!, config.project.id, amountCents, 0, "FAKE-ORDER"), listOf(accountMethod, wechatMethod))
        override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod) =
            FeeAccountPreparation(order.orderId!!, method.id, listOf(FeeAccount("demo-type", "统一账户", 1000)), "fake-uuid", "9876543210")
        override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) {
            payments++
            gate.await()
            failure?.let { throw it }
        }
        override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch = error("No real launch")
        override suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult {
            resultQueries++
            resultGate.await()
            return RechargeResult(resultStatus == 1, order.amountCents, null, resultStatus)
        }
    }
    @Test fun lost60050KeepsBackendReasonAndDoesNotSuggestRechargingOrQuery() {
        openPassword()
        fees.failure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":60050,\"success\":false,\"data\":null,\"msg\":\"持卡人已挂失！\"}"))
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        assertEquals("持卡人已挂失！", model.state.value.error)
        assertNotNull(model.state.value.preparation)
        assertEquals("FAKE-ORDER", model.state.value.order?.pending?.orderId)
        model.onForeground(); model.onResumed(true)
        assertEquals(0, fees.resultQueries)
        assertEquals(1, fees.payments)
    }

    @Test fun numericPreflightBalanceStillSuggestsAnAlternative() {
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Balance)
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Methods }
        assertEquals("一卡通余额不足，请选择其他支付方式", model.state.value.error)
        assertEquals(0, fees.resultQueries)
    }

}
