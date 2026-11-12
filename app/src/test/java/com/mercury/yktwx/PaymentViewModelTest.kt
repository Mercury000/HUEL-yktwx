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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class PaymentViewModelTest {
    private val vault = MemoryVault()
    private var account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 6496)
    private var codeAvailable = false
    private var payments = 0
    private var logins = 0
    private var loginFailure: Exception? = null
    private var problem = PaymentCodeProblem.Unavailable
    private var paymentGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var responses = 0
    private var accountQueries = 0
    private var accountFailure: Exception? = null
    private var accountGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var accountResponses = 0
    private var accountPublishGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var paymentFailure: Exception? = null
    @Volatile private var cardQueries = 0
    @Volatile private var cardResponses = 0
    private var cardFailure: Exception? = null
    private var cardGate = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var cardLost = false
    private var cardFrozen = false
    private var cardAccount: String? = null
    private val service = object : YktService by YktApi({ error("Unexpected network request") }) {
        override suspend fun paymentCard(token: String, account: String): CampusCard {
            cardQueries++
            val result = CampusCard(cardAccount ?: account, "校园卡", "123", cardLost, cardFrozen, 0)
            withContext(NonCancellable) { cardGate.await(); cardResponses++ }
            cardFailure?.let { throw it }
            return result
        }
        override suspend fun login(credentials: Credentials): Session {
            logins++
            loginFailure?.let { throw it }
            return Session("fake-token", Long.MAX_VALUE)
        }
        override suspend fun paymentAccount(token: String): PaymentAccount {
            accountQueries++
            val result = account
            withContext(NonCancellable) { accountGate.await() }
            accountResponses++
            accountFailure?.let { throw it }
            return result
        }
        override suspend fun payment(token: String, onAccount: suspend (PaymentAccount) -> Unit): PaymentBatch {
            payments++
            val allowed = codeAvailable
            val currentProblem = problem
            val currentAccount = account
            withContext(NonCancellable) { accountPublishGate.await(); onAccount(currentAccount) }
            withContext(NonCancellable) { paymentGate.await() }
            responses++
            paymentFailure?.let { throw it }
            if (!allowed) throw PaymentCodeUnavailable(currentAccount, "持卡人已挂失！", currentProblem)
            return PaymentBatch(listOf(payments.toString().padStart(20, '0')), 60, account)
        }
    }
    private val repository = AccountRepository(service, vault)
    private val model = PaymentViewModel(ApplicationProvider.getApplicationContext<Application>(), repository)
    private val store = ViewModelStore().apply { put("payment", model) }

    @After fun cleanup() { cardGate.complete(Unit); accountGate.complete(Unit); accountPublishGate.complete(Unit); paymentGate.complete(Unit); store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun shellBackgroundClearsCodeAndReturnRequestsANewCodeOnTheSameNetwork() {
        codeAvailable = true
        model.initialize(); await { !model.state.value.restoringLogin }
        val active = ReadEnvironment(ReadPage.Payment, true, true, 0, paymentVisible = true, resumeEpoch = 1)
        model.onReadEnvironment(active)
        await { model.state.value.code != null }
        val first = model.state.value.code
        val balance = model.state.value.account
        model.onReadEnvironment(active.copy(page = null, foreground = false))
        assertNull(model.state.value.code)
        assertSame(balance, model.state.value.account)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(1, payments)
        model.onReadEnvironment(active.copy(resumeEpoch = 2))
        await { model.state.value.code != null && model.state.value.code != first }
        assertEquals(2, payments)
        assertNull(model.state.value.error)
    }

    @Test fun coalescedShellResumeCannotKeepACodeWithAStoppedTicker() {
        codeAvailable = true
        model.initialize(); await { !model.state.value.restoringLogin }
        val active = ReadEnvironment(ReadPage.Payment, true, true, 0, paymentVisible = true, resumeEpoch = 1)
        model.onReadEnvironment(active)
        await { model.state.value.code != null }
        val first = model.state.value.code
        model.onReadEnvironment(active.copy(resumeEpoch = 2))
        await { model.state.value.code != null && model.state.value.code != first }
        assertEquals(2, payments)
        model.onReadEnvironment(active.copy(resumeEpoch = 2))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, payments)
    }

    @Test fun localInitializationPreservesCredentialsWithoutBusinessRequests() {
        codeAvailable = true
        val credentials = vault.credentials()
        val session = vault.session()
        model.initialize()
        await { !model.state.value.restoringLogin }
        assertFalse(model.state.value.needsLogin)
        assertEquals(credentials?.username, model.state.value.username)
        assertEquals(credentials, vault.credentials())
        assertEquals(session, vault.session())
        assertNull(model.state.value.code)
        assertEquals(0, logins)
        assertEquals(0, payments)
        model.onForeground()
        await { !model.state.value.busy }
        assertFalse(model.state.value.needsLogin)
        assertEquals(credentials?.username, model.state.value.username)
        assertEquals(0, logins)
        assertEquals(1, payments)
    }

    @Test fun logoutDuringLocalRestorationCannotPublishTheOldLoginWhileCredentialsAreClearing() {
        val readGate = CountDownLatch(1)
        val clearGate = CountDownLatch(1)
        val reading = AtomicBoolean(false)
        val clearing = AtomicBoolean(false)
        val delayedVault = object : Vault by vault {
            override fun credentials(): Credentials? {
                reading.set(true)
                check(readGate.await(10, TimeUnit.SECONDS))
                return vault.credentials()
            }
            override fun clear() {
                clearing.set(true)
                check(clearGate.await(10, TimeUnit.SECONDS))
                vault.clear()
            }
        }
        val restoring = PaymentViewModel(ApplicationProvider.getApplicationContext<Application>(),
            AccountRepository(service, delayedVault), initialEnvironment = ReadEnvironment.Inactive)
        store.put("restoring-payment", restoring)
        try {
            restoring.initialize()
            await { reading.get() }
            restoring.logout()
            readGate.countDown()
            await { clearing.get() }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(restoring.state.value.needsLogin)
            assertTrue(restoring.state.value.busy)
            assertNull(restoring.state.value.code)
            clearGate.countDown()
            await { !restoring.state.value.busy }
            assertTrue(restoring.state.value.needsLogin)
            assertNull(vault.credentials())
            assertNull(vault.session())
            assertEquals(0, logins)
            assertEquals(0, payments)
        } finally {
            readGate.countDown()
            clearGate.countDown()
        }
    }

    @Test fun balancePendingEndsBeforeTheRemainingPaymentVerification() {
        codeAvailable = true
        accountPublishGate = CompletableDeferred()
        paymentGate = CompletableDeferred()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground(); await { payments == 1 }
        assertTrue(model.state.value.balanceLoading)
        accountPublishGate.complete(Unit)
        await { model.state.value.account != null && !model.state.value.balanceLoading }
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.code)
        paymentGate.complete(Unit)
        await { !model.state.value.busy }
        assertFalse(model.state.value.balanceLoading)
        assertNotNull(model.state.value.code)
    }

    @Test fun switchingBeforePaymentBalanceReturnsLoadsMissingBalanceWithoutFetchingAnotherCode() {
        accountPublishGate = CompletableDeferred()
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { payments == 1 }
        model.setPageVisible(false)
        assertNull(model.state.value.account)
        accountGate = CompletableDeferred()
        model.setAccountPageVisible(true)
        await { accountQueries == 1 }
        assertTrue(model.state.value.accountLoading)
        model.refreshAccount(); model.refreshAccount(manual = true)
        assertEquals(1, accountQueries)
        accountGate.complete(Unit)
        await { model.state.value.account != null && !model.state.value.accountLoading }
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        assertEquals(PaymentCodeProblem.StatusUnverified, model.state.value.problem)
        assertNull(model.state.value.code)
        assertEquals(1, payments)
        accountPublishGate.complete(Unit)
        await { responses == 1 }
        assertNull(model.state.value.code)
        assertEquals(0L, model.state.value.secondsLeft)
    }

    @Test fun cachedBalanceIsReusedWhileCardStatusIsReadAndManualRefreshNeverFetchesCodes() {
        readyForTurn()
        model.setPageVisible(false)
        model.setAccountPageVisible(true)
        await { model.state.value.accountCard != null && !model.state.value.cardStatusLoading }
        assertEquals(0, accountQueries)
        assertEquals(1, cardQueries)
        model.setAccountPageVisible(false); model.setAccountPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, accountQueries)
        assertEquals(1, cardQueries)
        account = account.with(balanceCents = 1234)
        model.refreshAccount(manual = true)
        await { accountResponses == 1 && !model.state.value.accountLoading && !model.state.value.cardStatusLoading }
        assertEquals(1234L, model.state.value.account!!.balanceCents)
        assertNull(model.state.value.problem)
        assertEquals(1, payments)
        assertNull(model.state.value.code)
        assertEquals(2, cardQueries)
    }

    @Test fun differentBalanceAccountDoesNotInheritThePreviousCardStatus() {
        readyForTurn()
        model.setPageVisible(false)
        model.setAccountPageVisible(true)
        await { !model.state.value.cardStatusLoading }
        account = account.with(account = "other-account", balanceCents = 1234)
        model.refreshAccount(manual = true)
        await { model.state.value.account!!.account == "other-account" && !model.state.value.accountLoading && !model.state.value.cardStatusLoading }
        assertEquals(PaymentCodeProblem.StatusUnverified, model.state.value.problem)
        assertEquals("other-account", model.state.value.accountCard!!.account)
        assertNull(model.state.value.code)
        assertEquals(1, payments)
    }

    @Test fun failedBalanceIsRetriedOnAccountReentryInsteadOfBeingTreatedAsCache() {
        model.setPageVisible(false)
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        accountFailure = NetworkFailure()
        model.setAccountPageVisible(true)
        await { model.state.value.accountError != null && !model.state.value.accountLoading }
        assertNull(model.state.value.account)
        assertFalse(model.state.value.needsLogin)
        model.setAccountPageVisible(false)
        accountFailure = null
        model.setAccountPageVisible(true)
        await { model.state.value.account != null && !model.state.value.accountLoading }
        assertNull(model.state.value.accountError)
        assertEquals(2, accountQueries)
        assertEquals(0, payments)
    }

    @Test fun failedManualBalanceRefreshPreservesValuesAndRetriesOnForeground() {
        readyForTurn()
        model.setPageVisible(false)
        model.setAccountPageVisible(true)
        await { !model.state.value.cardStatusLoading }
        accountFailure = NetworkFailure()
        model.refreshAccount(manual = true)
        await { model.state.value.accountError != null }
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        accountFailure = null
        account = account.with(balanceCents = 5496)
        model.onBackground(); model.onForeground()
        await { model.state.value.account!!.balanceCents == 5496L && !model.state.value.accountLoading }
        assertNull(model.state.value.accountError)
        assertEquals(2, accountQueries)
        assertEquals(1, payments)
    }

    @Test fun lateAccountResponseCannotOverwriteNewerPaymentBalance() {
        model.setPageVisible(false)
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        accountGate = CompletableDeferred()
        model.setAccountPageVisible(true)
        await { accountQueries == 1 }
        account = account.with(balanceCents = 5496)
        codeAvailable = true
        model.setPageVisible(true)
        model.setAccountPageVisible(false)
        await { model.state.value.code != null }
        accountGate.complete(Unit)
        await { accountResponses == 1 }
        assertEquals(5496L, model.state.value.account!!.balanceCents)
        assertNotNull(model.state.value.code)
        assertFalse(model.state.value.accountLoading)
    }

    @Test fun lateAccountResponseAfterLogoutAndNewLoginCannotRestoreThePreviousOwner() {
        model.setPageVisible(false)
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        accountGate = CompletableDeferred()
        model.setAccountPageVisible(true)
        await { accountQueries == 1 }
        model.logout()
        await { model.state.value.needsLogin && !model.state.value.busy }
        assertNull(model.state.value.account)
        account = account.with(account = "new-account", balanceCents = 9876)
        codeAvailable = true
        model.login("456", "new-password")
        await { !model.state.value.needsLogin && !model.state.value.busy }
        accountGate.complete(Unit)
        await { accountResponses == 1 }
        assertEquals("456", model.state.value.username)
        assertEquals("new-account", model.state.value.account!!.account)
        assertEquals(9876L, model.state.value.account!!.balanceCents)
        assertNull(model.state.value.code)
    }

    @Test fun cancelledAccountReadOnBackgroundIsFetchedAgainOnForeground() {
        model.setPageVisible(false)
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        accountGate = CompletableDeferred()
        model.setAccountPageVisible(true)
        await { accountQueries == 1 }
        model.onBackground()
        assertFalse(model.state.value.accountLoading)
        accountGate.complete(Unit)
        await { accountResponses == 1 }
        assertNull(model.state.value.account)
        model.onForeground()
        await { model.state.value.account != null && !model.state.value.accountLoading }
        assertEquals(2, accountQueries)
        assertEquals(0, payments)
    }

    @Test fun expiredAccountSessionClearsCachedBalanceAndRoutesToLogin() {
        readyForTurn()
        model.setPageVisible(false)
        model.setAccountPageVisible(true)
        await { !model.state.value.cardStatusLoading }
        accountFailure = AuthenticationRequired()
        model.refreshAccount(manual = true)
        await { model.state.value.needsLogin && !model.state.value.accountLoading }
        assertNull(model.state.value.account)
        assertNull(model.state.value.code)
        assertNull(model.state.value.problem)
        assertEquals("登录已失效，请重新登录", model.state.value.error)
        assertEquals(1, payments)
    }

    @Test fun firstBalanceIsVisibleWhileCardVerificationAndBarcodeRemainPending() {
        codeAvailable = true
        paymentGate = CompletableDeferred()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.account != null }
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.code)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        paymentGate.complete(Unit)
        await { !model.state.value.busy }
        assertNotNull(model.state.value.code)
    }
    @Test fun authenticationFailureAfterPublishingBalanceClearsThePartialAccount() {
        paymentGate = CompletableDeferred()
        paymentFailure = AuthenticationRequired()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.account != null }
        assertTrue(model.state.value.busy)
        paymentGate.complete(Unit)
        await { model.state.value.needsLogin && !model.state.value.busy }
        assertNull(model.state.value.account)
        assertNull(model.state.value.code)
    }
    @Test fun lateBalanceCallbackAfterLogoutCannotRestoreTheOldAccount() {
        accountPublishGate = CompletableDeferred()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { payments == 1 }
        model.logout()
        await { model.state.value.needsLogin && !model.state.value.busy }
        accountPublishGate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.account)
        assertNull(model.state.value.code)
    }

    @Test fun rejectedLoginCannotEnterMainUsingPreviousCredentials() {
        loginFailure = InvalidCredentials()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.login("456", "wrong-password")
        await { !model.state.value.busy && model.state.value.error != null }
        assertTrue(model.state.value.needsLogin)
        assertEquals("456", model.state.value.username)
        assertEquals(loginFailure!!.message, model.state.value.error)
        assertNull(model.state.value.account)
        assertEquals(1, logins); assertEquals(0, payments)
        assertNull(vault.credentials())
    }

    @Test fun firstEntryShowsBalanceEvenWhenBarcodeIsUnavailable() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.account != null && !model.state.value.busy }
        assertFalse(model.state.value.needsLogin)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        assertEquals("持卡人已挂失！", model.state.value.error)
        assertNull(model.state.value.code)
        assertEquals(0, logins)
    }
    @Test fun leavingPaymentAndBackgroundRetainTheReadFailureKindAlongWithItsMessage() {
        paymentFailure = NetworkFailure("服务暂时无法访问")
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { !model.state.value.busy && model.state.value.error != null }
        assertEquals(ReadFailureKind.Network, model.state.value.errorKind)
        val message = model.state.value.error
        val reads = payments
        model.setPageVisible(false)
        assertEquals(message, model.state.value.error)
        assertEquals(ReadFailureKind.Network, model.state.value.errorKind)
        model.onBackground()
        assertEquals(message, model.state.value.error)
        assertEquals(ReadFailureKind.Network, model.state.value.errorKind)
        assertTrue(hasNetworkFailure(model.state.value.error to model.state.value.errorKind))
        assertEquals(reads, payments)
    }

    @Test fun hiddenPaymentPageStopsTicksAndRequestsUntilItBecomesVisible() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        val first = model.state.value.code
        val balance = model.state.value.account
        model.setPageVisible(false)
        val hidden = model.state.value
        assertNull(hidden.code)
        assertEquals(0L, hidden.secondsLeft)
        assertSame(balance, hidden.account)
        model.refresh()
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.SECONDS)
        assertEquals(hidden, model.state.value)
        assertEquals(1, payments)
        model.onBackground(); model.onForeground()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, payments)
        model.setPageVisible(true)
        await { model.state.value.code != null }
        assertEquals(2, payments)
        assertNotEquals(first, model.state.value.code)
    }
    @Test fun leavingDuringRefreshDiscardsTheLateCodeAndClearsBusyState() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        paymentGate = CompletableDeferred()
        model.onForeground()
        await { payments == 1 }
        model.setPageVisible(false)
        assertFalse(model.state.value.busy)
        paymentGate.complete(Unit)
        await { responses == 1 }
        assertNull(model.state.value.code)
        assertFalse(model.state.value.busy)
        model.setPageVisible(true)
        await { model.state.value.code != null }
        assertEquals(2, payments)
    }
    @Test fun explicitRefreshOnMyPageUpdatesAccountWithoutStartingCodeTicker() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.setPageVisible(false)
        model.onForeground()
        model.refresh(manual = true)
        await { payments == 1 && !model.state.value.busy }
        assertNotNull(model.state.value.account)
        assertNull(model.state.value.code)
        shadowOf(Looper.getMainLooper()).idleFor(120, TimeUnit.SECONDS)
        assertEquals(1, payments)
    }
    @Test fun rechargeOverlayKeepsCodeCountdownRunningWithoutAnotherEarlyRequest() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        model.setPageVisible(false, preserveDisplay = true)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertNotNull(model.state.value.code)
        assertEquals(55L, model.state.value.secondsLeft)
        assertEquals(1, payments)
        model.setPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(55L, model.state.value.secondsLeft)
        assertEquals(1, payments)
    }
    @Test fun rechargeOverlayRefreshesTheCodeAtExpiryInsteadOfShowingAnUnavailableBackdrop() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        val original = model.state.value.code
        model.setPageVisible(false, preserveDisplay = true)
        shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS)
        await { payments == 2 && model.state.value.code != null }
        assertNotEquals(original, model.state.value.code)
        assertNull(model.state.value.error)
        model.setPageVisible(true)
        assertEquals(2, payments)
    }
    @Test fun paymentResultDuringOverlayForcesFreshAccountAndCodesOnReturn() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        val original = model.state.value.code
        model.setPageVisible(false, preserveDisplay = true)
        account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 7496)
        model.refresh()
        shadowOf(Looper.getMainLooper()).idle()
        await { payments == 2 && !model.state.value.busy }
        assertNotEquals(original, model.state.value.code)
        assertEquals(7496L, model.state.value.account!!.balanceCents)
        model.setPageVisible(true)
    }
    @Test fun switchingTabsWhileAnOverlayIsOpenDiscardsItsRetainedCode() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        model.setPageVisible(false, preserveDisplay = true)
        model.setPageVisible(false)
        assertNull(model.state.value.code)
        shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS)
        assertEquals(1, payments)
        assertNull(model.state.value.error)
    }
    @Test fun goingToBackgroundWhileAnOverlayIsOpenClearsCodeAndDoesNotRefreshUnderTheOverlay() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        model.setPageVisible(false, preserveDisplay = true)
        model.onBackground()
        assertNull(model.state.value.code)
        model.onForeground()
        shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS)
        assertEquals(1, payments)
        model.setPageVisible(true)
        await { payments == 2 && model.state.value.code != null }
    }
    @Test fun coveringAnInFlightRefreshAllowsItsFreshCodeToReachTheBackdrop() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        paymentGate = CompletableDeferred()
        model.onForeground()
        await { payments == 1 }
        val loading = model.state.value
        model.setPageVisible(false, preserveDisplay = true)
        assertEquals(loading, model.state.value)
        paymentGate.complete(Unit)
        await { responses == 1 && model.state.value.code != null }
        assertFalse(model.state.value.busy)
        model.setPageVisible(true)
        assertEquals(1, payments)
    }

    @Test fun acceptedLoginDoesNotBecomeFailureJustBecauseBarcodeWasRefused() {
        vault.clear()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        model.login(" 123 ", "fake-password")
        await { !model.state.value.busy && model.state.value.account != null }
        assertFalse(model.state.value.needsLogin)
        assertEquals("123", model.state.value.username)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        assertEquals(1, logins)
        assertNotNull(vault.credentials())
    }

    @Test fun newAccountBalanceIsPreservedWhileOldBarcodeIsCleared() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        codeAvailable = true
        model.onForeground()
        await { model.state.value.code != null }
        codeAvailable = false
        account = PaymentAccount("fake-account", "fake-payacc", "1", "统一账户", 1234)
        model.refresh()
        await { !model.state.value.busy }
        assertEquals(1234L, model.state.value.account!!.balanceCents)
        assertNull(model.state.value.code)
        assertEquals("持卡人已挂失！", model.state.value.error)
        assertEquals(2, payments)
    }

    @Test fun confirmedLossKeepsServerIssuedCodeAndUnlostChangeFetchesANewBatch() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        codeAvailable = true
        model.onForeground()
        await { model.state.value.code != null }
        val first = model.state.value.code
        cardLost = true
        model.cardStateChanged()
        assertNull(model.state.value.code)
        await { !model.state.value.busy && !model.state.value.cardStatusLoading }
        assertTrue(model.state.value.accountCard!!.lost)
        assertNull(model.state.value.problem)
        assertNotNull(model.state.value.code)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        cardLost = false
        model.cardStateChanged()
        await { model.state.value.code != null && model.state.value.accountCard?.lost == false }
        assertNull(model.state.value.problem)
        assertNotEquals(first, model.state.value.code)
        assertEquals(3, payments)
    }

    @Test fun codeAndCardRequestsOverlapAndSlowStatusDoesNotDelayDisplayingCode() {
        codeAvailable = true
        paymentGate = CompletableDeferred()
        cardGate = CompletableDeferred()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        await { payments == 1 && cardQueries == 1 }
        assertTrue(model.state.value.busy)
        assertTrue(model.state.value.cardStatusLoading)
        paymentGate.complete(Unit)
        await { model.state.value.code != null }
        assertFalse(model.state.value.busy)
        assertTrue(model.state.value.cardStatusLoading)
        assertEquals(0, cardResponses)
        val code = model.state.value.code
        cardGate.complete(Unit)
        await { !model.state.value.cardStatusLoading }
        assertEquals(code, model.state.value.code)
    }

    @Test fun lossReturnedBeforeTheCodeDoesNotBlockOrDisappearWhenTheCodeArrives() {
        codeAvailable = true
        cardLost = true
        paymentGate = CompletableDeferred()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.accountCard?.lost == true }
        assertTrue(model.state.value.busy)
        paymentGate.complete(Unit)
        await { model.state.value.code != null }
        assertTrue(model.state.value.accountCard!!.lost)
        assertNull(model.state.value.problem)
    }

    @Test fun failedParallelStatusReadKeepsTheCodeAndNeverReportsLoss() {
        codeAvailable = true
        cardFailure = NetworkFailure()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null && model.state.value.cardStatusError != null }
        assertNull(model.state.value.problem)
        assertNull(model.state.value.error)
        assertNull(model.state.value.accountCard)
        assertEquals(ReadFailureKind.Network, model.state.value.cardStatusErrorKind)
    }

    @Test fun lateLossFromAPreviousRefreshCannotReplaceTheCurrentNormalCard() {
        codeAvailable = true
        cardLost = true
        cardGate = CompletableDeferred()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null && cardQueries == 1 }
        val oldGate = cardGate
        cardLost = false
        cardGate = CompletableDeferred<Unit>().apply { complete(Unit) }
        model.refresh(manual = true)
        await { payments == 2 && model.state.value.accountCard?.lost == false && !model.state.value.busy }
        val code = model.state.value.code
        oldGate.complete(Unit)
        await { cardResponses == 2 }
        assertFalse(model.state.value.accountCard!!.lost)
        assertEquals(code, model.state.value.code)
    }

    @Test fun frozenCardReturnedBeforeTheCodeStillPreventsDisplayingIt() {
        codeAvailable = true
        cardFrozen = true
        paymentGate = CompletableDeferred()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.problem == PaymentCodeProblem.Frozen }
        paymentGate.complete(Unit)
        await { !model.state.value.busy }
        assertNull(model.state.value.code)
        assertEquals(PaymentCodeProblem.Frozen, model.state.value.problem)
        cardFrozen = false
        model.refresh(manual = true)
        await { model.state.value.code != null && model.state.value.accountCard?.frozen == false }
        assertNull(model.state.value.problem)
    }

    @Test fun cardStatusReturnedAfterBackgroundCannotRestoreCodeOrWarning() {
        codeAvailable = true
        cardLost = true
        cardGate = CompletableDeferred()
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null && cardQueries == 1 }
        model.onBackground()
        cardGate.complete(Unit)
        await { cardResponses == 1 }
        assertNull(model.state.value.code)
        assertNull(model.state.value.accountCard)
        assertFalse(model.state.value.cardStatusLoading)
    }

    @Test fun latePreLossRefreshCannotRestoreItsOldCode() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        codeAvailable = true
        paymentGate = CompletableDeferred()
        model.onForeground()
        await { payments == 1 }
        codeAvailable = false
        problem = PaymentCodeProblem.Lost
        model.cardStateChanged()
        assertNull(model.state.value.code)
        paymentGate.complete(Unit)
        await { responses == 2 && !model.state.value.busy }
        assertEquals(PaymentCodeProblem.Lost, model.state.value.problem)
        assertNull(model.state.value.code)
    }

    @Test fun failedCardVerificationIsNotPresentedAsALostCard() {
        problem = PaymentCodeProblem.StatusUnverified
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { !model.state.value.busy }
        assertEquals(PaymentCodeProblem.StatusUnverified, model.state.value.problem)
        assertNull(model.state.value.code)
        assertNotNull(model.state.value.account)
    }

    @Test fun accountPageConfirmsCardWithoutEverLoadingThePaymentPage() {
        model.setPageVisible(false)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground(); model.setAccountPageVisible(true)
        await { model.state.value.accountCard != null && !model.state.value.cardStatusLoading }
        assertEquals("正常", com.mercury.yktwx.ui.pages.cardStatus(model.state.value.accountCard, account.account, false))
        assertEquals(PaymentCodeProblem.StatusUnverified, model.state.value.problem)
        assertNull(model.state.value.code)
        assertEquals(0, payments)
        assertEquals(1, accountQueries)
        assertEquals(1, cardQueries)
    }

    @Test fun manualAccountRefreshUpdatesLostCardIndependentlyOfThePreviousPaymentError() {
        problem = PaymentCodeProblem.Lost
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground(); await { !model.state.value.busy }
        cardLost = true
        model.setPageVisible(false); model.setAccountPageVisible(true)
        await { model.state.value.accountCard != null && !model.state.value.cardStatusLoading }
        assertEquals("已挂失", com.mercury.yktwx.ui.pages.cardStatus(model.state.value.accountCard, account.account, false))
        cardLost = false
        model.refreshAccount(manual = true)
        await { !model.state.value.cardStatusLoading && model.state.value.accountCard?.lost == false }
        assertEquals("正常", com.mercury.yktwx.ui.pages.cardStatus(model.state.value.accountCard, account.account, false))
        assertEquals(PaymentCodeProblem.Lost, model.state.value.problem)
        assertNull(model.state.value.code)
        assertEquals(1, payments)
    }

    @Test fun delayedCardReadDoesNotHideTheBalanceAndAccountReentrySharesIt() {
        model.setPageVisible(false)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        cardGate = CompletableDeferred()
        model.setAccountPageVisible(true)
        await { cardQueries == 1 && model.state.value.account != null }
        assertFalse(model.state.value.accountLoading)
        assertTrue(model.state.value.cardStatusLoading)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        model.refreshAccount(); model.refreshAccount(manual = true)
        assertEquals(1, cardQueries)
        assertEquals(1, accountQueries)
        cardGate.complete(Unit)
        await { model.state.value.accountCard != null && !model.state.value.cardStatusLoading }
        assertEquals(0, payments)
    }

    @Test fun failedCardReadPreservesBalanceAndIsRetriedWithoutRequeryingFreshBalance() {
        model.setPageVisible(false)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        cardFailure = NetworkFailure()
        model.setAccountPageVisible(true)
        await { model.state.value.cardStatusError != null && !model.state.value.cardStatusLoading }
        assertNotNull(model.state.value.account)
        assertNull(model.state.value.accountError)
        assertNull(model.state.value.accountCard)
        model.setAccountPageVisible(false)
        cardFailure = null; cardFrozen = true
        model.setAccountPageVisible(true)
        await { model.state.value.accountCard != null && !model.state.value.cardStatusLoading }
        assertEquals("已冻结", com.mercury.yktwx.ui.pages.cardStatus(model.state.value.accountCard, account.account, false))
        assertNull(model.state.value.cardStatusError)
        assertEquals(1, accountQueries)
        assertEquals(2, cardQueries)
        assertEquals(0, payments)
    }

    @Test fun mismatchedCardResponseCannotMarkTheSelectedAccountNormal() {
        model.setPageVisible(false)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        cardAccount = "another-account"
        model.setAccountPageVisible(true)
        await { model.state.value.cardStatusError != null && !model.state.value.cardStatusLoading }
        assertNull(model.state.value.accountCard)
        assertEquals("状态待确认", com.mercury.yktwx.ui.pages.cardStatus(model.state.value.accountCard, account.account, false))
        assertNotNull(model.state.value.account)
        assertEquals(0, payments)
    }

    @Test fun lateCardResponseCannotRestoreStateAfterLogoutAndAnotherLogin() {
        model.setPageVisible(false)
        model.initialize(); await { !model.state.value.restoringLogin }
        model.onForeground()
        cardGate = CompletableDeferred()
        model.setAccountPageVisible(true)
        await { cardQueries == 1 && model.state.value.account != null }
        model.logout(); await { model.state.value.needsLogin && !model.state.value.busy }
        account = account.with(account = "new-account", balanceCents = 9876)
        codeAvailable = true
        model.login("456", "new-password")
        await { !model.state.value.needsLogin && !model.state.value.busy }
        cardGate.complete(Unit)
        await { cardResponses == 1 }
        assertEquals("456", model.state.value.username)
        assertEquals("new-account", model.state.value.account!!.account)
        assertNull(model.state.value.accountCard)
        assertFalse(model.state.value.cardStatusLoading)
        assertNull(model.state.value.code)
    }

    private fun PaymentAccount.with(account: String = this.account, balanceCents: Long = this.balanceCents) =
        PaymentAccount(account, payacc, paytype, name, balanceCents)

    private fun readyForTurn() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        model.setPaymentMotionActive(true)
        model.paymentTurnStarted()
    }
    @Test fun decreasedBalanceShowsDifferenceAndRefreshesCodeAndBalance() {
        readyForTurn()
        val first = model.state.value.code
        account = account.with(balanceCents = 5996)
        model.paymentTurnReturned()
        await { model.paymentSuccess.value != null && !model.state.value.busy }
        assertEquals(500L, model.paymentSuccess.value)
        assertEquals(5996L, model.state.value.account!!.balanceCents)
        assertEquals(1, accountQueries)
        assertEquals(2, payments)
        assertNotEquals(first, model.state.value.code)
        val versions = repository.dataChanges.state.value.versions
        assertTrue(AccountResource.Data.ExpenseBills in versions)
        assertFalse(AccountResource.Data.Balance in versions)
        assertFalse(versions.keys.any { it is AccountResource.Hall })
        model.dismissPaymentSuccess()
        model.paymentTurnStarted(); model.paymentTurnReturned()
        await { accountQueries == 2 }
        assertNull(model.paymentSuccess.value)
        assertEquals(versions, repository.dataChanges.state.value.versions)
    }
    @Test fun unchangedOrIncreasedBalanceAndErrorsKeepThePageSilent() {
        readyForTurn()
        val before = model.state.value
        account = account.with(balanceCents = 6996)
        model.paymentTurnReturned()
        await { accountQueries == 1 }
        assertNull(model.paymentSuccess.value)
        assertNull(model.state.value.error)
        assertEquals(before.code, model.state.value.code)
        assertEquals(1, payments)
        model.cancelPaymentDetection()
        model.paymentTurnStarted()
        accountFailure = NetworkFailure()
        model.paymentTurnReturned()
        await { accountQueries == 2 }
        assertNull(model.paymentSuccess.value)
        assertNull(model.state.value.error)
    }
    @Test fun periodicRefreshCannotOverwriteAnInProgressTurnBaseline() {
        readyForTurn()
        account = account.with(balanceCents = 6000)
        model.refresh()
        await { !model.state.value.busy && payments == 2 }
        model.paymentTurnReturned()
        await { model.paymentSuccess.value != null && !model.state.value.busy }
        assertEquals(496L, model.paymentSuccess.value)
    }
    @Test fun lateBalanceResponseAfterBackgroundIsIgnored() {
        readyForTurn()
        account = account.with(balanceCents = 5000)
        accountGate = CompletableDeferred()
        model.paymentTurnReturned()
        await { accountQueries == 1 }
        model.onBackground()
        accountGate.complete(Unit)
        await { accountResponses == 1 }
        assertNull(model.paymentSuccess.value)
        assertNull(model.state.value.code)
        assertEquals(1, payments)
    }
    @Test fun differentAccountIsNeverCompared() {
        readyForTurn()
        account = account.with(account = "other", balanceCents = 1)
        model.paymentTurnReturned()
        await { accountResponses == 1 }
        assertNull(model.paymentSuccess.value)
        assertEquals(1, payments)
    }
    @Test fun returnedWithoutStartedAndOffscreenMotionDoNotQuery() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        model.paymentTurnReturned()
        model.paymentTurnStarted(); model.paymentTurnReturned()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, accountQueries)
    }
    @Test fun silentRetryFindsAnEventuallyUpdatedBalance() {
        readyForTurn()
        model.paymentTurnReturned()
        await { accountResponses == 1 }
        account = account.with(balanceCents = 6296)
        // The IO result may schedule its retry after a single virtual-time jump.
        await(advanceTime = true) { model.paymentSuccess.value != null }
        assertEquals(200L, model.paymentSuccess.value)
    }

    private fun finishSilentRetries() {
        // IO completion can post the next delay after the current virtual-time jump.
        // Advance in small steps until each response has returned to the main looper.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (accountResponses < 3 && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            Thread.sleep(5)
        }
        assertEquals("Silent retries must reach their bounded third attempt", 3, accountResponses)
        shadowOf(Looper.getMainLooper()).idleFor(6, TimeUnit.SECONDS)
    }
    @Test fun unchangedBalanceExhaustsThreeQueriesWithoutRefreshingOrShowingAnything() {
        readyForTurn()
        val before = model.state.value
        model.paymentTurnReturned()
        finishSilentRetries()
        assertEquals(3, accountQueries)
        assertEquals(1, payments)
        assertNull(model.paymentSuccess.value)
        assertNull(model.state.value.error)
        assertEquals(before.code, model.state.value.code)
        assertEquals(before.account!!.balanceCents, model.state.value.account!!.balanceCents)
    }
    @Test fun allFailedQueriesAlsoRemainSilentAndStop() {
        readyForTurn()
        accountFailure = NetworkFailure()
        model.paymentTurnReturned()
        finishSilentRetries()
        assertEquals(3, accountQueries)
        assertEquals(1, payments)
        assertNull(model.paymentSuccess.value)
        assertNull(model.state.value.error)
        assertFalse(model.state.value.busy)
    }
    @Test fun repeatedReturnEventsCannotRunConcurrentQueriesOrShowTheSameDebitTwice() {
        readyForTurn()
        account = account.with(balanceCents = 6000)
        accountGate = CompletableDeferred()
        model.paymentTurnReturned()
        await { accountQueries == 1 }
        model.paymentTurnReturned(); model.paymentTurnReturned()
        assertEquals(1, accountQueries)
        accountGate.complete(Unit)
        await { model.paymentSuccess.value != null && !model.state.value.busy }
        model.paymentTurnReturned()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, accountQueries)
        assertEquals(496L, model.paymentSuccess.value)
    }
    @Test fun switchingAwayCancelsLateDebitResponse() {
        readyForTurn()
        account = account.with(balanceCents = 6000)
        accountGate = CompletableDeferred()
        model.paymentTurnReturned()
        await { accountQueries == 1 }
        model.setPaymentMotionActive(false)
        accountGate.complete(Unit)
        await { accountResponses == 1 }
        assertNull(model.paymentSuccess.value)
        assertEquals(1, payments)
    }
    @Test fun confirmedDebitSurvivesAnUnavailableNewBarcode() {
        readyForTurn()
        account = account.with(balanceCents = 6000)
        codeAvailable = false
        model.paymentTurnReturned()
        await { model.paymentSuccess.value != null && !model.state.value.busy }
        assertEquals(496L, model.paymentSuccess.value)
        assertEquals(6000L, model.state.value.account!!.balanceCents)
        assertNull(model.state.value.code)
    }

    private fun await(advanceTime: Boolean = false, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (advanceTime) shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            else shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for payment state")
    }
    private class MemoryVault : Vault {
        private var value: Credentials? = Credentials("123", "fake-password")
        private var token: Session? = Session("fake-token", Long.MAX_VALUE)
        override fun credentials() = value
        override fun session() = token
        override fun saveCredentials(value: Credentials) { this.value = value }
        override fun saveSession(value: Session) { token = value }
        override fun clearSession() { token = null }
        override fun clear() { value = null; token = null }
    }

    @Test fun credentialRejectionClearsOnRelatedEditWithoutAuthenticatingAgain() {
        loginFailure = InvalidCredentials()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.login("456", "wrong")
        await { !model.state.value.busy && model.state.value.error != null }
        model.loginInputEdited(FormField.Amount)
        assertNotNull(model.state.value.error)
        model.loginInputEdited(FormField.Password)
        assertNull(model.state.value.error)
        assertTrue(model.state.value.errorFields.isEmpty())
        assertTrue(model.state.value.needsLogin)
        assertEquals(1, logins)
        assertEquals(0, payments)
    }
    @Test fun captchaRequirementSurvivesUsernameAndPasswordEdits() {
        loginFailure = CaptchaRequired()
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.login("456", "wrong")
        await { !model.state.value.busy && model.state.value.error != null }
        model.loginInputEdited(FormField.Username)
        model.loginInputEdited(FormField.Password)
        assertEquals(loginFailure!!.message, model.state.value.error)
        assertEquals(1, logins)
    }

}
