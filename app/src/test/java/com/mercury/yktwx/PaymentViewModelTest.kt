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
    private val service = object : YktService by YktApi({ error("Unexpected network request") }) {
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
    private val model = PaymentViewModel(ApplicationProvider.getApplicationContext<Application>(), AccountRepository(service, vault))
    private val store = ViewModelStore().apply { put("payment", model) }

    @After fun cleanup() { accountGate.complete(Unit); accountPublishGate.complete(Unit); paymentGate.complete(Unit); store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun newlyAcceptedNoticeClearsOldCredentialsAndWaitsForExplicitLogin() {
        codeAvailable = true
        model.onForeground()
        model.initialize(restoreLogin = false)
        await { !model.state.value.restoringLogin }
        assertTrue(model.state.value.needsLogin)
        assertEquals("", model.state.value.username)
        assertNull(vault.credentials())
        assertNull(vault.session())
        assertEquals(0, logins)
        assertEquals(0, payments)
        model.login("456", "new-password")
        await { !model.state.value.busy }
        assertFalse(model.state.value.needsLogin)
        assertEquals("456", model.state.value.username)
        assertEquals(1, logins)
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

    @Test fun cachedBalanceAvoidsAutomaticReadsAndManualAccountRefreshOnlyReadsBalance() {
        readyForTurn()
        model.setPageVisible(false)
        model.setAccountPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, accountQueries)
        account = account.with(balanceCents = 1234)
        model.refreshAccount(manual = true)
        await { accountResponses == 1 && !model.state.value.accountLoading }
        assertEquals(1234L, model.state.value.account!!.balanceCents)
        assertNull(model.state.value.problem)
        assertEquals(1, payments)
        assertNull(model.state.value.code)
    }

    @Test fun differentBalanceAccountDoesNotInheritThePreviousCardStatus() {
        readyForTurn()
        model.setPageVisible(false)
        model.setAccountPageVisible(true)
        account = account.with(account = "other-account", balanceCents = 1234)
        model.refreshAccount(manual = true)
        await { model.state.value.account!!.account == "other-account" && !model.state.value.accountLoading }
        assertEquals(PaymentCodeProblem.StatusUnverified, model.state.value.problem)
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
        assertEquals("123", vault.credentials()!!.username)
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
    @Test fun rechargeOverlayKeepsValidCodeStableAndClosingItResumesWithoutAnotherRequest() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        val displayed = model.state.value
        model.setPageVisible(false, preserveDisplay = true)
        shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS)
        assertEquals(displayed, model.state.value)
        assertEquals(1, payments)
        model.setPageVisible(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(displayed.code, model.state.value.code)
        assertEquals(55L, model.state.value.secondsLeft)
        assertEquals(1, payments)
    }
    @Test fun expiredOverlayCodeIsRemovedWithoutRequestingAnotherUntilTheOverlayCloses() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        model.onForeground()
        await { model.state.value.code != null }
        val original = model.state.value.code
        model.setPageVisible(false, preserveDisplay = true)
        shadowOf(Looper.getMainLooper()).idleFor(65, TimeUnit.SECONDS)
        assertNull(model.state.value.code)
        assertEquals(0L, model.state.value.secondsLeft)
        assertEquals("付款码已过期，关闭弹窗后刷新", model.state.value.error)
        assertEquals(1, payments)
        model.setPageVisible(true)
        await { model.state.value.code != null }
        assertNotEquals(original, model.state.value.code)
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
        assertEquals(original, model.state.value.code)
        assertEquals(1, payments)
        model.setPageVisible(true)
        await { payments == 2 && !model.state.value.busy }
        assertNotEquals(original, model.state.value.code)
        assertEquals(7496L, model.state.value.account!!.balanceCents)
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
    @Test fun coveringAnInFlightRefreshKeepsTheBackdropAndRejectsItsLateCode() {
        codeAvailable = true
        model.initialize()
        await { !model.state.value.restoringLogin }
        paymentGate = CompletableDeferred()
        model.onForeground()
        await { payments == 1 }
        val loading = model.state.value
        model.setPageVisible(false, preserveDisplay = true)
        assertEquals(loading.copy(balanceLoading = false), model.state.value)
        paymentGate.complete(Unit)
        await { responses == 1 }
        assertEquals(loading.copy(balanceLoading = false), model.state.value)
        model.setPageVisible(true)
        await { payments == 2 && model.state.value.code != null }
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

    @Test fun confirmedLossClearsCodeAndUnlostChangeFetchesANewBatch() {
        model.initialize()
        await { !model.state.value.restoringLogin }
        codeAvailable = true
        model.onForeground()
        await { model.state.value.code != null }
        val first = model.state.value.code
        codeAvailable = false
        problem = PaymentCodeProblem.Lost
        model.cardStateChanged()
        assertNull(model.state.value.code)
        await { !model.state.value.busy }
        assertEquals(PaymentCodeProblem.Lost, model.state.value.problem)
        assertEquals(0L, model.state.value.secondsLeft)
        assertEquals(6496L, model.state.value.account!!.balanceCents)
        codeAvailable = true
        model.cardStateChanged()
        await { model.state.value.code != null }
        assertNull(model.state.value.problem)
        assertNotEquals(first, model.state.value.code)
        assertEquals(3, payments)
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
        val event = runBlocking { withTimeoutOrNull(1_000) { model.transactionUpdates.first() } }
        assertNotNull(event)
        assertFalse(event!!.balance)
        assertFalse(event.hall)
        model.dismissPaymentSuccess()
        model.paymentTurnStarted(); model.paymentTurnReturned()
        await { accountQueries == 2 }
        assertNull(model.paymentSuccess.value)
        assertNull(runBlocking { withTimeoutOrNull(100) { model.transactionUpdates.first() } })
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
        shadowOf(Looper.getMainLooper()).idleFor(400, TimeUnit.MILLISECONDS)
        await { model.paymentSuccess.value != null }
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

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
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
}
