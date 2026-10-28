package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth

class MyUiState(
    val profile: PersonalProfile? = null, val avatar: ByteArray? = null,
    val summary: BillSummary? = null, val counts: BillCounts? = null,
    val month: YearMonth = YearMonth.now(), val loading: Boolean = false,
    val profileError: String? = null, val summaryError: String? = null,
    val needsLogin: Boolean = false,
    val homeIdentity: HomeCardIdentity? = null,
    val homeIdentityError: String? = null,
    val loaded: Boolean = false,
) { override fun toString() = "MyUiState([redacted])" }

class MyViewModel internal constructor(app: Application, private val repository: AccountRepository) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(MyUiState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var preview = false
    private var generation = 0L
    private var task: Job? = null
    private var refreshedAt = 0L
    fun accountChanged(username: String, uiPreview: Boolean = false) {
        val enabledPreview = UiPreview.enabled && uiPreview
        if (owner == username && preview == enabledPreview) return
        generation++; task?.cancel(); task = null; refreshedAt = 0L
        owner = username; preview = enabledPreview
        mutable.value = MyUiState()
        if (UiPreview.enabled && preview && username.isNotBlank()) {
            mutable.value = PreviewData.myState()
            refreshedAt = System.currentTimeMillis()
        }
    }
    fun onActivate() { if (refreshedAt == 0L || System.currentTimeMillis() - refreshedAt > 60_000 || mutable.value.month != YearMonth.now()) refresh() }
    fun refresh() {
        if (owner.isBlank() || preview || task?.isActive == true) return
        val attempt = generation
        val username = owner
        val month = YearMonth.now()
        val current = mutable.value
        mutable.value = MyUiState(current.profile, current.avatar,
            current.summary.takeIf { current.month == month }, current.counts.takeIf { current.month == month }, month, true,
            profileError = current.profileError, summaryError = current.summaryError.takeIf { current.month == month },
            homeIdentity = current.homeIdentity, homeIdentityError = current.homeIdentityError, loaded = current.loaded)
        task = viewModelScope.launch {
            var profile = current.profile
            var avatar = current.avatar
            var profileError: String? = null
            var summary: BillSummary? = current.summary.takeIf { current.month == month }
            var counts: BillCounts? = current.counts.takeIf { current.month == month }
            var summaryError: String? = null
            var needsLogin = false
            var homeIdentity = current.homeIdentity
            var homeIdentityError: String? = null
            coroutineScope {
                launch identity@{
                    val fresh = try { repository.personalProfile(username) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        profileError = message(e); needsLogin = needsLogin || isAuthenticationFailure(e)
                        return@identity
                    }
                    if (attempt != generation) return@identity
                    profile = fresh
                    avatar = null
                    coroutineScope {
                        launch {
                            try { avatar = fresh.avatarPath?.let { repository.profileAvatar(it) } }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { profileError = message(e); needsLogin = needsLogin || isAuthenticationFailure(e) }
                        }
                        launch {
                            try { homeIdentity = repository.homeIdentity(username, fresh.sno) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { homeIdentityError = message(e); needsLogin = needsLogin || isAuthenticationFailure(e) }
                        }
                    }
                }
                launch {
                    try { summary = repository.billSummary(month.atDay(1).toString(), month.atEndOfMonth().toString(), username) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { summaryError = message(e); needsLogin = needsLogin || isAuthenticationFailure(e) }
                }
                launch {
                    try { counts = repository.billCounts(month.atDay(1).toString(), month.atEndOfMonth().toString(), username) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { summaryError = message(e); needsLogin = needsLogin || isAuthenticationFailure(e) }
                }
            }
            if (attempt != generation) return@launch
            mutable.value = MyUiState(profile, avatar, summary, counts, month, false, profileError, summaryError,
                needsLogin = needsLogin, homeIdentity = homeIdentity, homeIdentityError = homeIdentityError, loaded = true)
            refreshedAt = System.currentTimeMillis()
        }
    }
    private fun message(e: Exception) = (e as? YktException)?.message ?: "读取失败，请稍后重试"
    private fun isAuthenticationFailure(e: Exception) = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
}
