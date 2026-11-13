package com.mercury.yktwx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.mercury.yktwx.state.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    val rechargeConfig: RechargeConfiguration? = null,
    val error: String? = null,
    val completed: Boolean = false,
    val preview: Boolean = false,
    val errorFields: Set<FormField> = emptySet(),
    val restorationFailed: Boolean = false,
) {
    val operation get() = if (action == CampusCardAction.Unlost || selectedCard?.lost == true) CampusCardAction.Unlost else CampusCardAction.Lost
    val needsPassword get() = if (operation == CampusCardAction.Unlost) unlostNeedsPassword else lostNeedsPassword
    override fun toString() = "CardServiceUiState([redacted])"
}
class RecipientRechargeRequest(val username: String, val config: RechargeConfiguration, val amountCents: Long,
    val preview: Boolean, internal val attempt: Long) {
    override fun toString() = "RecipientRechargeRequest([redacted])"
}

@HiltViewModel
class CardServiceViewModel @Inject constructor(private val repository: AccountRepository,
    savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    private val mutable = MutableStateFlow(CardServiceUiState())
    val state = mutable.asStateFlow()
    private val formMutable = MutableStateFlow(RecipientFormDraft())
    internal val form = formMutable.asStateFlow()
    internal fun updateForm(value: RecipientFormDraft) { if (value.valid) formMutable.value = value }
    private var binding: Pair<String?, Boolean>? = null
    private var restoring: CardOverlaySavedState? = null
    private var restoreAllowed = false
    private var writing = false
    private val saved = RestorableState(savedState, "card-services", CardOverlaySavedState.serializer(), { it.valid }) {
        restoring ?: CardOverlaySavedState(state.value.visible || state.value.loading, state.value.action,
            state.value.selectedCard?.account, paymentAccount, form.value,
            state.value.recipients.map(RecipientReference::from), state.value.selectedRecipient?.targetId, writing)
    }
    private val ready = Channel<RecipientRechargeRequest>(Channel.BUFFERED)
    val rechargeRequests = ready.receiveAsFlow()
    private val notifications = Channel<String>(Channel.BUFFERED)
    val messages = notifications.receiveAsFlow()
    private var task: Job? = null
    private var owner = ""
    private var attempt = 0L
    private var paymentAccount: String? = null
    private val recipientConfigs = mutableMapOf<RechargeRecipient, RechargeConfiguration>()
    init {
        repository.observeAccount(viewModelScope) { username, renewed ->
            if (binding != null && binding?.second != true) accountChanged(username.orEmpty(), renewed = renewed)
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

    fun open(action: CampusCardAction, username: String, preview: Boolean) {
        if (task?.isActive == true) return
        accountChanged(username, preview)
        dismiss()
        owner = username
        mutable.value = CardServiceUiState(visible = action == CampusCardAction.OthersRecharge, action = action, preview = UiPreview.enabled && preview)
        if (action == CampusCardAction.OthersRecharge) refreshCaptcha() else refreshCards()
    }
    fun openUnlost(username: String, account: String, preview: Boolean) {
        if (task?.isActive == true || account.isBlank()) return
        accountChanged(username, preview)
        dismiss()
        owner = username
        paymentAccount = account
        mutable.value = CardServiceUiState(action = CampusCardAction.Unlost, preview = UiPreview.enabled && preview)
        refreshCards()
    }
    private fun refreshCards() {
        if (owner.isEmpty() || task?.isActive == true) return
        val current = attempt
        val username = owner
        val preview = mutable.value.preview
        val targetAccount = paymentAccount
        mutable.value = mutable.value.copy(loading = true, error = null, errorFields = emptySet(), completed = false, selectedCard = null)
        task = viewModelScope.launch {
            try {
                val config = if (UiPreview.enabled && preview) PreviewData.cardConfiguration(targetAccount)
                else repository.cardConfiguration(username)
                if (attempt != current) return@launch
                val cards = if (targetAccount == null) config.cards else listOf(
                    config.cards.singleOrNull { it.account == targetAccount }
                        ?: throw YktException("付款账户对应的校园卡已变更，请刷新卡状态"))
                if (targetAccount != null && !cards.single().lost) {
                    mutable.value = mutable.value.copy(visible = false, loading = false, cards = cards)
                    if (!preview) repository.dataChanges.invalidate(repository.identity(username),
                        setOf(AccountResource.Data.Cards, AccountResource.Data.Profile))
                    notifications.send("校园卡已解挂，正在更新付款状态")
                    return@launch
                }
                mutable.value = mutable.value.copy(visible = true, loading = false, cards = cards,
                    selectedCard = cards.singleOrNull()?.takeIf { !it.frozen && it.status == 0 },
                    lostNeedsPassword = config.lostNeedsPassword, unlostNeedsPassword = config.unlostNeedsPassword)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == current) {
                    mutable.value = mutable.value.copy(visible = false, loading = false, error = null, errorFields = emptySet())
                    notifications.send(safeError(e))
                }
            }
        }
    }
    fun selectCard(card: CampusCard) {
        val s = mutable.value
        if (s.loading || s.completed || card !in s.cards || card.frozen || card.status != 0) return
        mutable.value = s.copy(selectedCard = card, error = null, errorFields = emptySet())
    }
    fun inputEdited(field: FormField) {
        val s = mutable.value
        if (s.visible && !s.loading && field in s.errorFields)
            mutable.value = s.copy(error = null, errorFields = emptySet())
    }
    fun changeCard(password: String) {
        val s = mutable.value
        val card = s.selectedCard ?: return
        if (!s.visible || s.preview || s.loading || s.completed || task?.isActive == true || s.action == CampusCardAction.OthersRecharge) return
        if (s.needsPassword && !password.matches(Regex("[0-9]{6}"))) { mutable.value = s.copy(error = "请输入 6 位查询密码", errorFields = setOf(FormField.Password)); return }
        val current = attempt
        val username = owner
        val operation = s.operation
        writing = true
        mutable.value = s.copy(loading = true, error = null, errorFields = emptySet())
        task = viewModelScope.launch {
            try {
                val cards = repository.changeCardState(username, card, operation, password)
                if (attempt != current) return@launch
                completeCardChange(cards, operation)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt != current) return@launch
                if (e !is CardOperationUnconfirmed) {
                    writing = false
                    // Preflight failures and explicit server rejections stay in the dialog
                    // with their original message. Do not query or claim a write was submitted.
                    mutable.value = mutable.value.copy(loading = false, error = safeError(e), errorFields = inputErrorFields(e))
                    return@launch
                }
                // Only an explicitly ambiguous write is checked. Never replay the POST.
                val refreshed = try { repository.cardConfiguration(username).cards } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
                if (attempt != current) return@launch
                val confirmed = refreshed.singleOrNull { it.account == card.account }?.lost == (operation == CampusCardAction.Lost)
                if (confirmed) {
                    completeCardChange(refreshed, operation)
                } else {
                    mutable.value = mutable.value.copy(visible = false, loading = false, cards = refreshed.ifEmpty { s.cards }, selectedCard = null, error = null, errorFields = emptySet())
                    // Clear stale payment codes and require a fresh read before another write.
                    notifications.send(safeError(e) + "。操作结果待确认，请重新查看卡状态")
                }
            }
        }
    }
    private suspend fun completeCardChange(cards: List<CampusCard>, operation: CampusCardAction) {
        writing = false
        // Do not call dismiss(): it would cancel this task and drain the result events.
        mutable.value = mutable.value.copy(visible = false, loading = false, cards = cards, selectedCard = null, completed = true, error = null, errorFields = emptySet())
        notifications.send(operation.label + "成功")
    }
    fun clearRecipient() {
        if (mutable.value.loading) return
        restoring = null
        recipientConfigs.clear()
        mutable.value = mutable.value.copy(recipients = emptyList(), selectedRecipient = null, rechargeConfig = null,
            restorationFailed = false,
            error = mutable.value.error.takeUnless { FormField.Recipient in mutable.value.errorFields },
            errorFields = mutable.value.errorFields.takeUnless { FormField.Recipient in it } ?: emptySet())
    }
    fun refreshCaptcha() {
        if (!mutable.value.visible || mutable.value.action != CampusCardAction.OthersRecharge || task?.isActive == true) return
        restoring = null
        val current = attempt
        val username = owner
        val preview = mutable.value.preview
        recipientConfigs.clear()
        mutable.value = mutable.value.copy(loading = true, captcha = null, recipients = emptyList(), selectedRecipient = null, rechargeConfig = null,
            error = null, errorFields = emptySet(), restorationFailed = false)
        task = viewModelScope.launch {
            try {
                val captcha = if (UiPreview.enabled && preview) null else repository.recipientCaptcha(username)
                if (attempt == current) mutable.value = mutable.value.copy(loading = false, captcha = captcha)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == current) mutable.value = mutable.value.copy(loading = false, error = safeError(e), errorFields = inputErrorFields(e)) }
        }
    }
    fun queryRecipient(type: RecipientQueryType, value: String, code: String) {
        val s = mutable.value
        if (!s.visible || s.loading || task?.isActive == true || s.action != CampusCardAction.OthersRecharge) return
        if (value.isBlank() || (!s.preview && code.isBlank())) { mutable.value = s.copy(error = "请输入收款人信息和验证码", errorFields = setOf(FormField.Recipient, FormField.Captcha)); return }
        if (!s.preview && s.captcha == null) { refreshCaptcha(); return }
        restoring = null
        val current = attempt
        val username = owner
        recipientConfigs.clear()
        mutable.value = s.copy(loading = true, recipients = emptyList(), selectedRecipient = null, rechargeConfig = null,
            error = null, errorFields = emptySet(), restorationFailed = false)
        launchTask {
            try {
                val recipients = if (UiPreview.enabled && s.preview) PreviewData.recipients()
                    else repository.rechargeRecipients(username, type, value, s.captcha!!, code)
                if (attempt != current) return@launchTask
                val first = recipients.firstOrNull() ?: throw RecipientLookupRejected("未查询到可充值账户")
                mutable.value = mutable.value.copy(recipients = recipients, selectedRecipient = first, captcha = null)
                loadRecipientConfig(first, username, current)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt != current) return@launchTask
                val captcha = if (e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired) null
                    else try { repository.recipientCaptcha(username) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                if (attempt == current) mutable.value = mutable.value.copy(loading = false, captcha = captcha, error = safeError(e), errorFields = inputErrorFields(e))
            }
        }
    }
    fun selectRecipient(recipient: RechargeRecipient) {
        val s = mutable.value
        if (!s.visible || s.loading || task?.isActive == true || recipient !in s.recipients) return
        if (s.selectedRecipient === recipient) {
            mutable.value = s.copy(selectedRecipient = null, error = null, errorFields = emptySet())
            return
        }
        val cached = recipientConfigs[recipient]
        mutable.value = s.copy(selectedRecipient = recipient, rechargeConfig = cached, loading = cached == null, error = null, errorFields = emptySet())
        if (cached == null) {
            val current = attempt
            val username = owner
            launchTask { loadRecipientConfig(recipient, username, current) }
        }
    }
    fun retryRecipientConfig() {
        val s = mutable.value
        val recipient = s.selectedRecipient ?: return
        if (!s.visible || s.loading || task?.isActive == true) return
        val current = attempt
        val username = owner
        mutable.value = s.copy(loading = true, rechargeConfig = null, error = null, errorFields = emptySet())
        launchTask { loadRecipientConfig(recipient, username, current) }
    }
    private suspend fun loadRecipientConfig(recipient: RechargeRecipient, username: String, current: Long) {
        try {
            val config = if (UiPreview.enabled && mutable.value.preview) PreviewData.recipientConfiguration(recipient)
                else repository.recipientRechargeConfiguration(username, recipient)
            if (current != attempt) return
            if (config.recipient !== recipient || config.targetId != recipient.targetId) throw YktException("充值账户配置不匹配，请重新查询")
            recipientConfigs[recipient] = config
            mutable.value = mutable.value.copy(loading = false, rechargeConfig = config, error = null, errorFields = emptySet())
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (current == attempt) mutable.value = mutable.value.copy(loading = false, rechargeConfig = null, error = safeError(e), errorFields = inputErrorFields(e))
        }
    }
    fun continueRecharge(amountCents: Long) {
        val s = mutable.value
        val recipient = s.selectedRecipient ?: return
        val config = s.rechargeConfig ?: return
        if (!s.visible || s.preview || s.loading || task?.isActive == true ||
            s.action != CampusCardAction.OthersRecharge || config.recipient !== recipient) return
        config.amountError(amountCents)?.let { mutable.value = s.copy(error = it, errorFields = setOf(FormField.Amount)); return }
        if (!repository.ownsAccount(owner)) { mutable.value = s.copy(error = "账户已切换，请重新查询", errorFields = emptySet()); return }
        mutable.value = s.copy(loading = true, error = null, errorFields = emptySet())
        ready.trySend(RecipientRechargeRequest(owner, config, amountCents, s.preview, attempt))
    }
    private fun launchTask(block: suspend CoroutineScope.() -> Unit) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY, block = block)
        task = job
        job.start()
    }
    fun accepts(request: RecipientRechargeRequest) = request.attempt == attempt && request.username == owner &&
        repository.ownsAccount(owner) && mutable.value.visible && mutable.value.loading &&
        mutable.value.rechargeConfig === request.config && mutable.value.selectedRecipient === request.config.recipient
    fun accountChanged(username: String, preview: Boolean = false, renewed: Boolean = false) {
        val next = username.takeIf(String::isNotBlank) to (UiPreview.enabled && preview)
        if (binding == next && !renewed) return
        val restored = saved.bind(next.first, next.second, renewed)
        dismiss(); binding = next
        if (restored?.visible == true) {
            owner = username; restoring = restored; paymentAccount = restored.fixedCard
            formMutable.value = restored.form
            mutable.value = CardServiceUiState(visible = true, action = restored.action, loading = true, preview = next.second)
        }
    }
    internal fun restoreIfNeeded(active: Boolean, online: Boolean) {
        restoreAllowed = active && online
        val restored = restoring ?: return
        if (!restoreAllowed) { if (task?.isActive == true) { attempt++; task?.cancel(); task = null }; return }
        if (mutable.value.restorationFailed || task?.isActive == true) return
        val current = attempt
        val username = owner
        mutable.value = mutable.value.copy(loading = true, error = null)
        launchTask {
            try {
                if (restored.action == CampusCardAction.OthersRecharge) {
                    val recipients = restored.recipients.map { it.recipient() }
                    val selected = recipients.singleOrNull { it.targetId == restored.selectedTarget }
                    val config = selected?.let { if (binding?.second == true) PreviewData.recipientConfiguration(it)
                        else repository.recipientRechargeConfiguration(username, it) }
                    val captcha = if (recipients.isEmpty() && binding?.second != true) repository.recipientCaptcha(username) else null
                    if (attempt != current) return@launchTask
                    if (config != null && (config.recipient !== selected || config.targetId != selected.targetId))
                        throw YktException("原充值账户已变更，请重新查询")
                    restoring = null
                    if (selected != null && config != null) recipientConfigs[selected] = config
                    mutable.value = CardServiceUiState(visible = true, action = restored.action, recipients = recipients,
                        selectedRecipient = selected, rechargeConfig = config, captcha = captcha, preview = binding?.second == true)
                } else {
                    val config = if (binding?.second == true) PreviewData.cardConfiguration(restored.fixedCard)
                        else repository.cardConfiguration(username)
                    if (attempt != current) return@launchTask
                    restoring = null
                    val cards = config.cards.filter { restored.fixedCard == null || it.account == restored.fixedCard }
                    val selected = cards.singleOrNull { it.account == restored.card && !it.frozen && it.status == 0 }
                    mutable.value = CardServiceUiState(visible = true, action = restored.action, cards = cards,
                        selectedCard = selected.takeUnless { restored.interrupted },
                        lostNeedsPassword = config.lostNeedsPassword, unlostNeedsPassword = config.unlostNeedsPassword,
                        preview = binding?.second == true,
                        error = if (restored.interrupted) "操作已中断，已重新读取卡状态，请核实后重新选择" else
                            "原校园卡已变更，请重新选择".takeIf { restored.card != null && selected == null })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (current == attempt) {
                mutable.value = mutable.value.copy(loading = false, error = safeError(e), restorationFailed = true) } }
        }
    }
    internal fun retryRestore() {
        mutable.value = mutable.value.copy(restorationFailed = false)
        restoreIfNeeded(restoreAllowed, restoreAllowed)
    }
    fun dismiss() {
        restoring = null; writing = false; formMutable.value = RecipientFormDraft()
        attempt++
        task?.cancel(); task = null; owner = ""; paymentAccount = null
        recipientConfigs.clear()
        mutable.value = CardServiceUiState()
        while (ready.tryReceive().isSuccess) { }
        while (notifications.tryReceive().isSuccess) { }
    }
    private fun safeError(e: Exception) = if (e is YktException) e.message ?: "校园卡服务暂不可用" else "校园卡服务暂不可用，请稍后重试"
}
