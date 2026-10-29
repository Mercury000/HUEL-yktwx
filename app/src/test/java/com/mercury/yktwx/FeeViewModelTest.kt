package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
class FeeViewModelTest {
    private val fees = FakeFees()
    private val vault = MemoryVault()
    private val repository = AccountRepository(FakeAccountService(), vault, feeService = fees)
    private val model = FeeViewModel(ApplicationProvider.getApplicationContext<Application>(), repository, 0L)
    private val store = ViewModelStore().apply { put("fees", model) }

    @After fun cleanup() {
        fees.createGate.complete(Unit)
        fees.prepareGate.complete(Unit)
        fees.gate.complete(Unit)
        fees.resultGate.complete(Unit)
        store.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun passwordRejectionKeepsOrderAndKeyboardForExplicitPasswordCorrection() {
        openPassword()
        val before = model.state.value
        fees.failure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":60005,\"success\":false,\"data\":null,\"msg\":\"账户密码错误\"}"))
        model.payAccount("demo-type", "123456")
        await { fees.payments == 1 && !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        assertEquals("账户密码错误", model.state.value.error)
        assertSame(before.preparation, model.state.value.preparation)
        assertSame(before.order, model.state.value.order)
        assertSame(before.method, model.state.value.method)
        assertNull(model.state.value.paymentRetry)
        assertCannotContinue()
        model.onForeground(); model.onResumed(true)
        assertEquals(0, fees.resultQueries)
        assertEquals(1, fees.creations)
        assertEquals(1, fees.preparedOrders.size)
        assertEquals(1, fees.payments)
        fees.failure = null
        model.payAccount("demo-type", "654321")
        await { model.state.value.resultPhase == RechargePhase.Paid }
        assertEquals(2, fees.payments)
        assertEquals(1, fees.creations)
        assertEquals(1, fees.preparedOrders.size)
        assertEquals(listOf("FAKE-ORDER", "FAKE-ORDER"), fees.paidOrders)
        assertEquals(0, fees.launches)
    }

