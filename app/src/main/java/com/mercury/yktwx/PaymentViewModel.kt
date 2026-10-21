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
) {
    override fun toString() = "PaymentUiState([redacted])"
}

class PaymentViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = AccountRepository.shared(app)
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

    fun initialize(uiPreview: Boolean = false) {
        if (initialized) return
        initialized = true
        preview = BuildConfig.DEBUG && uiPreview
        if (preview) {
            val account = PaymentAccount("demo", "000", "1", "统一账户", 0)
            val batch = PaymentBatch(List(10) { "00000000000000000000" }, 60, System.currentTimeMillis(), account)
            mutable.value = PaymentUiState(needsLogin = false, username = "界面预览", batch = batch, code = batch.codes[0], secondsLeft = 60, preview = true)
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
                mutable.value = PaymentUiState()
                notifications.send("无法读取本机登录信息，请重新登录")
            }
        }
    }
    fun onForeground() {
        foreground = true
        if (initialized && !preview && !mutable.value.restoringLogin && !mutable.value.needsLogin && !mutable.value.busy) refresh()
    }
    fun onBackground() {
        foreground = false; ticker?.cancel(); request?.cancel()
        val old = mutable.value
        mutable.value = PaymentUiState(old.needsLogin, logoutJob?.isActive == true, old.error, old.username, preview = preview, restoringLogin = old.restoringLogin)
    }
    fun login(username: String, password: String) {
        if (mutable.value.busy || mutable.value.restoringLogin || logoutJob?.isActive == true) return
        val old = mutable.value
        mutable.value = PaymentUiState(true, true, username = username)
        request = viewModelScope.launch {
            try {
                val batch = withContext(Dispatchers.IO) { repository.login(username, password) }
                notifications.send("登录成功")
                show(batch, username.trim())
            } catch (e: CancellationException) { throw e
            } catch (e: YktException) {
                val stored = withContext(Dispatchers.IO) { repository.hasCredentials() }
                notifications.send(e.message ?: "登录失败，请重试")
                mutable.value = PaymentUiState(needsLogin = !stored, username = username)
            } catch (_: Exception) {
                notifications.send("登录失败，请稍后重试")
                mutable.value = PaymentUiState(needsLogin = true, username = old.username)
            }
        }
    }
    fun refresh(manual: Boolean = false) {
        if (preview || mutable.value.restoringLogin || mutable.value.busy || mutable.value.needsLogin) return
        ticker?.cancel()
        val username = mutable.value.username
        mutable.value = PaymentUiState(needsLogin = false, busy = true, username = username, refreshing = manual)
        request = viewModelScope.launch {
            try { show(withContext(Dispatchers.IO) { repository.refresh() }, username) }
            catch (e: CancellationException) { throw e }
            catch (e: YktException) {
                val requireLogin = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                if (requireLogin) notifications.send(e.message ?: "登录已失效，请重新登录")
                mutable.value = PaymentUiState(needsLogin = requireLogin, error = if (requireLogin) null else e.message, username = username)
            } catch (_: Exception) {
                mutable.value = PaymentUiState(needsLogin = false, error = "服务数据异常，请稍后刷新", username = username)
            }
        }
    }
    private fun show(batch: PaymentBatch, username: String) {
        if (!foreground) { mutable.value = PaymentUiState(needsLogin = false, username = username); return }
        val interval = batch.displayIntervalSeconds
        mutable.value = PaymentUiState(false, username = username, batch = batch, code = batch.codes[0], secondsLeft = interval)
        ticker?.cancel()
        ticker = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (foreground) {
                val elapsed = (SystemClock.elapsedRealtime() - started) / 1000
                if (elapsed >= interval) {
                    refresh(); return@launch
                }
                mutable.value = PaymentUiState(false, username = username, batch = batch, code = batch.codes[0], secondsLeft = interval - elapsed)
                delay(1000)
            }
        }
    }
    fun logout() {
        if (logoutJob?.isActive == true) return
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
