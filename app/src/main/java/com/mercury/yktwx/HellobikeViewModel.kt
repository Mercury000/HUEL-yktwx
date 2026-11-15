package com.mercury.yktwx

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import com.mercury.yktwx.state.*
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.analytics.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class HellobikeUiState(
    val restoring: Boolean = true, val connectedPhone: String = "", val accountBusy: Boolean = false, val accountError: String? = null,
    val recoveryRequired: Boolean = false,
    val loginVisible: Boolean = false, val changingAccount: Boolean = false, val phone: String = "", val code: String = "",
    val sendingCode: Boolean = false, val loggingIn: Boolean = false, val codeSeconds: Int = 0, val loginError: String? = null,
    val sliderVerification: Boolean = false, val loginOfficialRequired: Boolean = false,
    val slider: HellobikeSlider? = null, val sliderLoading: Boolean = false, val sliderOffset: Int = 0,
    val region: HellobikeRegion? = null, val locating: Boolean = false,
    val locationError: String? = null, val locationPermissionRequest: Long? = null,
    val locationPermissionRequired: Boolean = false, val locationPermissionBlocked: Boolean = false,
    val scannedBikeNo: String? = null, val precheck: HellobikePrecheck? = null, val loadingDevice: Boolean = false,
    val sessions: List<HellobikeSession> = emptyList(), val selectedSessionId: String? = null, val confirmedSessions: Set<String> = emptySet(),
    val closeProof: HellobikeCloseProof? = null, val busy: Boolean = false, val error: String? = null, val officialRequired: Boolean = false,
    val paymentVisible: Boolean = false, val paymentLoading: Boolean = false, val payDetail: HellobikePayDetail? = null,
    val payOptions: List<HellobikePayOption> = emptyList(), val paymentFeedback: LifePaymentFeedback? = null,
    val paymentLaunch: LifePaymentLaunch? = null, val paymentInFlight: Boolean = false,
    val paymentMethod: String? = null,
    val paymentRestorePending: Boolean = false,
    val sessionErrors: Map<String, String> = emptyMap(),
) {
    val connected: Boolean get() = connectedPhone.isNotBlank()
    val session: HellobikeSession? get() = sessions.firstOrNull { it.sessionId == selectedSessionId }
}

