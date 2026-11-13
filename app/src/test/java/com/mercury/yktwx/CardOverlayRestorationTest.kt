package com.mercury.yktwx

import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.*
import com.mercury.yktwx.state.RecipientFormDraft
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class CardOverlayRestorationTest {
    private class Cards : CardService by cardServiceApiFixture({ error("Unexpected network") }) {
        var reads = 0
        var writes = 0
        var captchas = 0
        var lookups = 0
        var recipientReads = 0
        var recipientFailure: Exception? = null
        var recipientTarget = "recipient-target"
        var recipientGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        var cardId = "saved-card"
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        fun card() = CampusCard(cardId, "校园卡", "123456", false, false, 0)
        override suspend fun cardConfiguration(token: String): CardServiceConfiguration {
            reads++; return CardServiceConfiguration(listOf(card()), false, true)
        }
        override suspend fun passwordConfiguration(token: String): CardPasswordConfiguration {
            reads++; return CardPasswordConfiguration(listOf(card()))
        }
        override suspend fun modifyCardPassword(token: String, card: CampusCard, oldPassword: String, newPassword: String, confirmation: String) {
            writes++; gate.await()
        }
        override suspend fun changeCardState(token: String, card: CampusCard, action: CampusCardAction, password: String): List<CampusCard> {
            writes++; gate.await(); return listOf(card())
        }
        override suspend fun recipientCaptcha(token: String): RecipientCaptcha {
            captchas++; return RecipientCaptcha("captcha-$captchas", byteArrayOf(1))
        }
        override suspend fun rechargeRecipients(token: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String): List<RechargeRecipient> {
            lookups++; return listOf(RechargeRecipient("recipient-card", recipientTarget, "姓名", "456789", "校园卡"))
        }
        override suspend fun recipientRechargeConfiguration(token: String, recipient: RechargeRecipient): RechargeConfiguration {
            recipientReads++
            recipientFailure?.let { throw it }
            recipientGate.await()
            return RechargeConfiguration("401", recipient.targetId, listOf(1000), 1, 50000, true, false, recipient)
        }
    }
    private fun repository(cards: Cards) = accountRepositoryFixture(FakeAccountService(), object : Vault {
        override fun credentials() = Credentials("owner", "fixture")
        override fun session() = Session("fixture-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }, cardService = cards)
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Card restoration did not settle")
    }

    private fun recipientAmountHandle(models: ViewModelStore): SavedStateHandle {
        val handle = SavedStateHandle()
        val first = CardServiceViewModel(repository(Cards()), handle).also { models.put("first", it) }
        first.open(CampusCardAction.OthersRecharge, "owner", false)
        await { !first.state.value.loading }
        first.updateForm(RecipientFormDraft(identifier = "456789", amount = "29.50"))
        first.queryRecipient(RecipientQueryType.StudentNumber, "456789", "abc")
        await { first.state.value.rechargeConfig != null }
        return handle.recreated()
    }

    @Test fun failedRecipientRestoreRetriesOriginalTargetWithoutReplayingLookup() {
        val models = ViewModelStore()
        try {
            val cards = Cards().apply { recipientFailure = YktException("暂时无法读取收款账户") }
            val next = CardServiceViewModel(repository(cards), recipientAmountHandle(models)).also { models.put("next", it) }
            next.accountChanged("owner"); next.restoreIfNeeded(true, true)
            await { next.state.value.restorationFailed }
            assertEquals(1, cards.recipientReads)
            next.restoreIfNeeded(true, true)
            assertEquals(1, cards.recipientReads)
            cards.recipientFailure = null
            cards.recipientGate = CompletableDeferred()
            next.retryRestore()
            await { cards.recipientReads == 2 }
            assertTrue(next.state.value.loading)
            next.clearRecipient()
            cards.recipientGate.complete(Unit)
            await { next.state.value.rechargeConfig != null }
            assertFalse(next.state.value.restorationFailed)
            assertEquals("recipient-target", next.state.value.rechargeConfig?.targetId)
            assertEquals(RecipientFormDraft(identifier = "456789", amount = "29.50"), next.form.value)
            assertEquals(2, cards.recipientReads)
            assertEquals(0, cards.captchas)
            assertEquals(0, cards.lookups)
            assertEquals(0, cards.writes)
        } finally { models.clear() }
    }

    @Test fun editingAfterFailedRestoreKeepsNewDraftAndTargetAcrossAnotherRecreation() {
        val models = ViewModelStore()
        try {
            val handle = recipientAmountHandle(models)
            val failedCards = Cards().apply { recipientFailure = YktException("暂时无法读取收款账户") }
            val failed = CardServiceViewModel(repository(failedCards), handle).also { models.put("failed", it) }
            failed.accountChanged("owner"); failed.restoreIfNeeded(true, true)
            await { failed.state.value.restorationFailed }
            val draft = RecipientFormDraft(identifier = "987654", amount = "")
            failed.updateForm(draft); failed.clearRecipient()
            assertFalse(failed.state.value.restorationFailed)

            val newHandle = handle.recreated()
            val cards = Cards().apply { recipientTarget = "replacement-target" }
            val edited = CardServiceViewModel(repository(cards), newHandle).also { models.put("edited", it) }
            edited.accountChanged("owner"); edited.restoreIfNeeded(true, true)
            await { !edited.state.value.loading }
            assertEquals(draft, edited.form.value)
            assertEquals(0, cards.recipientReads)
            assertNotNull(edited.state.value.captcha)
            edited.queryRecipient(RecipientQueryType.StudentNumber, "987654", "new-code")
            await { edited.state.value.rechargeConfig != null }
            edited.updateForm(draft.copy(amount = "15.00"))

            val restoredCards = Cards()
            val restored = CardServiceViewModel(repository(restoredCards), newHandle.recreated()).also { models.put("restored", it) }
            restored.accountChanged("owner"); restored.restoreIfNeeded(true, true)
            await { !restored.state.value.loading }
            assertEquals("replacement-target", restored.state.value.rechargeConfig?.targetId)
            assertEquals(draft.copy(amount = "15.00"), restored.form.value)
            assertEquals(1, restoredCards.recipientReads)
            assertEquals(0, restoredCards.lookups)
            assertEquals(0, restoredCards.writes)
        } finally { models.clear() }
    }

    @Test fun refreshingCaptchaAfterFailedRestoreDiscardsOriginalTargetSnapshot() {
        val models = ViewModelStore()
        try {
            val handle = recipientAmountHandle(models)
            val cards = Cards().apply { recipientFailure = YktException("暂时无法读取收款账户") }
            val failed = CardServiceViewModel(repository(cards), handle).also { models.put("failed", it) }
            failed.accountChanged("owner"); failed.restoreIfNeeded(true, true)
            await { failed.state.value.restorationFailed }
            failed.refreshCaptcha()
            await { failed.state.value.captcha != null }
            assertFalse(failed.state.value.restorationFailed)

            val restoredCards = Cards()
            val restored = CardServiceViewModel(repository(restoredCards), handle.recreated()).also { models.put("restored", it) }
            restored.accountChanged("owner"); restored.restoreIfNeeded(true, true)
            await { !restored.state.value.loading }
            assertNotNull(restored.state.value.captcha)
            assertEquals(0, restoredCards.recipientReads)
            assertTrue(restored.state.value.recipients.isEmpty())
            assertEquals(RecipientFormDraft(identifier = "456789", amount = "29.50"), restored.form.value)
        } finally { models.clear() }
    }

    @Test fun passwordIntentRequeriesOriginalCardWithoutReplayingWrite() {
        val models = ViewModelStore()
        val firstCards = Cards()
        try {
            val handle = SavedStateHandle()
            val first = CardPasswordViewModel(repository(firstCards), handle).also { models.put("first", it) }
            first.open("owner", false)
            await { first.state.value.visible }
            firstCards.gate = CompletableDeferred()
            first.submit("123456", "456789", "456789")
            await { firstCards.writes == 1 }
            val newCards = Cards()
            val next = CardPasswordViewModel(repository(newCards), handle.recreated()).also { models.put("next", it) }
            next.restoreIfNeeded(true, true)
            assertEquals(0, newCards.reads)
            next.accountChanged("owner")
            next.restoreIfNeeded(true, false)
            assertEquals(0, newCards.reads)
            next.restoreIfNeeded(true, true)
            await { !next.state.value.loading }
            assertEquals("saved-card", next.state.value.selectedCard?.account)
            assertTrue(next.state.value.error.orEmpty().contains("核实"))
            assertEquals(0, newCards.writes)
            next.accountChanged("another-owner")
            assertFalse(next.state.value.visible)
        } finally { firstCards.gate.complete(Unit); models.clear() }
    }

    @Test fun changedCardNeverSelectsReplacementForOriginalConfirmation() {
        val models = ViewModelStore()
        try {
            val handle = SavedStateHandle()
            val first = CardServiceViewModel(repository(Cards()), handle).also { models.put("first", it) }
            first.open(CampusCardAction.ManageState, "owner", false)
            await { first.state.value.visible }
            val newCards = Cards().apply { cardId = "replacement-card" }
            val next = CardServiceViewModel(repository(newCards), handle.recreated()).also { models.put("next", it) }
            next.accountChanged("owner"); next.restoreIfNeeded(true, true)
            await { !next.state.value.loading }
            assertNull(next.state.value.selectedCard)
            assertTrue(next.state.value.error.orEmpty().contains("已变更"))
            next.changeCard("")
            assertEquals(0, newCards.writes)
        } finally { models.clear() }
    }

    @Test fun interruptedCardWriteOnlyReadsStateAndRequiresNewSelection() {
        val models = ViewModelStore()
        val firstCards = Cards()
        try {
            val handle = SavedStateHandle()
            val first = CardServiceViewModel(repository(firstCards), handle).also { models.put("first", it) }
            first.open(CampusCardAction.ManageState, "owner", false)
            await { first.state.value.visible }
            firstCards.gate = CompletableDeferred()
            first.changeCard("")
            await { firstCards.writes == 1 }
            val newCards = Cards()
            val next = CardServiceViewModel(repository(newCards), handle.recreated()).also { models.put("next", it) }
            next.accountChanged("owner"); next.restoreIfNeeded(true, true)
            await { !next.state.value.loading }
            assertNull(next.state.value.selectedCard)
            assertTrue(next.state.value.error.orEmpty().contains("核实"))
            next.changeCard("")
            assertEquals(1, newCards.reads)
            assertEquals(0, newCards.writes)
        } finally { firstCards.gate.complete(Unit); models.clear() }
    }

    @Test fun recipientAmountRequeriesSelectedTargetAndNeverReplaysCaptchaOrContinueEvent() {
        val models = ViewModelStore()
        try {
            val handle = SavedStateHandle()
            val first = CardServiceViewModel(repository(Cards()), handle).also { models.put("first", it) }
            first.open(CampusCardAction.OthersRecharge, "owner", false)
            await { !first.state.value.loading }
            first.updateForm(RecipientFormDraft(identifier = "456789", amount = "29.50"))
            first.queryRecipient(RecipientQueryType.StudentNumber, "456789", "abc")
            await { first.state.value.rechargeConfig != null }
            val newCards = Cards()
            val next = CardServiceViewModel(repository(newCards), handle.recreated()).also { models.put("next", it) }
            next.accountChanged("owner"); next.restoreIfNeeded(true, true)
            await { !next.state.value.loading }
            assertEquals("recipient-target", next.state.value.rechargeConfig?.targetId)
            assertSame(next.state.value.selectedRecipient, next.state.value.rechargeConfig?.recipient)
            assertEquals(RecipientFormDraft(identifier = "456789", amount = "29.50"), next.form.value)
            assertEquals(1, newCards.recipientReads)
            assertEquals(0, newCards.captchas)
            assertEquals(0, newCards.lookups)
            assertEquals(0, newCards.writes)
            assertFalse(next.state.value.loading)
        } finally { models.clear() }
    }
}
