package com.mercury.yktwx

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PaymentUiState(
    val needsLogin: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val username: String = "",
    val batch: PaymentBatch? = null,
    val code: String? = null,
    val secondsLeft: Long = 0,
    val preview: Boolean = false,
    val refreshing: Boolean = false,
    val restoringLogin: Boolean = false,
    // Account presentation outlives a code batch; never keep old payment codes
    // merely to preserve the card while a refresh is in flight.
    val account: PaymentAccount? = batch?.account,
    val problem: PaymentCodeProblem? = null,
) {
    override fun toString() = "PaymentUiState([redacted])"
}

class PaymentViewModel internal constructor(app: Application, private val repository: AccountRepository) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(PaymentUiState(restoringLogin = true))
    val state = mutable.asStateFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private var initialized = false
    private var foreground = false
    private var ticker: Job? = null
    private var request: Job? = null
    private var logoutJob: Job? = null
    private var preview = false
    private var requestVersion = 0L

    fun initialize(uiPreview: Boolean = false, previewLost: Boolean = false) {
        if (initialized) return
        initialized = true
        preview = BuildConfig.DEBUG && uiPreview
        if (preview) {
            val account = PaymentAccount("demo", "000", "1", "统一账户", 0)
            if (previewLost) {
                mutable.value = PaymentUiState(needsLogin = false, username = "界面预览", account = account,
                    preview = true, problem = PaymentCodeProblem.Lost)
            } else {
                val batch = PaymentBatch(List(10) { "00000000000000000000" }, 60, System.currentTimeMillis(), account)
                mutable.value = PaymentUiState(needsLogin = false, username = "界面预览", batch = batch, code = batch.codes[0], secondsLeft = 60, preview = true)
            }
            return
        }
        viewModelScope.launch {
            try {
                val username = withContext(Dispatchers.IO) { repository.username() }
                val exists = username.isNotBlank()
                mutable.value = PaymentUiState(needsLogin = !exists, username = username)
                if (exists && foreground) refresh()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = PaymentUiState(error = "无法读取本机登录信息，请重新登录")
            }
        }
    }
    fun onForeground() {
        foreground = true
        if (initialized && !preview && !mutable.value.restoringLogin && !mutable.value.needsLogin && !mutable.value.busy) refresh()
    }
    fun onBackground() {
        requestVersion++
        foreground = false; ticker?.cancel(); request?.cancel()
        val old = mutable.value
        mutable.value = PaymentUiState(
            old.needsLogin, logoutJob?.isActive == true, old.error, old.username,
            preview = preview, restoringLogin = old.restoringLogin,
            account = old.account.takeUnless { old.needsLogin },
            problem = old.problem.takeUnless { old.needsLogin },
        )
    }
    fun login(username: String, password: String) {
        if (mutable.value.busy || mutable.value.restoringLogin || logoutJob?.isActive == true) return
        val old = mutable.value
        val version = ++requestVersion
        mutable.value = PaymentUiState(true, true, username = username)
        request = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { repository.login(username, password) }
                if (version != requestVersion) return@launch
                notifications.send("登录成功")
                show(batch, username.trim(), version)
            } catch (e: CancellationException) { throw e
            } catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                // Credentials were accepted; an unavailable barcode must not hide the account.
                notifications.send("登录成功")
                mutable.value = PaymentUiState(needsLogin = false, username = username.trim(), account = e.account, error = e.message, problem = e.problem)
            } catch (e: YktException) {
                val stored = withContext(Dispatchers.IO) { repository.hasCredentials() }
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = !stored, username = username,
                    error = e.message ?: "登录失败，请重试")
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = true, username = old.username,
                    error = "登录失败，请稍后重试")
            }
        }
    }
    fun refresh(manual: Boolean = false) {
        if (preview || mutable.value.restoringLogin || mutable.value.busy || mutable.value.needsLogin) return
        ticker?.cancel()
        val version = ++requestVersion
        val username = mutable.value.username
        val account = mutable.value.account
        mutable.value = PaymentUiState(needsLogin = false, busy = true, username = username, refreshing = manual, account = account)
        request = viewModelScope.launch {
            try { show(withContext(Dispatchers.IO) { repository.refresh() }, username, version) }
            catch (e: CancellationException) { throw e }
            catch (e: PaymentCodeUnavailable) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = false, username = username, account = e.account, error = e.message, problem = e.problem)
            }
            catch (e: YktException) {
                if (version != requestVersion) return@launch
                val requireLogin = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                mutable.value = PaymentUiState(
                    needsLogin = requireLogin,
                    error = e.message ?: if (requireLogin) "登录已失效，请重新登录" else "服务异常，请稍后刷新",
                    username = username, account = account.takeUnless { requireLogin },
                )
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                mutable.value = PaymentUiState(needsLogin = false, error = "服务数据异常，请稍后刷新", username = username, account = account)
            }
        }
    }
    /** Card-state changes invalidate both the displayed code and any older in-flight refresh. */
    fun cardStateChanged() {
        if (preview || mutable.value.needsLogin || mutable.value.restoringLogin) return
        requestVersion++
        request?.cancel()
        ticker?.cancel()
        val state = mutable.value
        mutable.value = PaymentUiState(needsLogin = false, username = state.username, account = state.account)
        refresh()
    }
    private fun show(batch: PaymentBatch, username: String, version: Long) {
        if (version != requestVersion) return
        if (!foreground) {
            mutable.value = PaymentUiState(needsLogin = false, username = username, account = batch.account)
            return
        }
        val interval = batch.displayIntervalSeconds
        mutable.value = PaymentUiState(false, username = username, batch = batch, code = batch.codes[0], secondsLeft = interval)
        ticker?.cancel()
        ticker = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (foreground && version == requestVersion) {
                val elapsed = (SystemClock.elapsedRealtime() - started) / 1000
                if (elapsed >= interval) {
                    refresh(); return@launch
                }
                mutable.value = PaymentUiState(false, username = username, batch = batch, code = batch.codes[0], secondsLeft = interval - elapsed)
                delay(1000)
            }
        }
    }
    fun cardPasswordChanged(username: String) {
        if (preview || mutable.value.username != username) return
        notifications.trySend("查询密码修改成功，请重新登录")
        logout()
    }
    fun logout() {
        if (logoutJob?.isActive == true) return
        requestVersion++
        ticker?.cancel(); request?.cancel()
        // Revoke access and clear payment codes immediately; only an inert shell
        // may remain briefly for the page exit animation. Login waits for storage.
        mutable.value = PaymentUiState(busy = true)
        logoutJob = viewModelScope.launch {
            try { withContext(Dispatchers.IO) { repository.logout() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notifications.send("清除登录信息失败，请稍后重试") }
            finally { mutable.value = PaymentUiState() }
        }
    }
}
