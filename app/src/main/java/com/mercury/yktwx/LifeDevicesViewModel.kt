package com.mercury.yktwx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.mercury.yktwx.state.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.analytics.*
import com.mercury.yktwx.data.RechargeResult
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Foreground payment feedback is separate from the persisted device progress. */
data class LifePaymentFeedback(val sessionId: String, val phase: RechargePhase,
    val loading: Boolean = false, val detail: String? = null)
typealias LaundryPaymentFeedback = LifePaymentFeedback
private class LaundryOrderClosed : Exception()

data class LifeDevicesUiState(
    val restoring: Boolean = true, val connectedPhone: String = "", val feature: LifeFeature = LifeFeature.Water,
    val entryRequest: Long = 0, val loginVisible: Boolean = false, val phone: String = "", val code: String = "",
    val sendingCode: Boolean = false, val loggingIn: Boolean = false, val codeSeconds: Int = 0,
    val loginError: String? = null, val scanning: Boolean = false, val loadingDevice: Boolean = false,
    val scannedSerial: String? = null,
    val device: LifeDevice? = null, val selectedSkuId: String = "", val error: String? = null,
    val water: WaterSession? = null, val waterBusy: Boolean = false,
    val laundry: LaundrySession? = null, val laundryQuote: LaundryQuote? = null, val quoteLoading: Boolean = false,
    val laundryBusy: Boolean = false, val paymentVisible: Boolean = false, val paymentLaunch: LifePaymentLaunch? = null,
    val paymentFeedback: LaundryPaymentFeedback? = null,
    val waterSessions: List<WaterSession> = emptyList(), val laundrySessions: List<LaundrySession> = emptyList(),
    val changingAccount: Boolean = false, val accountBusy: Boolean = false,
    val accountError: String? = null,
    val paymentMethod: String? = null,
    val paymentRestorePending: Boolean = false,
    val cancelOrderConfirmation: String? = null,
    val sessionErrors: Map<String, String> = emptyMap(),
)