    @Test fun passwordClassificationUsesReasonInsteadOfMatchingMessageAndAlsoWorksWithSingleMethod() {
        fees.methods = listOf(fees.accountMethod)
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Password, "服务端其他语言的密码提示")
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        assertEquals("服务端其他语言的密码提示", model.state.value.error)
        assertNotNull(model.state.value.preparation)
        assertNotNull(model.state.value.order)
        assertCannotContinue()
        assertEquals(1, fees.payments)
        assertEquals(0, fees.resultQueries)
    }

    @Test fun genericRejectionNamedPasswordErrorDoesNotEnableSameOrderPasswordRetry() {
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Other, "账户密码错误")
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertEquals("账户密码错误", model.state.value.error)
        assertNull(model.state.value.order)
        assertNull(model.state.value.preparation)
        assertEquals(listOf(fees.wechatMethod), model.state.value.paymentRetry?.methods)
    }

    @Test fun correctedPasswordStillHandlesExpiredOrderWithoutAnotherOrderOrAutomaticPayment() {
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Password, "账户密码错误")
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        assertEquals(FeeStage.Password, model.state.value.stage)
        fees.failure = FeeOrderExpired()
        model.payAccount("demo-type", "654321")
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        assertEquals(RechargePhase.TimedOut, model.state.value.resultPhase)
        assertEquals("订单已过期，请重新发起缴费", model.state.value.error)
        assertCannotContinue()
        assertEquals(1, fees.creations)
        assertEquals(0, fees.resultQueries)
    }

    @Test fun passwordCorrectionCanBeCanceledWithoutRestoringOldDialog() {
        openPassword()
        fees.failure = FeePaymentRejected(FeeRejectionReason.Password, "账户密码错误")
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading }
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNull(model.state.value.order)
        assertNull(model.state.value.preparation)
        assertNull(model.state.value.error)
        model.payAccount("demo-type", "654321")
        assertEquals(1, fees.payments)
        assertEquals(1, fees.creations)
    }

    @Test fun hallPreferredCardMethodPreparesOneOrderAndWaitsForPasswordConfirmation() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        fees.prepareGate = CompletableDeferred()
        model.createWithMethod(100, "ACCOUNTTSM")
        model.createWithMethod(100, "ACCOUNTTSM")
        await { fees.preparedOrders.size == 1 }
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.preparation)
        assertEquals(0, fees.payments)
        fees.prepareGate.complete(Unit)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Password }
        assertEquals(1, fees.creations)
        assertEquals(listOf("FAKE-ORDER"), fees.preparedOrders)
        assertEquals(fees.accountMethod.id, model.state.value.order?.pending?.payId)
        assertEquals(0, fees.payments)
        assertEquals(0, fees.launches)
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNotNull(model.state.value.query?.thirdParty)
        assertNull(model.state.value.preparation)
        assertNull(model.state.value.order)
    }

    @Test fun unavailablePreferredMethodStillRequiresExplicitSelection() {
        fees.methods = listOf(fees.wechatMethod)
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.createWithMethod(100, fees.accountMethod.code)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Methods }
        assertEquals("所选支付方式不可用，请重新选择", model.state.value.error)
        assertEquals(listOf(fees.wechatMethod), model.state.value.order?.methods)
        assertEquals(0, fees.launches)
        assertEquals(0, fees.preparedOrders.size)
        model.onForeground()
        model.onResumed(true)
        model.chooseMethod(fees.wechatMethod)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        assertEquals(1, fees.creations)
        assertEquals(1, fees.launches)
        assertEquals(listOf("FAKE-ORDER"), fees.launchedOrders)
        assertEquals(0, fees.payments)
    }

    @Test fun hallPreferredMethodLateOrderCannotRestoreDialogOrLaunchAfterDismissal() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        fees.createGate = CompletableDeferred()
        fees.ignoreCreateCancellation = true
        model.createWithMethod(100, "TSM")
        await { fees.creations == 1 }
        model.dismiss()
        fees.createGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.config)
        assertNull(model.state.value.order)
        assertNull(model.state.value.preparation)
        assertEquals(0, fees.launches)
        assertEquals(0, fees.payments)
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

    @Test fun expiryShowsTimeoutAndFinishRetainsTarget() {
        openPassword()
        fees.failure = FeeOrderExpired()
        model.payAccount("demo-type", "123456")
        await { model.state.value.resultPhase == RechargePhase.TimedOut }
        assertEquals("订单已过期，请重新发起缴费", model.state.value.error)
        assertCannotContinue()
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertNotNull(model.state.value.query?.thirdParty)
        assertNull(model.state.value.order)
    }

    @Test fun paymentRequestFailureShowsMessageWithoutStartingResultQueries() {
        openPassword()
        fees.failure = NetworkFailure()
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Query }
        assertEquals(NetworkFailure().message, model.state.value.error)
        assertEquals(1, fees.payments)
        assertEquals(0, fees.resultQueries)
        assertNull(model.state.value.order)
        assertNull(model.state.value.preparation)
        assertCannotContinue()
    }

    @Test fun accountSubmissionUsesOfficialUnpaidStatusAndMessage() {
        openPassword()
        fees.resultStatus = 0
        fees.resultMessage = "后端订单提示"
        model.payAccount("demo-type", "123456")
        await { !model.state.value.loading && model.state.value.resultPhase == RechargePhase.Failed }
        assertEquals("后端订单提示", model.state.value.error)
        assertEquals(1, fees.payments)
        assertEquals(3, fees.resultQueries)
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

    @Test fun confirmedFeePaymentEmitsOneUpdateEvenAfterImmediatelyClosingTheSheet() {
        openPassword()
        model.payAccount("demo-type", "123456")
        await { model.state.value.resultPhase == RechargePhase.Paid }
        model.checkResult()
        model.dismiss()
        val event = runBlocking { withTimeoutOrNull(1_000) { model.transactionUpdates.first() } }
        assertNotNull(event)
        assertTrue(event!!.hall)
        assertTrue(event.balance)
        assertNull(runBlocking { withTimeoutOrNull(100) { model.transactionUpdates.first() } })
        assertEquals(1, fees.payments)
    }

    @Test fun confirmedWechatFeeUpdatesHallWithoutInvalidatingCampusCardBalance() {
        model.onForeground(); model.onResumed(true)
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.createWithMethod(100, fees.wechatMethod.code)
        await { model.state.value.resultPhase == RechargePhase.AwaitingPayment }
        model.checkResult()
        await { model.state.value.resultPhase == RechargePhase.Paid }
        val event = runBlocking { withTimeoutOrNull(1_000) { model.transactionUpdates.first() } }
        assertNotNull(event)
        assertTrue(event!!.hall)
        assertFalse(event.balance)
        assertEquals(0, fees.payments)
    }

    @Test fun suspendedQueryEndsAtIndependentDeadlineWithoutReplayingPayment() {
        val shortModel = FeeViewModel(ApplicationProvider.getApplicationContext<Application>(), repository,
            checkDelayMillis = 0, checkTimeoutMillis = 100)
        store.put("short-fees", shortModel)
        shortModel.open(FeeProject.Network, "test-user", false)
        await { !shortModel.state.value.loading && shortModel.state.value.query != null }
        shortModel.createWithMethod(100, fees.accountMethod.code)
        await { shortModel.state.value.stage == FeeStage.Password }
        fees.resultGate = CompletableDeferred()
        // No foreground event is required for an account query.
        shortModel.payAccount("demo-type", "123456")
        await { fees.resultQueries == 1 }
        shadowOf(Looper.getMainLooper()).idleFor(200, TimeUnit.MILLISECONDS)
        await { shortModel.state.value.resultPhase == RechargePhase.Checking && !shortModel.state.value.loading }
        assertFalse(shortModel.state.value.loading)
        assertTrue(shortModel.state.value.error.orEmpty().contains("查询超时"))
        fees.resultGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(RechargePhase.Checking, shortModel.state.value.resultPhase)
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
        fees.prepareGate = CompletableDeferred()
        model.continuePayment(fees.accountMethod)
        model.continuePayment(fees.wechatMethod)
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.paymentRetry)
        assertNull(model.state.value.order?.pending?.orderId)
        await { fees.creations == 2 }
        fees.createGate.complete(Unit)
        await { fees.preparedOrders.size == 1 }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals(RechargePhase.Creating, model.state.value.resultPhase)
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.preparation)
        fees.prepareGate.complete(Unit)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Password }
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
        await { model.state.value.stage == FeeStage.Query && !model.state.value.loading }
        assertEquals(NetworkFailure().message, model.state.value.error)
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
        runBlocking { repository.login("different-user", "fake-password") }
        model.continuePayment(fees.wechatMethod)
        assertEquals(1, fees.creations)
        model.accountChanged("different-user")
        assertNull(model.state.value.paymentRetry)
    }

    @Test fun failedChannelCannotBeReintroducedBySameIdWithAnotherCode() {
        val sameIdDifferentCode = FeeMethod("64", "TSM", "异常渠道", false, 0)
        fees.methods = listOf(fees.accountMethod, sameIdDifferentCode, fees.wechatMethod)
        rejectAccount()
        assertEquals(listOf(fees.wechatMethod), model.state.value.paymentRetry?.methods)
        model.continuePayment(sameIdDifferentCode)
        assertEquals(1, fees.creations)
    }

    @Test fun cancelFromFailureClearsRetryAndRetainsTargetWithoutCreatingOrPayingAgain() {
        rejectAccount()
        val before = model.state.value
        model.cancelPayment()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertEquals(RechargePhase.Amount, model.state.value.resultPhase)
        assertFalse(model.state.value.loading)
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
        model.dismiss()
        assertEquals(FeeStage.Query, model.state.value.stage)
        assertEquals(RechargePhase.Amount, model.state.value.resultPhase)
        assertFalse(model.state.value.loading)
        assertEquals(1, fees.creations)
        assertEquals(1, fees.payments)
    }

    @Test fun paymentPreparationRejectionUsesFailureResultAndNoPasswordPayment() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        fees.prepareFailure = FeePaymentRejected(FeeRejectionReason.Other, "服务端准备失败")
        model.createWithMethod(100, fees.accountMethod.code)
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        assertEquals("服务端准备失败", model.state.value.error)
        assertTrue(model.state.value.canContinuePayment)
        assertNull(model.state.value.order)
        assertEquals(0, fees.payments)
    }

    @Test fun passwordCodeDuringPreparationDoesNotEnterPasswordCorrection() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        fees.prepareFailure = FeePaymentRejected(FeeRejectionReason.Password, "账户密码错误")
        model.createWithMethod(100, fees.accountMethod.code)
        await { !model.state.value.loading }
        assertEquals(FeeStage.Result, model.state.value.stage)
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertNull(model.state.value.preparation)
        assertEquals(listOf(fees.wechatMethod), model.state.value.paymentRetry?.methods)
        assertEquals(0, fees.payments)
    }

    @Test fun initialOrderRejectionShowsMessageWithoutReusingMissingChannels() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        val message = "金额大于每笔交易允许的最大金额300元"
        fees.createFailure = FeePaymentErrors.rejection(org.json.JSONObject(
            "{\"code\":500,\"success\":false,\"data\":null,\"msg\":\"$message\"}"))
        model.createWithMethod(100, "ACCOUNTTSM")
        await { !model.state.value.loading && model.state.value.stage == FeeStage.Result }
        assertEquals(RechargePhase.Failed, model.state.value.resultPhase)
        assertEquals(message, model.state.value.error)
        assertEquals(1, fees.creations)
        assertEquals(0, fees.payments)
        assertEquals(0, fees.resultQueries)
        assertCannotContinue()
    }

    @Test fun unpaidWechatResultIsNotProofOfClosedChannelAndCannotContinue() {
        model.open(FeeProject.Network, "test-user", false)
        await { !model.state.value.loading && model.state.value.query != null }
        model.onForeground(); model.onResumed(true)
        model.createWithMethod(100, fees.wechatMethod.code)
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
        model.onForeground(); model.onResumed(true)
        model.createWithMethod(100, fees.wechatMethod.code)
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
        fees.launchFailure = FeePaymentRejected(FeeRejectionReason.Other, "微信准备失败")
        model.createWithMethod(100, fees.wechatMethod.code)
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
        model.createWithMethod(100, fees.accountMethod.code)
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
        override fun saveCredentials(value: Credentials) { username = value.username }
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
        var prepareGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var ignoreCreateCancellation = false
        var createFailure: Exception? = null
        var prepareFailure: Exception? = null
        var launchFailure: Exception? = null
        val paidOrders = mutableListOf<String>()
        var payments = 0
        var resultQueries = 0
        var resultGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var failure: Exception? = null
        var resultStatus: Int? = 1
        var resultMessage: String? = null
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        override suspend fun feeConfiguration(token: String, project: FeeProject) = FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false))
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
            return FeeOrder(PendingRecharge(username, query.thirdParty, config.project.id, amountCents, if (number == 1) "FAKE-ORDER" else "FAKE-ORDER-$number"), methods)
        }
        override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation {
            preparedOrders += order.orderId!!
            prepareGate.await()
            prepareFailure?.let { throw it }
            return FeeAccountPreparation(order.orderId, method.id, listOf(FeeAccount("demo-type", "统一账户", 1000)),
                "fake-uuid-${order.orderId}", "9876543210")
        }
        override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) {
            payments++
            paidOrders += order.orderId!!
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
            return RechargeResult(resultStatus == 1, resultStatus, resultMessage)
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
