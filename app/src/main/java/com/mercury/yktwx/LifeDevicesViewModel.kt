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
    val laundry: LaundrySession? = null, val laundryQuote: LaundryQuote? = null, val quoteLoading: Boolean = false,
    val laundryBusy: Boolean = false, val paymentVisible: Boolean = false, val paymentLaunch: LifeAlipayLaunch? = null,
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
    private val laundryApi = api as? LifeLaundryService
    private var laundryTask: Job? = null
    private var quoteTask: Job? = null
    private var paymentSequence = 0L
    private var paymentInFlight: Long? = null
    private var visible = false
    private var foreground = false
    private var scanGeneration = 0L

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(io) { Triple(store.account(), store.session(), store.laundrySession()) }
                account = saved.first
                ownerPhone = account?.phone.orEmpty()
                mutable.update { it.copy(restoring = false, connectedPhone = account?.maskedPhone.orEmpty(), water = saved.second, laundry = saved.third) }
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
            val washing = mutable.value.laundry
            val interrupted = if (feature == LifeFeature.Laundry && laundryTask?.isActive != true) when (washing?.phase) {
                LaundryPhase.Checking -> "设备检查已中断，请重试"
                LaundryPhase.Creating -> "订单创建结果未返回，请在胖乖生活核对，避免重复下单"
                else -> null
            } else null
            pollTask?.cancel(); pollTask = null
            mutable.update { it.copy(feature = feature, entryRequest = it.entryRequest + 1, loginVisible = false,
                scanning = if (feature == LifeFeature.Water) session?.active != true && session?.phase != WaterPhase.Ended else washing == null,
                device = if (feature == LifeFeature.Water) session?.device else washing?.device, error = interrupted,
                laundryQuote = null, paymentVisible = false) }
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
                    pollTask?.cancel(); waterTask?.cancel(); laundryTask?.cancel(); quoteTask?.cancel()
                    mutable.update { it.copy(water = null, laundry = null, device = null, selectedSkuId = "", laundryQuote = null, paymentLaunch = null) }
                }
                account = next
                ownerPhone = next.phone
                val restored = withContext(io) { store.session() to store.laundrySession() }
                mutable.update { it.copy(water = restored.first, laundry = restored.second) }
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
        if (mutable.value.feature == LifeFeature.Laundry) {
            if (mutable.value.laundry?.active == true) return
            scanGeneration++; deviceTask?.cancel(); quoteTask?.cancel()
            mutable.update { it.copy(scanning = true, device = null, selectedSkuId = "", error = null, loadingDevice = false,
                laundry = null, laundryQuote = null, paymentVisible = false, quoteLoading = false) }
            viewModelScope.launch { withContext(io) { store.clearLaundrySession() } }
            return
        }
        if (mutable.value.water?.active == true) {
            mutable.update { it.copy(scanning = false, device = it.water?.device, error = null) }; return
        }
        if (mutable.value.water?.let { it.phase == WaterPhase.Ended && !it.finalized } == true) return
        scanGeneration++; deviceTask?.cancel()
        mutable.update { it.copy(scanning = true, device = null, selectedSkuId = "", error = null, loadingDevice = false, water = null) }
        viewModelScope.launch { withContext(io) { store.clearSession() } }
    }
    fun scanned(raw: String) {
        if (!visible || mutable.value.loadingDevice || !mutable.value.scanning) return
        val serial = LifeDeviceCode.parse(raw, mutable.value.feature)
        if (serial == null) { mutable.update { it.copy(error = "无法识别此二维码，请输入设备编号") }; return }
        loadDevice(serial)
    }
    fun refreshDevice() {
        val serial = mutable.value.device?.serial ?: return
        if (mutable.value.loadingDevice || (if (mutable.value.feature == LifeFeature.Water) mutable.value.water?.active else mutable.value.laundry?.active) == true) return
        loadDevice(serial)
    }
    private fun loadDevice(serial: String) {
        val token = account?.token ?: return requireLogin()
        val generation = ++scanGeneration
        val feature = mutable.value.feature
        deviceTask?.cancel()
        deviceTask = viewModelScope.launch {
            mutable.update { it.copy(loadingDevice = true, error = null, scanning = false) }
            try {
                val device = if (feature == LifeFeature.Water) api.device(serial, token)
                    else requireNotNull(laundryApi).laundryDevice(serial, token)
                if (generation != scanGeneration) return@launch
                mutable.update { it.copy(device = device, selectedSkuId = device.skus.firstOrNull { sku -> sku.available }?.id.orEmpty(),
                    loadingDevice = false, error = if (feature == LifeFeature.Water) device.startError else device.laundryError,
                    laundryQuote = null) }
                if (feature == LifeFeature.Laundry && device.laundryError == null) loadLaundryQuote()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (generation == scanGeneration) mutable.update { it.copy(loadingDevice = false, scanning = it.device == null, error = failure(e)) }
                expired(e)
            }
        }
    }
    fun selectSku(id: String) {
        if ((if (mutable.value.feature == LifeFeature.Water) mutable.value.water?.active else mutable.value.laundry?.active) == true) return
        if (mutable.value.device?.skus?.any { it.id == id && it.available } == true) {
            mutable.update { it.copy(selectedSkuId = id) }
            if (mutable.value.feature == LifeFeature.Laundry) loadLaundryQuote()
        }
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
        mutable.update { it.copy(water = session, device = if (it.feature == LifeFeature.Water) session.device else it.device,
            scanning = if (it.feature == LifeFeature.Water) false else it.scanning) }
        withContext(io) { store.saveSession(session) }
    }
    private fun resumeWater() {
        if (mutable.value.feature == LifeFeature.Laundry) { resumeLaundry(); return }
        if (!foreground || account == null || waterTask?.isActive == true || pollTask?.isActive == true) return
        val session = mutable.value.water ?: return
        if (!session.active && (session.phase != WaterPhase.Ended || session.finalized)) return
        if (session.phase == WaterPhase.Ended && mutable.value.error != null) return
        val token = account!!.token
        pollTask = viewModelScope.launch {
            while (isActive && foreground && mutable.value.feature == LifeFeature.Water && mutable.value.water?.let { it.active || !it.finalized } == true) {
                try { readWater(token) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; if (expired(e)) break }
                if (mutable.value.water?.phase == WaterPhase.Ended) break
                delay(if (mutable.value.error == null) 1500 else 5000)
            }
        }
    }
    fun loadLaundryQuote() {
        val value = mutable.value
        if (value.feature != LifeFeature.Laundry || value.laundry?.active == true || value.loadingDevice || value.device?.laundryError != null) return
        val device = value.device ?: return
        val token = account?.token ?: return requireLogin()
        val service = laundryApi ?: return
        quoteTask?.cancel()
        quoteTask = viewModelScope.launch {
            mutable.update { it.copy(quoteLoading = true, laundryQuote = null, error = device.laundryError) }
            try {
                if (device.laundryError != null) return@launch
                val quote = service.laundryQuote(device, value.selectedSkuId, token)
                if (mutable.value.device?.id == device.id && mutable.value.selectedSkuId == quote.skuId)
                    mutable.update { it.copy(laundryQuote = quote, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; expired(e) }
            finally { mutable.update { it.copy(quoteLoading = false) } }
        }
    }
    fun dismissLaundryPayment() { mutable.update { it.copy(paymentVisible = false) } }
    fun showLaundryPayment() {
        val value = mutable.value
        if (value.laundryBusy || value.quoteLoading || value.feature != LifeFeature.Laundry) return
        val session = value.laundry
        if (session != null && session.active) {
            if (session.phase != LaundryPhase.Paying || session.orderNo.isBlank()) return
            laundryWork { token, service ->
                if (readLaundryOrder(token, service)) return@laundryWork
                if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                    saveLaundrySession(checkNotNull(mutable.value.laundry).copy(paid = true, phase = LaundryPhase.Starting))
                    waitLaundryStart(token, service)
                    return@laundryWork
                }
                val channels = service.laundryChannels(session, token)
                mutable.update { it.copy(laundryQuote = LaundryQuote(session.device.id, session.skuId, session.price, session.minutes, channels),
                    paymentVisible = true, error = null) }
            }
        } else if (value.laundryQuote != null && value.device?.laundryError == null)
            mutable.update { it.copy(paymentVisible = true, error = null) }
    }
    fun payLaundry(method: String) {
        val value = mutable.value
        val quote = value.laundryQuote ?: return
        val device = value.device ?: return
        if (value.feature != LifeFeature.Laundry || !value.paymentVisible || value.laundryBusy || value.quoteLoading ||
            quote.deviceId != device.id || (value.laundry == null && quote.skuId != value.selectedSkuId) ||
            quote.channels.none { it.method == method && it.laundryUnavailableReason() == null }) return
        laundryWork { token, service ->
            mutable.update { it.copy(paymentVisible = false) }
            var session = mutable.value.laundry
            if (session == null || !session.active) {
                session = LaundrySession(device, quote.skuId, quote.price, method, minutes = quote.minutes)
                saveLaundrySession(session)
                checkLaundryReady(session, token, service)
                session = session.copy(phase = LaundryPhase.Creating, createSent = true)
                saveLaundrySession(session) // A lost create response must never create a second order.
                val order = service.createLaundryOrder(device.id, quote.skuId, token)
                session = session.copy(orderNo = order.orderNo, orderId = order.orderId, phase = LaundryPhase.Paying)
                saveLaundrySession(session)
            } else {
                if (session.phase != LaundryPhase.Paying || session.orderNo.isBlank()) return@laundryWork
                if (readLaundryOrder(token, service)) return@laundryWork
                if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                    saveLaundrySession(checkNotNull(mutable.value.laundry).copy(paid = true, phase = LaundryPhase.Starting))
                    waitLaundryStart(token, service); return@laundryWork
                }
                session = session.copy(payMethod = method)
                saveLaundrySession(session)
                checkLaundryReady(session, token, service)
            }
            if (readLaundryOrder(token, service)) return@laundryWork
            session = checkNotNull(mutable.value.laundry)
            saveLaundrySession(session.copy(phase = LaundryPhase.Paying, paymentSent = true))
            if (method == "2") {
                service.payLaundryBalance(session.orderNo, token)
                waitLaundryPaid(token, service)
            } else {
                val payload = service.prepareLaundryAlipay(session.orderNo, token)
                mutable.update { it.copy(paymentLaunch = LifeAlipayLaunch(++paymentSequence, session.orderNo, payload)) }
            }
        }
    }
    fun laundryPaymentReturned(requestId: Long, resultStatus: String?) {
        if (mutable.value.paymentLaunch?.id != requestId) return
        mutable.update { it.copy(paymentLaunch = null) }
        paymentInFlight = null
        // SDK callbacks do not prove payment. Always read the server for this order.
        laundryWork { token, service ->
            if (resultStatus == "6001" && mutable.value.laundry?.let { !service.laundryPaid(it.orderNo, it.payMethod, token) } == true)
                throw LifeDeviceException("支付已取消，可继续支付同一订单")
            waitLaundryPaid(token, service)
        }
    }
    fun claimLaundryPayment(requestId: Long): Boolean {
        if (mutable.value.paymentLaunch?.id != requestId || paymentInFlight != null) return false
        paymentInFlight = requestId
        return true
    }
    fun launchLaundryPayment(requestId: Long, execute: suspend () -> String?) {
        if (!claimLaundryPayment(requestId)) return
        viewModelScope.launch {
            val status = withContext(io) { execute() }
            laundryPaymentReturned(requestId, status)
        }
    }
    fun retryLaundry() {
        val session = mutable.value.laundry ?: return loadLaundryQuote()
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
            }
        }
    }
    private fun laundryWork(block: suspend (String, LifeLaundryService) -> Unit) {
        if (laundryTask?.isActive == true) return
        val token = account?.token ?: return requireLogin()
        val service = laundryApi ?: return
        pollTask?.cancel(); pollTask = null
        laundryTask = viewModelScope.launch {
            mutable.update { it.copy(laundryBusy = true, error = null) }
            try { block(token, service) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; expired(e) }
            finally { laundryTask = null; mutable.update { it.copy(laundryBusy = false) }; resumeWater() }
        }
    }
    private suspend fun checkLaundryReady(session: LaundrySession, token: String, service: LifeLaundryService) {
        val message = service.checkLaundryDevice(session.device.id, token)
        repeat(20) {
            if (service.laundryMachineReady(message, token)) return
            delay(1000)
        }
        throw LifeDeviceException("设备响应较慢，请留意现场状态后重试")
    }
    private suspend fun waitLaundryPaid(token: String, service: LifeLaundryService) {
        val session = mutable.value.laundry ?: return
        repeat(20) {
            if (service.laundryPaid(session.orderNo, session.payMethod, token)) {
                saveLaundrySession(session.copy(paid = true, phase = LaundryPhase.Starting))
                waitLaundryStart(token, service); return
            }
            delay(1000)
        }
        throw LifeDeviceException("支付尚未完成，可继续支付同一订单")
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
    private suspend fun readLaundryOrder(token: String, service: LifeLaundryService): Boolean {
        val session = mutable.value.laundry ?: return false
        if (session.orderNo.isBlank()) throw LifeDeviceException("订单创建结果未返回，请在胖乖生活核对，避免重复下单")
        val reading = service.laundryOrder(session.orderNo, token)
        if (reading.orderNo != session.orderNo || reading.orderId != session.orderId || reading.deviceId != session.device.id || reading.skuId != session.skuId)
            throw LifeDeviceException("洗衣订单信息不匹配，请在胖乖生活核对")
        if (reading.price.toBigDecimalOrNull()?.compareTo(session.price.toBigDecimalOrNull()) != 0)
            throw LifeDeviceException("订单价格与所选程序不一致，请在胖乖生活核对")
        val next = session.copy(minutes = reading.minutes ?: session.minutes,
            startedAtMillis = reading.paidAtMillis ?: session.startedAtMillis,
            expectedEndMillis = reading.expectedEndMillis ?: session.expectedEndMillis,
            finishedAtMillis = reading.finishedAtMillis ?: session.finishedAtMillis)
        when (reading.status) {
            3 -> saveLaundrySession(next.copy(phase = LaundryPhase.Ended, paid = true))
            2 -> if (session.phase != LaundryPhase.Ended) saveLaundrySession(next.copy(phase = LaundryPhase.Running, paid = true))
            0 -> if (session.phase !in setOf(LaundryPhase.Ended, LaundryPhase.Running)) saveLaundrySession(next)
            else -> throw LifeDeviceException("暂时无法读取洗衣状态，请在胖乖生活核对")
        }
        mutable.update { it.copy(error = null) }
        return mutable.value.laundry?.phase in setOf(LaundryPhase.Running, LaundryPhase.Ended)
    }
    private suspend fun saveLaundrySession(session: LaundrySession) {
        mutable.update { it.copy(laundry = session, device = if (it.feature == LifeFeature.Laundry) session.device else it.device,
            scanning = if (it.feature == LifeFeature.Laundry) false else it.scanning) }
        withContext(io) { store.saveLaundrySession(session) }
    }
    private fun resumeLaundry() {
        if (!foreground || account == null || laundryTask?.isActive == true || pollTask?.isActive == true) return
        val session = mutable.value.laundry ?: return
        if (!session.active || session.orderNo.isBlank() || mutable.value.error != null && session.phase != LaundryPhase.Running || mutable.value.paymentLaunch != null) return
        val token = account!!.token
        val service = laundryApi ?: return
        pollTask = viewModelScope.launch {
            while (isActive && foreground && mutable.value.feature == LifeFeature.Laundry && mutable.value.laundry?.active == true) {
                try {
                    val current = checkNotNull(mutable.value.laundry)
                    if (current.phase == LaundryPhase.Paying && !current.paid && service.laundryPaid(current.orderNo, current.payMethod, token))
                        saveLaundrySession(current.copy(paid = true, phase = LaundryPhase.Starting))
                    readLaundryOrder(token, service)
                    if (mutable.value.laundry?.phase == LaundryPhase.Starting && service.laundryStarted(current.orderNo, token))
                        saveLaundrySession(checkNotNull(mutable.value.laundry).copy(phase = LaundryPhase.Running, paid = true))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { mutable.update { it.copy(error = failure(e)) }; if (expired(e)) break }
                if (mutable.value.laundry?.active != true) break
                delay(if (mutable.value.error == null) 5000 else 10000)
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
