package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.*
import com.mercury.yktwx.state.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeeRestorationTest {
    @Test fun explicitlyRejectedPaymentRestoresEditableAmountAndAlternativeWithoutRepeatingCreation() {
        val handle = SavedStateHandle()
        val originalFees = Fees().apply { rejectPreparation = true }
        val original = model(handle, originalFees); open(original); select(original)
        original.createWithMethod(3700, "ACCOUNTTSM")
        await { original.state.value.canContinuePayment }
        val restored = handle.recreated(); models.clear()
        val fees = Fees(); val next = model(restored, fees); restore(next)
        assertEquals(FeeStage.Query, next.state.value.stage)
        assertNotNull(next.state.value.query)
        assertEquals("37.00", next.form.value.amount); assertTrue(next.form.value.amountStep)
        assertEquals("TSM", next.form.value.methodCode)
        assertNull(next.state.value.order); assertNull(next.state.value.error)
        assertEquals(0, fees.commands); assertTrue(fees.queried.isEmpty())
    }
    private val models = ViewModelStore()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val services = mutableListOf<Fees>()
    @After fun cleanup() { models.clear(); services.forEach { it.createGate.complete(Unit); it.readGate.complete(Unit); it.prepareGate.complete(Unit) }; shadowOf(Looper.getMainLooper()).idle() }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
        shadowOf(Looper.getMainLooper()).idle(); assertTrue("Fee state did not settle", condition())
    }
    private fun model(handle: SavedStateHandle = SavedStateHandle(), fees: Fees = Fees(), owner: String = "owner"): FeeViewModel {
        services += fees
        val repository = accountRepositoryFixture(FakeAccountService(), object : Vault {
            override fun credentials() = Credentials(owner, "fixture-credential")
            override fun session() = Session("fixture-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }, feeService = fees)
        return FeeViewModel(repository, checkDelayMillis = 0, analytics = AnalyticsFixtures.from(app), savedState = handle)
            .also { models.put("fee-${models.keys().size}", it) }
    }
    private fun open(model: FeeViewModel) {
        model.open(FeeProject.Zhengdong, "owner", false)
        await { !model.state.value.loading && model.state.value.options[2] != null }
    }
    private fun select(model: FeeViewModel) {
        model.select(model.state.value.levels.last(), model.state.value.options.getValue(2).first())
        await { model.state.value.query != null && !model.state.value.selecting }
    }
    private fun restore(model: FeeViewModel) {
        model.accountChanged("owner")
        model.restoreIfNeeded(active = true, online = true)
        await { !model.state.value.loading }
    }

    @Test fun partialDraftWaitsForIdentityAndNetworkThenResolvesNewLabelsWithoutCommands() {
        val handle = SavedStateHandle(); val before = model(handle); open(before)
        before.updateForm(FeeFormDraft(amount = "37.5", methodCode = "TSM"))
        val saved = handle.recreated(); models.clear()
        val waiting = model(saved)
        val savedAgain = saved.recreated(); models.clear() // No account binding yet; don't lose the pending state.
        val fees = Fees().apply { campusName = "新校区名称" }; val after = model(savedAgain, fees)
        after.accountChanged("owner"); after.restoreIfNeeded(true, false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, fees.reads)
        after.restoreIfNeeded(true, true); await { !after.state.value.loading }
        assertEquals(mapOf("campus" to "c"), after.state.value.selections)
        assertEquals("新校区名称", after.state.value.names["campus"])
        assertEquals("37.5", after.form.value.amount); assertEquals("TSM", after.form.value.methodCode)
        assertNull(after.state.value.query); assertEquals(0, fees.commands)
        assertNull(waiting.entry.value)
    }

    @Test fun fixedTargetAndUnsubmittedConfirmationRestoreButChangedBindingCannotBeConfirmed() {
        val handle = SavedStateHandle(); val before = model(handle); open(before); select(before)
        val room = before.state.value.currentRoom!!
        before.open(FeeProject.Zhengdong, "owner", false, room, saveOnly = true)
        await { before.state.value.currentRoom != null && !before.state.value.loading }
        before.updateForm(FeeFormDraft(amount = "55", saveCommon = true, bindingPending = true))
        before.requestRoomAction()
        assertNotNull(before.state.value.roomDialog)
        val sameSaved = handle.recreated(); val changedSaved = handle.recreated(); models.clear()
        val sameFees = Fees(); val same = model(sameSaved, sameFees); restore(same)
        assertEquals(room.selections, same.entry.value?.fixed); assertTrue(same.entry.value!!.saveOnly)
        assertEquals("55", same.form.value.amount); assertNotNull(same.state.value.roomDialog)
        assertEquals(0, sameFees.commands)
        val changedFees = Fees().apply { bound = room }; val changed = model(changedSaved, changedFees); restore(changed)
        assertNull(changed.state.value.roomDialog); assertFalse(changed.form.value.bindingPending)
        changed.confirmRoomAction(); assertEquals(0, changedFees.commands)
    }

    @Test fun missingFixedOptionReturnsToEditableTargetAndFailedReadKeepsDraftForRetry() {
        val handle = SavedStateHandle(); val before = model(handle); open(before); select(before)
        before.open(FeeProject.Zhengdong, "owner", false, before.state.value.currentRoom)
        await { !before.state.value.loading }
        before.updateForm(FeeFormDraft(amount = "29", amountStep = true, methodCode = "TSM"))
        val missingSaved = handle.recreated(); val failureSaved = handle.recreated(); models.clear()
        val missingFees = Fees().apply { roomId = "different-room" }; val missing = model(missingSaved, missingFees); restore(missing)
        assertNull(missing.entry.value?.fixed); assertFalse(missing.form.value.amountStep)
        assertEquals(mapOf("campus" to "c"), missing.state.value.selections)
        assertNotNull(missing.state.value.error)
        val failureFees = Fees().apply { failRead = true }; val failure = model(failureSaved, failureFees); restore(failure)
        assertNotNull(failure.state.value.error); assertEquals("29", failure.form.value.amount)
        failureFees.failRead = false; failure.retry(); await { failure.state.value.query != null && !failure.state.value.loading }
        assertEquals(mapOf("campus" to "c", "room" to "r"), failure.state.value.selections)
        assertTrue(failure.form.value.amountStep); assertEquals("TSM", failure.form.value.methodCode)
        assertEquals(0, missingFees.commands + failureFees.commands)
    }

    @Test fun existingPaymentRestoresOnlyOriginalOrderQueryWithNoPaymentPayloadOrKeyboard() {
        val handle = SavedStateHandle(); val beforeFees = Fees(); val before = model(handle, beforeFees); open(before); select(before)
        before.createWithMethod(3700, "ACCOUNTTSM"); await { before.state.value.stage == FeeStage.Password }
        val saved = handle.recreated(); val wrongOwnerSaved = handle.recreated(); models.clear()
        val afterFees = Fees(); val after = model(saved, afterFees); restore(after)
        assertEquals(RechargePhase.Paid, after.state.value.resultPhase)
        val pending = afterFees.queried.single()
        assertEquals("original-order", pending.orderId); assertEquals("64", pending.payId)
        assertEquals(3700L, pending.amountCents); assertEquals("", pending.targetId)
        assertEquals(feeTargetFingerprint(beforeFees.target), pending.targetFingerprint)
        assertNull(after.state.value.preparation); assertNull(after.state.value.method)
        assertEquals(0, afterFees.commands)
        after.payAccount("account", "123456"); after.onForeground(); assertEquals(0, afterFees.commands)
        val otherFees = Fees(); val other = model(wrongOwnerSaved, otherFees, "another-owner")
        other.accountChanged("another-owner"); other.restoreIfNeeded(true, true)
        assertNull(other.entry.value); assertEquals(0, otherFees.reads + otherFees.commands)
    }
    @Test fun unpaidPasswordPromptRechecksOriginalOrderAndLoadsANewKeyboardWithoutPaying() {
        val handle = SavedStateHandle(); val before = model(handle); open(before); select(before)
        before.createWithMethod(3700, "ACCOUNTTSM"); await { before.state.value.stage == FeeStage.Password }
        val originalKeyboard = before.state.value.preparation
        val saved = handle.recreated(); models.clear()
        val fees = Fees().apply { resultPaid = false }
        val after = model(saved, fees); restore(after)
        assertEquals(FeeStage.Password, after.state.value.stage)
        assertNotSame(originalKeyboard, after.state.value.preparation)
        assertEquals("original-order", after.state.value.preparation?.orderId)
        assertEquals("64", after.state.value.method?.id)
        assertEquals("account", after.form.value.accountType)
        assertEquals(1, fees.restoredOrders); assertEquals(1, fees.preparations)
        assertEquals(0, fees.commands)
        assertEquals("original-order", fees.queried.single().orderId)
    }

    private fun passwordPrompt(): SavedStateHandle {
        val handle = SavedStateHandle(); val before = model(handle); open(before); select(before)
        before.updateForm(FeeFormDraft(amount = "37.00", amountStep = true, accountType = "account"))
        before.createWithMethod(3700, "ACCOUNTTSM"); await { before.state.value.stage == FeeStage.Password }
        return handle.recreated().also { models.clear() }
    }

    @Test fun cancellingDuringRestoredPreparationDropsOrderAndRestoresTargetEvenAfterAnotherRecreation() {
        val handle = passwordPrompt()
        val fees = Fees().apply { resultPaid = false; prepareGate = CompletableDeferred(); readGate = CompletableDeferred() }
        val during = model(handle, fees); during.accountChanged("owner"); during.restoreIfNeeded(true, true)
        await { fees.preparations == 1 }
        during.cancelPayment()
        during.restoreIfNeeded(false, true)
        val cancelled = handle.recreated(); models.clear()
        val afterFees = Fees(); val after = model(cancelled, afterFees); restore(after)
        assertEquals(FeeStage.Query, after.state.value.stage)
        assertEquals(mapOf("campus" to "c", "room" to "r"), after.state.value.selections)
        assertNotNull(after.state.value.query); assertNull(after.state.value.order)
        assertEquals("37.00", after.form.value.amount); assertTrue(after.form.value.amountStep)
        assertTrue(afterFees.queried.isEmpty()); assertEquals(0, afterFees.preparations + afterFees.commands)
        fees.prepareGate.complete(Unit); shadowOf(Looper.getMainLooper()).idle()
        assertNull(after.state.value.preparation)
    }

    @Test fun cancellingSuccessfullyRestoredPasswordReturnsToOriginalEditableTargetWithoutAnotherOrder() {
        val handle = passwordPrompt(); val fees = Fees().apply { resultPaid = false }
        val after = model(handle, fees); restore(after)
        after.cancelPayment()
        await { !after.state.value.loading }
        assertEquals(FeeStage.Query, after.state.value.stage)
        assertNotNull(after.state.value.config); assertNotNull(after.state.value.query)
        assertEquals(mapOf("campus" to "c", "room" to "r"), after.state.value.selections)
        assertEquals("37.00", after.form.value.amount); assertNull(after.state.value.order)
        val nextHandle = handle.recreated(); models.clear()
        val nextFees = Fees(); val next = model(nextHandle, nextFees); restore(next)
        assertNotNull(next.state.value.query); assertTrue(nextFees.queried.isEmpty())
        assertEquals(0, fees.commands + nextFees.commands + nextFees.preparations)
    }

    @Test fun failedRestoredPreparationKeepsOriginalOrderForExplicitRetryAndNeverFallsBackToCreation() {
        val handle = passwordPrompt(); val fees = Fees().apply { resultPaid = false; prepareFailure = YktException("键盘读取失败") }
        val after = model(handle, fees); restore(after)
        assertEquals(FeeStage.Result, after.state.value.stage)
        assertEquals("original-order", after.state.value.order?.pending?.orderId)
        assertEquals("键盘读取失败", after.state.value.error)
        val nextHandle = handle.recreated(); models.clear()
        val nextFees = Fees().apply { resultPaid = false; prepareFailure = YktException("仍然失败") }
        val next = model(nextHandle, nextFees); restore(next)
        assertEquals(FeeStage.Result, next.state.value.stage)
        nextFees.prepareFailure = null; next.checkResult()
        await { !next.state.value.loading && next.state.value.stage == FeeStage.Password }
        assertEquals(2, nextFees.preparations); assertEquals(2, nextFees.restoredOrders)
        assertTrue((fees.queried + nextFees.queried).all { it.orderId == "original-order" && it.payId == "64" })
        assertEquals(0, fees.commands + nextFees.commands)
    }

    @Test fun expiredRestoredPreparationDiscardsPasswordPromptBeforeSavingAgain() {
        val handle = passwordPrompt(); val fees = Fees().apply { resultPaid = false; prepareFailure = FeeOrderExpired() }
        val after = model(handle, fees); restore(after)
        assertEquals(RechargePhase.TimedOut, after.state.value.resultPhase)
        val nextHandle = handle.recreated(); models.clear()
        val nextFees = Fees().apply { resultPaid = false }; val next = model(nextHandle, nextFees); restore(next)
        assertEquals(FeeStage.Result, next.state.value.stage)
        assertEquals(0, nextFees.preparations + nextFees.restoredOrders + nextFees.commands)
    }

    @Test fun explicitlyRejectedRestoredPreparationSavesEditableTargetWithoutUnknownSubmission() {
        val handle = passwordPrompt(); val fees = Fees().apply { resultPaid = false; rejectPreparation = true }
        val after = model(handle, fees); restore(after)
        assertEquals(RechargePhase.Failed, after.state.value.resultPhase)
        val nextHandle = handle.recreated(); models.clear()
        val nextFees = Fees(); val next = model(nextHandle, nextFees); restore(next)
        assertEquals(FeeStage.Query, next.state.value.stage)
        assertNotNull(next.state.value.query); assertNull(next.state.value.order); assertNull(next.state.value.error)
        assertEquals("37.00", next.form.value.amount)
        assertTrue(nextFees.queried.isEmpty()); assertEquals(0, nextFees.preparations + nextFees.commands)
    }

    @Test fun interruptedCreationHasNoOrderToReplayOrQueryAndReloginDropsTheDraft() {
        val handle = SavedStateHandle(); val fees = Fees().apply { createGate = CompletableDeferred() }
        val before = model(handle, fees); open(before); select(before)
        before.createWithMethod(3700, "ACCOUNTTSM"); await { fees.commands == 1 }
        val saved = handle.recreated(); models.clear()
        val afterFees = Fees(); val after = model(saved, afterFees); restore(after)
        assertEquals(FeeStage.Result, after.state.value.stage)
        assertTrue(after.state.value.error!!.contains("未取得订单号"))
        assertEquals(0, afterFees.commands); assertTrue(afterFees.queried.isEmpty())
        after.accountChanged("owner", renewed = true); assertNull(after.entry.value)
        after.restoreIfNeeded(true, true); assertEquals(0, afterFees.commands)
    }

    @Test fun leavingDuringRestoreKeepsPendingSelectionAndRemovedHierarchyCannotAuthorizeAnotherTarget() {
        val handle = SavedStateHandle(); val before = model(handle); open(before); select(before)
        before.open(FeeProject.Zhengdong, "owner", false, before.state.value.currentRoom)
        await { !before.state.value.loading }
        val saved = handle.recreated(); models.clear()
        val slowFees = Fees().apply { readGate = CompletableDeferred() }; val during = model(saved, slowFees)
        during.accountChanged("owner"); during.restoreIfNeeded(true, true)
        await { slowFees.reads == 1 }
        during.restoreIfNeeded(false, true)
        val stopped = saved.recreated(); models.clear(); slowFees.readGate.complete(Unit)
        val changedFees = Fees().apply { noLevels = true }; val after = model(stopped, changedFees); restore(after)
        assertNull(after.entry.value?.fixed); assertNull(after.state.value.query)
        assertFalse(after.form.value.amountStep); assertNotNull(after.state.value.error)
        after.createWithMethod(3700, "ACCOUNTTSM"); assertEquals(0, changedFees.commands)
    }

    internal class Fees : FeeService {
        var reads = 0; var commands = 0; var campusName = "原校区名称"; var roomId = "r"; var failRead = false; var noLevels = false
        var resultPaid = true; var preparations = 0; var restoredOrders = 0
        var bound: FeeRoomBinding? = null
        var createGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var readGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var prepareGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var prepareFailure: Exception? = null
        val queried = mutableListOf<PendingRecharge>()
        val target = "{\"room\":\"r\",\"reading\":1.0}"
        var rejectPreparation = false
        val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("room", 2, "房间"))
        override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
            reads++; readGate.await(); if (failRead) throw YktException("读取失败")
            return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(3700), 1, 100000, true, false),
                roomBindingEnabled = true, roomBinding = bound)
        }
        override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
            reads++
            return if (detail) FeeQuery(thirdParty = target) else FeeQuery(levels = if (noLevels) emptyList() else levels,
                options = if (level == 0) listOf(FeeOption("c", campusName)) else listOf(FeeOption(roomId, "新房间名称")))
        }
        override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) { commands++; bound = room }
        override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder {
            commands++; createGate.await()
            return FeeOrder(PendingRecharge(username, target, config.project.id, amountCents, "original-order"),
                listOf(FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)) +
                    if (rejectPreparation) listOf(FeeMethod("65", "TSM", "微信", false, 0)) else emptyList())
        }
        override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation {
            preparations++
            prepareGate.await(); prepareFailure?.let { throw it }
            if (rejectPreparation) throw FeePaymentRejected(FeeRejectionReason.Rejected, "原渠道明确拒绝")
            return FeeAccountPreparation(order.orderId!!, method.id, listOf(FeeAccount("account", "一卡通", 9000)), "temporary-key", "9876543210")
        }
        override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation,
            type: String, password: String) { commands++ }
        override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch {
            commands++; return WechatRechargeLaunch("weixin://fixture")
        }
        override suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult {
            reads++; queried += order; return RechargeResult(resultPaid, if (resultPaid) 1 else 0)
        }
        override suspend fun restoreFeeOrder(token: String, order: PendingRecharge): FeeOrder {
            restoredOrders++
            return FeeOrder(order, listOf(FeeMethod("64", "ACCOUNTTSM", "一卡通", false, 0)))
        }
    }
}