/** One login gate and scanner route each recognized code to its device service. */
@HiltViewModel
class LifeDevicesViewModel internal constructor(
    private val api: LifeDeviceService, private val store: LifeDeviceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis,
    private val analytics: AppAnalytics,
    savedState: SavedStateHandle = SavedStateHandle(),
) : ViewModel() {
    @Inject internal constructor(api: LifeDeviceService, store: LifeDeviceStore, analytics: AppAnalytics, savedState: SavedStateHandle) :
        this(api, store, io = Dispatchers.IO, analytics = analytics, savedState = savedState)
    private val mutable = MutableStateFlow(LifeDevicesUiState())
    val state = mutable.asStateFlow()
    private var pendingOverlay: LifeOverlaySavedState? = null
    private val overlays = LifeOverlayRestoration(savedState) {
        LifeOverlaySavedState(state.value.loginVisible, state.value.changingAccount, state.value.phone,
            payment = pendingOverlay?.payment == true || state.value.paymentVisible,
            session = pendingOverlay?.session ?: state.value.laundry?.sessionId,
            device = pendingOverlay?.device ?: state.value.device?.id,
            sku = pendingOverlay?.sku ?: state.value.selectedSkuId.takeIf(String::isNotBlank), method = state.value.paymentMethod,
            cancelSession = state.value.cancelOrderConfirmation)
    }
    internal fun bindPresentation(campus: String, preview: Boolean, generation: Long) {
        if (state.value.restoring) return
        val restored = overlays.bind(campus, restorationOwner, preview, generation) {
            pendingOverlay = null; dismissLogin(); dismissLaundryPayment(); mutable.update { it.copy(paymentMethod = null, cancelOrderConfirmation = null) }
        } ?: return
        mutable.update { it.copy(loginVisible = restored.login, changingAccount = restored.changing, phone = restored.phone,
            code = "", paymentMethod = restored.method, paymentRestorePending = restored.payment, cancelOrderConfirmation = restored.cancelSession) }
        pendingOverlay = restored.takeIf { it.payment }
    }
    internal fun resumePresentation(active: Boolean, online: Boolean) {
        val restored = pendingOverlay ?: return
        if (!active || !online || state.value.restoring) return
        val session = state.value.laundry
        if (restored.session != null) {
            if (session?.sessionId != restored.session) return
            pendingOverlay = null
            mutable.update { it.copy(paymentVisible = true, paymentLaunch = null, paymentRestorePending = false,
                paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Checking,
                    detail = "原订单结果待确认，请继续查询")) }
            if (session.orderNo.isNotBlank()) checkLaundryPayment()
            else mutable.update { it.copy(paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Failed,
                detail = "未取得原订单号，请先核实结果")) }
        } else if (state.value.device?.id == restored.device && state.value.selectedSkuId == restored.sku) {
            pendingOverlay = null; mutable.update { it.copy(paymentRestorePending = false) }; showLaundryPayment()
        }
    }
    internal fun selectPaymentMethod(method: String?) { mutable.update { it.copy(paymentMethod = method) } }
    internal fun requestLaundryCancellation() {
        state.value.laundry?.takeIf { it.canCancel }?.let { session ->
            mutable.update { it.copy(cancelOrderConfirmation = session.sessionId) }
        }
    }
    internal fun dismissLaundryCancellation() { mutable.update { it.copy(cancelOrderConfirmation = null) } }
    internal fun discardRestoredPayment() { pendingOverlay = null; mutable.update { it.copy(paymentRestorePending = false) }; dismissLaundryPayment() }
    private var account: LifeAccount? = null
    private var ownerPhone = ""
    private var requestedFeature: LifeFeature? = null
    private var requestedScan = false
    private var requestedSessionId: String? = null
    private var pendingDeviceSerial: String? = null
    private var restoredSkuId: String? = null
    internal val restorationOwner: String? get() = ownerPhone.takeIf(String::isNotBlank)

    /** Restore a device selection by its service identifier, without scanning or starting it. */
    internal fun restoreDevice(feature: LifeFeature, serial: String, skuId: String?): Boolean {
        if (mutable.value.restoring || feature == LifeFeature.Ride || LifeDeviceCode.identify(serial)?.first != feature) return false
        scan()
        restoredSkuId = skuId
        mutable.update { it.copy(feature = feature) }
        loadDevice(serial)
        return true
    }
    private val persistence = Mutex()
    private val sessionErrors = mutableMapOf<String, String>()
    private val dismissedSessionIds = mutableSetOf<String>()
    private var deviceTask: Job? = null
    private var authTask: Job? = null
    private var timerTask: Job? = null
    private var restoreTask: Job? = null
    private var waterTask: Job? = null
    private var waterPollTask: Job? = null
    private var laundryPollTask: Job? = null
    private val laundryApi = api as? LifeLaundryService
    private var laundryTask: Job? = null
    private var quoteTask: Job? = null
    private var quoteSequence = 0L
    // Activity results can survive process recreation; do not reuse the previous process's ids.
    private var paymentSequence = java.util.UUID.randomUUID().mostSignificantBits
    private var paymentInFlight: Long? = null
    private var visible = false
    private var foreground = false
    private var scanGeneration = 0L
    private val accountVerifier = LifeAccountVerifier(viewModelScope, now, { account }, { owner: LifeAccount ->
        val profile = (api as? LifeAccountService)?.profile(owner.token)
            ?: throw LifeDeviceException("暂时无法确认登录状态，请重新登录")
        if (profile.phone != owner.phone) throw LifeDeviceException("胖乖账户信息不一致，请重新登录")
    }, { e, _ -> expired(e, showLogin = false) }, ::failure)
    val accountConnection = accountVerifier.state
    private var accountTask: Job? = null
    val hasActiveServices: Boolean get() = mutable.value.waterSessions.any { it.active } ||
        mutable.value.laundrySessions.any { it.active } || mutable.value.water?.active == true ||
        mutable.value.laundry?.active == true || mutable.value.paymentLaunch != null
    private fun accountWorkPending(): Boolean = mutable.value.restoring ||
        mutable.value.waterBusy || mutable.value.laundryBusy || mutable.value.accountBusy

    fun checkAccount(force: Boolean = false) {
        if (!mutable.value.restoring && !mutable.value.loggingIn && !mutable.value.accountBusy) accountVerifier.check(force)
    }

    fun manageLogin(change: Boolean = false): Boolean {
        if (mutable.value.restoring || mutable.value.loggingIn || mutable.value.accountBusy ||
            change && accountWorkPending()) return false
        requestedFeature = null; requestedScan = false; requestedSessionId = null
        pendingDeviceSerial = null
        mutable.update { it.copy(loginVisible = true, changingAccount = change, phone = "", code = "", loginError = null, accountError = null) }
        return true
    }

    fun removeAccount(): Boolean {
        if (mutable.value.accountBusy || accountTask?.isActive == true) return false
        mutable.update { it.copy(accountBusy = true, accountError = null, loginVisible = false,
            paymentLaunch = null, paymentVisible = false, paymentFeedback = null) }
        accountVerifier.stop()
        scanGeneration++; quoteSequence++; paymentSequence++; paymentInFlight = null
        val pending = listOfNotNull(restoreTask, authTask, timerTask, deviceTask, quoteTask,
            waterTask, laundryTask, waterPollTask, laundryPollTask)
        pending.forEach { it.cancel() }
        accountTask = viewModelScope.launch {
            try {
                pending.joinAll()
                persistence.withLock {
                    withContext(io) { store.removeAccount() }
                    account = null; ownerPhone = ""
                }
                requestedFeature = null; requestedScan = false; requestedSessionId = null
                pendingDeviceSerial = null; pendingOverlay = null
                sessionErrors.clear(); accountVerifier.cleared()
                mutable.update { LifeDevicesUiState(restoring = false, entryRequest = it.entryRequest) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(accountError = failure(e)) } }
            finally { mutable.update { it.copy(accountBusy = false) } }
        }
        return true
    }

    init {
        restoreTask = viewModelScope.launch {
            try {
                val saved = withContext(io) { Triple(store.account(), store.waterSessions(), store.laundrySessions()) }
                account = saved.first
                ownerPhone = account?.phone.orEmpty()
                accountVerifier.restored(account?.maskedPhone.orEmpty())
                mutable.update { it.copy(connectedPhone = account?.maskedPhone.orEmpty()) }
                restoreLaundrySessions(saved.third)
                account?.let { recoverWaterSessions(it.token, saved.second.filter { session -> session.active }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(restoring = false, loginError = failure(e)) } }
            mutable.update { it.copy(restoring = false) }
            if (api is LifeAccountService) checkAccount()
            continuePendingDevice()
            requestedFeature?.let { continueEntry(it) }
            resumeSessions(waitBeforeWaterRead = true)
        }
    }
    fun openScan() {
        if (mutable.value.waterBusy || mutable.value.laundryBusy || mutable.value.paymentLaunch != null) return
        val feature = mutable.value.feature
        requestedScan = true
        requestedSessionId = null
        requestedFeature = feature
        if (mutable.value.restoring || account == null) { open(feature); return }
        open(feature)
        scan()
        requestedScan = false
    }
    /** Account recovery and login are handled after the shared camera identifies a platform. */
    fun openUnifiedScan() {
        if (mutable.value.waterBusy || mutable.value.laundryBusy || mutable.value.paymentLaunch != null) return
        requestedFeature = null; requestedScan = false; requestedSessionId = null
        scan()
        mutable.update { it.copy(entryRequest = it.entryRequest + 1) }
    }
    fun openSession(feature: LifeFeature, sessionId: String) {
        if (mutable.value.waterBusy || mutable.value.laundryBusy || mutable.value.paymentLaunch != null) return
        val water = mutable.value.waterSessions.firstOrNull { it.sessionId == sessionId }
        val laundry = mutable.value.laundrySessions.firstOrNull { it.sessionId == sessionId }
        if (feature == LifeFeature.Water && water == null || feature == LifeFeature.Laundry && laundry == null) return
        pendingDeviceSerial = null
        requestedScan = false
        requestedSessionId = sessionId
        scanGeneration++; deviceTask?.cancel(); quoteTask?.cancel()
        mutable.update { if (feature == LifeFeature.Water) it.copy(water = water) else it.copy(laundry = laundry) }
        open(feature)
        mutable.update { it.copy(scanning = false, scannedSerial = null, loadingDevice = false, quoteLoading = false, error = sessionErrors[sessionId]) }
        if (account != null) requestedSessionId = null
    }
    fun dismissSession(feature: LifeFeature, sessionId: String) {
        val value = mutable.value
        if (feature == LifeFeature.Water) {
            val session = value.waterSessions.firstOrNull { it.sessionId == sessionId } ?: return
            if (value.accountBusy || value.waterBusy && session.active || !session.canDismissRecord(sessionErrors[sessionId])) return
            mutable.update { it.copy(waterSessions = it.waterSessions.filterNot { item -> item.sessionId == sessionId },
                water = it.water?.takeUnless { item -> item.sessionId == sessionId },
                error = if (it.water?.sessionId == sessionId && it.feature == feature) null else it.error) }
        } else {
            val session = value.laundrySessions.firstOrNull { it.sessionId == sessionId } ?: return
            if (value.accountBusy || value.laundryBusy && session.active || !session.canDismissRecord(sessionErrors[sessionId])) return
            if (value.laundry?.sessionId == sessionId) {
                paymentSequence++; paymentInFlight = null; pendingOverlay = null
                quoteSequence++; quoteTask?.cancel()
            }
            mutable.update { it.copy(laundrySessions = it.laundrySessions.filterNot { item -> item.sessionId == sessionId },
                laundry = it.laundry?.takeUnless { item -> item.sessionId == sessionId },
                paymentLaunch = if (it.laundry?.sessionId == sessionId) null else it.paymentLaunch,
                paymentVisible = it.paymentVisible && it.laundry?.sessionId != sessionId,
                paymentRestorePending = it.paymentRestorePending && it.laundry?.sessionId != sessionId,
                paymentFeedback = it.paymentFeedback?.takeUnless { feedback -> feedback.sessionId == sessionId },
                cancelOrderConfirmation = it.cancelOrderConfirmation?.takeUnless { id -> id == sessionId },
                error = if (it.laundry?.sessionId == sessionId && it.feature == feature) null else it.error) }
        }
        sessionErrors.remove(sessionId)
        dismissedSessionIds.add(sessionId)
        mutable.update { it.copy(sessionErrors = sessionErrors.toMap()) }
        viewModelScope.launch {
            try {
                if (feature == LifeFeature.Laundry) persistence.withLock {
                    withContext(io) { store.removeLaundrySession(sessionId) }
                } else persistence.withLock { withContext(io) { store.removeWaterSession(sessionId) } }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { serviceError(feature, failure(e)) }
        }
    }
    fun open(feature: LifeFeature) {
        pendingDeviceSerial = null
        requestedFeature = feature
        if (mutable.value.restoring) return
        if (account == null) {
            mutable.update { it.copy(loginVisible = true, loginError = null, code = "") }
        } else {
            requestedFeature = null
            val session = mutable.value.water
            val washing = mutable.value.laundry
            val interrupted = if (feature == LifeFeature.Laundry && laundryTask?.isActive != true) when (washing?.phase) {
                LaundryPhase.Checking -> "设备检查已中断，请重试"
                LaundryPhase.Creating -> LaundryCreationUnknownMessage
                else -> null
            } else null
            mutable.update { it.copy(feature = feature, entryRequest = it.entryRequest + 1, loginVisible = false, scannedSerial = null,
                scanning = if (feature == LifeFeature.Water) session?.active != true && session?.phase != WaterPhase.Ended else washing == null,
                device = if (feature == LifeFeature.Water) session?.device else washing?.device,
                selectedSkuId = if (feature == LifeFeature.Water) session?.skuId.orEmpty() else washing?.skuId.orEmpty(),
                error = interrupted ?: sessionErrors[if (feature == LifeFeature.Water) session?.sessionId else washing?.sessionId],
                laundryQuote = null, paymentVisible = false, paymentFeedback = null) }
            resumeSessions()
        }
    }
    fun dismissLogin() {
        authTask?.cancel(); authTask = null
        requestedFeature = null
        requestedScan = false; requestedSessionId = null
        mutable.update { it.copy(loginVisible = false, loggingIn = false, sendingCode = false, code = "", loginError = null) }
    }
    fun connectAccount(clearPending: Boolean = true) {
        if (mutable.value.restoring || account != null || mutable.value.loggingIn) return
        if (clearPending) pendingDeviceSerial = null
        else if (mutable.value.water == null && mutable.value.laundry == null) pendingDeviceSerial = mutable.value.scannedSerial
        requestedFeature = null; requestedScan = false; requestedSessionId = null
        mutable.update { it.copy(loginVisible = true, changingAccount = false, loginError = null, code = "") }
    }
    fun phoneChanged(value: String) {
        if (mutable.value.loggingIn || mutable.value.sendingCode) return
        mutable.update { it.copy(phone = value.filter { c -> c in '0'..'9' }.take(11), code = "", loginError = null) }
    }
    fun codeChanged(value: String) {
        if (!mutable.value.loggingIn) mutable.update { it.copy(code = value.filter { c -> c in '0'..'9' }.take(8), loginError = null) }
    }
    fun sendCode() {
        val value = mutable.value
        if (!value.loginVisible || value.loggingIn || value.sendingCode || value.codeSeconds > 0 || value.accountBusy) return
        if (!value.phone.matches(Regex("1[0-9]{10}"))) {
            mutable.update { it.copy(loginError = "请输入正确的手机号") }; return
        }
        authTask = viewModelScope.launch {
            mutable.update { it.copy(sendingCode = true, loginError = null) }
            try {
                api.sendCode(value.phone)
                mutable.update { it.copy(sendingCode = false, codeSeconds = 60) }
                timerTask = viewModelScope.launch {
                    while (mutable.value.codeSeconds > 0) {
                        delay(1000)
                        mutable.update { it.copy(codeSeconds = (it.codeSeconds - 1).coerceAtLeast(0)) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(sendingCode = false, loginError = failure(e)) } }
        }
    }
    fun login() {
        val value = mutable.value
        if (!value.loginVisible || value.loggingIn || value.sendingCode || value.accountBusy) return
        if (!value.phone.matches(Regex("1[0-9]{10}")) || value.code.isBlank()) {
            mutable.update { it.copy(loginError = "请输入手机号和验证码") }; return
        }
        val analyticsAttempt = analytics.begin()
        authTask = viewModelScope.launch {
            mutable.update { it.copy(loggingIn = true, loginError = null) }
            try {
                val next = api.login(value.phone, value.code)
                ensureActive()
                if (ownerPhone.isNotBlank() && ownerPhone != next.phone && accountWorkPending())
                    throw LifeDeviceException("正在处理请求，请稍后更换账户")
                accountVerifier.reset()
                persistence.withLock { withContext(io) { store.saveAccount(next) }; ensureActive(); account = next }
                analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Panggui)
                if (ownerPhone.isNotBlank() && ownerPhone != next.phone) {
                    waterPollTask?.cancel(); laundryPollTask?.cancel(); waterTask?.cancel(); laundryTask?.cancel(); quoteTask?.cancel()
                    sessionErrors.clear()
                    mutable.update { it.copy(water = null, laundry = null, waterSessions = emptyList(), laundrySessions = emptyList(),
                        device = null, selectedSkuId = "", laundryQuote = null, paymentLaunch = null, paymentFeedback = null) }
                }
                account = next
                ownerPhone = next.phone
                dismissLaundryPayment(); dismissLaundryCancellation()
                accountVerifier.verified(next.maskedPhone)
                mutable.update { it.copy(loggingIn = false, connectedPhone = next.maskedPhone, loginVisible = false,
                    code = "", changingAccount = false, paymentMethod = null) }
                val restored = withContext(io) { store.waterSessions() to store.laundrySessions() }
                val candidates = restored.first.filter { item -> item.active &&
                    mutable.value.waterSessions.none { it.sessionId == item.sessionId } }
                restoreLaundrySessions(restored.second)
                try { recoverWaterSessions(next.token, candidates) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(error = failure(e)) } }
                continuePendingDevice()
                requestedFeature?.let { continueEntry(it) }
                resumeSessions(waitBeforeWaterRead = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Panggui, AnalyticsError.from(e))
                mutable.update { it.copy(loggingIn = false, loginError = failure(e)) }
            }
        }
    }
    fun pageVisible(active: Boolean) {
        visible = active
        if (!active) {
            pendingDeviceSerial = null
            scanGeneration++; deviceTask?.cancel(); mutable.update { it.copy(loadingDevice = false) }
        }
    }
    fun environment(active: Boolean) {
        foreground = active
        if (active) { resumeSessions(); checkAccount() } else {
            accountVerifier.stop()
            waterPollTask?.cancel(); waterPollTask = null
            laundryPollTask?.cancel(); laundryPollTask = null
        }
    }
    fun scan() {
        if (mutable.value.waterBusy || mutable.value.laundryBusy || mutable.value.paymentLaunch != null) return
        pendingDeviceSerial = null
        restoredSkuId = null
        requestedFeature = null; requestedScan = false; requestedSessionId = null
        scanGeneration++; deviceTask?.cancel(); quoteTask?.cancel()
        mutable.update { it.copy(scanning = true, scannedSerial = null, device = null, selectedSkuId = "", error = null, loadingDevice = false,
            water = null, laundry = null, laundryQuote = null, paymentVisible = false, paymentFeedback = null, quoteLoading = false) }
    }
    fun scanned(raw: String) {
        if (!visible || mutable.value.loadingDevice || !mutable.value.scanning) return
        val (feature, serial) = LifeDeviceCode.identify(raw)
            ?: run { mutable.update { it.copy(error = "未知设备") }; return }
        mutable.update { it.copy(feature = feature) }
        loadDevice(serial)
    }
    fun refreshDevice() {
        val serial = mutable.value.scannedSerial ?: mutable.value.device?.serial ?: return
        if (mutable.value.loadingDevice || mutable.value.waterBusy || mutable.value.laundryBusy) return
        loadDevice(serial)
    }
    private fun loadDevice(serial: String) {
        if (mutable.value.accountBusy) return
        pendingDeviceSerial = serial
        mutable.update { it.copy(scannedSerial = serial, scanning = false, error = null) }
        if (mutable.value.restoring) return
        val token = account?.token ?: return
        val generation = ++scanGeneration
        val feature = mutable.value.feature
        deviceTask?.cancel()
        deviceTask = viewModelScope.launch {
            mutable.update { it.copy(loadingDevice = true, error = null, scanning = false) }
            try {
                val device = if (feature == LifeFeature.Water) api.device(serial, token)
                    else requireNotNull(laundryApi).laundryDevice(serial, token)
                if (generation != scanGeneration) return@launch
                pendingDeviceSerial = null
                val water = mutable.value.water?.takeIf { it.device.id == device.id }
                val laundry = mutable.value.laundry?.takeIf { it.device.id == device.id }
                if (water != null) saveSession(water.copy(device = device))
                if (laundry != null) saveLaundrySession(laundry.copy(device = device))
                mutable.update { it.copy(device = device, selectedSkuId = water?.skuId ?: laundry?.skuId ?: device.skus.firstOrNull { sku -> sku.available != false }?.id.orEmpty(),
                    loadingDevice = false, error = if (feature == LifeFeature.Water) device.startError else device.laundryError,
                    laundryQuote = null) }
                restoredSkuId?.let(::selectSku); restoredSkuId = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == scanGeneration) mutable.update { it.copy(loadingDevice = false, scanning = it.device == null, error = failure(e)) }
                expired(e)
            }
        }
    }
    private fun continuePendingDevice() {
        if (visible && !mutable.value.restoring && account != null) pendingDeviceSerial?.let(::loadDevice)
    }
    fun selectSku(id: String) {
        if (mutable.value.loadingDevice || mutable.value.laundryBusy || mutable.value.paymentVisible || mutable.value.selectedSkuId == id) return
        if ((if (mutable.value.feature == LifeFeature.Water) mutable.value.water?.active else mutable.value.laundry?.active) == true) return
        if (mutable.value.device?.skus?.any { it.id == id && it.available != false } == true) {
            mutable.update { it.copy(selectedSkuId = id) }
            if (mutable.value.feature == LifeFeature.Laundry) {
                quoteSequence++; quoteTask?.cancel()
                mutable.update { it.copy(laundryQuote = null, quoteLoading = false, error = it.device?.laundryError) }
            }
        }
    }
    fun startWater() {
        val value = mutable.value
        val device = value.device ?: return
        if (value.feature != LifeFeature.Water || value.water?.active == true || value.loadingDevice || value.accountBusy || waterTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        val analyticsAttempt = analytics.begin()
        val analyticsFeature = if (device.isShower) AnalyticsFeature.PangguiShower else AnalyticsFeature.PangguiWater
        waterPollTask?.cancel(); waterPollTask = null
        waterTask = viewModelScope.launch {
            serviceError(LifeFeature.Water, null)
            mutable.update { it.copy(waterBusy = true) }
            try {
                val fresh = api.device(device.serial, token)
                ensureActive()
                if (fresh.id != device.id) throw LifeDeviceException("设备信息不匹配，请重新扫码")
                mutable.update { it.copy(device = fresh) }
                fresh.startError?.let { throw LifeDeviceException(it) }
                if (fresh.skus.none { it.id == value.selectedSkuId && it.available != false })
                    throw LifeDeviceException("接水选项已变更，请刷新设备")
                analytics.submitted(analyticsAttempt, analyticsFeature)
                try {
                    api.selectAfterPay(token)
                    val sent = WaterSession(fresh, value.selectedSkuId, WaterPhase.Starting, startSent = true)
                    saveSession(sent, select = true)
                    val unlock = if (device.isShower) api.unlockShower(value.selectedSkuId, token) else api.unlock(value.selectedSkuId, token)
                    saveSession(sent.copy(messageId = unlock.messageId, orderNo = unlock.orderNo))
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) {
                    analytics.operationFailed(analyticsAttempt, analyticsFeature, AnalyticsError.from(e))
                    throw e
                }
                waitForWater(token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.value.water?.takeIf { it.phase == WaterPhase.Starting && it.orderNo.isBlank() }?.let {
                    if (e is LifeDeviceException && e.businessRejected) removeWaterSession(it.sessionId)
                    else saveSession(it.copy(creationUncertain = true))
                }
                serviceError(LifeFeature.Water, failure(e)); expired(e)
            }
            finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeSessions() }
        }
    }
    fun stopWater() {
        val session = mutable.value.water ?: return
        if (session.phase != WaterPhase.Running || session.fault != null || mutable.value.accountBusy || waterTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        waterPollTask?.cancel(); waterPollTask = null
        waterTask = viewModelScope.launch {
            serviceError(LifeFeature.Water, null)
            mutable.update { it.copy(waterBusy = true) }
            try {
                readWater(token, session.sessionId)
                val current = mutable.value.waterSessions.firstOrNull { it.sessionId == session.sessionId }
                if (current?.phase != WaterPhase.Running || current.fault != null || account?.token != token) return@launch
                val supported = api.finishSupported(current.device.id, token)
                ensureActive()
                if (supported == false) {
                    saveSession(current.copy(device = current.device.copy(supportsFinish = false)))
                    throw LifeDeviceException("该设备不支持在应用内结束，请按设备说明操作")
                }
                saveSession(current.copy(phase = WaterPhase.Stopping))
                if (session.device.isShower) api.endShower(session.device.id, session.orderNo, token)
                else api.endWater(session.device.id, token)
                waitForWater(token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A rejected command keeps the normal running page; transport errors retain the command context.
                if (e is LifeDeviceException && !e.loginExpired && mutable.value.water?.phase == WaterPhase.Stopping) saveSession(session)
                serviceError(LifeFeature.Water, failure(e)); expired(e)
            } finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeSessions() }
        }
    }
    fun retryWater() {
        if (mutable.value.accountBusy || waterTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        waterPollTask?.cancel(); waterPollTask = null
        waterTask = viewModelScope.launch {
            serviceError(LifeFeature.Water, null)
            mutable.update { it.copy(waterBusy = true) }
            try {
                if (mutable.value.water?.phase == WaterPhase.Ended) finalizeWater(token)
                else {
                    val session = mutable.value.water ?: return@launch
                    readWater(token)
                    if (session.phase == WaterPhase.Stopping && mutable.value.water?.phase == WaterPhase.Stopping)
                        saveSession(checkNotNull(mutable.value.water).copy(phase = WaterPhase.Running))
                    waitForWater(token)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { serviceError(LifeFeature.Water, failure(e)); expired(e) }
            finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeSessions() }
        }
    }
    fun rescanWaterException() {
        val session = mutable.value.water ?: return
        if (!session.canDismissException || mutable.value.waterBusy) return
        dismissSession(LifeFeature.Water, session.sessionId)
        scan()
    }
    fun rescanLaundryException() {
        val session = mutable.value.laundry ?: return
        if (!session.canDismissUnknownCreation || mutable.value.laundryBusy) return
        dismissSession(LifeFeature.Laundry, session.sessionId)
        scan()
    }
    private suspend fun waitForWater(token: String) {
        repeat(20) {
            readWater(token)
            val phase = mutable.value.water?.phase
            if (phase == null || phase == WaterPhase.Running || phase == WaterPhase.Ended || mutable.value.water?.fault != null) return
            delay(1500)
        }
        throw LifeDeviceException("设备响应较慢，请留意现场状态并重试")
    }
    private suspend fun readWater(token: String, sessionId: String? = mutable.value.water?.sessionId) {
        var session = mutable.value.waterSessions.firstOrNull { it.sessionId == sessionId } ?: return
        if (session.phase == WaterPhase.Ended) { finalizeWater(token, sessionId); return }
        if (session.orderNo.isBlank()) throw LifeDeviceException("启动请求未返回${session.device.waterAction}订单，请在胖乖生活查看")
        if (session.phase == WaterPhase.Starting && session.messageId.isNotBlank()) {
            val message = api.message(session.messageId, token)
            if (message == null) return
            // Official DeviceStartupAct/StartStatusAct: OK (case insensitive) OR code 05.
            // All other combinations continue polling; a numeric code alone is not a fault.
            if (!message.state.equals("OK", ignoreCase = true) && message.code != "05") return
        }
        // Official showers use shower/sync at startup, then water/sync for the same outlet/order.
        val reading = if (session.device.isShower && session.phase == WaterPhase.Starting) api.shower(session.skuId, token)
            else api.water(session.skuId, token)
        if (account?.token != token) return
        session = mutable.value.waterSessions.firstOrNull { it.sessionId == sessionId } ?: return
        if (reading.orderNo.isBlank()) throw LifeDeviceException("设备未返回${session.device.waterAction}订单，请重试")
        if (reading.orderNo != session.orderNo) {
            if (session.phase == WaterPhase.Starting) throw LifeDeviceException("设备订单已变化，请在胖乖生活核对")
            // The device now reports another order; stop tracking ours without finalizing or controlling it.
            removeWaterSession(session.sessionId)
            return
        }
        when {
            reading.workStatus == 2 && reading.status == 1 -> {
                val next = session.copy(phase = if (session.phase == WaterPhase.Stopping) WaterPhase.Stopping else WaterPhase.Running,
                    startedAtMillis = session.startedAtMillis ?: now(), creationUncertain = false, fault = null)
                if (next != session) saveSession(next)
                sessionError(LifeFeature.Water, session.sessionId, null)
            }
            reading.ended(session.device) -> {
                // The zero-consumption capture ends here without creating a payable after-pay order.
                val noPayment = if (session.device.isShower) !reading.positiveConsumption else reading.zeroConsumption
                saveSession(session.copy(phase = WaterPhase.Ended, endedAtMillis = now(), finalized = noPayment))
                if (noPayment) sessionError(LifeFeature.Water, session.sessionId, null)
                else finalizeWater(token, session.sessionId)
            }
            session.device.isShower && reading.status == 6 -> {
                saveSession(session.copy(fault = "设备异常，请更换设备使用"))
                sessionError(LifeFeature.Water, session.sessionId, "设备异常，请更换设备使用")
            }
            else -> throw LifeDeviceException("暂时无法读取设备状态，请重试")
        }
    }
    private suspend fun finalizeWater(token: String, sessionId: String? = mutable.value.water?.sessionId) {
        var session = mutable.value.waterSessions.firstOrNull { it.sessionId == sessionId } ?: return
        if (session.phase != WaterPhase.Ended || session.finalized) return
        if (session.orderId.isBlank()) {
            val orderId = api.createAfterPay(session.orderNo, token)
            if (mutable.value.waterSessions.none { it.sessionId == sessionId }) return
            session = session.copy(orderId = orderId)
            saveSession(session)
        }
        if (mutable.value.waterSessions.none { it.sessionId == sessionId }) return
        api.syncOrder(session.orderNo, token)
        saveSession(session.copy(finalized = true))
        sessionError(LifeFeature.Water, session.sessionId, null)
    }
    private suspend fun saveSession(session: WaterSession, select: Boolean = false, restoring: Boolean = false) {
        currentCoroutineContext().ensureActive()
        if (session.sessionId in dismissedSessionIds) return
        if (!select && !restoring && mutable.value.waterSessions.none { it.sessionId == session.sessionId }) return
        // Preserve the command in memory even if disk persistence fails after its response.
        mutable.update {
            val selected = select || it.water?.sessionId == session.sessionId
            val records = if (it.waterSessions.any { item -> item.sessionId == session.sessionId })
                it.waterSessions.map { item -> if (item.sessionId == session.sessionId) session else item } else it.waterSessions + session
            it.copy(water = if (selected) session else it.water, waterSessions = records,
                device = if (selected && it.feature == LifeFeature.Water) session.device else it.device,
                scanning = if (selected && it.feature == LifeFeature.Water) false else it.scanning)
        }
        persistSessions()
    }
    private suspend fun persistSessions() = persistence.withLock {
        if (account == null || mutable.value.accountBusy) return@withLock
        withContext(io) {
            val value = mutable.value
            store.saveWaterSessions(value.waterSessions)
            store.saveLaundrySessions(value.laundrySessions)
        }
    }
    private fun sessionError(feature: LifeFeature, sessionId: String, message: String?) {
        if (sessionId in dismissedSessionIds) return
        if (message == null) sessionErrors.remove(sessionId) else sessionErrors[sessionId] = message
        mutable.update { it.copy(sessionErrors = sessionErrors.toMap()) }
        val selected = if (feature == LifeFeature.Water) mutable.value.water?.sessionId else mutable.value.laundry?.sessionId
        if (selected == sessionId) serviceError(feature, message)
    }
    private fun serviceError(feature: LifeFeature, message: String?) {
        val sessionId = if (feature == LifeFeature.Water) mutable.value.water?.sessionId else mutable.value.laundry?.sessionId
        sessionId?.let { if (message == null) sessionErrors.remove(it) else sessionErrors[it] = message }
        mutable.update { it.copy(sessionErrors = sessionErrors.toMap(), error = if (it.feature == feature) message else it.error) }
    }
    private suspend fun removeWaterSession(id: String) {
        mutable.update { it.copy(waterSessions = it.waterSessions.filterNot { session -> session.sessionId == id },
            water = it.water?.takeUnless { session -> session.sessionId == id }) }
        sessionErrors.remove(id)
        persistSessions()
    }
    /** Verify saved progress once; failed reads retain an entry without replaying commands. */
    private suspend fun recoverWaterSessions(token: String, candidates: List<WaterSession>) {
        if (account?.token != token) return
        for (session in candidates) {
            if (session.sessionId in dismissedSessionIds) continue
            try {
                if (session.orderNo.isBlank()) {
                    saveSession(session.copy(phase = WaterPhase.Starting, creationUncertain = true), restoring = true)
                } else {
                    val reading = if (session.device.isShower) api.shower(session.skuId, token) else api.water(session.skuId, token)
                    if (session.sessionId in dismissedSessionIds) continue
                    if (account?.token != token) return
                    if (reading.orderNo.isBlank()) throw LifeDeviceException("设备未返回${session.device.waterAction}订单，请重试")
                    if (reading.orderNo == session.orderNo) {
                        when {
                            reading.status == 1 && reading.workStatus == 2 -> {
                                val running = session.copy(phase = WaterPhase.Running,
                                    startedAtMillis = session.startedAtMillis ?: now(), endedAtMillis = null,
                                    creationUncertain = false, fault = null)
                                mutable.update { it.copy(waterSessions = it.waterSessions + running,
                                    water = if (it.restoring && it.water == null && !it.scanning && it.scannedSerial == null) running else it.water) }
                            }
                            reading.ended(session.device) -> Unit
                            session.device.isShower && reading.status == 6 ->
                                saveSession(session.copy(fault = "设备异常，请更换设备使用"), restoring = true)
                            else -> throw LifeDeviceException("暂时无法读取设备状态，请重试")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (account?.token != token) return
                saveSession(session, restoring = true)
                sessionError(LifeFeature.Water, session.sessionId, failure(e))
                mutable.update { if (it.feature == LifeFeature.Water && it.water == null && !it.scanning && it.scannedSerial == null)
                    it.copy(error = failure(e)) else it }
                if (expired(e, showLogin = false)) return
            }
        }
        if (account?.token == token) persistSessions()
    }
    private fun resumeSessions(waitBeforeWaterRead: Boolean = false) {
        resumeWater(waitBeforeWaterRead)
        resumeLaundry()
    }
    private fun resumeWater(waitBeforeFirstRead: Boolean = false) {
        if (!foreground || account == null || mutable.value.accountBusy || waterTask?.isActive == true || waterPollTask?.isActive == true) return
        if (mutable.value.waterSessions.none { it.active || it.phase == WaterPhase.Ended && !it.finalized }) return
        val token = account!!.token
        waterPollTask = viewModelScope.launch {
            if (waitBeforeFirstRead) delay(1500)
            while (isActive && foreground && account?.token == token) {
                val sessions = mutable.value.waterSessions.filter { it.active ||
                    it.phase == WaterPhase.Ended && !it.finalized && sessionErrors[it.sessionId] == null }
                    .filter { it.orderNo.isNotBlank() && it.fault == null }
                if (sessions.isEmpty()) break
                for (session in sessions) {
                    try { readWater(token, session.sessionId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sessionError(LifeFeature.Water, session.sessionId, failure(e)); if (expired(e)) return@launch }
                }
                delay(1500)
            }
        }
    }
    fun loadLaundryQuote() {
        val value = mutable.value
        if (value.feature != LifeFeature.Laundry || !value.paymentVisible || value.laundry?.active == true || value.loadingDevice ||
            value.quoteLoading || value.accountBusy) return
        val device = value.device ?: return
        val token = account?.token ?: return requireLogin()
        val service = laundryApi ?: return
        quoteTask?.cancel()
        val request = ++quoteSequence
        val generation = scanGeneration
        fun current() = request == quoteSequence && generation == scanGeneration && account?.token == token &&
            mutable.value.paymentVisible && mutable.value.device?.id == device.id && mutable.value.selectedSkuId == value.selectedSkuId
        mutable.update { it.copy(quoteLoading = true, laundryQuote = null, error = null) }
        quoteTask = viewModelScope.launch {
            try {
                val fresh = service.laundryDevice(device.serial, token)
                ensureActive()
                if (!current()) return@launch
                if (fresh.id != device.id) throw LifeDeviceException("设备信息不匹配，请重新扫码")
                mutable.update { it.copy(device = fresh) }
                fresh.laundryError?.let { throw LifeDeviceException(it) }
                if (fresh.skus.none { it.id == value.selectedSkuId && it.available != false })
                    throw LifeDeviceException("洗衣程序已变更，请刷新设备")
                val quote = service.laundryQuote(fresh, value.selectedSkuId, token)
                ensureActive()
                if (current()) {
                    if (quote.deviceId != device.id || quote.skuId != value.selectedSkuId)
                        throw LifeDeviceException("程序信息不匹配，请重新选择")
                    mutable.update { it.copy(laundryQuote = quote, error = null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ensureActive()
                if (current()) { mutable.update { it.copy(error = failure(e)) }; expired(e) }
            }
            finally { if (request == quoteSequence) mutable.update { it.copy(quoteLoading = false) } }
        }
    }
    fun dismissLaundryPayment() {
        pendingOverlay = null
        quoteSequence++; quoteTask?.cancel()
        mutable.update { it.copy(paymentVisible = false, paymentRestorePending = false, paymentFeedback = null, quoteLoading = false,
            error = it.error ?: it.device?.laundryError) }
    }
    fun showLaundryPayment() {
        val value = mutable.value
        if (value.laundryBusy || value.quoteLoading || value.paymentLaunch != null || value.feature != LifeFeature.Laundry) return
        val session = value.laundry
        if (session?.phase == LaundryPhase.Closed) {
            mutable.update { it.copy(paymentVisible = true, paymentFeedback = closedLaundryFeedback(session)) }
            return
        }
        if (session != null && session.active) {
            if (session.phase == LaundryPhase.Checking && !session.createSent) {
                mutable.update { it.copy(paymentVisible = true, paymentFeedback = null) }
                if (value.laundryQuote == null) laundryWork(clearError = false) { token, service ->
                    val quote = service.laundryQuote(session.device, session.skuId, token)
                    mutable.update { it.copy(laundryQuote = quote) }
                }
                return
            }
            if (session.phase != LaundryPhase.Paying || session.orderNo.isBlank()) return
            mutable.update { it.copy(paymentVisible = true, paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Checking, loading = true)) }
            laundryWork(clearError = false) { token, service ->
                if (readLaundryOrder(token, service)) return@laundryWork
                if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                    saveLaundrySession(checkNotNull(mutable.value.laundry).copy(paid = true, phase = LaundryPhase.Starting))
                    waitLaundryStart(token, service)
                    return@laundryWork
                }
                val channels = service.laundryChannels(session, token)
                mutable.update { it.copy(laundryQuote = LaundryQuote(session.device.id, session.skuId, session.price, session.minutes, channels),
                    paymentFeedback = null) }
            }
        } else if (value.device != null && value.selectedSkuId.isNotBlank()) {
            if (value.paymentVisible && value.laundryQuote != null) return
            mutable.update { it.copy(paymentVisible = true, paymentFeedback = null) }
            if (value.laundryQuote?.let { it.deviceId == value.device.id && it.skuId == value.selectedSkuId } != true)
                loadLaundryQuote()
        }
    }
    fun payLaundry(method: String) {
        val value = mutable.value
        val quote = value.laundryQuote ?: return
        val device = value.device ?: return
        if (value.feature != LifeFeature.Laundry || !value.paymentVisible || value.laundryBusy || value.quoteLoading || value.paymentLaunch != null ||
            quote.deviceId != device.id || (value.laundry == null && quote.skuId != value.selectedSkuId) ||
            quote.channels.none { it.method == method && it.laundryUnavailableReason() == null }) return
        val analyticsAttempt = analytics.begin()
        laundryWork { token, service ->
            var session = mutable.value.laundry
            if (session?.phase == LaundryPhase.Closed) return@laundryWork
            if (session == null || !session.active || session.phase == LaundryPhase.Checking && !session.createSent) {
                session = session?.takeIf { it.active }?.copy(payMethod = method, payTypeName = "",
                    device = device.copy(channels = quote.channels))
                    ?: LaundrySession(device.copy(channels = quote.channels), quote.skuId, quote.price, method, minutes = quote.minutes)
                saveLaundrySession(session, select = true)
                mutable.update { it.copy(paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Creating)) }
                checkLaundryReady(session, token, service)
                session = createLaundrySession(session, token, service)
            } else {
                if (session.phase != LaundryPhase.Paying || session.orderNo.isBlank()) return@laundryWork
                mutable.update { it.copy(paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Creating)) }
                if (readLaundryOrder(token, service)) return@laundryWork
                if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                    saveLaundrySession(checkNotNull(mutable.value.laundry).copy(paid = true, phase = LaundryPhase.Starting))
                    waitLaundryStart(token, service); return@laundryWork
                }
                session = checkNotNull(mutable.value.laundry)
                val channels = service.laundryChannels(session, token)
                mutable.update { it.copy(laundryQuote = LaundryQuote(session.device.id, session.skuId, session.price, session.minutes, channels)) }
                if (channels.none { it.method == method && it.laundryUnavailableReason() == null })
                    throw LifeDeviceException(channels.firstOrNull { it.method == method }?.laundryUnavailableReason()
                        ?: "支付方式已变更，请重新选择")
                session = session.copy(device = session.device.copy(channels = channels))
                saveLaundrySession(session)
                checkLaundryReady(session, token, service)
                session = session.copy(payMethod = method, payTypeName = "")
                saveLaundrySession(session)
            }
            if (readLaundryOrder(token, service)) return@laundryWork
            session = checkNotNull(mutable.value.laundry)
            if (session.price.toBigDecimal().compareTo(quote.payable) != 0) {
                val channels = service.laundryChannels(session, token)
                mutable.update { it.copy(laundryQuote = LaundryQuote(session.device.id, session.skuId, session.price, session.minutes, channels),
                    paymentFeedback = null, error = "应付金额已更新，请确认后支付") }
                return@laundryWork
            }
            saveLaundrySession(session.copy(phase = LaundryPhase.Paying, paymentSent = true))
            laundryAnalyticsAttempt = analyticsAttempt
            analytics.submitted(analyticsAttempt, AnalyticsFeature.PangguiLaundry)
            if (method == "2") {
                paymentFeedback(session.sessionId, RechargePhase.Checking, loading = true)
                try { service.payLaundryBalance(session.orderNo, token) }
                catch (e: LifeDeviceException) {
                    analytics.operationFailed(analyticsAttempt, AnalyticsFeature.PangguiLaundry, AnalyticsError.from(e))
                    if (e.businessRejected) paymentFeedback(session.sessionId, RechargePhase.Failed, detail = failure(e))
                    throw e
                }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    analytics.operationFailed(analyticsAttempt, AnalyticsFeature.PangguiLaundry, AnalyticsError.from(e))
                    throw e
                }
                waitLaundryPaid(token, service)
            } else {
                val payload = try { service.prepareLaundryPayment(session.orderNo, method, token) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        analytics.operationFailed(analyticsAttempt, AnalyticsFeature.PangguiLaundry, AnalyticsError.from(e))
                        throw e
                    }
                paymentFeedback(session.sessionId, RechargePhase.AwaitingPayment)
                mutable.update { it.copy(paymentLaunch = LifePaymentLaunch(++paymentSequence, session.orderNo, payload, method)) }
            }
        }
    }
    fun laundryPaymentReturned(requestId: Long, resultStatus: String?) {
        val launch = mutable.value.paymentLaunch?.takeIf { it.id == requestId } ?: return
        val session = mutable.value.laundry?.takeIf { it.orderNo == launch.orderNo } ?: return
        if (resultStatus == "launch_failed" || resultStatus == "6002")
            analytics.operationFailed(laundryAnalyticsAttempt,
                AnalyticsFeature.PangguiLaundry, AnalyticsError.External)
        mutable.update { it.copy(paymentLaunch = null) }
        paymentInFlight = null
        paymentFeedback(session.sessionId, RechargePhase.Checking, loading = true)
        // SDK callbacks do not prove payment. Always read the server for this order.
        laundryWork { token, service ->
            if (readLaundryOrder(token, service)) return@laundryWork
            val pendingOrSuccess = if (launch.method == "52") resultStatus == null || resultStatus.equals("success", true)
                else resultStatus == null || resultStatus in setOf("9000", "8000", "6004")
            if (!pendingOrSuccess && !service.laundryPaid(session.orderNo, session.payMethod, token)) {
                val message = if (launch.method == "52") when {
                    resultStatus.equals("cancel", true) -> "支付已取消，可继续支付同一订单"
                    resultStatus == "launch_failed" -> "银联支付未能启动，可继续支付同一订单"
                    else -> "银联未完成支付，可继续支付同一订单"
                } else when (resultStatus) {
                    "6001" -> "支付已取消，可继续支付同一订单"
                    "6002" -> "支付宝连接失败，可继续支付同一订单"
                    "launch_failed" -> "支付宝支付未能启动，可继续支付同一订单"
                    else -> "支付宝未完成支付，可继续支付同一订单"
                }
                paymentFeedback(session.sessionId, RechargePhase.Failed, detail = message)
                throw LifeDeviceException(message)
            }
            waitLaundryPaid(token, service)
        }
    }
    fun claimLaundryPayment(requestId: Long): Boolean {
        if (mutable.value.paymentLaunch?.id != requestId || paymentInFlight != null) return false
        paymentInFlight = requestId
        return true
    }
    fun cancelLaundry() {
        val value = mutable.value
        val session = value.laundry ?: return
        if (value.feature != LifeFeature.Laundry || !session.canCancel || value.laundryBusy || value.quoteLoading ||
            value.paymentLaunch != null || paymentInFlight != null) return
        laundryWork(clearError = false) { token, service ->
            if (readLaundryOrder(token, service, session.sessionId)) return@laundryWork
            val latest = mutable.value.laundrySessions.firstOrNull { it.sessionId == session.sessionId } ?: return@laundryWork
            if (!latest.canCancel) return@laundryWork
            if (service.laundryPaid(latest.orderNo, latest.payMethod, token)) {
                saveLaundrySession(latest.copy(paid = true, phase = LaundryPhase.Starting))
                waitLaundryStart(token, service)
                return@laundryWork
            }
            service.cancelLaundryOrder(latest.orderId, token)
            // code=0 acknowledges cancellation; a lost/rejected response leaves the order queryable.
            saveLaundrySession(latest.copy(phase = LaundryPhase.Closed))
        }
    }
    fun retryLaundry() {
        if (mutable.value.feature != LifeFeature.Laundry || mutable.value.loadingDevice || mutable.value.quoteLoading) return
        val session = mutable.value.laundry ?: run {
            if (mutable.value.device?.laundryError != null) refreshDevice()
            else showLaundryPayment()
            return
        }
        if (session.phase == LaundryPhase.Checking && !session.createSent) {
            showLaundryPayment()
            return
        }
        laundryWork { token, service ->
            when (session.phase) {
                LaundryPhase.Checking, LaundryPhase.Creating -> throw LifeDeviceException(LaundryCreationUnknownMessage)
                LaundryPhase.Paying -> waitLaundryPaid(token, service)
                LaundryPhase.Starting -> waitLaundryStart(token, service)
                LaundryPhase.Running, LaundryPhase.Refunding, LaundryPhase.Ended -> readLaundryOrder(token, service)
                LaundryPhase.Closed -> Unit
            }
        }
    }
    fun checkLaundryPayment() {
        val session = mutable.value.laundry ?: return
        if (session.orderNo.isBlank() || mutable.value.laundryBusy || mutable.value.paymentLaunch != null) return
        mutable.update { it.copy(paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Checking, loading = true)) }
        laundryWork { token, service -> waitLaundryPaid(token, service) }
    }
    private fun closedLaundryFeedback(session: LaundrySession) = LaundryPaymentFeedback(session.sessionId,
        RechargePhase.Failed, detail = if (session.serverStatus == 5) "订单已退款" else "订单已关闭")
    private fun paymentFeedback(sessionId: String, phase: RechargePhase, loading: Boolean = false, detail: String? = null) {
        mutable.update { value ->
            if (value.paymentFeedback?.sessionId == sessionId)
                value.copy(paymentFeedback = LaundryPaymentFeedback(sessionId, phase, loading, detail)) else value
        }
    }
    private fun laundryWork(clearError: Boolean = true, block: suspend (String, LifeLaundryService) -> Unit) {
        if (mutable.value.accountBusy || laundryTask?.isActive == true || mutable.value.paymentLaunch != null) return
        val token = account?.token ?: return requireLogin()
        val service = laundryApi ?: return
        laundryPollTask?.cancel(); laundryPollTask = null
        laundryTask = viewModelScope.launch {
            if (clearError) serviceError(LifeFeature.Laundry, null)
            mutable.update { it.copy(laundryBusy = true) }
            try { block(token, service) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val session = mutable.value.laundry
                if (session?.phase == LaundryPhase.Checking && !session.createSent && session.orderNo.isBlank()) removeLaundrySession(session.sessionId)
                if (session?.phase == LaundryPhase.Creating && session.createSent && session.orderNo.isBlank()) {
                    saveLaundrySession(session.copy(creationUncertain = true))
                    mutable.update { it.copy(paymentVisible = false, paymentFeedback = null) }
                }
                val message = if (session?.phase == LaundryPhase.Creating && session.orderNo.isBlank())
                    LaundryCreationUnknownMessage else failure(e)
                serviceError(LifeFeature.Laundry, message)
                mutable.value.paymentFeedback?.takeIf { it.phase == RechargePhase.Creating }?.let {
                    // Creation/check/prepay has stopped. Restore the form and its error instead of a stale processing result.
                    mutable.update { value -> value.copy(paymentFeedback = null) }
                }
                mutable.value.paymentFeedback?.takeIf { it.phase in setOf(RechargePhase.AwaitingPayment, RechargePhase.Checking) }?.let {
                    paymentFeedback(it.sessionId, RechargePhase.Checking, detail = message)
                }
                expired(e)
            }
            finally { laundryTask = null; mutable.update { it.copy(laundryBusy = false) }; resumeSessions() }
        }
    }
    private var laundryAnalyticsAttempt: AnalyticsAttempt? = null
    private suspend fun checkLaundryReady(session: LaundrySession, token: String, service: LifeLaundryService) {
        val message = service.checkLaundryDevice(session.device.id, token)
        currentCoroutineContext().ensureActive()
        if (message.isBlank()) {
            sessionError(LifeFeature.Laundry, session.sessionId, null)
            return
        }
        repeat(40) {
            if (service.laundryMachineReady(message, token)) {
                sessionError(LifeFeature.Laundry, session.sessionId, null)
                return
            }
            delay(500)
        }
        throw LifeDeviceException("设备响应较慢，请留意现场状态后重试")
    }
    private suspend fun waitLaundryPaid(token: String, service: LifeLaundryService) {
        val session = mutable.value.laundry ?: return
        // Reuse campus payment's bounded read-only polling, adapting laundry's status codes.
        // In particular, laundry orderStatus=2 means running, never payment timeout.
        val poller = OrderResultPoller(viewModelScope, delayMillis = 5000)
        val result = suspendCancellableCoroutine<OrderResultPoller.Outcome> { continuation ->
            continuation.invokeOnCancellation { poller.reset() }
            poller.start(newRound = true, isCurrent = { account?.token == token &&
                mutable.value.laundrySessions.any { it.sessionId == session.sessionId } }, onJob = { job ->
                    job.invokeOnCompletion { if (continuation.isActive) continuation.cancel() }
                },
                query = {
                    val complete = readLaundryOrder(token, service, session.sessionId)
                    if (mutable.value.laundry?.phase == LaundryPhase.Closed) throw LaundryOrderClosed()
                    RechargeResult(paid = complete || service.laundryPaid(session.orderNo, session.payMethod, token),
                        serverStatus = 0, message = "支付尚未完成，可继续支付同一订单")
                }, onResult = { if (continuation.isActive) continuation.resume(it) },
                onError = { if (continuation.isActive) {
                    if (it is LaundryOrderClosed) continuation.resume(OrderResultPoller.Outcome(null, null))
                    else continuation.resumeWithException(it)
                } })
        }
        if (mutable.value.laundry?.phase == LaundryPhase.Closed) return
        if (result.phase != RechargePhase.Paid) {
            val message = result.message ?: "支付尚未完成，可继续支付同一订单"
            paymentFeedback(session.sessionId, RechargePhase.Failed, detail = message)
            throw LifeDeviceException(message)
        }
        val latest = mutable.value.laundrySessions.firstOrNull { it.sessionId == session.sessionId } ?: return
        if (latest.phase in setOf(LaundryPhase.Running, LaundryPhase.Refunding, LaundryPhase.Ended)) return
        saveLaundrySession(latest.copy(paid = true, phase = LaundryPhase.Starting))
        waitLaundryStart(token, service)
    }
    private suspend fun waitLaundryStart(token: String, service: LifeLaundryService) {
        val session = mutable.value.laundry ?: return
        repeat(30) {
            if (readLaundryOrder(token, service)) return
            if (service.laundryStarted(session.orderNo, token)) {
                saveLaundrySession(checkNotNull(mutable.value.laundry).copy(phase = LaundryPhase.Running, paid = true))
                return
            }
            delay(1000)
        }
        throw LifeDeviceException("已处理支付，设备启动较慢，请检查设备后重试")
    }
    private suspend fun readLaundryOrder(token: String, service: LifeLaundryService,
        sessionId: String? = mutable.value.laundry?.sessionId): Boolean {
        val session = mutable.value.laundrySessions.firstOrNull { it.sessionId == sessionId } ?: return false
        if (session.orderNo.isBlank()) throw LifeDeviceException(LaundryCreationUnknownMessage)
        val reading = service.laundryOrder(session.orderNo, token)
        if (reading.orderNo != session.orderNo || reading.orderId != session.orderId || reading.deviceId != session.device.id || reading.skuId != session.skuId)
            throw LifeDeviceException("洗衣订单信息不匹配，请在胖乖生活核对")
        val next = session.copy(price = reading.price, serverStatus = reading.status, creationUncertain = false, device = session.device.copy(
            name = reading.deviceName.ifBlank { session.device.name },
            shopName = reading.shopName.ifBlank { session.device.shopName },
            skus = session.device.skus.map { sku ->
                if (sku.id == reading.skuId) sku.copy(name = reading.program.ifBlank { sku.name },
                    feature = reading.programFeature.ifBlank { sku.feature }) else sku
            }), payTypeName = reading.payTypeName.ifBlank { session.payTypeName },
            minutes = reading.minutes ?: session.minutes,
            createdAtMillis = reading.createdAtMillis ?: session.createdAtMillis,
            startedAtMillis = reading.paidAtMillis ?: session.startedAtMillis,
            expectedEndMillis = reading.expectedEndMillis ?: session.expectedEndMillis,
            finishedAtMillis = reading.finishedAtMillis ?: session.finishedAtMillis)
        when (reading.status) {
            1, 5 -> saveLaundrySession(next.copy(phase = LaundryPhase.Closed))
            4 -> saveLaundrySession(next.copy(phase = LaundryPhase.Refunding))
            3 -> saveLaundrySession(next.copy(phase = LaundryPhase.Ended, paid = true))
            2 -> saveLaundrySession(next.copy(phase = LaundryPhase.Running, paid = true))
            0 -> {
                // Resolve a pending detail that conflicts with confirmed payment through the server.
                val paid = session.paid && service.laundryPaid(session.orderNo, session.payMethod, token)
                saveLaundrySession(next.copy(phase = if (paid) LaundryPhase.Starting else LaundryPhase.Paying, paid = paid))
            }
            else -> throw LifeDeviceException("暂时无法读取洗衣状态，请在胖乖生活核对")
        }
        // An unpaid order does not prove the device is free or that a failed payment succeeded.
        if (reading.status in setOf(2, 3)) {
            sessionError(LifeFeature.Laundry, session.sessionId, null)
        }
        return mutable.value.laundrySessions.firstOrNull { it.sessionId == sessionId }?.phase in setOf(LaundryPhase.Running, LaundryPhase.Refunding, LaundryPhase.Ended, LaundryPhase.Closed)
    }
    private suspend fun restoreLaundrySessions(saved: List<LaundrySession>) {
        // A device check sends no order creation/payment command and needs no persistent progress card.
        val restored = saved.filterNot { it.orderNo.isBlank() &&
            (it.islandDismissed || it.phase == LaundryPhase.Checking && !it.createSent) }
            .map { session -> session.withCompletionObservation(now()).let {
                if (it.phase == LaundryPhase.Creating && it.createSent && it.orderNo.isBlank()) it.copy(creationUncertain = true) else it
            } }
        mutable.update { it.copy(laundry = if (!it.scanning && it.scannedSerial == null)
            restored.lastOrNull { session -> !session.islandDismissed } else it.laundry, laundrySessions = restored) }
        if (restored != saved) persistence.withLock { withContext(io) { store.saveLaundrySessions(restored) } }
    }
    private suspend fun createLaundrySession(session: LaundrySession, token: String, service: LifeLaundryService): LaundrySession {
        val creating = session.copy(phase = LaundryPhase.Creating, createSent = true)
        saveLaundrySession(creating) // Track a lost response without automatically repeating creation.
        val order = try { service.createLaundryOrder(session.device.id, session.skuId, token) }
        catch (e: LifeDeviceException) {
            if (e.businessRejected) removeLaundrySession(session.sessionId)
            throw e
        }
        return creating.copy(orderNo = order.orderNo, orderId = order.orderId, phase = LaundryPhase.Paying)
            .also { saveLaundrySession(it) }
    }
    private suspend fun removeLaundrySession(sessionId: String) {
        mutable.update { it.copy(laundrySessions = it.laundrySessions.filterNot { session -> session.sessionId == sessionId },
            laundry = it.laundry?.takeUnless { session -> session.sessionId == sessionId }) }
        sessionErrors.remove(sessionId)
        persistSessions()
    }
    private suspend fun saveLaundrySession(value: LaundrySession, select: Boolean = false) {
        currentCoroutineContext().ensureActive()
        if (value.sessionId in dismissedSessionIds) return
        val session = value.withCompletionObservation(now())
        // A response that finishes after manual dismissal must not restore the removed card.
        if (!select && mutable.value.laundrySessions.none { it.sessionId == session.sessionId }) return
        mutable.update {
            val selected = select || it.laundry?.sessionId == session.sessionId
            val records = if (it.laundrySessions.any { item -> item.sessionId == session.sessionId })
                it.laundrySessions.map { item -> if (item.sessionId == session.sessionId) session else item } else it.laundrySessions + session
            it.copy(laundry = if (selected) session else it.laundry, laundrySessions = records,
                device = if (selected && it.feature == LifeFeature.Laundry) session.device else it.device,
                scanning = if (selected && it.feature == LifeFeature.Laundry) false else it.scanning)
        }
        if (session.paid) paymentFeedback(session.sessionId, RechargePhase.Paid)
        else if (session.phase == LaundryPhase.Closed) {
            sessionError(LifeFeature.Laundry, session.sessionId, null)
            mutable.update { if (it.paymentFeedback?.sessionId == session.sessionId)
                it.copy(paymentFeedback = closedLaundryFeedback(session)) else it }
        }
        persistSessions()
    }
    private fun resumeLaundry() {
        if (!foreground || account == null || mutable.value.accountBusy || laundryTask?.isActive == true || laundryPollTask?.isActive == true) return
        if (mutable.value.laundrySessions.none { it.active && it.orderNo.isNotBlank() }) return
        val token = account!!.token
        val service = laundryApi ?: return
        laundryPollTask = viewModelScope.launch {
            while (isActive && foreground && account?.token == token) {
                val sessions = mutable.value.laundrySessions.filter { it.active && it.orderNo.isNotBlank() && it.orderNo != mutable.value.paymentLaunch?.orderNo }
                if (sessions.isEmpty()) break
                for (current in sessions) {
                    try {
                        readLaundryOrder(token, service, current.sessionId)
                        var latest = mutable.value.laundrySessions.firstOrNull { it.sessionId == current.sessionId }
                        if (latest?.phase == LaundryPhase.Paying && !latest.paid && service.laundryPaid(current.orderNo, current.payMethod, token)) {
                            saveLaundrySession(latest.copy(paid = true, phase = LaundryPhase.Starting))
                            latest = mutable.value.laundrySessions.firstOrNull { it.sessionId == current.sessionId }
                        }
                        if (latest?.phase == LaundryPhase.Starting && service.laundryStarted(current.orderNo, token))
                            saveLaundrySession(latest.copy(phase = LaundryPhase.Running, paid = true))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { sessionError(LifeFeature.Laundry, current.sessionId, failure(e)); if (expired(e)) return@launch }
                }
                delay(5000)
            }
        }
    }
    private fun requireLogin() {
        if (requestedFeature == null && pendingDeviceSerial == null) requestedFeature = mutable.value.feature
        mutable.update { it.copy(loginVisible = true, loginError = "请登录胖乖生活", code = "") }
    }
    private fun continueEntry(feature: LifeFeature) {
        val sessionId = requestedSessionId
        if (requestedScan) openScan()
        else if (sessionId != null) openSession(feature, sessionId)
        else open(feature)
    }
    private suspend fun expired(e: Exception, showLogin: Boolean = true): Boolean {
        if (e !is LifeDeviceException || !e.loginExpired) return false
        persistence.withLock {
            withContext(io) { store.clearAccount() }
            account = null
        }
        accountVerifier.expired(failure(e))
        mutable.update { it.copy(connectedPhone = "") }
        if (showLogin && visible) {
            requireLogin()
            mutable.update { it.copy(loginError = failure(e)) }
        }
        return true
    }
    private fun failure(e: Exception) = when (e) {
        is LifeDeviceException -> e.message ?: "操作失败，请重试"
        is com.mercury.yktwx.data.YktException -> "无法保存账号或服务进度，请检查设备存储"
        else -> "连接失败，请检查网络后重试"
    }
}
