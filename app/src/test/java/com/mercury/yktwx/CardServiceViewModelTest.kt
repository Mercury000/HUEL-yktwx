package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
class CardServiceViewModelTest {
    private val vault = MemoryVault()
    private val service = FakeCards()
    private val repository = AccountRepository(FakeAccountService(), vault, cardService = service)
    private val model = CardServiceViewModel(ApplicationProvider.getApplicationContext<Application>(), repository)
    private val store = ViewModelStore().apply { put("card", model) }
    @After fun cleanup() {
        service.writeGate.complete(Unit); service.queryGate.complete(Unit); service.cardGate.complete(Unit); service.configGate.complete(Unit)
        store.clear(); shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun paymentUnlostOpensImmediatelyAndSelectsOnlyTheMatchingCard() {
        service.lost = true
        service.needsPassword = true
        service.extraCards = listOf(CampusCard("other-card", "另一张卡", "202600000002", true, false, 0))
        service.cardGate = CompletableDeferred()
        model.openUnlost(vault.username, "fake-card", false)
        assertTrue(model.state.value.visible)
        assertTrue(model.state.value.loading)
        assertEquals(CampusCardAction.Unlost, model.state.value.operation)
        service.cardGate.complete(Unit)
        await { !model.state.value.loading }
        assertEquals("fake-card", model.state.value.selectedCard!!.account)
        assertEquals(1, model.state.value.cards.size)
        assertTrue(model.state.value.needsPassword)
        model.changeCard("123456")
        await { model.state.value.completed }
        assertEquals(CampusCardAction.Unlost, service.lastAction)
        assertFalse(service.lost)
        assertOneResult("解挂成功")
    }

    @Test fun paymentUnlostNeverTurnsIntoLostWhenTheCardWasAlreadyRestored() {
        service.lost = false
        model.openUnlost(vault.username, "fake-card", false)
        await { !model.state.value.loading }
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.selectedCard)
        model.changeCard("")
        assertEquals(0, service.writes)
        assertOneResult("校园卡已解挂，正在更新付款状态")
    }

    @Test fun paymentUnlostWithMissingTargetOrFailedReadKeepsAnInertDialog() {
        service.cardFailure = NetworkFailure()
        model.openUnlost(vault.username, "fake-card", false)
        await { !model.state.value.loading }
        assertTrue(model.state.value.visible)
        assertNull(model.state.value.selectedCard)
        assertEquals(NetworkFailure().message, model.state.value.error)
        model.changeCard("123456")
        assertEquals(0, service.writes)
        model.dismiss()
        service.cardFailure = null
        model.openUnlost(vault.username, "missing-card", false)
        await { !model.state.value.loading }
        assertTrue(model.state.value.visible)
        assertNull(model.state.value.selectedCard)
        assertTrue(model.state.value.cards.isEmpty())
        assertEquals(0, service.writes)
    }

