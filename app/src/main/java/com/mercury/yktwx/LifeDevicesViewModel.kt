package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LifeDevicesUiState(
    val restoring: Boolean = true, val connectedPhone: String = "", val feature: LifeFeature = LifeFeature.Water,
    val entryRequest: Long = 0, val loginVisible: Boolean = false, val phone: String = "", val code: String = "",
    val sendingCode: Boolean = false, val loggingIn: Boolean = false, val codeSeconds: Int = 0,
    val loginError: String? = null, val scanning: Boolean = false, val loadingDevice: Boolean = false,
    val device: LifeDevice? = null, val selectedSkuId: String = "", val error: String? = null,
    val water: WaterSession? = null, val waterBusy: Boolean = false,
)

/** One login gate, account, scan request and device state shared by the two entries. */
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
    private var deviceTask: Job? = null
    private var authTask: Job? = null
    private var waterTask: Job? = null
    private var pollTask: Job? = null
    private var visible = false
    private var foreground = false
    private var scanGeneration = 0L

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(io) { store.account() to store.session() }
                account = saved.first
                ownerPhone = account?.phone.orEmpty()
                mutable.update { it.copy(restoring = false, connectedPhone = account?.maskedPhone.orEmpty(), water = saved.second) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(restoring = false, loginError = failure(e)) } }
            requestedFeature?.let(::open)
        }
    }
    fun open(feature: LifeFeature) {
        requestedFeature = feature
        if (mutable.value.restoring) return
        if (account == null) {
            mutable.update { it.copy(loginVisible = true, loginError = null, code = "") }
        } else {
            requestedFeature = null
            val session = mutable.value.water
            mutable.update { it.copy(feature = feature, entryRequest = it.entryRequest + 1, loginVisible = false,
                scanning = feature == LifeFeature.Water && session?.active != true && session?.phase != WaterPhase.Ended,
                device = if (feature == LifeFeature.Water) session?.device else null, error = null) }
            resumeWater()
        }
    }
    fun dismissLogin() {
        authTask?.cancel(); authTask = null
        requestedFeature = null
        mutable.update { it.copy(loginVisible = false, loggingIn = false, sendingCode = false, code = "", loginError = null) }
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
                withContext(io) { store.saveAccount(next) }
                if (ownerPhone.isNotBlank() && ownerPhone != next.phone) {
                    pollTask?.cancel(); waterTask?.cancel()
                    mutable.update { it.copy(water = null, device = null, selectedSkuId = "") }
                }
                account = next
                ownerPhone = next.phone
                val restored = withContext(io) { store.session() }
                mutable.update { it.copy(water = restored) }
                mutable.update { it.copy(loggingIn = false, connectedPhone = next.maskedPhone, loginVisible = false, code = "") }
                open(requestedFeature ?: mutable.value.feature)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loggingIn = false, loginError = failure(e)) } }
        }
    }
    fun pageVisible(active: Boolean) {
        visible = active
        if (!active) { scanGeneration++; deviceTask?.cancel(); mutable.update { it.copy(loadingDevice = false) } }
    }
    fun environment(active: Boolean) {
        foreground = active
        if (active) resumeWater() else { pollTask?.cancel(); pollTask = null }
    }
    fun scan() {
        if (mutable.value.water?.active == true) {
            mutable.update { it.copy(scanning = false, device = it.water?.device, error = null) }; return
        }
        if (mutable.value.water?.let { it.phase == WaterPhase.Ended && !it.finalized } == true) return
        scanGeneration++; deviceTask?.cancel()
        mutable.update { it.copy(scanning = true, device = null, selectedSkuId = "", error = null, loadingDevice = false, water = null) }
        viewModelScope.launch { withContext(io) { store.clearSession() } }
    }
    fun scanned(raw: String) {
        if (!visible || mutable.value.feature != LifeFeature.Water || mutable.value.loadingDevice || !mutable.value.scanning) return
        val serial = DeviceSerial.parse(raw)
        if (serial == null) { mutable.update { it.copy(error = "无法识别此二维码，请输入设备编号") }; return }
        loadDevice(serial)
    }
    fun refreshDevice() {
        val serial = mutable.value.device?.serial ?: return
        if (mutable.value.water?.active == true || mutable.value.loadingDevice) return
        loadDevice(serial)
    }
    private fun loadDevice(serial: String) {
        val token = account?.token ?: return requireLogin()
        val generation = ++scanGeneration
        deviceTask?.cancel()
        deviceTask = viewModelScope.launch {
            mutable.update { it.copy(loadingDevice = true, error = null, scanning = false) }
            try {
                val device = api.device(serial, token)
                if (generation != scanGeneration) return@launch
                mutable.update { it.copy(device = device, selectedSkuId = device.skus.firstOrNull { sku -> sku.available }?.id.orEmpty(),
                    loadingDevice = false, error = device.startError) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == scanGeneration) mutable.update { it.copy(loadingDevice = false, scanning = it.device == null, error = failure(e)) }
                expired(e)
            }
        }
    }
    fun selectSku(id: String) {
        if (mutable.value.water?.active == true) return
        if (mutable.value.device?.skus?.any { it.id == id && it.available } == true) mutable.update { it.copy(selectedSkuId = id) }
    }
    fun startWater() {
        val value = mutable.value
        val device = value.device ?: return
        if (value.feature != LifeFeature.Water || value.water?.active == true || value.loadingDevice || waterTask?.isActive == true) return
        device.startError?.let { message -> mutable.update { it.copy(error = message) }; return }
        if (device.skus.none { it.id == value.selectedSkuId && it.available }) return
        val token = account?.token ?: return requireLogin()
        pollTask?.cancel(); pollTask = null
        waterTask = viewModelScope.launch {
            mutable.update { it.copy(waterBusy = true, error = null) }
            try {
                api.selectAfterPay(token)
                val sent = WaterSession(device, value.selectedSkuId, WaterPhase.Starting, startSent = true)
                saveSession(sent) // Persist before sending the command, including a lost-response attempt.
                val unlock = api.unlock(value.selectedSkuId, token)
                saveSession(sent.copy(messageId = unlock.messageId, orderNo = unlock.orderNo))
                waitForWater(token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; expired(e) }
            finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeWater() }
        }
    }
    fun stopWater() {
        val session = mutable.value.water ?: return
        if (session.phase != WaterPhase.Running || !session.device.supportsFinish || waterTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        pollTask?.cancel(); pollTask = null
        waterTask = viewModelScope.launch {
            mutable.update { it.copy(waterBusy = true, error = null) }
            try {
                saveSession(session.copy(phase = WaterPhase.Stopping))
                api.endWater(session.device.id, token)
                waitForWater(token)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A rejected command keeps the normal running page; transport errors retain the command context.
                if (e is LifeDeviceException && !e.loginExpired && mutable.value.water?.phase == WaterPhase.Stopping) saveSession(session)
                mutable.update { it.copy(error = failure(e)) }; expired(e)
            } finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeWater() }
        }
    }
    fun retryWater() {
        if (waterTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        pollTask?.cancel(); pollTask = null
        waterTask = viewModelScope.launch {
            mutable.update { it.copy(waterBusy = true, error = null) }
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
            catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; expired(e) }
            finally { waterTask = null; mutable.update { it.copy(waterBusy = false) }; resumeWater() }
        }
    }
    private suspend fun waitForWater(token: String) {
        repeat(30) {
            readWater(token)
            val phase = mutable.value.water?.phase
            if (phase == WaterPhase.Running || phase == WaterPhase.Ended) return
            delay(1000)
        }
        throw LifeDeviceException("设备响应较慢，请留意现场状态并重试")
    }
    private suspend fun readWater(token: String) {
        var session = mutable.value.water ?: return
        if (session.phase == WaterPhase.Ended) { finalizeWater(token); return }
        if (session.orderNo.isBlank()) throw LifeDeviceException("启动请求未返回接水订单，请在胖乖生活查看")
        if (session.phase == WaterPhase.Starting && session.messageId.isNotBlank()) {
            val message = api.message(session.messageId, token)
            if (message == null) return
            if (message.state != "OK" || message.code != "0") throw LifeDeviceException("启动失败，请在胖乖生活查看设备")
        }
        val reading = api.water(session.skuId, token)
        session = mutable.value.water ?: return
        if (session.orderNo.isNotBlank() && reading.orderNo != session.orderNo) throw LifeDeviceException("设备订单已变化，请在胖乖生活核对")
        if (reading.orderNo.isBlank()) throw LifeDeviceException("设备未返回接水订单，请重试")
        when {
            reading.workStatus == 2 && reading.status == 1 -> {
                val next = session.copy(phase = if (session.phase == WaterPhase.Stopping) WaterPhase.Stopping else WaterPhase.Running,
                    startedAtMillis = session.startedAtMillis ?: now())
                if (next != session) saveSession(next)
                mutable.update { it.copy(error = null) }
            }
            reading.workStatus == 1 && reading.status == 5 -> {
                // The zero-consumption capture ends here without creating a payable after-pay order.
                saveSession(session.copy(phase = WaterPhase.Ended, endedAtMillis = now(), finalized = reading.zeroConsumption))
                if (reading.zeroConsumption) mutable.update { it.copy(error = null) }
                else finalizeWater(token)
            }
            else -> throw LifeDeviceException("暂时无法读取设备状态，请重试")
        }
    }
    private suspend fun finalizeWater(token: String) {
        var session = mutable.value.water ?: return
        if (session.phase != WaterPhase.Ended || session.finalized) return
        if (session.orderId.isBlank()) {
            val orderId = api.createAfterPay(session.orderNo, token)
            session = session.copy(orderId = orderId)
            saveSession(session)
        }
        api.syncOrder(session.orderNo, token)
        saveSession(session.copy(finalized = true))
        mutable.update { it.copy(error = null) }
    }
    private suspend fun saveSession(session: WaterSession) {
        // Preserve the command in memory even if disk persistence fails after its response.
        mutable.update { it.copy(water = session, device = session.device, scanning = false) }
        withContext(io) { store.saveSession(session) }
    }
    private fun resumeWater() {
        if (!foreground || account == null || waterTask?.isActive == true || pollTask?.isActive == true) return
        val session = mutable.value.water ?: return
        if (!session.active && (session.phase != WaterPhase.Ended || session.finalized)) return
        if (session.phase == WaterPhase.Ended && mutable.value.error != null) return
        val token = account!!.token
        pollTask = viewModelScope.launch {
            while (isActive && foreground && mutable.value.water?.let { it.active || !it.finalized } == true) {
                try { readWater(token) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; if (expired(e)) break }
                if (mutable.value.water?.phase == WaterPhase.Ended) break
                delay(if (mutable.value.error == null) 1500 else 5000)
            }
        }
    }
    private fun requireLogin() {
        requestedFeature = mutable.value.feature
        mutable.update { it.copy(loginVisible = true, loginError = "请登录胖乖生活", code = "") }
    }
    private suspend fun expired(e: Exception): Boolean {
        if (e !is LifeDeviceException || !e.loginExpired) return false
        account = null
        withContext(io) { store.clearAccount() }
        mutable.update { it.copy(connectedPhone = "") }
        requireLogin()
        return true
    }
    private fun failure(e: Exception) = when (e) {
        is LifeDeviceException -> e.message ?: "操作失败，请重试"
        is com.mercury.yktwx.data.YktException -> "无法保存账号或接水进度，请检查设备存储"
        else -> "连接失败，请检查网络后重试"
    }
}
