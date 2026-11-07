package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
data class LaundryPaymentFeedback(val sessionId: String, val phase: RechargePhase,
    val loading: Boolean = false, val detail: String? = null)
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
)

/** One login gate and scanner route each recognized code to its device service. */
class LifeDevicesViewModel internal constructor(
    app: Application, private val api: LifeDeviceService, private val store: LifeDeviceStore,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, LifeDeviceApi(), LifeDeviceAccountStore(app))
    private val mutable = MutableStateFlow(LifeDevicesUiState())
    val state = mutable.asStateFlow()
    private var account: LifeAccount? = null
    private var ownerPhone = ""
    private var requestedFeature: LifeFeature? = null
    private var requestedScan = false
    private var requestedSessionId: String? = null
    private var pendingDeviceSerial: String? = null
    private val persistence = Mutex()
    private val sessionErrors = mutableMapOf<String, String>()
    private var deviceTask: Job? = null
    private var authTask: Job? = null
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
        if (profile.phone != owner.phone) throw LifeDeviceException("胖乖账户信息不一致，请重新登录", true)
    }, { e, _ -> expired(e, showLogin = false) }, ::failure)
    val accountConnection = accountVerifier.state
    private var accountTask: Job? = null
    val hasActiveServices: Boolean get() = mutable.value.waterSessions.any { it.active } ||
        mutable.value.laundrySessions.any { it.active } || mutable.value.water?.active == true ||
        mutable.value.laundry?.active == true || mutable.value.paymentLaunch != null
    private fun accountChangeBlocked(): Boolean = hasActiveServices || mutable.value.restoring ||
        mutable.value.waterBusy || mutable.value.laundryBusy || mutable.value.accountBusy

    fun checkAccount(force: Boolean = false) {
        if (!mutable.value.restoring && !mutable.value.loggingIn && !mutable.value.accountBusy) accountVerifier.check(force)
    }

    fun manageLogin(change: Boolean = false): Boolean {
        if (mutable.value.restoring || mutable.value.loggingIn || mutable.value.accountBusy ||
            change && accountChangeBlocked()) return false
        requestedFeature = null; requestedScan = false; requestedSessionId = null
        pendingDeviceSerial = null
        mutable.update { it.copy(loginVisible = true, changingAccount = change, phone = "", code = "", loginError = null, accountError = null) }
        return true
    }

    fun removeAccount(): Boolean {
        if (accountChangeBlocked() || mutable.value.loggingIn) return false
        accountVerifier.reset()
        accountTask = viewModelScope.launch {
            mutable.update { it.copy(accountBusy = true, accountError = null) }
            try {
                persistence.withLock {
                    withContext(io) { store.removeAccount() }
                    account = null; ownerPhone = ""
                }
                scanGeneration++; deviceTask?.cancel(); quoteTask?.cancel(); waterPollTask?.cancel(); laundryPollTask?.cancel()
                requestedFeature = null; requestedScan = false; requestedSessionId = null
                pendingDeviceSerial = null
                sessionErrors.clear(); accountVerifier.cleared()
                mutable.update { LifeDevicesUiState(restoring = false, entryRequest = it.entryRequest) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(accountError = failure(e)) } }
            finally { mutable.update { it.copy(accountBusy = false) } }
        }
        return true
    }

    init {
        viewModelScope.launch {
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
            if (session.phase != WaterPhase.Ended) return
            mutable.update { it.copy(waterSessions = it.waterSessions.filterNot { item -> item.sessionId == sessionId },
                water = it.water?.takeUnless { item -> item.sessionId == sessionId }) }
        } else {
            val session = value.laundrySessions.firstOrNull { it.sessionId == sessionId } ?: return
            if (session.active) return
            mutable.update { it.copy(laundrySessions = it.laundrySessions.filterNot { item -> item.sessionId == sessionId },
                laundry = it.laundry?.takeUnless { item -> item.sessionId == sessionId }) }
        }
        sessionErrors.remove(sessionId)
        viewModelScope.launch {
            try { persistSessions() }
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
                LaundryPhase.Creating -> "订单创建结果未返回，请在胖乖生活核对，避免重复下单"
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
        if (!value.loginVisible || value.loggingIn || value.sendingCode || value.codeSeconds > 0) return
        if (!value.phone.matches(Regex("1[0-9]{10}"))) {
            mutable.update { it.copy(loginError = "请输入正确的手机号") }; return
        }
        authTask = viewModelScope.launch {
            mutable.update { it.copy(sendingCode = true, loginError = null) }
            try {
                api.sendCode(value.phone)
                mutable.update { it.copy(sendingCode = false, codeSeconds = 60) }
                viewModelScope.launch {
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
        if (!value.loginVisible || value.loggingIn || value.sendingCode) return
        if (!value.phone.matches(Regex("1[0-9]{10}")) || value.code.isBlank()) {
            mutable.update { it.copy(loginError = "请输入手机号和验证码") }; return
        }
        authTask = viewModelScope.launch {
            mutable.update { it.copy(loggingIn = true, loginError = null) }
            try {
                val next = api.login(value.phone, value.code)
                ensureActive()
                if (ownerPhone.isNotBlank() && ownerPhone != next.phone && accountChangeBlocked())
                    throw LifeDeviceException("还有服务进行中，请使用原账户重新登录")
                accountVerifier.reset()
                persistence.withLock { withContext(io) { store.saveAccount(next) }; ensureActive(); account = next }
                if (ownerPhone.isNotBlank() && ownerPhone != next.phone) {
                    waterPollTask?.cancel(); laundryPollTask?.cancel(); waterTask?.cancel(); laundryTask?.cancel(); quoteTask?.cancel()
                    sessionErrors.clear()
                    mutable.update { it.copy(water = null, laundry = null, waterSessions = emptyList(), laundrySessions = emptyList(),
                        device = null, selectedSkuId = "", laundryQuote = null, paymentLaunch = null, paymentFeedback = null) }
                }
                account = next
                ownerPhone = next.phone
                accountVerifier.verified(next.maskedPhone)
                mutable.update { it.copy(loggingIn = false, connectedPhone = next.maskedPhone, loginVisible = false,
                    code = "", changingAccount = false) }
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
            catch (e: Exception) { mutable.update { it.copy(loggingIn = false, loginError = failure(e)) } }
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
        if (mutable.value.loadingDevice || (if (mutable.value.feature == LifeFeature.Water) mutable.value.water?.active else mutable.value.laundry?.active) == true) return
        loadDevice(serial)
    }
    private fun loadDevice(serial: String) {
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
                mutable.update { it.copy(device = device, selectedSkuId = device.skus.firstOrNull { sku -> sku.available }?.id.orEmpty(),
                    loadingDevice = false, error = if (feature == LifeFeature.Water) device.startError else device.laundryError,
                    laundryQuote = null) }
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
        if (mutable.value.device?.skus?.any { it.id == id && it.available } == true) {
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
        if (value.feature != LifeFeature.Water || value.water?.active == true || value.loadingDevice || waterTask?.isActive == true) return
        device.startError?.let { message -> mutable.update { it.copy(error = message) }; return }
        if (device.skus.none { it.id == value.selectedSkuId && it.available }) return
        val token = account?.token ?: return requireLogin()
        waterPollTask?.cancel(); waterPollTask = null
        waterTask = viewModelScope.launch {
            serviceError(LifeFeature.Water, null)
            mutable.update { it.copy(waterBusy = true) }
            try {
                api.selectAfterPay(token)
                val sent = WaterSession(device, value.selectedSkuId, WaterPhase.Starting, startSent = true)
                saveSession(sent, select = true) // Persist before sending the command, including a lost-response attempt.
                val unlock = if (device.isShower) api.unlockShower(value.selectedSkuId, token) else api.unlock(value.selectedSkuId, token)
                saveSession(sent.copy(messageId = unlock.messageId, orderNo = unlock.orderNo))
                waitForWater(token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { serviceError(LifeFeature.Water, failure(e)); expired(e) }
            finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeSessions() }
        }
    }
    fun stopWater() {
        val session = mutable.value.water ?: return
        if (session.phase != WaterPhase.Running || !session.device.supportsFinish || waterTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        waterPollTask?.cancel(); waterPollTask = null
        waterTask = viewModelScope.launch {
            serviceError(LifeFeature.Water, null)
            mutable.update { it.copy(waterBusy = true) }
            try {
                readWater(token, session.sessionId)
                val current = mutable.value.waterSessions.firstOrNull { it.sessionId == session.sessionId }
                if (current?.phase != WaterPhase.Running || account?.token != token) return@launch
                saveSession(session.copy(phase = WaterPhase.Stopping))
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
        if (waterTask?.isActive == true) return
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
    private suspend fun waitForWater(token: String) {
        repeat(20) {
            readWater(token)
            val phase = mutable.value.water?.phase
            if (phase == null || phase == WaterPhase.Running || phase == WaterPhase.Ended) return
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
            if (message.state != "OK" || message.code != "0") throw LifeDeviceException("启动失败，请在胖乖生活查看设备")
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
                    startedAtMillis = session.startedAtMillis ?: now())
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
            session.device.isShower && reading.status == 6 -> throw LifeDeviceException("设备异常，请在胖乖生活查看")
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
    private suspend fun saveSession(session: WaterSession, select: Boolean = false) {
        if (!select && mutable.value.waterSessions.none { it.sessionId == session.sessionId }) return
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
        withContext(io) {
            val value = mutable.value
            store.saveWaterSessions(value.waterSessions)
            store.saveLaundrySessions(value.laundrySessions)
        }
    }
    private fun sessionError(feature: LifeFeature, sessionId: String, message: String?) {
        if (message == null) sessionErrors.remove(sessionId) else sessionErrors[sessionId] = message
        val selected = if (feature == LifeFeature.Water) mutable.value.water?.sessionId else mutable.value.laundry?.sessionId
        if (selected == sessionId) serviceError(feature, message)
    }
    private fun serviceError(feature: LifeFeature, message: String?) {
        val sessionId = if (feature == LifeFeature.Water) mutable.value.water?.sessionId else mutable.value.laundry?.sessionId
        sessionId?.let { if (message == null) sessionErrors.remove(it) else sessionErrors[it] = message }
        mutable.update { if (it.feature == feature) it.copy(error = message) else it }
    }
    private suspend fun removeWaterSession(id: String) {
        mutable.update { it.copy(waterSessions = it.waterSessions.filterNot { session -> session.sessionId == id },
            water = it.water?.takeUnless { session -> session.sessionId == id }) }
        sessionErrors.remove(id)
        persistSessions()
    }
    /** Recovery is attempted once; only verified running orders are retained, without replaying commands. */
    private suspend fun recoverWaterSessions(token: String, candidates: List<WaterSession>) {
        if (account?.token != token) return
        // Drop the old cache before querying so an expired login cannot leave failed candidates behind.
        persistSessions()
        for (session in candidates) {
            try {
                if (session.orderNo.isNotBlank()) {
                    val reading = if (session.device.isShower) api.shower(session.skuId, token) else api.water(session.skuId, token)
                    if (account?.token != token) return
                    if (reading.orderNo.isBlank()) throw LifeDeviceException("设备未返回${session.device.waterAction}订单，请重试")
                    if (reading.orderNo == session.orderNo) {
                        when {
                            reading.status == 1 && reading.workStatus == 2 -> {
                                val running = session.copy(phase = WaterPhase.Running,
                                    startedAtMillis = session.startedAtMillis ?: now(), endedAtMillis = null)
                                mutable.update { it.copy(waterSessions = it.waterSessions + running,
                                    water = if (it.restoring && it.water == null && !it.scanning && it.scannedSerial == null) running else it.water) }
                            }
                            reading.ended(session.device) -> Unit
                            else -> throw LifeDeviceException("暂时无法读取设备状态，请重试")
                        }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (account?.token != token) return
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
        if (!foreground || account == null || waterTask?.isActive == true || waterPollTask?.isActive == true) return
        if (mutable.value.waterSessions.none { it.active || it.phase == WaterPhase.Ended && !it.finalized }) return
        val token = account!!.token
        waterPollTask = viewModelScope.launch {
            if (waitBeforeFirstRead) delay(1500)
            while (isActive && foreground && account?.token == token) {
                val sessions = mutable.value.waterSessions.filter { it.active ||
                    it.phase == WaterPhase.Ended && !it.finalized && sessionErrors[it.sessionId] == null }
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
            value.quoteLoading || value.device?.laundryError != null) return
        val device = value.device ?: return
        if (device.skus.none { it.id == value.selectedSkuId && it.available }) return
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
                val quote = service.laundryQuote(device, value.selectedSkuId, token)
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
        quoteSequence++; quoteTask?.cancel()
        mutable.update { it.copy(paymentVisible = false, paymentFeedback = null, quoteLoading = false,
            error = if (it.laundry == null) it.device?.laundryError else it.error) }
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
            mutable.update { it.copy(paymentVisible = true, paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Creating)) }
            laundryWork(clearError = false) { token, service ->
                if (readLaundryOrder(token, service)) return@laundryWork
                if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                    saveLaundrySession(checkNotNull(mutable.value.laundry).copy(paid = true, phase = LaundryPhase.Starting))
                    waitLaundryStart(token, service)
                    return@laundryWork
                }
                if (stopExpiredLaundryPayment(checkNotNull(mutable.value.laundry))) return@laundryWork
                val channels = service.laundryChannels(session, token)
                mutable.update { it.copy(laundryQuote = LaundryQuote(session.device.id, session.skuId, session.price, session.minutes, channels),
                    paymentVisible = true) }
            }
        } else if (value.device?.laundryError == null && value.device?.skus?.any { it.id == value.selectedSkuId && it.available } == true) {
            if (value.paymentVisible && value.laundryQuote != null) return
            mutable.update { it.copy(paymentVisible = true, paymentFeedback = null) }
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
                session = session.copy(phase = LaundryPhase.Creating, createSent = true)
                saveLaundrySession(session) // A lost create response must never create a second order.
                val order = service.createLaundryOrder(device.id, quote.skuId, token)
                session = session.copy(orderNo = order.orderNo, orderId = order.orderId, phase = LaundryPhase.Paying)
                saveLaundrySession(session)
            } else {
                if (session.phase != LaundryPhase.Paying || session.orderNo.isBlank()) return@laundryWork
                mutable.update { it.copy(paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Creating)) }
                if (readLaundryOrder(token, service)) return@laundryWork
                if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                    saveLaundrySession(checkNotNull(mutable.value.laundry).copy(paid = true, phase = LaundryPhase.Starting))
                    waitLaundryStart(token, service); return@laundryWork
                }
                session = checkNotNull(mutable.value.laundry)
                if (stopExpiredLaundryPayment(session)) return@laundryWork
                session = session.copy(payMethod = method, payTypeName = "")
                saveLaundrySession(session)
                val channels = service.laundryChannels(session, token)
                mutable.update { it.copy(laundryQuote = LaundryQuote(session.device.id, session.skuId, session.price, session.minutes, channels)) }
                if (channels.none { it.method == method && it.laundryUnavailableReason() == null })
                    throw LifeDeviceException(channels.firstOrNull { it.method == method }?.laundryUnavailableReason()
                        ?: "支付方式已变更，请重新选择")
                session = session.copy(device = session.device.copy(channels = channels))
                saveLaundrySession(session)
                checkLaundryReady(session, token, service)
            }
            if (readLaundryOrder(token, service)) return@laundryWork
            session = checkNotNull(mutable.value.laundry)
            if (stopExpiredLaundryPayment(session)) return@laundryWork
            saveLaundrySession(session.copy(phase = LaundryPhase.Paying, paymentSent = true))
            if (method == "2") {
                paymentFeedback(session.sessionId, RechargePhase.Checking, loading = true)
                service.payLaundryBalance(session.orderNo, token)
                waitLaundryPaid(token, service)
            } else {
                val payload = service.prepareLaundryPayment(session.orderNo, method, token)
                if (stopExpiredLaundryPayment(session)) return@laundryWork
                paymentFeedback(session.sessionId, RechargePhase.AwaitingPayment)
                mutable.update { it.copy(paymentLaunch = LifePaymentLaunch(++paymentSequence, session.orderNo, payload, method)) }
            }
        }
    }
    fun laundryPaymentReturned(requestId: Long, resultStatus: String?) {
        val launch = mutable.value.paymentLaunch?.takeIf { it.id == requestId } ?: return
        val session = mutable.value.laundry?.takeIf { it.orderNo == launch.orderNo } ?: return
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
                if (stopExpiredLaundryPayment(checkNotNull(mutable.value.laundry))) return@laundryWork
                paymentFeedback(session.sessionId, RechargePhase.Failed, detail = message)
                throw LifeDeviceException(message)
            }
            waitLaundryPaid(token, service)
        }
    }
    fun claimLaundryPayment(requestId: Long): Boolean {
        if (mutable.value.paymentLaunch?.id != requestId || paymentInFlight != null) return false
        mutable.value.laundry?.takeIf { it.paymentExpired(now()) }?.let {
            mutable.update { state -> state.copy(paymentLaunch = null) }
            stopExpiredLaundryPayment(it)
            return false
        }
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
        laundryWork { token, service ->
            when (session.phase) {
                LaundryPhase.Checking -> {
                    checkLaundryReady(session, token, service)
                    saveLaundrySession(session.copy(phase = LaundryPhase.Creating, createSent = true))
                    val order = service.createLaundryOrder(session.device.id, session.skuId, token)
                    saveLaundrySession(session.copy(orderNo = order.orderNo, orderId = order.orderId, phase = LaundryPhase.Paying, createSent = true))
                    if (!readLaundryOrder(token, service)) mutable.update { it.copy(paymentVisible = false) }
                }
                LaundryPhase.Creating -> throw LifeDeviceException("订单创建结果未返回，请在胖乖生活核对，避免重复下单")
                LaundryPhase.Paying -> waitLaundryPaid(token, service)
                LaundryPhase.Starting -> waitLaundryStart(token, service)
                LaundryPhase.Running, LaundryPhase.Ended -> readLaundryOrder(token, service)
                LaundryPhase.Closed -> Unit
            }
        }
    }
    fun checkLaundryPayment() {
        val session = mutable.value.laundry ?: return
        if (mutable.value.paymentFeedback?.phase != RechargePhase.Checking && !session.paymentExpired(now()) ||
            mutable.value.laundryBusy || mutable.value.paymentLaunch != null) return
        mutable.update { it.copy(paymentFeedback = LaundryPaymentFeedback(session.sessionId, RechargePhase.Checking, loading = true)) }
        laundryWork { token, service -> waitLaundryPaid(token, service) }
    }
    private fun stopExpiredLaundryPayment(session: LaundrySession): Boolean {
        if (!session.paymentExpired(now())) return false
        paymentFeedback(session.sessionId, RechargePhase.Checking, detail = LaundryPaymentExpiredMessage)
        return true
    }
    private fun closedLaundryFeedback(session: LaundrySession) = LaundryPaymentFeedback(session.sessionId,
        if (session.paymentDeadlineReached(now())) RechargePhase.TimedOut else RechargePhase.Failed, detail = "订单已关闭")
    private fun paymentFeedback(sessionId: String, phase: RechargePhase, loading: Boolean = false, detail: String? = null) {
        mutable.update { value ->
            if (value.paymentFeedback?.sessionId == sessionId)
                value.copy(paymentFeedback = LaundryPaymentFeedback(sessionId, phase, loading, detail)) else value
        }
    }
    private fun laundryWork(clearError: Boolean = true, block: suspend (String, LifeLaundryService) -> Unit) {
        if (laundryTask?.isActive == true || mutable.value.paymentLaunch != null) return
        val token = account?.token ?: return requireLogin()
        val service = laundryApi ?: return
        laundryPollTask?.cancel(); laundryPollTask = null
        laundryTask = viewModelScope.launch {
            if (clearError) serviceError(LifeFeature.Laundry, null)
            mutable.update { it.copy(laundryBusy = true) }
            try { block(token, service) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val message = failure(e)
                serviceError(LifeFeature.Laundry, message)
                mutable.value.paymentFeedback?.takeIf { it.phase in setOf(RechargePhase.AwaitingPayment, RechargePhase.Checking) }?.let {
                    paymentFeedback(it.sessionId, RechargePhase.Checking, detail = message)
                }
                expired(e)
            }
            finally { laundryTask = null; mutable.update { it.copy(laundryBusy = false) }; resumeSessions() }
        }
    }
    private suspend fun checkLaundryReady(session: LaundrySession, token: String, service: LifeLaundryService) {
        val message = service.checkLaundryDevice(session.device.id, token)
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
            if (stopExpiredLaundryPayment(checkNotNull(mutable.value.laundry))) return
            paymentFeedback(session.sessionId, RechargePhase.Failed, detail = message)
            throw LifeDeviceException(message)
        }
        val latest = mutable.value.laundrySessions.firstOrNull { it.sessionId == session.sessionId } ?: return
        if (latest.phase in setOf(LaundryPhase.Running, LaundryPhase.Ended)) return
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
        if (session.orderNo.isBlank()) throw LifeDeviceException("订单创建结果未返回，请在胖乖生活核对，避免重复下单")
        val reading = service.laundryOrder(session.orderNo, token)
        if (reading.orderNo != session.orderNo || reading.orderId != session.orderId || reading.deviceId != session.device.id || reading.skuId != session.skuId)
            throw LifeDeviceException("洗衣订单信息不匹配，请在胖乖生活核对")
        if (reading.price.toBigDecimalOrNull()?.compareTo(session.price.toBigDecimalOrNull()) != 0)
            throw LifeDeviceException("订单价格与所选程序不一致，请在胖乖生活核对")
        val next = session.copy(device = session.device.copy(
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
            1 -> if (!session.paid && session.phase !in setOf(LaundryPhase.Running, LaundryPhase.Ended))
                saveLaundrySession(next.copy(phase = LaundryPhase.Closed))
            3 -> saveLaundrySession(next.copy(phase = LaundryPhase.Ended, paid = true))
            2 -> if (session.phase != LaundryPhase.Ended) saveLaundrySession(next.copy(phase = LaundryPhase.Running, paid = true))
            0 -> if (session.phase !in setOf(LaundryPhase.Ended, LaundryPhase.Running, LaundryPhase.Closed)) saveLaundrySession(next)
            else -> throw LifeDeviceException("暂时无法读取洗衣状态，请在胖乖生活核对")
        }
        // An unpaid order does not prove the device is free or that a failed payment succeeded.
        if (reading.status in setOf(2, 3)) {
            sessionError(LifeFeature.Laundry, session.sessionId, null)
        }
        return mutable.value.laundrySessions.firstOrNull { it.sessionId == sessionId }?.phase in setOf(LaundryPhase.Running, LaundryPhase.Ended, LaundryPhase.Closed)
    }
    private suspend fun restoreLaundrySessions(saved: List<LaundrySession>) {
        val restored = saved.map { it.withCompletionObservation(now()) }
        mutable.update { it.copy(laundry = if (!it.scanning && it.scannedSerial == null) restored.lastOrNull() else it.laundry,
            laundrySessions = restored) }
        if (restored != saved) persistence.withLock { withContext(io) { store.saveLaundrySessions(restored) } }
    }
    private suspend fun saveLaundrySession(value: LaundrySession, select: Boolean = false) {
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
        if (!foreground || account == null || laundryTask?.isActive == true || laundryPollTask?.isActive == true) return
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
        accountVerifier.expired()
        mutable.update { it.copy(connectedPhone = "") }
        if (showLogin && visible) requireLogin()
        return true
    }
    private fun failure(e: Exception) = when (e) {
        is LifeDeviceException -> e.message ?: "操作失败，请重试"
        is com.mercury.yktwx.data.YktException -> "无法保存账号或服务进度，请检查设备存储"
        else -> "连接失败，请检查网络后重试"
    }
}