    private fun openCards() {
        model.open(CampusCardAction.ManageState, vault.username, false)
        await { !model.state.value.loading }
        assertEquals(1, model.state.value.cards.size)
        model.selectCard(model.state.value.cards.single())
    }
    @Test fun singleEntryChoosesLostOrUnlostFromSelectedCurrentState() {
        openCards()
        assertEquals(CampusCardAction.Lost, model.state.value.operation)
        model.changeCard("")
        await { model.state.value.completed }
        assertEquals(CampusCardAction.Lost, service.lastAction)
        assertTrue(model.state.value.cards.single().lost)
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.selectedCard)
        assertOneResult("挂失成功")
        model.changeCard("")
        assertEquals(1, service.writes)
        openCards()
        assertEquals(CampusCardAction.Unlost, model.state.value.operation)
        model.changeCard("")
        await { model.state.value.completed }
        assertEquals(CampusCardAction.Unlost, service.lastAction)
        assertFalse(model.state.value.cards.single().lost)
        assertFalse(model.state.value.visible)
        assertOneResult("解挂成功")
        assertEquals(2, service.writes)
    }
    @Test fun duplicateClicksAndInvalidPasswordsNeverDuplicateWrites() {
        service.needsPassword = true
        openCards()
        model.changeCard("123")
        assertEquals(0, service.writes); assertNotNull(model.state.value.error)
        service.writeGate = CompletableDeferred()
        model.changeCard("123456"); model.changeCard("123456")
        await { service.writes == 1 }
        service.writeGate.complete(Unit)
        await { model.state.value.completed }
        assertEquals(1, service.writes)
        assertFalse(model.state.value.toString().contains("123456"))
    }
    @Test fun ambiguousWriteOnlyQueriesAndDoesNotReplayPost() {
        openCards()
        service.failAfterWrite = true
        model.changeCard("")
        await { model.state.value.completed }
        assertEquals(1, service.writes)
        assertTrue(model.state.value.cards.single().lost)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.visible)
        assertOneResult("挂失成功")
    }
    @Test fun unconfirmedWriteToastsAndRequiresReopeningBeforeAnotherWrite() {
        openCards()
        service.writeFailure = CardOperationUnconfirmed("网络连接失败")
        service.cardFailure = NetworkFailure()
        model.changeCard("")
        await { !model.state.value.loading }
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.completed)
        assertNull(model.state.value.selectedCard)
        assertNull(model.state.value.error)
        assertEquals(1, service.writes)
        assertOneResult("网络连接失败。操作结果待确认，请重新查看卡状态")
        model.changeCard("")
        assertEquals(1, service.writes)
    }
    @Test fun lateWriteCannotRestoreDialogAfterAccountChanges() {
        openCards()
        service.writeGate = CompletableDeferred()
        model.changeCard("")
        await { service.writes == 1 }
        runBlocking {
            val switch = async(Dispatchers.Default) { repository.login("new-owner", "fake-password") }
            // The old write keeps the account lock until it finishes. Invalidate its UI first.
            model.accountChanged("new-owner")
            service.writeGate.complete(Unit)
            await { switch.isCompleted && service.writeResponses == 1 }
            switch.await()
        }
        assertEquals("new-owner", vault.username)
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.selectedCard)
        assertNoResult()
    }
    @Test fun dismissedWriteDoesNotSendALateResultToast() {
        openCards()
        service.writeGate = CompletableDeferred()
        model.changeCard("")
        await { service.writes == 1 }
        model.dismiss()
        service.writeGate.complete(Unit)
        await { service.writeResponses == 1 }
        assertFalse(model.state.value.visible)
        assertNoResult()
    }
    @Test fun lookupSelectsFirstAccountAndReadsAmountsWithoutSendingPayment() {
        openRecipients()
        assertSame(model.state.value.recipients.single(), model.state.value.selectedRecipient)
        assertSame(model.state.value.selectedRecipient, model.state.value.rechargeConfig!!.recipient)
        assertEquals(1, service.configurations)
        assertNoRechargeRequest()
        model.clearRecipient()
        assertTrue(model.state.value.recipients.isEmpty())
        assertNull(model.state.value.selectedRecipient)
        assertNull(model.state.value.rechargeConfig)
        model.continueRecharge(1000)
        assertNoRechargeRequest()
    }
    @Test fun recipientCanBeDeselectedAndCannotContinueUntilReselected() {
        openRecipients()
        val recipient = model.state.value.recipients.single()
        assertSame(recipient, model.state.value.selectedRecipient)
        model.selectRecipient(recipient)
        assertNull(model.state.value.selectedRecipient)
        assertEquals(listOf(recipient), model.state.value.recipients)
        model.continueRecharge(1000)
        assertNoRechargeRequest()
        assertEquals(1, service.configurations)
        model.selectRecipient(recipient)
        assertSame(recipient, model.state.value.selectedRecipient)
        model.continueRecharge(1000)
        model.continueRecharge(5000)
        // Selection cannot change while handing this confirmed amount to payment.
        model.selectRecipient(recipient)
        assertSame(recipient, model.state.value.selectedRecipient)
        await { service.configurations == 1 }
        val request = runBlocking { withTimeout(1_000) { model.rechargeRequests.first() } }
        assertSame(recipient, request.config.recipient)
        assertEquals(1000L, request.amountCents)
        assertNoRechargeRequest()
        assertEquals(1, service.queries)
    }
    @Test fun recipientCanContinueToSharedRechargeWithoutPayerConfiguration() {
        openRecipients()
        val selected = model.state.value.recipients.single()
        model.continueRecharge(1000)
        await { service.configurations == 1 }
        val request = runBlocking { model.rechargeRequests.first() }
        assertTrue(model.accepts(request))
        assertSame(selected, request.config.recipient)
        assertEquals("recipient-target", request.config.targetId)
        assertEquals(1000L, request.amountCents)
        model.dismiss()
        assertFalse(model.accepts(request))
    }
    @Test fun selectingAnotherReturnedAccountUsesItsOwnAmountConfiguration() {
        val second = RechargeRecipient("second-card", "second-target", "示例同学", "202600000002", "电子账户")
        service.extraRecipients = listOf(second)
        model.open(CampusCardAction.OthersRecharge, vault.username, false)
        await { !model.state.value.loading }
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "abcde")
        await { !model.state.value.loading && model.state.value.rechargeConfig != null }
        assertSame(model.state.value.recipients.first(), model.state.value.selectedRecipient)
        service.configGate = CompletableDeferred()
        model.selectRecipient(second)
        await { service.configurations == 2 }
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.rechargeConfig)
        model.continueRecharge(1000)
        assertNoRechargeRequest()
        service.configGate.complete(Unit)
        await { !model.state.value.loading }
        assertSame(second, model.state.value.rechargeConfig!!.recipient)
        model.continueRecharge(5000)
        val request = runBlocking { withTimeout(1_000) { model.rechargeRequests.first() } }
        assertEquals("second-target", request.config.targetId)
        assertEquals(5000L, request.amountCents)
    }
    @Test fun amountReadFailureKeepsResultsAndExplicitRetryDoesNotRepeatLookup() {
        service.configFailure = NetworkFailure()
        openRecipients()
        assertNotNull(model.state.value.selectedRecipient)
        assertNull(model.state.value.rechargeConfig)
        assertNotNull(model.state.value.error)
        model.continueRecharge(1000)
        assertNoRechargeRequest()
        service.configFailure = null
        model.retryRecipientConfig()
        await { !model.state.value.loading }
        assertNotNull(model.state.value.rechargeConfig)
        assertEquals(2, service.configurations)
        assertEquals(1, service.queries)
        assertEquals(1, service.captchas)
        assertNoRechargeRequest()
    }
    @Test fun dismissedAmountReadCannotRestoreRecipientOrSendPayment() {
        service.configGate = CompletableDeferred()
        model.open(CampusCardAction.OthersRecharge, vault.username, false)
        await { !model.state.value.loading }
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "abcde")
        await { service.configurations == 1 }
        model.dismiss()
        service.configGate.complete(Unit)
        await { service.configResponses == 1 }
        assertFalse(model.state.value.visible)
        assertNull(model.state.value.rechargeConfig)
        assertTrue(model.state.value.recipients.isEmpty())
        assertNoRechargeRequest()
    }
    @Test fun invalidAmountSwitchedOwnerAndPreviewCannotStartRecipientPayment() {
        openRecipients()
        model.continueRecharge(50001)
        assertNotNull(model.state.value.error)
        assertNoRechargeRequest()
        runBlocking { repository.login("new-owner", "fake-password") }
        model.continueRecharge(1000)
        assertNoRechargeRequest()
        model.accountChanged(vault.username)
        model.open(CampusCardAction.OthersRecharge, "preview", true)
        await { !model.state.value.loading }
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "")
        await { !model.state.value.loading && model.state.value.rechargeConfig != null }
        assertNotNull(model.state.value.selectedRecipient)
        model.continueRecharge(1000)
        assertNoRechargeRequest()
        assertEquals(1, service.configurations)
    }
    @Test fun failedLookupClearsRecipientAndFetchesFreshCaptcha() {
        openRecipients()
        model.selectRecipient(model.state.value.recipients.single())
        model.refreshCaptcha()
        await { !model.state.value.loading }
        service.queryFailure = RecipientLookupRejected("验证码有误")
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "wrong")
        await { !model.state.value.loading }
        assertTrue(model.state.value.recipients.isEmpty())
        assertNull(model.state.value.selectedRecipient)
        assertNotNull(model.state.value.captcha)
        assertEquals("验证码有误", model.state.value.error)
        assertEquals(3, service.captchas)
    }
    @Test fun previewCannotChangeRealCardAndLateQueriesDoNotReopenClosedDialog() {
        model.open(CampusCardAction.ManageState, "preview", true)
        await { !model.state.value.loading }
        model.selectCard(model.state.value.cards.single()); model.changeCard("123456")
        assertEquals(0, service.writes)
        model.open(CampusCardAction.OthersRecharge, vault.username, false)
        await { !model.state.value.loading }
        service.queryGate = CompletableDeferred()
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "abcde")
        await { service.queries == 1 }
        model.dismiss(); service.queryGate.complete(Unit)
        await { service.queryResponses == 1 }
        assertFalse(model.state.value.visible)
        assertTrue(model.state.value.recipients.isEmpty())
    }
    @Test fun returningToLookupDiscardsOldCaptchaAndSelectionButDoesNotReuseCode() {
        openRecipients()
        model.selectRecipient(model.state.value.recipients.single())
        model.refreshCaptcha()
        await { !model.state.value.loading }
        assertTrue(model.state.value.recipients.isEmpty())
        assertNull(model.state.value.selectedRecipient)
        assertEquals("fake-key-2", model.state.value.captcha!!.key)
        val queries = service.queries
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "")
        assertEquals(queries, service.queries)
        assertNotNull(model.state.value.error)
    }
    @Test fun rejectedQueryPasswordKeepsCardSelectedAndDoesNotRetryWrite() {
        service.lost = true
        service.needsPassword = true
        openCards()
        val selected = model.state.value.selectedCard
        service.writeFailure = CardOperationRejected("查询密码错误，请重新输入")
        model.changeCard("123456")
        await { !model.state.value.loading }
        assertSame(selected, model.state.value.selectedCard)
        assertFalse(model.state.value.completed)
        assertTrue(model.state.value.visible)
        assertEquals("查询密码错误，请重新输入", model.state.value.error)
        assertEquals(1, service.writes)
        assertEquals(1, service.cardReads)
        assertNoResult()
        service.writeFailure = null
        model.changeCard("123456")
        await { model.state.value.completed }
        assertFalse(model.state.value.visible)
        assertEquals(2, service.writes)
        assertOneResult("解挂成功")
    }
    @Test fun stateEntryWaitsForCardReadAndRepeatedClicksOpenOnlyOneConfirmation() {
        service.cardGate = CompletableDeferred()
        model.open(CampusCardAction.ManageState, vault.username, false)
        await { service.cardReads == 1 }
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.visible)
        model.open(CampusCardAction.ManageState, vault.username, false)
        model.open(CampusCardAction.ManageState, vault.username, false)
        assertEquals(1, service.cardReads)
        service.cardGate.complete(Unit)
        await { model.state.value.visible && !model.state.value.loading }
        assertEquals(CampusCardAction.Lost, model.state.value.operation)
        assertNotNull(model.state.value.selectedCard)
        assertEquals(1, service.cardReads)
    }
    @Test fun closingPendingCardReadCannotOpenLateConfirmation() {
        service.cardGate = CompletableDeferred()
        model.open(CampusCardAction.ManageState, vault.username, false)
        await { service.cardReads == 1 }
        model.dismiss()
        service.cardGate.complete(Unit)
        await { service.cardResponses == 1 }
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.loading)
    }
    @Test fun failedInitialCardReadLeavesDialogClosedAndNotifiesOnce() {
        service.cardFailure = NetworkFailure()
        model.open(CampusCardAction.ManageState, vault.username, false)
        await { !model.state.value.loading }
        assertFalse(model.state.value.visible)
        assertTrue(runBlocking { model.messages.first() }.contains("网络"))
        assertEquals(1, service.cardReads)
    }
    private fun openRecipients() {
        model.open(CampusCardAction.OthersRecharge, vault.username, false)
        await { !model.state.value.loading }
        model.queryRecipient(RecipientQueryType.StudentNumber, "202600000002", "abcde")
        await { !model.state.value.loading }
        assertEquals(1, model.state.value.recipients.size)
    }
    private fun assertOneResult(message: String) {
        runBlocking {
            assertEquals(message, withTimeout(1_000) { model.messages.first() })
            withTimeout(1_000) { model.cardChanges.first() }
        }
        assertNoResult()
    }
    private fun assertNoResult() {
        runBlocking {
            assertNull(withTimeoutOrNull(50) { model.messages.first() })
            assertNull(withTimeoutOrNull(50) { model.cardChanges.first() })
        }
    }
    private fun assertNoRechargeRequest() {
        runBlocking { assertNull(withTimeoutOrNull(50) { model.rechargeRequests.first() }) }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for local card-service test")
    }
    private class MemoryVault : Vault {
        var username = "test-owner"
        override fun credentials() = Credentials(username, "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) { username = value.username }
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() { username = "" }
    }
    private class FakeCards : CardService {
        var extraCards: List<CampusCard> = emptyList()
        var lost = false
        var needsPassword = false
        var writes = 0; var writeResponses = 0; var queries = 0; var queryResponses = 0; var configurations = 0; var captchas = 0
        var lastAction: CampusCardAction? = null
        var failAfterWrite = false
        var writeFailure: Exception? = null
        var queryFailure: Exception? = null
        var writeGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var queryGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var cardGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var cardReads = 0
        var cardResponses = 0
        var cardFailure: Exception? = null
        var extraRecipients: List<RechargeRecipient> = emptyList()
        var configFailure: Exception? = null
        var configGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var configResponses = 0
        fun cards() = listOf(CampusCard("fake-card", "校园卡", "202600000001", lost, false, 0)) + extraCards
        override suspend fun cardConfiguration(token: String): CardServiceConfiguration {
            cardReads++
            withContext(NonCancellable) { cardGate.await() }; cardResponses++
            cardFailure?.let { throw it }
            return CardServiceConfiguration(cards(), needsPassword, needsPassword)
        }
        override suspend fun changeCardState(token: String, card: CampusCard, action: CampusCardAction, password: String): List<CampusCard> {
            writes++; lastAction = action
            withContext(NonCancellable) { writeGate.await() }; writeResponses++
            writeFailure?.let { throw it }
            lost = action == CampusCardAction.Lost
            if (failAfterWrite) throw CardOperationUnconfirmed("网络连接失败")
            return cards()
        }
        override suspend fun recipientCaptcha(token: String): RecipientCaptcha { captchas++; return RecipientCaptcha("fake-key-$captchas", byteArrayOf(1)) }
        override suspend fun rechargeRecipients(token: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String): List<RechargeRecipient> {
            queries++
            withContext(NonCancellable) { queryGate.await() }; queryResponses++
            queryFailure?.let { throw it }
            return listOf(RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户")) + extraRecipients
        }
        override suspend fun recipientRechargeConfiguration(token: String, recipient: RechargeRecipient): RechargeConfiguration {
            configurations++
            withContext(NonCancellable) { configGate.await() }; configResponses++
            configFailure?.let { throw it }
            return RechargeConfiguration("401", recipient.targetId, listOf(1000, 5000), 1, 50000, true, false, recipient)
        }
    }
    @Test fun preflightAndBusinessFailuresStayInDialogWithOriginalMessageAndNoReadback() {
        for (failure in listOf(CardOperationNotStarted("安全键盘读取失败"),
            CardOperationRejected("  后端密码错误提示\n"), AuthenticationRequired(),
            YktException("未分类客户端错误"))) {
            model.dismiss()
            openCards()
            val reads = service.cardReads
            service.writeFailure = failure
            model.changeCard("")
            await { !model.state.value.loading }
            assertTrue(model.state.value.visible)
            assertNotNull(model.state.value.selectedCard)
            assertEquals(reads, service.cardReads)
            assertEquals(failure.message, model.state.value.error)
            assertNoResult()
        }
    }

    @Test fun ambiguousWriteWithUnchangedStatePreservesCauseWithoutReplaying() {
        service.lost = true
        openCards()
        val reads = service.cardReads
        service.writeFailure = CardOperationUnconfirmed("上游响应超时")
        model.changeCard("")
        await { !model.state.value.loading }
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.completed)
        assertEquals(reads + 1, service.cardReads)
        assertEquals(1, service.writes)
        assertOneResult("上游响应超时。操作结果待确认，请重新查看卡状态")
    }

    @Test fun userRetryAfterExplicitRejectionCanSucceedWithoutAutomaticRetry() {
        service.lost = true
        openCards()
        service.writeFailure = CardOperationRejected("查询密码错误")
        model.changeCard("")
        await { !model.state.value.loading }
        assertEquals(1, service.writes)
        assertEquals("查询密码错误", model.state.value.error)
        assertNoResult()
        service.writeFailure = null
        model.changeCard("")
        await { model.state.value.completed }
        assertEquals(2, service.writes)
        assertOneResult("解挂成功")
    }

}
