package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class CardServiceUiState(
    val visible: Boolean = false,
    val action: CampusCardAction = CampusCardAction.ManageState,
    val loading: Boolean = false,
    val cards: List<CampusCard> = emptyList(),
    val selectedCard: CampusCard? = null,
    val lostNeedsPassword: Boolean = false,
    val unlostNeedsPassword: Boolean = true,
    val captcha: RecipientCaptcha? = null,
    val recipients: List<RechargeRecipient> = emptyList(),
    val selectedRecipient: RechargeRecipient? = null,
    val error: String? = null,
    val completed: Boolean = false,
    val preview: Boolean = false,
) {
    val operation get() = if (selectedCard?.lost == true) CampusCardAction.Unlost else CampusCardAction.Lost
    val needsPassword get() = if (selectedCard?.lost == true) unlostNeedsPassword else lostNeedsPassword
    override fun toString() = "CardServiceUiState([redacted])"
}
class RecipientRechargeRequest(val username: String, val config: RechargeConfiguration, val preview: Boolean, internal val attempt: Long) {
    override fun toString() = "RecipientRechargeRequest([redacted])"
}

class CardServiceViewModel internal constructor(app: Application, private val repository: AccountRepository) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(CardServiceUiState())
    val state = mutable.asStateFlow()
    private val ready = Channel<RecipientRechargeRequest>(Channel.BUFFERED)
    val rechargeRequests = ready.receiveAsFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private val changed = Channel<Unit>(Channel.BUFFERED)
    val cardChanges = changed.receiveAsFlow()
    private var task: Job? = null
    private var owner = ""
    private var attempt = 0L
    fun open(action: CampusCardAction, username: String, preview: Boolean) {
        if (task?.isActive == true) return
        dismiss()
        owner = username
        mutable.value = CardServiceUiState(visible = action == CampusCardAction.OthersRecharge, action = action, preview = preview)
        if (action == CampusCardAction.OthersRecharge) refreshCaptcha() else refreshCards()
    }
    private fun refreshCards() {
        if (owner.isEmpty() || task?.isActive == true) return
        val current = attempt
        val username = owner
        val preview = mutable.value.preview
        mutable.value = mutable.value.copy(loading = true, error = null, completed = false, selectedCard = null)
        task = viewModelScope.launch {
            try {
                val config = if (preview) CardServiceConfiguration(listOf(
                    CampusCard("demo-card", "校园卡", "202600000001", false, false, 0)), false, true)
                else repository.cardConfiguration(username)
                if (attempt != current) return@launch
                mutable.value = mutable.value.copy(visible = true, loading = false, cards = config.cards,
                    selectedCard = config.cards.singleOrNull()?.takeIf { !it.frozen && it.status == 0 },
                    lostNeedsPassword = config.lostNeedsPassword, unlostNeedsPassword = config.unlostNeedsPassword)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == current) {
                    mutable.value = mutable.value.copy(visible = false, loading = false)
                    notifications.send(safeError(e))
                }
            }
        }
    }
    fun selectCard(card: CampusCard) {
        val s = mutable.value
        if (s.loading || s.completed || card !in s.cards || card.frozen || card.status != 0) return
        mutable.value = s.copy(selectedCard = card, error = null)
    }
    fun changeCard(password: String) {
        val s = mutable.value
        val card = s.selectedCard ?: return
        if (!s.visible || s.preview || s.loading || s.completed || task?.isActive == true || s.action == CampusCardAction.OthersRecharge) return
        if (s.needsPassword && !password.matches(Regex("[0-9]{6}"))) { mutable.value = s.copy(error = "请输入 6 位查询密码"); return }
        val current = attempt
        val username = owner
        val operation = s.operation
        mutable.value = s.copy(loading = true, error = null)
        task = viewModelScope.launch {
            try {
                val cards = repository.changeCardState(username, card, operation, password)
                if (attempt != current) return@launch
                completeCardChange(cards, operation)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt != current) return@launch
                if (e is CardOperationRejected) {
                    mutable.value = mutable.value.copy(loading = false, error = null)
                    notifications.send(safeError(e))
                    return@launch
                }
                // Never replay the POST. A read can establish the outcome after an ambiguous transport error.
                val refreshed = try { repository.cardConfiguration(username).cards } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
                if (attempt != current) return@launch
                val confirmed = refreshed.singleOrNull { it.account == card.account }?.lost == (operation == CampusCardAction.Lost)
                if (confirmed) {
                    completeCardChange(refreshed, operation)
                } else {
                    mutable.value = mutable.value.copy(visible = false, loading = false, cards = refreshed.ifEmpty { s.cards }, selectedCard = null, error = null)
                    // Clear stale payment codes and require a fresh read before another write.
                    changed.send(Unit)
                    notifications.send("操作结果待确认，请重新查看卡状态")
                }
            }
        }
    }
    private suspend fun completeCardChange(cards: List<CampusCard>, operation: CampusCardAction) {
        // Do not call dismiss(): it would cancel this task and drain the result events.
        mutable.value = mutable.value.copy(visible = false, loading = false, cards = cards, selectedCard = null, completed = true, error = null)
        changed.send(Unit)
        notifications.send(operation.label + "成功")
    }
    fun clearRecipient() {
        if (mutable.value.loading) return
        mutable.value = mutable.value.copy(recipients = emptyList(), selectedRecipient = null, error = null)
    }
    fun refreshCaptcha() {
        if (!mutable.value.visible || mutable.value.action != CampusCardAction.OthersRecharge || task?.isActive == true) return
        val current = attempt
        val username = owner
        val preview = mutable.value.preview
        mutable.value = mutable.value.copy(loading = true, captcha = null, recipients = emptyList(), selectedRecipient = null, error = null)
        task = viewModelScope.launch {
            try {
                val captcha = if (preview) null else repository.recipientCaptcha(username)
                if (attempt == current) mutable.value = mutable.value.copy(loading = false, captcha = captcha)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == current) mutable.value = mutable.value.copy(loading = false, error = safeError(e)) }
        }
    }
    fun queryRecipient(type: RecipientQueryType, value: String, code: String) {
        val s = mutable.value
        if (s.loading || task?.isActive == true || s.action != CampusCardAction.OthersRecharge) return
        if (value.isBlank() || (!s.preview && code.isBlank())) { mutable.value = s.copy(error = "请输入收款人信息和验证码"); return }
        if (!s.preview && s.captcha == null) { refreshCaptcha(); return }
        val current = attempt
        val username = owner
        mutable.value = s.copy(loading = true, recipients = emptyList(), selectedRecipient = null, error = null)
        task = viewModelScope.launch {
            try {
                val recipients = if (s.preview) listOf(RechargeRecipient("demo-recipient", "demo-target", "示例同学", "202600000002", "统一账户", 0))
                    else repository.rechargeRecipients(username, type, value, s.captcha!!, code)
                if (attempt == current) mutable.value = mutable.value.copy(loading = false, recipients = recipients, captcha = null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt != current) return@launch
                val captcha = if (e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired) null
                    else try { repository.recipientCaptcha(username) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                if (attempt == current) mutable.value = mutable.value.copy(loading = false, captcha = captcha, error = safeError(e))
            }
        }
    }
    fun selectRecipient(recipient: RechargeRecipient) {
        if (!mutable.value.loading && recipient in mutable.value.recipients) mutable.value = mutable.value.copy(selectedRecipient = recipient, error = null)
    }
    fun continueRecharge() {
        val s = mutable.value
        val recipient = s.selectedRecipient ?: return
        if (s.loading || task?.isActive == true) return
        val current = attempt
        val username = owner
        mutable.value = s.copy(loading = true, error = null)
        task = viewModelScope.launch {
            try {
                val config = if (s.preview) RechargeConfiguration("demo", recipient.targetId, recipient.accountName, 0,
                    listOf(1000, 5000, 10000), 1, 50000, true, false, recipient)
                    else repository.recipientRechargeConfiguration(username, recipient)
                if (current == attempt) ready.send(RecipientRechargeRequest(username, config, s.preview, current))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == attempt) mutable.value = mutable.value.copy(loading = false, error = safeError(e)) }
        }
    }
    fun accepts(request: RecipientRechargeRequest) = request.attempt == attempt && request.username == owner &&
        (mutable.value.preview || repository.username() == owner) && mutable.value.visible
    fun accountChanged(username: String) { if (owner.isNotEmpty() && owner != username) dismiss() }
    fun dismiss() {
        attempt++
        task?.cancel(); task = null; owner = ""
        mutable.value = CardServiceUiState()
        while (ready.tryReceive().isSuccess) { }
        while (changed.tryReceive().isSuccess) { }
        while (notifications.tryReceive().isSuccess) { }
    }
    private fun safeError(e: Exception) = if (e is YktException) e.message ?: "校园卡服务暂不可用" else "校园卡服务暂不可用，请稍后重试"
}
