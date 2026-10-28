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

data class MyUiState(
    val profile: PersonalProfile? = null, val avatar: ByteArray? = null,
    val summary: BillSummary? = null, val counts: BillCounts? = null,
    val month: YearMonth = YearMonth.now(), val loading: Boolean = false,
    val profileError: String? = null, val summaryError: String? = null,
    val needsLogin: Boolean = false,
    val homeIdentity: HomeCardIdentity? = null,
    val homeIdentityError: String? = null,
    val loaded: Boolean = false,
    val profileLoaded: Boolean = loaded,
    val summaryLoaded: Boolean = loaded,
    val countsLoaded: Boolean = loaded,
    val homeIdentityLoaded: Boolean = loaded,
    val countsError: String? = null,
    val avatarError: String? = null,
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
    fun onActivate() {
        val current = mutable.value
        if (refreshedAt == 0L || System.currentTimeMillis() - refreshedAt > 60_000 || current.month != YearMonth.now() ||
            current.profileError != null || current.homeIdentityError != null || current.avatarError != null ||
            current.summaryError != null || current.countsError != null) refresh()
    }
    fun refresh() {
        if (owner.isBlank() || preview || task?.isActive == true) return
        val attempt = generation
        val username = owner
        val month = YearMonth.now()
        val current = mutable.value
        val sameMonth = current.month == month
        mutable.value = current.copy(
            month = month, loading = true, needsLogin = false,
            summary = current.summary.takeIf { sameMonth }, counts = current.counts.takeIf { sameMonth },
            profileError = null, homeIdentityError = null, avatarError = null, summaryError = null, countsError = null,
            profileLoaded = current.profile != null, homeIdentityLoaded = current.homeIdentity != null,
            summaryLoaded = sameMonth && current.summary != null, countsLoaded = sameMonth && current.counts != null)
        task = viewModelScope.launch {
            // Each branch updates the latest state on Main, so a late avatar cannot undo a summary.
            fun publish(change: (MyUiState) -> MyUiState) {
                if (attempt == generation) mutable.value = change(mutable.value)
            }
            coroutineScope {
                launch identity@{
                    val fresh = try { repository.personalProfile(username) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        publish { it.copy(profileError = message(e), profileLoaded = true, homeIdentityLoaded = true,
                            needsLogin = it.needsLogin || isAuthenticationFailure(e)) }
                        return@identity
                    }
                    if (attempt != generation) return@identity
                    publish { it.copy(profile = fresh, profileError = null, profileLoaded = true,
                        // Never associate an older student's card identity with a new profile.
                        homeIdentity = it.homeIdentity.takeIf { old -> old?.sno == fresh.sno },
                        homeIdentityLoaded = it.homeIdentityLoaded && current.profile?.sno == fresh.sno,
                        avatar = it.avatar.takeIf { current.profile?.avatarPath == fresh.avatarPath }) }
                    coroutineScope {
                        launch {
                            try {
                                val avatar = fresh.avatarPath?.let { repository.profileAvatar(it) }
                                publish { it.copy(avatar = avatar, avatarError = null) }
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { publish { it.copy(avatarError = message(e),
                                needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                        }
                        launch {
                            try {
                                val identity = repository.homeIdentity(username, fresh.sno)
                                publish { it.copy(homeIdentity = identity, homeIdentityError = null, homeIdentityLoaded = true) }
                            }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { publish { it.copy(homeIdentityError = message(e), homeIdentityLoaded = true,
                                needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                        }
                    }
                }
                launch {
                    try {
                        val summary = repository.billSummary(month.atDay(1).toString(), month.atEndOfMonth().toString(), username)
                        publish { it.copy(summary = summary, summaryError = null, summaryLoaded = true) }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { publish { it.copy(summaryError = message(e), summaryLoaded = true,
                        needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                }
                launch {
                    try {
                        val counts = repository.billCounts(month.atDay(1).toString(), month.atEndOfMonth().toString(), username)
                        publish { it.copy(counts = counts, countsError = null, countsLoaded = true) }
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { publish { it.copy(countsError = message(e), countsLoaded = true,
                        needsLogin = it.needsLogin || isAuthenticationFailure(e)) } }
                }
            }
            if (attempt != generation) return@launch
            publish { it.copy(loading = false, loaded = true) }
            refreshedAt = System.currentTimeMillis()
        }
    }
    private fun message(e: Exception) = (e as? YktException)?.message ?: "读取失败，请稍后重试"
    private fun isAuthenticationFailure(e: Exception) = e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
}
