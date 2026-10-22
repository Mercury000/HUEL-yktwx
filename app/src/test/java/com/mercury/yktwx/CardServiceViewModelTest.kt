package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
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
    private val repository = AccountRepository(YktApi { error("Unexpected network request") }, vault, cardService = service)
    private val model = CardServiceViewModel(ApplicationProvider.getApplicationContext<Application>(), repository)
    private val store = ViewModelStore().apply { put("card", model) }
    @After fun cleanup() {
        service.writeGate.complete(Unit); service.queryGate.complete(Unit); service.cardGate.complete(Unit)
        store.clear(); shadowOf(Looper.getMainLooper()).idle()
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
        service.writeFailure = NetworkFailure()
        service.cardFailure = NetworkFailure()
        model.changeCard("")
        await { !model.state.value.loading }
        assertFalse(model.state.value.visible)
        assertFalse(model.state.value.completed)
        assertNull(model.state.value.selectedCard)
        assertNull(model.state.value.error)
        assertEquals(1, service.writes)
        assertOneResult("操作结果待确认，请重新查看卡状态")
        model.changeCard("")
        assertEquals(1, service.writes)
    }
    @Test fun lateWriteCannotRestoreDialogAfterAccountChanges() {
        openCards()
        service.writeGate = CompletableDeferred()
        model.changeCard("")
        await { service.writes == 1 }
        vault.username = "new-owner"
        model.accountChanged(vault.username)
        service.writeGate.complete(Unit)
        await { service.writeResponses == 1 }
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
    @Test fun recipientRequiresExplicitSelectionAndEditingClearsOldTarget() {
        openRecipients()
        assertNull(model.state.value.selectedRecipient)
        model.continueRecharge()
        assertEquals(0, service.configurations)
        model.selectRecipient(model.state.value.recipients.single())
        model.clearRecipient()
        assertTrue(model.state.value.recipients.isEmpty())
        assertNull(model.state.value.selectedRecipient)
        model.continueRecharge()
        assertEquals(0, service.configurations)
    }
    @Test fun recipientCanContinueToSharedRechargeWithoutPayerConfiguration() {
        openRecipients()
        val selected = model.state.value.recipients.single()
        model.selectRecipient(selected)
        model.continueRecharge()
        await { service.configurations == 1 }
        val request = runBlocking { model.rechargeRequests.first() }
        assertTrue(model.accepts(request))
        assertSame(selected, request.config.recipient)
        assertEquals("recipient-target", request.config.targetId)
        model.dismiss()
        assertFalse(model.accepts(request))
    }
    @Test fun failedLookupClearsRecipientAndFetchesFreshCaptcha() {
        openRecipients()
        model.selectRecipient(model.state.value.recipients.single())
        model.refreshCaptcha()
        await { !model.state.value.loading }
        service.queryFailure = RecipientLookupRejected(true, "验证码有误")
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
        assertNull(model.state.value.error)
        assertEquals(1, service.writes)
        assertEquals(1, service.cardReads)
        assertEquals("查询密码错误，请重新输入", runBlocking { withTimeout(1_000) { model.messages.first() } })
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
        fun cards() = listOf(CampusCard("fake-card", "校园卡", "202600000001", lost, false, 0))
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
            if (failAfterWrite) throw NetworkFailure()
            return cards()
        }
        override suspend fun recipientCaptcha(token: String): RecipientCaptcha { captchas++; return RecipientCaptcha("fake-key-$captchas", byteArrayOf(1)) }
        override suspend fun rechargeRecipients(token: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String): List<RechargeRecipient> {
            queries++
            withContext(NonCancellable) { queryGate.await() }; queryResponses++
            queryFailure?.let { throw it }
            return listOf(RechargeRecipient("recipient-card", "recipient-target", "示例同学", "202600000002", "统一账户", 0))
        }
        override suspend fun recipientRechargeConfiguration(token: String, recipient: RechargeRecipient): RechargeConfiguration {
            configurations++
            return RechargeConfiguration("401", recipient.targetId, recipient.accountName, 0, listOf(1000, 5000), 1, 50000, true, false, recipient)
        }
    }
}
