package com.mercury.yktwx.data

import com.mercury.yktwx.data.accountRepositoryFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CampusCardStoreTest {
    private var card = CampusCard("account", "校园卡", "user", false, false, 0)
    private var cardQueries = 0
    private var unconfirmed = false
    private var readGate: CompletableDeferred<Unit>? = null
    private val entered = CompletableDeferred<Unit>()
    private val service = object : YktService by yktApiFixture({ error("Unexpected live request") }) {
        override suspend fun paymentCard(token: String, account: String): CampusCard {
            cardQueries++
            val result = card
            entered.complete(Unit)
            readGate?.let { withContext(NonCancellable) { it.await() } }
            return result
        }
    }
    private val cards = object : CardService by cardServiceApiFixture({ error("Unexpected live request") }) {
        override suspend fun cardConfiguration(token: String) = CardServiceConfiguration(listOf(card), false, false)
        override suspend fun passwordConfiguration(token: String) = CardPasswordConfiguration(listOf(card))
        override suspend fun changeCardState(token: String, card: CampusCard, action: CampusCardAction, password: String): List<CampusCard> {
            if (unconfirmed) throw CardOperationUnconfirmed("pending")
            this@CampusCardStoreTest.card = CampusCard(card.account, card.name, card.sno, action == CampusCardAction.Lost, card.frozen, card.status)
            return listOf(this@CampusCardStoreTest.card)
        }
    }
    private val vault = object : Vault {
        override fun credentials() = Credentials("user", "password")
        override fun session() = Session("token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private val repository = accountRepositoryFixture(service, vault, cardService = cards, readNow = { 10_000L })

    @Test fun bothCardConfigurationReadersPopulateTheSameAccountStatus() = runTest {
        repository.cardConfiguration("user")
        assertEquals(card, repository.paymentCard("user", "account"))
        assertEquals(0, cardQueries)
        repository.passwordConfiguration("user")
        assertEquals(card, repository.paymentCard("user", "account"))
        repository.paymentCard("user", "account", force = true)
        assertEquals(1, cardQueries)
    }

    @Test fun confirmedCardMutationReplacesAnOlderConcurrentAccountRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        readGate = gate
        val old = async { runCatching { repository.paymentCard("user", "account") } }
        entered.await()
        try {
            repository.changeCardState("user", card, CampusCardAction.Lost, "")
            assertTrue(repository.paymentCard("user", "account").lost)
            assertEquals(1, cardQueries)
        } finally { gate.complete(Unit) }
        old.await()
        assertTrue(repository.cardSnapshot("user", "account").value!!.lost)
    }

    @Test fun unconfirmedWriteRequiresFreshStatusEvenIfTheServerReturnsAnUnchangedCard() = runTest {
        repository.cardConfiguration("user")
        unconfirmed = true
        try {
            repository.changeCardState("user", card, CampusCardAction.Lost, "")
            fail("Expected unconfirmed write")
        } catch (_: CardOperationUnconfirmed) { }
        assertFalse(repository.cardSnapshot("user", "account").fresh(10_000L))
        assertFalse(repository.paymentCard("user", "account").lost)
        assertTrue(repository.cardSnapshot("user", "account").fresh(10_000L))
        assertEquals(1, cardQueries)
    }
}
