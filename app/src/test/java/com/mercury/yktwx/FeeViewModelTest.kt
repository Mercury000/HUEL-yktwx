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
class FeeViewModelTest {
    private val fees = FakeFees()
    private val vault = MemoryVault()
    private val repository = AccountRepository(YktApi(), vault, feeService = fees)
    private val model = FeeViewModel(ApplicationProvider.getApplicationContext<Application>(), repository, 0L)
    private val store = ViewModelStore().apply { put("fees", model) }

    @After fun cleanup() {
        fees.createGate.complete(Unit)
        fees.gate.complete(Unit)
        fees.resultGate.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun passwordRejectionShowsResultExcludesFailedMethodAndContinuesWithNewOrder() {
        openPassword()
        fees.failure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":60005,\"success\":false,\"data\":null,\"msg\":\"账户密码错误\"}"))
        model.payAccount("demo-type", "123456")
        await { fees.payments == 1 && !model.state.value.loading }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertEquals("账户密码错误", model.state.value.error)
        assertNull(model.state.value.preparation)
        assertNull(model.state.value.order)
        assertTrue(model.state.value.canContinuePayment)
        assertEquals(100L, model.state.value.paymentRetry?.amountCents)
        assertEquals(listOf(fees.wechatMethod), model.state.value.paymentRetry?.methods)
        assertEquals(0, fees.resultQueries)
        model.continuePayment(fees.accountMethod)
        assertEquals(1, fees.creations)
        fees.failure = null
        model.onForeground(); model.onResumed(true)
        assertEquals(0, fees.resultQueries)
        model.continuePayment(fees.wechatMethod)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        assertEquals(2, fees.creations)
        assertEquals("FAKE-ORDER-2", model.state.value.order?.pending?.orderId)
        assertNull(model.state.value.paymentRetry)
        assertEquals(1, fees.payments)
        assertEquals(1, fees.launches)
        assertEquals(1, fees.preparedOrders.size)
    }

    @Test fun server60050ShowsOfficialMessageInResultWithoutAutomaticallyPaying() {
        openPassword()
        fees.failure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":60050,\"success\":false,\"data\":null,\"msg\":\"账户余额不足！\"}"))
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertEquals("账户余额不足！", model.state.value.error)
        assertNull(model.state.value.preparation)
        assertNull(model.state.value.order)
        assertTrue(model.state.value.canContinuePayment)
        assertEquals(0, fees.resultQueries)
        assertEquals(1, fees.creations)
    }

    @Test fun expiryShowsFailureAndFinishRetainsTarget() {
        openPassword()
        fees.failure = FeeOrderExpired()
        model.payAccount("demo-type", "123456")
        await { model.state.value.resultPhase == RechargePhase.Failed }
        assertEquals("订单已过期，请重新发起缴费", model.state.value.error)
        assertCannotContinue()
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
        assertCannotContinue()
    }

    @Test fun preflightFailureShowsResultWithoutKeepingOldKeyboardOrQuerying() {
        openPassword()
        fees.failure = FeePaymentNotStarted("无法确认订单有效期，请稍后重试")
        model.payAccount("demo-type", "123456")
        await { fees.payments == 1 && !model.state.value.loading }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertTrue(model.state.value.canContinuePayment)
        assertNull(model.state.value.preparation)
        assertNull(model.state.value.order)
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

    @Test fun explicitFailureCanChooseWechatButOnlyUsesFreshOrderAndSameTargetAndAmount() {
        rejectAccount()
        model.onForeground(); model.onResumed(true)
        model.continuePayment(fees.wechatMethod)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        assertEquals(2, fees.creations)
        assertEquals(1, fees.launches)
        assertEquals("FAKE-ORDER-2", fees.launchedOrders.single())
        assertEquals("101", model.state.value.order?.pending?.payId)
        assertEquals(listOf(100L, 100L), fees.createdAmounts)
        assertEquals(listOf("{\"userId\":\"test-user\"}", "{\"userId\":\"test-user\"}"), fees.createdTargets)
        assertEquals(listOf(FeeProject.Network, FeeProject.Network), fees.createdProjects)
        assertEquals(1, fees.payments)
    }

    @Test fun repeatedContinueOnlyCreatesAndPreparesOnce() {
        rejectWechat()
        fees.createGate = CompletableDeferred()
        model.continuePayment(fees.accountMethod)
        model.continuePayment(fees.wechatMethod)
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.paymentRetry)
        assertNull(model.state.value.order?.pending?.orderId)
        await { fees.creations == 2 }
        fees.createGate.complete(Unit)
        await { model.state.value.stage == FeeStage.Password }
        assertEquals(2, fees.creations)
        assertEquals(1, fees.preparedOrders.size)
        assertEquals(1, fees.launches)
        assertEquals(0, fees.payments)
    }

    @Test fun changedMethodListDoesNotReintroduceFailedChannelOrLaunchMissingAlternative() {
        rejectAccount()
        fees.methods = listOf(fees.accountMethod)
        model.continuePayment(fees.wechatMethod)
        await { model.state.value.stage == FeeStage.Result && !model.state.value.loading }
        assertEquals("其他支付方式已不可用，请重新发起缴费", model.state.value.error)
        assertEquals("FAKE-ORDER-2", model.state.value.order?.pending?.orderId)
        assertEquals(0, model.state.value.order?.methods?.size)
        assertCannotContinue()
        assertEquals(0, fees.launches)
        assertEquals(1, fees.preparedOrders.size)
        assertEquals(2, fees.creations)
    }

    @Test fun changedAlternativeIdLetsUserSelectNewIdButStillExcludesFailedCode() {
        rejectAccount()
        val newWechat = FeeMethod("102", "TSM", "微信", false, 0)
        fees.methods = listOf(fees.accountMethod, newWechat)
        model.continuePayment(fees.wechatMethod)
        await { model.state.value.stage == FeeStage.Methods && !model.state.value.loading }
        assertEquals("支付方式已变化，请重新选择", model.state.value.error)
        assertEquals(listOf(newWechat), model.state.value.order?.methods)
        model.chooseMethod(fees.accountMethod)
        assertEquals(1, fees.preparedOrders.size)
        model.onForeground(); model.onResumed(true)
        model.chooseMethod(newWechat)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        assertEquals("102", model.state.value.order?.pending?.payId)
        assertEquals(2, fees.creations)
        assertEquals(1, fees.launches)
    }

    @Test fun uncertainNewOrderCreationNeverAutomaticallyRepeatsOrEnablesContinue() {
        rejectAccount()
        fees.createFailure = NetworkFailure()
        model.continuePayment(fees.wechatMethod)
        await { model.state.value.resultPhase == RechargePhase.Unknown && !model.state.value.loading }
        assertCannotContinue()
        assertEquals(2, fees.creations)
        assertEquals(0, fees.launches)
        assertEquals(1, fees.payments)
    }

    @Test fun dismissedNewOrderLateResponseCannotRestorePaymentOrLaunchWechat() {
        rejectAccount()
        fees.createGate = CompletableDeferred()
        fees.ignoreCreateCancellation = true
        model.continuePayment(fees.wechatMethod)
        await { fees.creations == 2 }
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNull(model.state.value.paymentRetry)
        fees.createGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNull(model.state.value.order)
        assertEquals(0, fees.launches)
    }

    @Test fun unsupportedMethodOrSwitchedAccountCannotStartNewPayment() {
        rejectAccount()
        model.continuePayment(FeeMethod("999", "TSM", "未知", false, 0))
        assertEquals(1, fees.creations)
        vault.username = "different-user"
        model.continuePayment(fees.wechatMethod)
        assertEquals(1, fees.creations)
        model.accountChanged("different-user")
        assertNull(model.state.value.paymentRetry)
    }

    @Test fun failedChannelCannotBeReintroducedByAnotherIdOrCode() {
        val sameCodeDifferentId = FeeMethod("65", "ACCOUNTTSM", "一卡通别名", false, 0)
        val sameIdDifferentCode = FeeMethod("64", "TSM", "异常渠道", false, 0)
        fees.methods = listOf(fees.accountMethod, sameCodeDifferentId, sameIdDifferentCode, fees.wechatMethod)
        rejectAccount()
        assertEquals(listOf(fees.wechatMethod), model.state.value.paymentRetry?.methods)
        model.continuePayment(sameCodeDifferentId)
        model.continuePayment(sameIdDifferentCode)
        assertEquals(1, fees.creations)
    }

    @Test fun cancelFromFailureClearsRetryAndRetainsTargetWithoutCreatingOrPayingAgain() {
        rejectAccount()
        val before = model.state.value
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertSame(before.config, model.state.value.config)
        assertSame(before.query, model.state.value.query)
        assertEquals(before.selections, model.state.value.selections)
        assertNull(model.state.value.paymentRetry)
        assertNull(model.state.value.order)
        assertNull(model.state.value.error)
        model.continuePayment(fees.wechatMethod)
        model.onForeground(); model.onResumed(true)
        assertEquals(1, fees.creations)
        assertEquals(1, fees.payments)
        assertEquals(0, fees.launches)
        assertEquals(0, fees.resultQueries)
    }

    @Test fun singleMethodFailureHasNoContinueAction() {
        fees.methods = listOf(fees.accountMethod)
        rejectAccount()
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertCannotContinue()
        assertNull(model.state.value.order)
    }

    @Test fun paymentPreparationRejectionUsesFailureResultAndNoPasswordPayment() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.create(100)
        await { model.state.value.stage == FeeStage.Methods }
        fees.prepareFailure = FeePaymentRejected(FeeRejectionReason.Other, "服务端准备失败")
        model.chooseMethod(fees.accountMethod)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        assertEquals("服务端准备失败", model.state.value.error)
        assertTrue(model.state.value.canContinuePayment)
        assertNull(model.state.value.order)
        assertEquals(0, fees.payments)
    }

    @Test fun initialOrderRejectionShowsMessageWithoutReusingMissingChannels() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        fees.createFailure = FeePaymentRejected(FeeRejectionReason.Other, "服务端下单拒绝")
        model.create(100)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertEquals("服务端下单拒绝", model.state.value.error)
        assertCannotContinue()
    }

    @Test fun unpaidWechatResultIsNotProofOfClosedChannelAndCannotContinue() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.create(100)
        await { model.state.value.stage == FeeStage.Methods }
        model.onForeground(); model.onResumed(true)
        model.chooseMethod(fees.wechatMethod)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        fees.resultStatus = 0
        model.checkResult()
        await { !model.state.value.loading && model.state.value.resultPhase == RechargePhase.Failed }
        assertCannotContinue()
        assertEquals(1, fees.creations)
        assertEquals(1, fees.launches)
    }

    @Test fun launchFailureOffersNewPaymentWithoutRestoringOldWechatReturnObserver() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.create(100)
        await { model.state.value.stage == FeeStage.Methods }
        model.onForeground(); model.onResumed(true)
        model.chooseMethod(fees.wechatMethod)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        model.wechatLaunched()
        model.launchFailed()
        assertTrue(model.state.value.canContinuePayment)
        assertNull(model.state.value.order)
        model.onPaused(); model.onBackground()
        model.onForeground(); model.onResumed(true)
        assertEquals(0, fees.resultQueries)
        model.continuePayment(fees.accountMethod)
        await { model.state.value.stage == FeeStage.Password }
        assertEquals("FAKE-ORDER-2", model.state.value.order?.pending?.orderId)
        assertEquals(2, fees.creations)
        assertEquals(1, fees.launches)
        assertEquals(0, fees.payments)
    }

    @Test fun eachRejectionExcludesJustFailedChannelAndNextAttemptUsesNewKeyboard() {
        rejectAccount()
        fees.launchFailure = FeePaymentRejected(FeeRejectionReason.Other, "微信准备失败")
        model.continuePayment(fees.wechatMethod)
        await { !model.state.value.loading && model.state.value.resultPhase == RechargePhase.Failed }
        assertTrue(model.state.value.canContinuePayment)
        assertEquals(listOf(fees.accountMethod), model.state.value.paymentRetry?.methods)
        assertNull(model.state.value.order)
        assertNull(model.state.value.method)
        assertNull(model.state.value.preparation)
        fees.launchFailure = null
        model.continuePayment(fees.accountMethod)
        await { model.state.value.stage == FeeStage.Password }
        assertEquals("FAKE-ORDER-3", model.state.value.order?.pending?.orderId)
        assertEquals(3, fees.creations)
        assertEquals(listOf("FAKE-ORDER", "FAKE-ORDER-3"), fees.preparedOrders)
        assertEquals(1, fees.payments)
        model.payAccount("demo-type", "654321")
        await { model.state.value.resultPhase == RechargePhase.Paid }
        assertEquals(2, fees.payments)
    }

    private fun assertCannotContinue() {
        assertFalse(model.state.value.canContinuePayment)
        val creations = fees.creations
        model.continuePayment(fees.accountMethod)
        assertEquals(creations, fees.creations)
    }
    private fun rejectWechat() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.create(100)
        await { model.state.value.stage == FeeStage.Methods }
        fees.launchFailure = FeePaymentRejected(FeeRejectionReason.Other, "微信准备失败")
        model.chooseMethod(fees.wechatMethod)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        fees.launchFailure = null
    }
    private fun rejectAccount() {
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Rejected, "持卡人已挂失！")
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        fees.failure = null
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
        var username = "test-user"
        override fun credentials() = Credentials(username, "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private class FakeFees : FeeService {
        val accountMethod = FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)
        val wechatMethod = FeeMethod("101", "TSM", "微信", false, 0)
        var methods = listOf(accountMethod, wechatMethod)
        var creations = 0
        var launches = 0
        val preparedOrders = mutableListOf<String>()
        val launchedOrders = mutableListOf<String>()
        val createdAmounts = mutableListOf<Long>()
        val createdTargets = mutableListOf<String>()
        val createdProjects = mutableListOf<FeeProject>()
        var createGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var ignoreCreateCancellation = false
        var createFailure: Exception? = null
        var prepareFailure: Exception? = null
        var launchFailure: Exception? = null
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
        override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder {
            val number = ++creations
            createdAmounts += amountCents
            createdTargets += query.thirdParty!!
            createdProjects += config.project
            if (ignoreCreateCancellation) withContext(NonCancellable) { createGate.await() } else createGate.await()
            createFailure?.let { throw it }
            return FeeOrder(PendingRecharge(username, query.thirdParty, config.project.id, amountCents, 0,
                if (number == 1) "FAKE-ORDER" else "FAKE-ORDER-$number"), methods)
        }
        override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation {
            preparedOrders += order.orderId!!
            prepareFailure?.let { throw it }
            return FeeAccountPreparation(order.orderId, method.id, listOf(FeeAccount("demo-type", "统一账户", 1000)),
                "fake-uuid-${order.orderId}", "9876543210")
        }
        override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) {
            payments++
            gate.await()
            failure?.let { throw it }
        }
        override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch {
            launches++
            launchedOrders += order.orderId!!
            launchFailure?.let { throw it }
            return WechatRechargeLaunch("weixin://fake-payment")
        }
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
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals("持卡人已挂失！", model.state.value.error)
        assertNull(model.state.value.preparation)
        assertNull(model.state.value.order)
        model.onForeground(); model.onResumed(true)
        assertEquals(0, fees.resultQueries)
        assertEquals(1, fees.payments)
    }

    @Test fun backendBalanceFailureDoesNotInventRechargeAdvice() {
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Rejected, "后端账户余额提示")
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals("后端账户余额提示", model.state.value.error)
        assertEquals(1, fees.payments)
        assertEquals(0, fees.resultQueries)
    }

}