/** Platform state stays separate; accounts, transport, payment bridges and presentation use shared app facilities. */
@HiltViewModel
class HellobikeViewModel internal constructor(
    private val api: HellobikeService, private val store: HellobikeStore, private val location: HellobikeLocationService,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    savedState: SavedStateHandle = SavedStateHandle(),
    private val analytics: AppAnalytics,
) : ViewModel() {
    @Inject internal constructor(api: HellobikeService, store: HellobikeStore, location: HellobikeLocationService, analytics: AppAnalytics, savedState: SavedStateHandle) :
        this(api, store, location, io = Dispatchers.IO, savedState = savedState, analytics = analytics)
    private var initialized = false
    private val dismissedRideGuids = mutableSetOf<String>()
    private val dismissedSessionIds = mutableSetOf<String>()
    private var preview = true
    private val mutable = MutableStateFlow(HellobikeUiState())
    val state = mutable.asStateFlow()
    private var pendingOverlay: LifeOverlaySavedState? = null
    private var restoreSlider = false
    private val overlays = LifeOverlayRestoration(savedState) {
        LifeOverlaySavedState(state.value.loginVisible, state.value.changingAccount, state.value.phone,
            state.value.sliderVerification, pendingOverlay?.payment == true || state.value.paymentVisible,
            pendingOverlay?.session ?: state.value.selectedSessionId, method = state.value.paymentMethod)
    }
    internal fun bindPresentation(campus: String, preview: Boolean, generation: Long) {
        if (state.value.restoring) return
        val restored = overlays.bind(campus, restorationOwner, preview, generation) {
            pendingOverlay = null; restoreSlider = false; dismissLogin(); dismissPayment(); mutable.update { it.copy(paymentMethod = null) }
        } ?: return
        loginGuid = UUID.randomUUID().toString(); challenge = null
        mutable.update { it.copy(loginVisible = restored.login, changingAccount = restored.changing, phone = restored.phone,
            code = "", sliderVerification = restored.verification && restored.login, slider = null, sliderOffset = 0,
            paymentMethod = restored.method, paymentRestorePending = restored.payment) }
        restoreSlider = restored.login && restored.verification
        pendingOverlay = restored.takeIf { it.payment }
    }
    internal fun resumePresentation(active: Boolean, online: Boolean, paymentPage: Boolean) {
        if (!active || !online || state.value.restoring) return
        if (restoreSlider && foreground) { restoreSlider = false; refreshSlider() }
        val restored = pendingOverlay ?: return
        if (paymentPage && restored.session != null && state.value.selectedSessionId == restored.session && foreground) {
            pendingOverlay = null; mutable.update { it.copy(paymentRestorePending = false) }; showPayment()
        }
    }
    internal fun selectPaymentMethod(method: String?) { mutable.update { it.copy(paymentMethod = method) } }
    internal fun discardRestoredPayment() { pendingOverlay = null; mutable.update { it.copy(paymentRestorePending = false) }; dismissPayment() }
    private var account: HellobikeAccount? = null
    private var ownerGuid = ""
    internal val restorationOwner: String? get() = ownerGuid.takeIf(String::isNotBlank)
    private var clientId = ""
    private var foreground = false
    private var visible = false
    internal val cards = HellobikeCardsController(viewModelScope, api, store, io, { account }, { state.value.region }, location,
        { !preview && !state.value.restoring && !state.value.accountBusy && !state.value.busy && !state.value.loggingIn && !state.value.paymentInFlight },
        ::expired, now, { requirePermission(LocationAction.Cards, openSettings = state.value.locationPermissionBlocked) })
    private var deviceGeneration = 0L
    private var deviceSelection = 0L
    private var authGeneration = 0L
    private var pendingBike: String? = null
    private var challenge: String? = null
    private var loginGuid = ""
    private var permissionCounter = 0L
    private var permissionClaimed: Long? = null
    private var locationSettingsPending = false
    private var locationSettingsRequested = false
    private enum class LocationAction { Prepare, Close, Cards }
    private var pendingLocationAction: LocationAction? = null
    private data class LocationPermissionOwner(val account: HellobikeAccount, val selection: Long, val bikeNo: String?, val sessionId: String?)
    private var locationPermissionOwner: LocationPermissionOwner? = null
    private val persistence = Mutex()
    private val pendingScanRecords = mutableMapOf<String, Pair<HellobikeAccount, HellobikeSession>>()
    private val errors = mutableMapOf<String, String>()
    private var restoreTask: Job? = null
    private var authTask: Job? = null
    private var timerTask: Job? = null
    private var deviceTask: Job? = null
    private var commandTask: Job? = null
    private var recoveryTask: Job? = null
    private var pollTask: Job? = null
    private var accountTask: Job? = null
    private var paymentCounter = 0L
    private data class PaymentOwner(val account: HellobikeAccount, val sessionId: String, val requestId: Long)
    private var launchedPayment: PaymentOwner? = null
    private var paymentAnalyticsAttempt: AnalyticsAttempt? = null
    private val verifier = LifeAccountVerifier(viewModelScope, now, { account }, { owner: HellobikeAccount ->
        val reading = api.checkRide(owner)
        if (reading is HellobikeRideReading.Ride && reading.userGuid != null && reading.userGuid != owner.userGuid)
            throw HellobikeException("哈啰响应的账户标识与当前账户不一致")
    }, ::expired, ::failure)
    val accountConnection = verifier.state

    // The activity's accepted preview mode is known before any account storage is read.
    fun initialize(preview: Boolean) {
        if (initialized) return
        initialized = true
        this.preview = preview
        if (preview) mutable.value = HellobikeUiState(restoring = false)
        else restoreTask = viewModelScope.launch {
            try {
                val saved = withContext(io) { store.load() }
                // The official value is server-issued, with an empty native fallback.
                // Older local UUIDs have no issuance evidence and must not be sent as clientId.
                clientId = ""
                account = saved.account; ownerGuid = saved.ownerGuid
                cards.bind(saved.cardPurchases[saved.ownerGuid])
                verifier.restored(saved.maskedPhone)
                if (saved.account == null && saved.maskedPhone.isNotBlank()) verifier.expired()
                mutable.update { it.copy(connectedPhone = saved.account?.maskedPhone.orEmpty(), sessions = saved.sessions,
                    region = saved.region,
                    recoveryRequired = saved.recoveryRequired) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(error = failure(e), accountError = failure(e)) }
                if (e is HellobikeException && e.storageFailure) {
                    clientId = ""
                    try {
                        withContext(io) { store.recoverUnreadable(clientId) }
                        mutable.update { it.copy(recoveryRequired = true) }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (saveError: Exception) { mutable.update { it.copy(accountError = failure(saveError)) } }
                }
            }
            finally { mutable.update { it.copy(restoring = false) } }
            resume()
            if (visible && account != null) pendingBike?.let(::prepareBike)
        }
    }
    fun checkAccount(force: Boolean = false) {
        if (foreground && !mutable.value.restoring && !mutable.value.loggingIn && !mutable.value.accountBusy) verifier.check(force)
    }
    fun environment(active: Boolean) {
        val allowed = active && !preview
        if (foreground == allowed) return
        foreground = allowed
        cards.environment(allowed)
        if (allowed) {
            // Permission may have changed to "ask every time" while outside the app.
            if (mutable.value.locationPermissionRequest == null) mutable.update { it.copy(locationPermissionBlocked = false) }
            resume(); checkAccount()
        }
        else {
            verifier.stop(); pollTask?.cancel(); recoveryTask?.cancel(); deviceTask?.cancel(); commandTask?.cancel()
            authTask?.cancel(); authGeneration++
            if (!locationSettingsPending && permissionClaimed == null) pendingLocationAction = null
            mutable.update { it.copy(confirmedSessions = emptySet(), closeProof = null, precheck = null,
                loadingDevice = false, locating = false, loggingIn = false, sendingCode = false, sliderLoading = false,
                locationPermissionRequest = if (locationSettingsPending || permissionClaimed != null) it.locationPermissionRequest else null, paymentLoading = false) }
        }
    }
    fun pageVisible(active: Boolean) {
        visible = active
        if (!active) {
            pendingBike = null; deviceGeneration++; deviceTask?.cancel(); commandTask?.cancel()
            if (pendingLocationAction != LocationAction.Cards && !locationSettingsPending && permissionClaimed == null) pendingLocationAction = null
            mutable.update { it.copy(loadingDevice = false, closeProof = null,
                locating = false, locationPermissionRequest = if (pendingLocationAction == LocationAction.Cards || locationSettingsPending || permissionClaimed != null) it.locationPermissionRequest else null) }
        } else if (!mutable.value.restoring && account != null && foreground && mutable.value.locationPermissionRequest == null) {
            if (mutable.value.session != null) refreshState()
            else if (mutable.value.precheck == null) pendingBike?.let(::prepareBike)
        }
    }
    fun connectAccount(clearPending: Boolean = true) {
        if (preview || mutable.value.restoring || mutable.value.accountBusy || mutable.value.busy || mutable.value.loggingIn) return
        if (clearPending) pendingBike = null
        loginGuid = UUID.randomUUID().toString(); challenge = null
        mutable.update { it.copy(loginVisible = true, changingAccount = false, phone = "", code = "", slider = null,
            sliderVerification = false, loginOfficialRequired = false, sliderOffset = 0, loginError = null, locationError = null) }
    }
    fun manageLogin(change: Boolean = false): Boolean {
        if (mutable.value.restoring || mutable.value.accountBusy || mutable.value.busy || mutable.value.loggingIn || mutable.value.paymentInFlight || cards.occupied) return false
        connectAccount(); mutable.update { it.copy(changingAccount = change) }; return true
    }
    fun dismissLogin() {
        restoreSlider = false
        authGeneration++; authTask?.cancel(); challenge = null; pendingLocationAction = null
        permissionClaimed = null; locationSettingsPending = false; locationSettingsRequested = false; locationPermissionOwner = null
        mutable.update { it.copy(loginVisible = false, loggingIn = false, sendingCode = false, sliderLoading = false,
            sliderVerification = false, loginOfficialRequired = false, slider = null, sliderOffset = 0, code = "", loginError = null, locating = false, locationPermissionRequest = null) }
    }
    fun phoneChanged(value: String) {
        if (mutable.value.loggingIn || mutable.value.sendingCode || mutable.value.sliderLoading) return
        challenge = null
        mutable.update { it.copy(phone = value.filter { c -> c in '0'..'9' }.take(11), code = "", sliderVerification = false, loginOfficialRequired = false, slider = null, sliderOffset = 0, loginError = null) }
    }
    fun codeChanged(value: String) {
        if (!mutable.value.loggingIn) mutable.update { it.copy(code = value.filter { c -> c in '0'..'9' }.take(8), loginError = null) }
    }
    private suspend fun persistRegion(region: HellobikeRegion) {
        if (!region.valid) throw HellobikeException("所在地区无效，请重新获取位置")
        persistence.withLock { withContext(io) { store.saveRegion(region) }; currentCoroutineContext().ensureActive()
            mutable.update { it.copy(region = region, locationError = null) } }
    }
    private fun requirePermission(action: LocationAction, openSettings: Boolean = false) {
        val owner = account ?: return
        if (mutable.value.locationPermissionRequest != null || !foreground ||
            (if (action == LocationAction.Cards) !cards.canRequestOrdinaryLocation else !visible)) return
        pendingLocationAction = action; permissionClaimed = null
        locationSettingsRequested = openSettings
        locationPermissionOwner = LocationPermissionOwner(owner, deviceSelection, mutable.value.scannedBikeNo, mutable.value.selectedSessionId)
        mutable.update { it.copy(locationPermissionRequest = ++permissionCounter) }
    }
    fun locationSettingsOpening(id: Long): Boolean {
        if (mutable.value.locationPermissionRequest != id || !locationSettingsRequested) return false
        locationSettingsPending = true
        return true
    }
    fun claimLocationPermission(id: Long): Boolean {
        if (!foreground || mutable.value.locationPermissionRequest != id || permissionClaimed == id) return false
        permissionClaimed = id; return true
    }
    fun locationPermissionReturned(id: Long, granted: Boolean, permanentlyDenied: Boolean = false) {
        if (mutable.value.locationPermissionRequest != id) return
        val owner = locationPermissionOwner; locationPermissionOwner = null
        val action = pendingLocationAction; pendingLocationAction = null; permissionClaimed = null
        locationSettingsPending = false; locationSettingsRequested = false
        mutable.update { it.copy(locationPermissionRequest = null) }
        if (action == null || owner == null || owner.account != account || when (action) {
            LocationAction.Prepare -> owner.selection != deviceSelection || owner.bikeNo != mutable.value.scannedBikeNo || mutable.value.session != null
            LocationAction.Close -> owner.selection != deviceSelection || owner.sessionId != mutable.value.selectedSessionId || mutable.value.session?.phase != HellobikePhase.Riding
            LocationAction.Cards -> !cards.canRequestOrdinaryLocation
        }) return
        mutable.update { it.copy(locationPermissionRequest = null,
            locationPermissionRequired = if (action == LocationAction.Cards && !granted) it.locationPermissionRequired else !granted,
            locationPermissionBlocked = !granted && permanentlyDenied) }
        if (!granted) {
            if (action != LocationAction.Cards) mutable.update { it.copy(locationError =
                if (permanentlyDenied) "定位未授权，请在系统设置中允许位置访问" else "定位未授权，请允许位置访问后重试",
                error = "定位未授权，暂时无法操作车辆") }
            return
        }
        if (!foreground) return
        mutable.update { it.copy(locationError = null, error = if (action == LocationAction.Cards) it.error else null) }
        when (action) {
            LocationAction.Prepare -> if (visible) mutable.value.scannedBikeNo?.let(::prepareBike)
            LocationAction.Close -> if (visible) checkClose()
            LocationAction.Cards -> cards.refresh()
        }
    }
    fun retryDeviceCheck() {
        if (mutable.value.loadingDevice || mutable.value.busy || mutable.value.accountBusy || mutable.value.locationPermissionRequest != null) return
        if (mutable.value.locationPermissionRequired) {
            requirePermission(if (mutable.value.session?.phase == HellobikePhase.Riding) LocationAction.Close else LocationAction.Prepare,
                openSettings = mutable.value.locationPermissionBlocked)
        } else if (mutable.value.session != null) refreshState()
        else mutable.value.scannedBikeNo?.let(::prepareBike)
    }
    fun sliderOffsetChanged(value: Int) {
        if (!mutable.value.sendingCode && !mutable.value.sliderLoading) mutable.update { it.copy(sliderOffset = value.coerceIn(0, it.slider?.maximum ?: 0)) }
    }
    fun cancelSlider() {
        if (mutable.value.sendingCode || mutable.value.loggingIn) return
        authGeneration++; authTask?.cancel(); challenge = null
        mutable.update { it.copy(sliderVerification = false, sliderLoading = false, slider = null,
            sliderOffset = 0, loginError = null, loginOfficialRequired = false) }
    }
    fun refreshSlider() {
        val value = mutable.value; val region = value.region
        if (!foreground || !value.loginVisible || !value.sliderVerification || value.sendingCode || value.loggingIn || value.sliderLoading) return
        val request = ++authGeneration
        mutable.update { it.copy(sliderVerification = true, sliderLoading = true, slider = null, sliderOffset = 0, loginError = null) }
        authTask = viewModelScope.launch {
            try {
                val slider = api.slider(value.phone, region, clientId)
                ensureActive(); if (request != authGeneration) return@launch
                mutable.update { it.copy(slider = slider) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == authGeneration) mutable.update { it.copy(loginError = failure(e)) } }
            finally { if (request == authGeneration) mutable.update { it.copy(sliderLoading = false) } }
        }
    }
    fun sendCode(submitSlider: Boolean = false) {
        val value = mutable.value; val region = value.region
        if (!foreground || !value.loginVisible || value.loggingIn || value.sendingCode || value.sliderLoading || value.codeSeconds > 0) return
        if (!value.phone.matches(Regex("1[0-9]{10}"))) {
            mutable.update { it.copy(loginError = "请输入正确的手机号") }; return
        }
        if (submitSlider != value.sliderVerification || submitSlider && value.slider == null) return
        val request = ++authGeneration
        mutable.update { it.copy(sendingCode = true, loginError = null, loginOfficialRequired = false) }
        authTask = viewModelScope.launch {
            try {
                val result = api.sendCode(value.phone, region, clientId, value.sliderOffset.takeIf { submitSlider }, challenge)
                ensureActive(); if (request != authGeneration) return@launch
                if (result.sliderRequired) {
                    challenge = result.challenge
                    mutable.update { it.copy(sliderVerification = true, sliderLoading = true, slider = null, sliderOffset = 0,
                        loginError = "请拖动滑块完成验证".takeIf { submitSlider }) }
                    val slider = api.slider(value.phone, region, clientId)
                    ensureActive(); if (request != authGeneration) return@launch
                    mutable.update { it.copy(slider = slider) }
                } else {
                    challenge = null
                    mutable.update { it.copy(sliderVerification = false, slider = null, sliderOffset = 0, codeSeconds = 60) }
                    timerTask?.cancel()
                    timerTask = viewModelScope.launch { while (mutable.value.codeSeconds > 0) {
                        delay(1000); mutable.update { it.copy(codeSeconds = (it.codeSeconds - 1).coerceAtLeast(0)) }
                    } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == authGeneration) mutable.update { it.copy(loginError = failure(e), loginOfficialRequired = (e as? HellobikeException)?.officialRequired == true, sliderOffset = 0) }
            } finally { if (request == authGeneration) mutable.update { it.copy(sendingCode = false, sliderLoading = false) } }
        }
    }
    fun login() {
        val value = mutable.value; val region = value.region
        if (!foreground || !value.loginVisible || value.sliderVerification || value.loggingIn || value.sendingCode || value.accountBusy || value.busy || cards.occupied) return
        if (!value.phone.matches(Regex("1[0-9]{10}")) || !value.code.matches(Regex("[0-9]{4,8}"))) {
            mutable.update { it.copy(loginError = "请输入手机号和短信验证码") }; return
        }
        val request = ++authGeneration
        val analyticsAttempt = analytics.begin()
        mutable.update { it.copy(loggingIn = true, loginError = null, loginOfficialRequired = false) }
        authTask = viewModelScope.launch {
            try {
                val next = api.login(value.phone, value.code, region, clientId, loginGuid.ifBlank { UUID.randomUUID().toString() })
                ensureActive(); if (request != authGeneration) return@launch
                if (next.phone != value.phone || next.clientId != clientId) throw HellobikeException("登录账户信息不一致，请重试")
                val tasks = listOfNotNull(deviceTask, recoveryTask, pollTask)
                tasks.forEach { it.cancel() }; tasks.joinAll(); verifier.reset(); deviceGeneration++
                cards.stopAndJoin()
                withContext(NonCancellable) {
                    persistence.withLock {
                        if (request != authGeneration || mutable.value.accountBusy) throw CancellationException("Login superseded before commit")
                        mutable.update { it.copy(accountBusy = true) }
                        try {
                            val saved = withContext(io) { store.saveAccount(next); store.load() }
                            account = next; ownerGuid = next.userGuid
                            errors.clear(); launchedPayment = null; paymentAnalyticsAttempt = null; pendingOverlay = null; restoreSlider = false
                            mutable.update { it.copy(connectedPhone = next.maskedPhone, loginVisible = false, changingAccount = false,
                                loggingIn = false, code = "", sliderVerification = false, slider = null, sessions = saved.sessions, confirmedSessions = emptySet(),
                                selectedSessionId = it.selectedSessionId?.takeIf { id -> saved.sessions.any { session -> session.sessionId == id } },
                                precheck = null, closeProof = null, error = null, officialRequired = false, accountError = null,
                                paymentLaunch = null, paymentInFlight = false, paymentVisible = false, paymentFeedback = null, payDetail = null, payOptions = emptyList(),
                                paymentRestorePending = false, paymentMethod = null,
                                recoveryRequired = saved.recoveryRequired) }
                            cards.bind(saved.cardPurchases[next.userGuid])
                            verifier.verified(next.maskedPhone)
                            analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Hellobike)
                        } finally { mutable.update { it.copy(accountBusy = false) } }
                    }
                }
                ensureActive()
                resume()
                if (visible) pendingBike?.let(::prepareBike)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request == authGeneration) {
                    analytics.loginResult(analyticsAttempt, AnalyticsPlatform.Hellobike, AnalyticsError.from(e))
                    mutable.update { it.copy(loggingIn = false, loginError = failure(e), loginOfficialRequired = (e as? HellobikeException)?.officialRequired == true) }
                }
            }
        }
    }
    fun removeAccount(): Boolean {
        if (preview || mutable.value.accountBusy) return false
        mutable.update { it.copy(accountBusy = true, accountError = null, loginVisible = false, paymentLaunch = null) }
        deviceGeneration++; authGeneration++; verifier.stop(); launchedPayment = null; paymentAnalyticsAttempt = null; pendingLocationAction = null
        permissionClaimed = null; locationSettingsPending = false; locationSettingsRequested = false; locationPermissionOwner = null
        val tasks = listOfNotNull(restoreTask, authTask, timerTask, deviceTask, commandTask, recoveryTask, pollTask)
        tasks.forEach { it.cancel() }
        accountTask = viewModelScope.launch {
            try {
                tasks.joinAll()
                cards.stopAndJoin()
                persistence.withLock { withContext(io) { store.removeAccount() }; account = null; ownerGuid = "" }
                cards.bind(null)
                pendingBike = null; pendingOverlay = null; restoreSlider = false
                clientId = ""; challenge = null; errors.clear(); verifier.cleared()
                mutable.value = HellobikeUiState(restoring = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(accountError = failure(e)) } }
            finally { mutable.update { it.copy(accountBusy = false) } }
        }
        return true
    }
    fun openDevice(bikeNo: String) {
        if (!HellobikeDeviceCode.validIdentifier(bikeNo) || mutable.value.busy || mutable.value.accountBusy || mutable.value.paymentInFlight) return
        pendingBike = bikeNo; deviceSelection++; deviceGeneration++; deviceTask?.cancel()
        mutable.update { it.copy(scannedBikeNo = bikeNo, selectedSessionId = null, precheck = null, closeProof = null,
            loadingDevice = false, locationPermissionRequired = false, locationPermissionBlocked = false,
            error = null, officialRequired = false, paymentVisible = false, paymentFeedback = null, payDetail = null, payOptions = emptyList()) }
        if (!mutable.value.restoring && account != null && foreground && visible) prepareBike(bikeNo)
    }
    fun openSession(id: String) {
        if (mutable.value.busy || mutable.value.accountBusy || mutable.value.paymentInFlight || mutable.value.sessions.none { it.sessionId == id }) return
        pendingBike = null; deviceSelection++; deviceGeneration++; deviceTask?.cancel()
        mutable.update { it.copy(selectedSessionId = id, scannedBikeNo = null, precheck = null, closeProof = null,
            loadingDevice = false, locationPermissionRequired = false, error = errors[id], paymentVisible = false, paymentFeedback = null, payDetail = null, payOptions = emptyList()) }
        if (visible && foreground) refreshState()
    }
    private fun prepareBike(bikeNo: String) {
        val owner = account ?: return
        if (!foreground || !visible || mutable.value.restoring || mutable.value.busy || mutable.value.loadingDevice || mutable.value.accountBusy || mutable.value.loggingIn) return
        val request = ++deviceGeneration
        mutable.update { it.copy(loadingDevice = true, precheck = null, closeProof = null, error = null, officialRequired = false) }
        deviceTask = viewModelScope.launch {
            var scanStarted = false
            var scanRecord: HellobikeSession? = null
            try {
                val point = freshPosition(); val region = operationRegion(point)
                ensureOwner(owner); if (request != deviceGeneration || !visible) return@launch
                mutable.update { it.copy(locationPermissionRequired = false, locationPermissionBlocked = false) }
                // Pre-ride can itself start the server unlock. Reserve a durable local
                // recovery handle before sending it, without showing an invented ride.
                val reserved = HellobikeSession("", bikeNo, region, HellobikePhase.Opening, now(), uncertain = true)
                scanRecord = reserved
                reserveScan(owner, reserved)
                supervisorScope {
                    // The official page loads basic info independently of pre-ride.
                    // A slow bill must not delay canSkip or an ordinary confirmation.
                    val info = async { api.inspectBike(owner, bikeNo, region, point) }
                    currentCoroutineContext().ensureActive()
                    scanStarted = true
                    val prepared = api.prepare(owner, bikeNo, region, point)
                    if (prepared.serverUnlockStarted) {
                        saveSession(owner, reserved.copy(rideGuid = prepared.orderGuid, bikeNo = prepared.bikeNo,
                            priceRule = prepared.priceRule, uncertain = false), select = request == deviceGeneration && visible)
                    } else releaseScan(owner, reserved.sessionId)
                    ensureOwner(owner)
                    if (request != deviceGeneration || !visible) return@supervisorScope
                    mutable.update { it.copy(precheck = prepared, region = region, loadingDevice = false) }
                    if (prepared.skipConfirmation) {
                        info.cancel()
                        // Only the official canSkip response enters automatic confirmation.
                        startRide(prepared)
                        return@supervisorScope
                    }
                    try {
                        val page = info.await()
                        ensureOwner(owner)
                        if (request == deviceGeneration && visible) mutable.update { state -> state.copy(
                            precheck = state.precheck?.copy(priceRule = page.priceRule.ifBlank { prepared.priceRule },
                                notice = prepared.notice ?: page.notice)) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        if (request == deviceGeneration && account == owner) { report(e); expired(e, owner) }
                    }
                }
            } catch (e: HellobikeLocationPermissionRequired) {
                mutable.update { it.copy(locationPermissionRequired = true, error = "需要定位权限才能检查车辆") }
                requirePermission(LocationAction.Prepare)
            }
            catch (e: CancellationException) {
                scanRecord?.let { retainScanAfterFailure(owner, it.sessionId, scanStarted, select = false) }
                throw e
            }
            catch (e: Exception) {
                val uncertain = scanStarted && (e !is HellobikeException || e.rideMayHaveStarted ||
                    !e.rejected && !e.officialRequired && !e.loginExpired)
                scanRecord?.let { retainScanAfterFailure(owner, it.sessionId, uncertain, select = request == deviceGeneration && visible) }
                if (request == deviceGeneration && account == owner) { report(e); expired(e, owner) }
            }
            finally { if (request == deviceGeneration) mutable.update { it.copy(loadingDevice = false) } }
        }
    }
    fun startRide(confirmedPrecheck: HellobikePrecheck? = mutable.value.precheck) {
        val owner = account ?: return; val value = mutable.value; val precheck = value.precheck ?: return
        if (confirmedPrecheck != precheck) return
        if (!canCommand() || !precheck.fresh(elapsed())) {
            if (!precheck.fresh(elapsed())) { mutable.update { it.copy(precheck = null, error = "车辆检查数据不完整，请重新检查") } }
            return
        }
        val analyticsAttempt = analytics.begin()
        runCommand(owner) {
            // Consume the explicit confirmation, or the official canSkip response,
            // before starting any command. A retry must not replay a create request.
            mutable.update { it.copy(precheck = null) }
            val point = if (precheck.serverUnlockStarted) precheck.position else freshPosition()
            val region = if (precheck.serverUnlockStarted) precheck.region else operationRegion(point)
            ensureOwner(owner); ensureCommandVisible()
            val prepared = precheck.copy(region = region, position = point)
            // The official H5 create obtains rideId in its response; a pre-ride ID is
            // not a created order. Persist a local attempt with no invented cloud ID.
            var pending = saveSession(owner, HellobikeSession(
                if (prepared.serverUnlockStarted) prepared.orderGuid else "", prepared.bikeNo, region,
                HellobikePhase.Opening, now(), priceRule = prepared.priceRule), select = true)
            ensureOwner(owner); ensureCommandVisible()
            analytics.submitted(analyticsAttempt, AnalyticsFeature.HellobikeRide)
            try {
                if (!prepared.serverUnlockStarted) {
                    val rideGuid = api.create(owner, prepared, point)
                    ensureOwner(owner)
                    pending = saveSession(owner, pending.copy(rideGuid = rideGuid), select = true)
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HellobikeException && (e.loginExpired || e.officialRequired)) {
                    analytics.operationFailed(analyticsAttempt, AnalyticsFeature.HellobikeRide, AnalyticsError.from(e)); throw e
                }
                // A concurrent/previously accepted unlock can be reported as a create rejection.
                // Only this exact pending order's query can turn it into continued progress.
                try { pending = readOpening(owner, pending) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    analytics.operationFailed(analyticsAttempt, AnalyticsFeature.HellobikeRide, AnalyticsError.from(e)); throw e
                }
                val observed = mutable.value.sessions.firstOrNull { it.sessionId == pending.sessionId }
                if (observed == null || observed.rideGuid.isBlank() || observed.uncertain) {
                    analytics.operationFailed(analyticsAttempt, AnalyticsFeature.HellobikeRide, AnalyticsError.from(e)); throw e
                }
            }
            ensureOwner(owner)
            mutable.update { it.copy(precheck = null) }
            pollCommand(owner, pending.sessionId)
        }
    }
    fun checkClose() {
        val owner = account ?: return; val session = mutable.value.session ?: return
        if (!canCommand() || session.phase != HellobikePhase.Riding || mutable.value.loadingDevice) return
        val request = ++deviceGeneration
        mutable.update { it.copy(loadingDevice = true, closeProof = null, error = null, officialRequired = false) }
        deviceTask?.cancel()
        deviceTask = viewModelScope.launch {
            try {
                readSession(owner, session.sessionId)
                if (request != deviceGeneration || mutable.value.session?.phase != HellobikePhase.Riding || session.sessionId !in mutable.value.confirmedSessions) return@launch
                val deadline = elapsed() + 20_000
                var continuation = false
                while (true) {
                    val point = freshPosition(); val region = operationRegion(point)
                    val current = mutable.value.sessions.firstOrNull { it.sessionId == session.sessionId } ?: return@launch
                    val reading = api.preClose(owner, current, point, region, poll = continuation, commit = false)
                    ensureOwner(owner); if (request != deviceGeneration || !visible) return@launch
                    if (reading.completed) { finishRide(owner, current); break }
                    if (reading.ready) {
                        saveSession(owner, current.copy(region = region))
                        mutable.update { it.copy(closeProof = HellobikeCloseProof(session.sessionId, elapsed(), region, point, reading.detail)) }; break
                    }
                    if (!reading.processing || elapsed() >= deadline) throw HellobikeException(reading.detail ?: "还车位置校验较慢，请稍后重新校验")
                    continuation = true; delay(1500)
                }
            } catch (e: HellobikeLocationPermissionRequired) {
                mutable.update { it.copy(locationPermissionRequired = true, error = "需要定位权限才能检查还车位置") }
                requirePermission(LocationAction.Close)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == deviceGeneration && account == owner) { report(e); expired(e, owner) } }
            finally { if (request == deviceGeneration) mutable.update { it.copy(loadingDevice = false) } }
        }
    }
    fun dismissClose() { mutable.update { it.copy(closeProof = null) } }
    fun commitClose() {
        val owner = account ?: return; val session = mutable.value.session ?: return; val proof = mutable.value.closeProof ?: return
        if (!canCommand() || session.phase != HellobikePhase.Riding || proof.sessionId != session.sessionId) return
        if (!proof.fresh(elapsed())) { mutable.update { it.copy(closeProof = null, error = "还车校验数据不完整，请重新校验") }; return }
        runCommand(owner) {
            val point = freshPosition()
            readSession(owner, session.sessionId)
            val current = mutable.value.sessions.firstOrNull { it.sessionId == session.sessionId } ?: return@runCommand
            if (current.phase != HellobikePhase.Riding || current.sessionId !in mutable.value.confirmedSessions)
                throw HellobikeException("骑行状态已变化，请刷新订单")
            saveSession(owner, current.copy(phase = HellobikePhase.Closing, uncertain = false, attemptedAtMillis = now()))
            mutable.update { it.copy(closeProof = null) }; ensureOwner(owner); ensureCommandVisible()
            // A close is issued once. Running/unknown responses cannot reopen this command after submission.
            val result = api.preClose(owner, current, point, proof.region, poll = true, commit = true)
            ensureOwner(owner)
            if (result.completed) finishRide(owner, current) else pollCommand(owner, current.sessionId)
        }
    }
    fun refreshState() {
        val owner = account ?: return
        if (!foreground || mutable.value.restoring || mutable.value.busy || mutable.value.accountBusy || mutable.value.loadingDevice || mutable.value.paymentInFlight) return
        val session = mutable.value.session
        if (session == null) { pendingBike?.let(::prepareBike); return }
        val request = ++deviceGeneration
        mutable.update { it.copy(loadingDevice = true, error = null, officialRequired = false) }
        deviceTask?.cancel()
        deviceTask = viewModelScope.launch {
            try { readSession(owner, session.sessionId) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (request == deviceGeneration && account == owner) { report(e, session.sessionId); expired(e, owner) } }
            finally { if (request == deviceGeneration) mutable.update { it.copy(loadingDevice = false) } }
        }
    }
    private fun canCommand(): Boolean = foreground && visible && !mutable.value.restoring && !mutable.value.accountBusy &&
        !mutable.value.busy && !mutable.value.loadingDevice && !mutable.value.locating && !mutable.value.loggingIn && !mutable.value.paymentInFlight && !cards.occupied && account != null
    private fun runCommand(owner: HellobikeAccount, requirePage: Boolean = true, block: suspend () -> Unit) {
        val value = mutable.value
        if ((!canCommand() && requirePage) || !foreground || value.restoring || value.busy || value.accountBusy ||
            value.loggingIn || value.paymentInFlight || cards.occupied || commandTask?.isActive == true) return
        val previousReads = listOfNotNull(pollTask, deviceTask, recoveryTask)
        previousReads.forEach { it.cancel() }
        mutable.update { it.copy(busy = true, error = null, officialRequired = false) }
        commandTask = viewModelScope.launch {
            try { previousReads.joinAll(); ensureOwner(owner); if (requirePage) ensureCommandVisible(); block() }
            catch (e: CancellationException) {
                if (account == owner && !mutable.value.accountBusy) markUncertain(owner)
                throw e
            }
            catch (e: Exception) {
                if (account == owner) { markUncertain(owner); report(e, mutable.value.selectedSessionId); expired(e, owner) }
            } finally {
                commandTask = null
                if (account == owner) mutable.update { it.copy(busy = false, loadingDevice = false, paymentLoading = false) }
                resume(wait = true)
            }
        }
    }
    private suspend fun markUncertain(owner: HellobikeAccount) {
        val session = mutable.value.session ?: return
        if (session.phase in setOf(HellobikePhase.Opening, HellobikePhase.Closing)) {
            try { saveSession(owner, session.copy(uncertain = true)) } catch (_: Exception) { /* The pre-command record remains recoverable. */ }
        }
        if (session.paymentAttempt != null && !session.settled) feedback(session.sessionId, RechargePhase.Checking,
            detail = "支付结果尚未确认，请查询原订单")
    }
    private fun ensureCommandVisible() {
        if (!foreground || !visible) throw CancellationException("Device page left before command submission")
    }
    private suspend fun freshPosition(): HellobikePosition {
        val point = try { location.current() }
        catch (e: HellobikeLocationPermissionRequired) {
            mutable.update { it.copy(locationPermissionRequired = true) }
            throw e
        }
        currentCoroutineContext().ensureActive()
        if (!point.fresh(elapsed())) throw HellobikeException("定位数据不完整，请重新获取位置")
        mutable.update { it.copy(locationPermissionRequired = false, locationPermissionBlocked = false) }
        return point
    }
    private suspend fun operationRegion(point: HellobikePosition): HellobikeRegion {
        val region = api.region(point)
        persistRegion(region); return region
    }
    private suspend fun ensureOwner(owner: HellobikeAccount) {
        currentCoroutineContext().ensureActive()
        if (account != owner || mutable.value.accountBusy) throw CancellationException("Account changed")
    }
    private suspend fun reserveScan(owner: HellobikeAccount, session: HellobikeSession) = withContext(NonCancellable) {
        persistence.withLock {
            if (account != owner || mutable.value.accountBusy) throw CancellationException("Account changed")
            val list = sessionsWithPending(owner.userGuid, mutable.value.sessions + session)
            withContext(io) { store.saveSessions(owner.userGuid, list) }
            pendingScanRecords[session.sessionId] = owner to session
        }
    }
    private fun sessionsWithPending(ownerGuid: String, sessions: List<HellobikeSession>, exclude: String? = null): List<HellobikeSession> {
        val ids = sessions.mapTo(mutableSetOf()) { it.sessionId }
        return sessions + pendingScanRecords.values.filter { it.first.userGuid == ownerGuid && it.second.sessionId != exclude &&
            it.second.sessionId !in ids }.map { it.second }
    }
    private suspend fun releaseScan(owner: HellobikeAccount, id: String) = withContext(NonCancellable) {
        persistence.withLock {
            if (pendingScanRecords[id]?.first != owner) return@withLock
            val list = sessionsWithPending(owner.userGuid, mutable.value.sessions, exclude = id)
            if (account == owner) withContext(io) { store.saveSessions(owner.userGuid, list) }
            pendingScanRecords.remove(id)
        }
    }
    private suspend fun retainScanAfterFailure(owner: HellobikeAccount, id: String, uncertain: Boolean, select: Boolean) = withContext(NonCancellable) {
        val pending = pendingScanRecords[id]?.takeIf { it.first == owner }?.second ?: return@withContext
        try {
            if (!uncertain) releaseScan(owner, id)
            else persistence.withLock {
                if (account == owner && !mutable.value.accountBusy) {
                    // This exact record was already committed by reserveScan and kept
                    // in every intervening write. Publishing it needs no second write.
                    mutable.update { it.copy(sessions = if (it.sessions.any { s -> s.sessionId == id }) it.sessions else it.sessions + pending,
                        selectedSessionId = if (select) id else it.selectedSessionId) }
                }
                pendingScanRecords.remove(id)
            }
            // During an account switch, the already committed record remains on disk
            // for the old owner; switchAccount reloads those records after joining us.
        } catch (e: Exception) { if (account == owner && !mutable.value.accountBusy) report(e) }
    }
    /** Publish only after a successful disk commit. Non-cancellable writes keep the pending record visible on cancellation. */
    private suspend fun saveSession(owner: HellobikeAccount, session: HellobikeSession, select: Boolean = false): HellobikeSession = withContext(NonCancellable) {
        persistence.withLock {
            if (account != owner || mutable.value.accountBusy) throw CancellationException("Account changed")
            if (session.sessionId in dismissedSessionIds || session.rideGuid.isNotBlank() && session.rideGuid in dismissedRideGuids)
                return@withLock session
            val sameRide = session.rideGuid.takeIf(String::isNotBlank)?.let { guid ->
                mutable.value.sessions.firstOrNull { it.rideGuid == guid && it.sessionId != session.sessionId }
            }
            val existing = sameRide ?: mutable.value.sessions.firstOrNull { it.sessionId == session.sessionId }
            val committed = when {
                existing?.phase == HellobikePhase.Ended && session.phase != HellobikePhase.Ended -> existing
                sameRide == null -> session
                sameRide.phase != HellobikePhase.Opening -> sameRide
                else -> session.copy(sessionId = sameRide.sessionId, startedAtMillis = sameRide.startedAtMillis,
                    attemptedAtMillis = sameRide.attemptedAtMillis, priceRule = session.priceRule.ifBlank { sameRide.priceRule })
            }
            val list = mutable.value.sessions.let { old ->
                val remaining = old.filterNot { it.sessionId == session.sessionId && it.sessionId != committed.sessionId }
                if (remaining.none { it.sessionId == committed.sessionId }) remaining + committed
                else remaining.map { if (it.sessionId == committed.sessionId) committed else it }
            }
            val stored = sessionsWithPending(owner.userGuid, list, exclude = session.sessionId)
            withContext(io) { store.saveSessions(owner.userGuid, stored) }
            pendingScanRecords.remove(session.sessionId)
            mutable.update { it.copy(sessions = list,
                selectedSessionId = if (select || it.selectedSessionId == session.sessionId) committed.sessionId else it.selectedSessionId) }
            committed
        }
    }
    fun dismissSession(id: String) {
        val session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        if (!session.canDismissRecord(errors[id]) || mutable.value.busy || mutable.value.accountBusy) return
        dismissedSessionIds.add(id)
        if (session.rideGuid.isNotBlank()) dismissedRideGuids.add(session.rideGuid)
        viewModelScope.launch {
            try { persistence.withLock {
                val list = mutable.value.sessions.filterNot { it.sessionId == id }
                val stored = sessionsWithPending(ownerGuid, list)
                withContext(io) { store.saveSessions(ownerGuid, stored) }
                errors.remove(id)
                if (launchedPayment?.sessionId == id) launchedPayment = null
                mutable.update { it.copy(sessions = list, selectedSessionId = it.selectedSessionId?.takeUnless { selected -> selected == id },
                    confirmedSessions = it.confirmedSessions - id, sessionErrors = errors.toMap(),
                    error = if (it.selectedSessionId == id) null else it.error,
                    closeProof = if (it.selectedSessionId == id) null else it.closeProof,
                    paymentVisible = it.paymentVisible && it.selectedSessionId != id,
                    paymentFeedback = it.paymentFeedback?.takeUnless { feedback -> feedback.sessionId == id },
                    paymentLaunch = if (it.selectedSessionId == id) null else it.paymentLaunch,
                    paymentInFlight = it.paymentInFlight && it.selectedSessionId != id) }
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) { dismissedSessionIds.remove(id); dismissedRideGuids.remove(session.rideGuid); report(e, id) }
        }
    }
    fun acknowledgeRecovery() {
        if (mutable.value.accountBusy || mutable.value.busy || !mutable.value.recoveryRequired) return
        viewModelScope.launch {
            try { persistence.withLock {
                withContext(io) { store.acknowledgeRecovery() }
                mutable.update { it.copy(recoveryRequired = false) }
            } } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
        }
    }
    private suspend fun discover(owner: HellobikeAccount, ride: HellobikeRideReading.Ride): HellobikeSession {
        if (ride.userGuid != null && ride.userGuid != owner.userGuid) throw HellobikeException("骑行账户不一致，请在哈啰核对", officialRequired = true)
        val old = mutable.value.sessions.firstOrNull { it.rideGuid == ride.rideGuid } ?: mutable.value.sessions.firstOrNull {
            it.phase == HellobikePhase.Opening && it.rideGuid.isBlank() && it.bikeNo == ride.bikeNo
        }
        // OVER/P10 success is final for this order. Cached bikeCheck responses on resume
        // cannot resurrect a ride or discard its already persisted payment state.
        if (old?.phase == HellobikePhase.Ended) return old
        val pending = mutable.value.sessions.firstOrNull { it.riding }
        if (pending != null && pending.rideGuid != ride.rideGuid && pending.sessionId != old?.sessionId)
            throw HellobikeException("服务端骑行与待确认订单不一致，请在哈啰核对", officialRequired = true)
        if (ride.bikeNo.isBlank()) throw HellobikeException("骑行信息缺少车号，请在哈啰核对", officialRequired = true)
        // A server-discovered ride can be recovered before this installation has a location.
        val region = old?.region ?: mutable.value.region
        val session = old?.copy(rideGuid = ride.rideGuid) ?: HellobikeSession(ride.rideGuid, ride.bikeNo, region, HellobikePhase.Opening, now(),
            startedAtMillis = ride.seconds?.let { now() - it * 1000 })
        if (session.bikeNo != ride.bikeNo) throw HellobikeException("骑行车号不一致，请在哈啰核对", officialRequired = true)
        if (session.phase == HellobikePhase.Opening) {
            if (old == null || old.rideGuid.isBlank()) saveSession(owner, session)
            readOpening(owner, session)
            return mutable.value.sessions.first { it.sessionId == session.sessionId }
        }
        val updated = session.copy(phase = if (session.phase == HellobikePhase.Closing) HellobikePhase.Closing else HellobikePhase.Riding,
            uncertain = false, seconds = ride.seconds ?: session.seconds, startedAtMillis = session.startedAtMillis ?: ride.seconds?.let { now() - it * 1000 } ?: now())
        saveSession(owner, updated)
        mutable.update { it.copy(confirmedSessions = it.confirmedSessions + updated.sessionId) }
        return updated
    }
    private suspend fun readSession(owner: HellobikeAccount, id: String) {
        val before = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        if (before.phase == HellobikePhase.Opening) { readOpening(owner, before); return }
        if (before.phase == HellobikePhase.Ended) {
            if (before.paymentAttempt != null && !before.dismissible && !mutable.value.paymentInFlight) queryPayment(owner, id)
            else readSettlement(owner, id)
            return
        }
        // Query the submitted order even while ride discovery still returns a cached active ride.
        if (before.phase == HellobikePhase.Closing) {
            val ended = try { api.orderEnded(owner, before.rideGuid) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is HellobikeException && (e.loginExpired || e.officialRequired)) throw e
                false
            }
            ensureOwner(owner)
            if (ended) { finishRide(owner, before); return }
        }
        val reading = api.checkRide(owner); ensureOwner(owner)
        val current = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        when (reading) {
            HellobikeRideReading.NoRide -> if (api.orderEnded(owner, current.rideGuid)) { finishRide(owner, current); return } else {
                mutable.update { it.copy(confirmedSessions = it.confirmedSessions - id) }
                throw HellobikeException("本笔骑行结果尚未确认，请刷新原订单并在哈啰核对")
            }
            is HellobikeRideReading.Ride -> {
                if (reading.rideGuid != current.rideGuid || reading.bikeNo.isNotBlank() && reading.bikeNo != current.bikeNo ||
                    reading.userGuid != null && reading.userGuid != owner.userGuid)
                    throw HellobikeException("服务端订单与本地骑行不一致，请在哈啰核对", officialRequired = true)
                when (reading.status) {
                    0 -> {
                        saveSession(owner, current.copy(phase = if (current.phase == HellobikePhase.Closing) HellobikePhase.Closing else HellobikePhase.Riding,
                            startedAtMillis = current.startedAtMillis ?: reading.seconds?.let { now() - it * 1000 } ?: now(),
                            seconds = reading.seconds ?: current.seconds, uncertain = current.phase == HellobikePhase.Closing && current.uncertain))
                        mutable.update { it.copy(confirmedSessions = it.confirmedSessions + id) }
                    }
                    2 -> { finishRide(owner, current); return }
                    else -> throw HellobikeException("骑行状态尚未确认，请在哈啰核对", officialRequired = true)
                }
            }
        }
        ensureOwner(owner); errors.remove(id)
        mutable.update { it.copy(sessionErrors = errors.toMap(), error = if (it.selectedSessionId == id) null else it.error) }
    }
    private suspend fun readOpening(owner: HellobikeAccount, before: HellobikeSession,
        supplied: HellobikeOrderReading? = null): HellobikeSession {
        val identified = if (before.rideGuid.isNotBlank()) before else {
            val ride = api.checkRide(owner)
            ensureOwner(owner)
            if (ride !is HellobikeRideReading.Ride || ride.rideGuid.isBlank() || ride.bikeNo != before.bikeNo ||
                ride.userGuid != null && ride.userGuid != owner.userGuid)
                throw HellobikeException("开锁结果尚未返回订单，请重新查询车辆状态")
            saveSession(owner, before.copy(rideGuid = ride.rideGuid))
        }
        if (identified.phase == HellobikePhase.Ended) return identified
        val reading = supplied ?: api.orderState(owner, identified.rideGuid)
        ensureOwner(owner)
        val current = mutable.value.sessions.firstOrNull { it.sessionId == identified.sessionId } ?: return identified
        if (current.phase == HellobikePhase.Ended) return current
        if (reading.ended) {
            finishRide(owner, current)
            return mutable.value.sessions.firstOrNull { it.sessionId == current.sessionId } ?: current
        }
        val updated = if (reading.unlocked) current.copy(phase = HellobikePhase.Riding,
            uncertain = false, startedAtMillis = current.startedAtMillis ?: now())
        else current.copy(phase = HellobikePhase.Opening, uncertain = !reading.opening)
        saveSession(owner, updated)
        val detail = if (!reading.unlocked) reading.detail else null
        if (detail == null) errors.remove(updated.sessionId) else errors[updated.sessionId] = detail
        mutable.update { it.copy(
            sessionErrors = errors.toMap(),
            confirmedSessions = if (reading.unlocked) it.confirmedSessions + updated.sessionId else it.confirmedSessions - updated.sessionId,
            error = if (it.selectedSessionId == updated.sessionId) detail else it.error,
            officialRequired = if (it.selectedSessionId == updated.sessionId) false else it.officialRequired) }
        return updated
    }
    private suspend fun finishRide(owner: HellobikeAccount, session: HellobikeSession) {
        // Persist P10 success / the original order's OVER status before querying billing.
        // A missing or late bill must never keep a confirmed return in Closing.
        saveSession(owner, session.copy(phase = HellobikePhase.Ended, endedAtMillis = session.endedAtMillis ?: now(), uncertain = false))
        errors.remove(session.sessionId)
        mutable.update { it.copy(confirmedSessions = it.confirmedSessions - session.sessionId, closeProof = null,
            locationPermissionRequired = if (it.selectedSessionId == session.sessionId) false else it.locationPermissionRequired,
            sessionErrors = errors.toMap(), error = if (it.selectedSessionId == session.sessionId) null else it.error, officialRequired = false) }
        readSettlement(owner, session.sessionId)
    }
    private suspend fun readSettlement(owner: HellobikeAccount, id: String): HellobikePayDetail? {
        val session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return null
        if (session.phase != HellobikePhase.Ended || session.dismissible) return null
        val detail = api.payDetail(owner, session); ensureOwner(owner)
        if (detail.rideGuid != session.rideGuid) throw HellobikeException("费用订单不一致，请在哈啰核对", officialRequired = true)
        val attempt = session.paymentAttempt
        val settlement = when {
            detail.settlement in setOf(HellobikeSettlement.Paid, HellobikeSettlement.Closed) -> detail.settlement
            attempt != null && attempt.orders.all { it.guid in attempt.confirmedGuids } -> HellobikeSettlement.Unknown
            attempt != null -> HellobikeSettlement.Paying
            else -> detail.settlement
        }
        saveSession(owner, session.copy(settlement = settlement, amount = detail.amount ?: session.amount))
        if (detail.unsupported != null) errors[id] = detail.unsupported
        else if (detail.settlement != HellobikeSettlement.Unknown) errors.remove(id)
        mutable.update { it.copy(sessionErrors = errors.toMap(), error = if (it.selectedSessionId == id) errors[id] else it.error) }
        if (detail.settlement == HellobikeSettlement.Paid) feedback(id, RechargePhase.Paid)
        if (detail.settlement == HellobikeSettlement.Closed) feedback(id, RechargePhase.Failed, detail = "订单已关闭，请在哈啰查看费用记录")
        if (mutable.value.selectedSessionId == id) mutable.update { it.copy(payDetail = detail,
            officialRequired = detail.unsupported != null || detail.settlement == HellobikeSettlement.Closed) }
        return detail
    }
    private suspend fun pollCommand(owner: HellobikeAccount, id: String) {
        val opening = mutable.value.sessions.firstOrNull { it.sessionId == id }?.phase == HellobikePhase.Opening
        val deadline = elapsed() + if (opening) 35_000 else 20_000
        while (foreground && elapsed() < deadline) {
            ensureOwner(owner)
            val before = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
            if (before.phase == HellobikePhase.Ended) return
            try { readSession(owner, id) }
            catch (e: HellobikeException) { if (e.loginExpired || e.officialRequired) throw e }
            val current = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
            if (current.phase == HellobikePhase.Riding || current.phase == HellobikePhase.Ended) return
            delay(if (opening) 1000 else 1800)
        }
        throw HellobikeException("车辆响应较慢，结果尚未确认，请刷新原订单")
    }
    private fun resume(wait: Boolean = false) {
        val owner = account ?: return
        if (!foreground || mutable.value.restoring || mutable.value.accountBusy || mutable.value.busy || mutable.value.loggingIn) return
        if (recoveryTask?.isActive != true && !wait) {
            recoveryTask = viewModelScope.launch {
                try {
                    val reading = api.checkRide(owner); ensureOwner(owner)
                    if (reading is HellobikeRideReading.Ride && reading.status == 0 && reading.rideGuid !in dismissedRideGuids) discover(owner, reading)
                    for (session in mutable.value.sessions.filterNot { it.dismissible }) {
                        try { readSession(owner, session.sessionId) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report(e, session.sessionId); expired(e, owner) }
                    }
                    ensureOwner(owner); verifier.verified(owner.maskedPhone)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { if (account == owner) { verifier.failed(failure(e)); report(e); expired(e, owner) } }
                finally { startPolling(owner, wait = true) }
            }
        } else startPolling(owner, wait = true)
    }
    private fun startPolling(owner: HellobikeAccount, wait: Boolean) {
        if (!foreground || account != owner || mutable.value.accountBusy || mutable.value.busy || pollTask?.isActive == true ||
            mutable.value.sessions.none { !it.dismissible }) return
        pollTask = viewModelScope.launch {
            if (wait) delay(6000)
            while (isActive && foreground && account == owner) {
                if (!mutable.value.busy && !mutable.value.loadingDevice && !mutable.value.paymentInFlight) {
                    for (session in mutable.value.sessions.filterNot { it.dismissible }) {
                        try { readSession(owner, session.sessionId) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report(e, session.sessionId); expired(e, owner) }
                    }
                }
                delay(if (mutable.value.sessions.any { it.riding }) 6000 else 15_000)
            }
        }
    }
    fun showPayment() {
        val owner = account ?: return; val session = mutable.value.session ?: return
        if (!canCommand() || session.phase != HellobikePhase.Ended || session.dismissible) return
        mutable.update { it.copy(paymentVisible = true, paymentLoading = true, paymentFeedback = null, payOptions = emptyList(), error = null) }
        runCommand(owner) {
            if (session.paymentAttempt != null) { feedback(session.sessionId, RechargePhase.Checking, loading = true); queryPayment(owner, session.sessionId); return@runCommand }
            val detail = readSettlement(owner, session.sessionId) ?: return@runCommand
            if (detail.settlement == HellobikeSettlement.Paid) { feedback(session.sessionId, RechargePhase.Paid); return@runCommand }
            if (detail.unsupported != null) throw HellobikeException(detail.unsupported, officialRequired = true)
            val current = mutable.value.session ?: return@runCommand
            val options = api.payChannels(owner, current, detail); ensureOwner(owner)
            mutable.update { it.copy(payDetail = detail, payOptions = options, paymentLoading = false) }
        }
    }
    fun dismissPayment() { pendingOverlay = null; mutable.update { it.copy(paymentVisible = false, paymentRestorePending = false) } }
    fun pay(channel: String) {
        val owner = account ?: return; val session = mutable.value.session ?: return; val detail = mutable.value.payDetail ?: return
        val option = mutable.value.payOptions.singleOrNull { it.channel.method == channel } ?: return
        if (!canCommand() || !mutable.value.paymentVisible || session.phase != HellobikePhase.Ended || session.dismissible ||
            session.paymentAttempt != null || option.channel.available != true || option.channel.needsOpen == true) return
        val analyticsAttempt = analytics.begin()
        runCommand(owner) {
            val fresh = readSettlement(owner, session.sessionId) ?: return@runCommand
            if (fresh.settlement == HellobikeSettlement.Paid) { feedback(session.sessionId, RechargePhase.Paid); return@runCommand }
            if (fresh.settlement != HellobikeSettlement.Pending || fresh.orders != detail.orders || fresh.amount != detail.amount)
                throw HellobikeException("应付费用已更新，请重新确认支付方式")
            val options = api.payChannels(owner, session, fresh); ensureOwner(owner)
            val selected = options.singleOrNull { it.channel.method == channel }
            mutable.update { it.copy(payOptions = options) }
            if (selected == null || selected.channel.available != true || selected.channel.needsOpen == true || selected.amount != option.amount)
                throw HellobikeException("付款条件已更新，请重新确认")
            val orders = if (fresh.orders.size == 1) listOf(fresh.orders.single().copy(amount = selected.amount)) else fresh.orders
            val attempt = HellobikePaymentAttempt(orders, channel, now(), selected.extension)
            val current = mutable.value.session ?: return@runCommand
            saveSession(owner, current.copy(settlement = HellobikeSettlement.Paying, paymentAttempt = attempt))
            feedback(session.sessionId, RechargePhase.Creating, loading = true); ensureOwner(owner); ensureCommandVisible()
            analytics.submitted(analyticsAttempt, AnalyticsFeature.HellobikePayment)
            val component = try { api.pay(owner, current, orders, selected) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                analytics.operationFailed(analyticsAttempt, AnalyticsFeature.HellobikePayment, AnalyticsError.from(e)); throw e
            }
            ensureOwner(owner)
            val pending = mutable.value.session ?: return@runCommand
            saveSession(owner, pending.copy(paymentAttempt = attempt.copy(orders = component.orders, channel = component.channel, extension = component.extension)))
            if (component.channel !in HellobikeSupportedChannels) throw HellobikeException("服务端返回的支付方式需要在哈啰处理，请先查询本笔结果", officialRequired = true)
            if (component.channel == "003") { feedback(session.sessionId, RechargePhase.Checking, loading = true); queryPayment(owner, session.sessionId) }
            else {
                val payload = component.payload?.takeIf(String::isNotBlank) ?: throw HellobikeException("支付凭据尚未取得，请查询原订单")
                val id = ++paymentCounter
                launchedPayment = PaymentOwner(owner, session.sessionId, id)
                paymentAnalyticsAttempt = analyticsAttempt
                feedback(session.sessionId, RechargePhase.AwaitingPayment)
                mutable.update { it.copy(paymentInFlight = true, paymentLoading = false,
                    paymentLaunch = LifePaymentLaunch(id, session.rideGuid, payload, component.channel.hellobikeBridgeMethod()!!)) }
            }
        }
    }
    fun claimPayment(id: Long): Boolean {
        val binding = launchedPayment ?: return false
        if (!foreground || account != binding.account || binding.requestId != id || mutable.value.paymentLaunch?.id != id || mutable.value.accountBusy) return false
        mutable.update { it.copy(paymentLaunch = null) }; return true
    }
    fun paymentReturned(id: Long, status: String?) {
        val binding = launchedPayment ?: return
        if (binding.requestId != id || account != binding.account || mutable.value.accountBusy) return
        launchedPayment = null
        if (status == "launch_failed" || status == "6002")
            analytics.operationFailed(paymentAnalyticsAttempt, AnalyticsFeature.HellobikePayment, AnalyticsError.External)
        paymentAnalyticsAttempt = null
        mutable.update { it.copy(paymentLaunch = null, paymentInFlight = false) }
        feedback(binding.sessionId, RechargePhase.Checking, detail = when (status) {
            "launch_failed" -> "未能打开支付应用，请先查询本笔结果"
            "cancel", "6001" -> "已返回，请查询本笔付款结果"
            else -> "正在核对本笔付款结果"
        })
        if (foreground) checkPayment(binding.sessionId)
    }
    fun checkPayment(id: String? = mutable.value.selectedSessionId) {
        val owner = account ?: return; val session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        if (!foreground || mutable.value.busy || mutable.value.accountBusy || mutable.value.paymentInFlight || session.phase != HellobikePhase.Ended) return
        runCommand(owner, requirePage = false) { feedback(session.sessionId, RechargePhase.Checking, loading = true); queryPayment(owner, session.sessionId) }
    }
    private suspend fun queryPayment(owner: HellobikeAccount, id: String) {
        try { readSettlement(owner, id) }
        catch (e: CancellationException) { throw e }
        catch (e: HellobikeException) { if (e.loginExpired || e.officialRequired) throw e }
        catch (_: Exception) { /* The persisted payment identifiers remain queryable independently. */ }
        var session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        if (session.settled) { feedback(id, RechargePhase.Paid); return }
        if (session.settlement == HellobikeSettlement.Closed) {
            feedback(id, RechargePhase.Failed, detail = "订单已关闭，请在哈啰查看费用记录"); return
        }
        val attempt = session.paymentAttempt
        if (attempt == null) { feedback(id, RechargePhase.Checking, detail = "费用尚未确认，请刷新订单或在哈啰查看"); return }
        var confirmed = attempt.confirmedGuids
        var lastError: Exception? = null
        // Match REA: the initial query plus at most three retries at three-second intervals.
        repeat(4) { index ->
            if (index > 0) delay(3000)
            ensureOwner(owner)
            for (order in attempt.orders.filterNot { it.guid in confirmed }) {
                try { if (api.paymentPaid(owner, order, attempt.extension)) confirmed = confirmed + order.guid }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { lastError = e; if (e is HellobikeException && (e.loginExpired || e.officialRequired)) throw e }
            }
            ensureOwner(owner)
            session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
            if (confirmed.size == attempt.orders.size) {
                saveSession(owner, session.copy(settlement = HellobikeSettlement.Unknown,
                    paymentAttempt = attempt.copy(confirmedGuids = confirmed)))
                try { readSettlement(owner, id) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { report(e, id); expired(e, owner) }
                feedback(id, RechargePhase.Paid, detail = "付款已确认，骑行费用以哈啰订单记录为准")
                return
            }
        }
        saveSession(owner, session.copy(settlement = HellobikeSettlement.Paying, paymentAttempt = attempt.copy(confirmedGuids = confirmed)))
        feedback(id, RechargePhase.TimedOut, detail = lastError?.let(::failure) ?: "支付结果尚未确认，请继续查询原订单")
    }
    private fun feedback(id: String, phase: RechargePhase, loading: Boolean = false, detail: String? = null) {
        if (mutable.value.selectedSessionId == id) mutable.update { it.copy(paymentFeedback = LifePaymentFeedback(id, phase, loading, detail)) }
    }
    private fun report(e: Exception, id: String? = mutable.value.selectedSessionId) {
        val message = failure(e)
        if (id != null) {
            if (mutable.value.sessions.none { it.sessionId == id }) return
            errors[id] = message
        } else mutable.value.sessions.filterNot { it.dismissible }.forEach { errors[it.sessionId] = message }
        mutable.update { it.copy(sessionErrors = errors.toMap(),
            error = if (id == null || it.selectedSessionId == id) message else it.error,
            officialRequired = if (id == null || it.selectedSessionId == id) e is HellobikeException && e.officialRequired else it.officialRequired) }
    }
    private suspend fun expired(e: Exception, owner: HellobikeAccount) {
        if (e !is HellobikeException || !e.loginExpired || account != owner) return
        persistence.withLock {
            if (account != owner) return
            withContext(io) { store.clearAccount() }
            account = null; verifier.expired(failure(e)); pollTask?.cancel(); deviceGeneration++
            cards.bind(null)
            mutable.update { it.copy(connectedPhone = "", confirmedSessions = emptySet(), precheck = null, closeProof = null,
                busy = false, loadingDevice = false, paymentLaunch = null, paymentInFlight = false) }
            launchedPayment = null; paymentAnalyticsAttempt = null
        }
    }
    private fun failure(e: Exception): String = when (e) {
        is HellobikeLocationPermissionRequired -> "请允许定位后重试"
        is HellobikeException -> e.message ?: "哈啰请求失败，请重试"
        is com.mercury.yktwx.data.YktException -> "无法保存哈啰账号或骑行记录，请检查设备存储"
        else -> "连接失败，请检查网络后重试"
    }
}
