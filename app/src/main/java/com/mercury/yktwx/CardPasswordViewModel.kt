package com.mercury.yktwx

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// Passwords live only in the dialog and the active submission, never UI/saved state.
data class CardPasswordUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val submitting: Boolean = false,
    val cards: List<CampusCard> = emptyList(),
    val selectedCard: CampusCard? = null,
    val error: String? = null,
    val preview: Boolean = false,
    val errorFields: Set<FormField> = emptySet(),
) {
    override fun toString() = "CardPasswordUiState([redacted])"
}

@HiltViewModel
class CardPasswordViewModel @Inject constructor(private val repository: AccountRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CardPasswordUiState())
    val state = mutable.asStateFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private var task: Job? = null
    private var owner = ""
    private var attempt = 0L

    init {
        repository.observeAccount(viewModelScope) { username, renewed ->
            if (!mutable.value.preview && owner.isNotBlank() && (renewed || owner != username)) reset()
        }
        viewModelScope.launch { repository.cardReads.revision.collect { syncCards() } }
    }

    private fun syncCards() {
        val current = mutable.value
        if (current.preview || current.loading || owner.isBlank()) return
        val cards = current.cards.map { old ->
            repository.cardSnapshot(owner, old.account).let { if (it.fresh()) it.value ?: old else old }
        }
        val selected = current.selectedCard?.let { old -> cards.singleOrNull { it.account == old.account && !it.frozen && it.status == 0 } }
        mutable.value = current.copy(cards = cards, selectedCard = selected)
    }

    fun open(username: String, preview: Boolean) {
        if (username.isBlank() || task?.isActive == true) return
        reset()
        owner = username
        val current = attempt
        mutable.value = CardPasswordUiState(loading = true, preview = UiPreview.enabled && preview)
        task = viewModelScope.launch {
            try {
                val config = if (UiPreview.enabled && preview) PreviewData.passwordConfiguration()
                    else repository.passwordConfiguration(username)
                if (current != attempt) return@launch
                mutable.value = mutable.value.copy(visible = true, loading = false, cards = config.cards,
                    selectedCard = config.cards.singleOrNull())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == attempt) {
                    mutable.value = CardPasswordUiState()
                    notifications.send(safeError(e))
                }
            }
        }
    }
    fun selectCard(card: CampusCard) {
        val s = mutable.value
        if (!s.visible || s.loading || card !in s.cards || card.frozen || card.status != 0) return
        mutable.value = s.copy(selectedCard = card, error = null, errorFields = emptySet())
    }
    fun inputEdited(field: FormField) {
        val s = mutable.value
        if (s.visible && !s.loading && field in s.errorFields)
            mutable.value = s.copy(error = null, errorFields = emptySet())
    }
    fun submit(oldPassword: String, newPassword: String, confirmation: String) {
        val s = mutable.value
        val card = s.selectedCard ?: return
        if (!s.visible || s.preview || s.loading || task?.isActive == true) return
        if (listOf(oldPassword, newPassword, confirmation).any { !it.matches(Regex("[0-9]{6}")) }) {
            mutable.value = s.copy(error = "请输入 6 位数字查询密码", errorFields = setOf(FormField.OldPassword, FormField.NewPassword, FormField.Confirmation)); return
        }
        if (newPassword != confirmation) { mutable.value = s.copy(error = "两次输入的新密码不一致", errorFields = setOf(FormField.NewPassword, FormField.Confirmation)); return }
        val current = attempt
        val username = owner
        mutable.value = s.copy(loading = true, submitting = true, error = null, errorFields = emptySet())
        task = viewModelScope.launch {
            try {
                repository.modifyCardPassword(username, card, oldPassword, newPassword, confirmation)
                if (current == attempt) {
                    mutable.value = CardPasswordUiState()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current == attempt) mutable.value = mutable.value.copy(loading = false, submitting = false, error = safeError(e), errorFields = inputErrorFields(e))
            }
        }
    }
    // Do not dismiss an in-flight write and discard its confirmed success.
    fun dismiss() { if (!mutable.value.submitting) reset() }
    fun accountChanged(username: String) { if (owner.isNotEmpty() && owner != username) reset() }
    private fun reset() {
        attempt++
        task?.cancel(); task = null; owner = ""
        mutable.value = CardPasswordUiState()
        while (notifications.tryReceive().isSuccess) { }
    }
    private fun safeError(e: Exception) = if (e is YktException) e.message ?: "修改查询密码失败"
        else "修改查询密码失败"
}
