package com.mercury.yktwx

import android.app.Application
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

data class Hui798UiState(
    val restoring: Boolean = true, val connectedPhone: String = "",
    val loginVisible: Boolean = false, val phone: String = "", val code: String = "", val imageCode: String = "",
    val captcha: ByteArray? = null, val captchaLoading: Boolean = false,
    val sendingCode: Boolean = false, val loggingIn: Boolean = false, val codeSeconds: Int = 0,
    val loginError: String? = null, val device: Hui798Device? = null, val loadingDevice: Boolean = false,
    val deviceReady: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val scannedDeviceId: String? = null,
    val deviceInUse: Boolean = false,
    val sessions: List<Hui798Session> = emptyList(), val selectedSessionId: String? = null,
    val confirmedSessions: Set<String> = emptySet(),
) {
    val session: Hui798Session? get() = sessions.firstOrNull { it.sessionId == selectedSessionId }
    val connected: Boolean get() = connectedPhone.isNotBlank()
}

/** Uses our water command, persistence and foreground polling pattern, with a separate 798 account. */
class Hui798ViewModel internal constructor(
    app: Application, private val api: Hui798Service, private val store: Hui798Store,
    private val io: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, Hui798Api(), Hui798AccountStore(app))
    private val mutable = MutableStateFlow(Hui798UiState())
    val state = mutable.asStateFlow()
    private var account: Hui798Account? = null
    private var ownerPhone = ""
    private var generation = 0L
    private var pendingDeviceId: String? = null
    private var captchaKey = ""
    private var foreground = false
    private var visible = false
    private val persistence = Mutex()
    private val sessionErrors = mutableMapOf<String, String>()
    private var deviceTask: Job? = null
    private var commandTask: Job? = null
    private var authTask: Job? = null
    private var captchaTask: Job? = null
    private var timerTask: Job? = null
    private var pollTask: Job? = null
    private var recoveryInProgress = 0

    init {
        viewModelScope.launch {
            try {
                val saved = withContext(io) { store.account() to store.sessions() }
                account = saved.first; ownerPhone = account?.phone.orEmpty()
                mutable.update { it.copy(connectedPhone = account?.maskedPhone.orEmpty()) }
                account?.let { recoverSessions(it, saved.second.filter { session -> session.active }) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutable.update { it.copy(error = failure(e)) }
                account?.let { expired(e, it) }
            }
            mutable.update { it.copy(restoring = false) }
            resumeSessions(waitBeforeFirstRead = true)
        }
    }

    fun connectAccount(clearPending: Boolean = true) {
        if (mutable.value.restoring || mutable.value.busy || mutable.value.loggingIn) return
        if (clearPending) pendingDeviceId = null
        mutable.update { it.copy(loginVisible = true, loginError = null, code = "", imageCode = "") }
        refreshCaptcha()
    }
    fun dismissLogin() {
        authTask?.cancel(); captchaTask?.cancel(); captchaKey = ""
        mutable.update { it.copy(loginVisible = false, sendingCode = false, loggingIn = false,
            captchaLoading = false, captcha = null, code = "", imageCode = "", loginError = null) }
    }
    fun phoneChanged(value: String) {
        if (mutable.value.loggingIn || mutable.value.sendingCode) return
        mutable.update { it.copy(phone = value.filter { c -> c in '0'..'9' }.take(11), code = "", loginError = null) }
    }
    fun codeChanged(value: String) {
        if (!mutable.value.loggingIn) mutable.update { it.copy(code = value.filter { c -> c in '0'..'9' }.take(8), loginError = null) }
    }
    fun imageCodeChanged(value: String) {
        if (!mutable.value.sendingCode && !mutable.value.loggingIn && !mutable.value.captchaLoading)
            mutable.update { it.copy(imageCode = value.take(8), loginError = null) }
    }
    fun refreshCaptcha() {
        if (!mutable.value.loginVisible || mutable.value.loggingIn || mutable.value.sendingCode) return
        captchaTask?.cancel(); captchaKey = ""
        val key = java.util.UUID.randomUUID().toString().replace("-", "")
        captchaTask = viewModelScope.launch {
            mutable.update { it.copy(captchaLoading = true, captcha = null, imageCode = "", loginError = null) }
            try {
                val bytes = api.captcha(key)
                val bitmap = withContext(io) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                    ?: throw Hui798Exception("图形验证码加载失败，请刷新")
                bitmap.recycle()
                ensureActive()
                captchaKey = key
                mutable.update { it.copy(captchaLoading = false, captcha = bytes) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(captchaLoading = false, loginError = failure(e)) } }
        }
    }
    fun sendCode() {
        val value = mutable.value
        if (!value.loginVisible || value.loggingIn || value.sendingCode || value.captchaLoading || value.codeSeconds > 0) return
        if (!value.phone.matches(Regex("1[0-9]{10}")) || value.imageCode.isBlank() || captchaKey.isBlank()) {
            mutable.update { it.copy(loginError = "请输入正确的手机号和图形验证码") }; return
        }
        val key = captchaKey
        authTask = viewModelScope.launch {
            mutable.update { it.copy(sendingCode = true, loginError = null) }
            try {
                api.sendCode(value.phone, value.imageCode, key)
                ensureActive()
                mutable.update { it.copy(sendingCode = false, codeSeconds = 60) }
                timerTask?.cancel()
                timerTask = viewModelScope.launch {
                    while (mutable.value.codeSeconds > 0) {
                        delay(1000); mutable.update { it.copy(codeSeconds = (it.codeSeconds - 1).coerceAtLeast(0)) }
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // The old image/key must not be submitted again after a rejected SMS request.
                captchaKey = ""
                mutable.update { it.copy(sendingCode = false, captcha = null, imageCode = "", loginError = failure(e)) }
            }
        }
    }
    fun login() {
        val value = mutable.value
        if (!value.loginVisible || value.loggingIn || value.sendingCode) return
        if (!value.phone.matches(Regex("1[0-9]{10}")) || value.code.isBlank()) {
            mutable.update { it.copy(loginError = "请输入手机号和短信验证码") }; return
        }
        authTask = viewModelScope.launch {
            mutable.update { it.copy(loggingIn = true, loginError = null) }
            try {
                val next = api.login(value.phone, value.code)
                generation++; deviceTask?.cancel(); pollTask?.cancel(); pollTask = null
                val sameOwner = ownerPhone == next.phone
                if (!sameOwner) sessionErrors.clear()
                persistence.withLock {
                    withContext(io) { store.saveAccount(next) }
                    ensureActive()
                    account = next; ownerPhone = next.phone
                }
                val candidates = withContext(io) { store.sessions().filter { it.active } }
                val sessions = if (sameOwner) mutable.value.sessions else emptyList()
                mutable.update { it.copy(loggingIn = false, loginVisible = false, connectedPhone = next.maskedPhone,
                    code = "", imageCode = "", captcha = null, sessions = sessions, confirmedSessions = emptySet(),
                    selectedSessionId = it.selectedSessionId?.takeIf { id -> sessions.any { item -> item.sessionId == id } }, error = null) }
                try { recoverSessions(next, candidates) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (account?.phone == next.phone && account?.token == next.token) {
                        mutable.update { it.copy(error = failure(e)) }; account?.let { expired(e, it) }
                    }
                }
                pendingDeviceId?.takeIf { visible }?.let(::loadDevice)
                resumeSessions(waitBeforeFirstRead = true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(loggingIn = false, loginError = failure(e)) } }
        }
    }

    fun openDevice(id: String) {
        if (mutable.value.busy || mutable.value.restoring) return
        pendingDeviceId = id; generation++; deviceTask?.cancel()
        mutable.update { it.copy(device = null, scannedDeviceId = id, deviceReady = false, deviceInUse = false,
            loadingDevice = false, selectedSessionId = null, error = null) }
        if (account != null) loadDevice(id)
    }
    fun refreshDevice() { if (!mutable.value.busy && !mutable.value.loadingDevice) pendingDeviceId?.let(::loadDevice) }
    private fun loadDevice(id: String) {
        val owner = account ?: return
        val request = ++generation
        deviceTask?.cancel()
        deviceTask = viewModelScope.launch {
            mutable.update { it.copy(loadingDevice = true, deviceReady = false, deviceInUse = false, error = null) }
            try {
                val device = api.device(id, owner.token)
                if (request != generation || account != owner) return@launch
                mutable.update { it.copy(device = device) }
                val reading = api.status(id, owner.token)
                if (request != generation || account != owner) return@launch
                mutable.update { it.copy(device = device, loadingDevice = false, deviceReady = reading.status == 99,
                    deviceInUse = reading.status == 1, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request != generation || account != owner) return@launch
                mutable.update { it.copy(loadingDevice = false, error = failure(e)) }; expired(e, owner)
            }
        }
    }
    fun openSession(id: String) {
        if (mutable.value.busy) return
        val session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        generation++; deviceTask?.cancel(); pendingDeviceId = null
        mutable.update { it.copy(device = session.device, scannedDeviceId = null, selectedSessionId = id, deviceReady = false,
            deviceInUse = false, loadingDevice = false, error = sessionErrors[id]) }
        resumeSessions()
    }
    fun dismissSession(id: String) {
        val session = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
        if (session.active) return
        mutable.update { it.copy(sessions = it.sessions.filterNot { item -> item.sessionId == id },
            selectedSessionId = it.selectedSessionId?.takeUnless { selected -> selected == id },
            device = it.device.takeUnless { _ -> it.selectedSessionId == id }, confirmedSessions = it.confirmedSessions - id) }
        sessionErrors.remove(id)
        viewModelScope.launch { try { persist() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = failure(e)) } } }
    }
    fun pageVisible(active: Boolean) {
        visible = active
        if (!active) { generation++; deviceTask?.cancel(); mutable.update { it.copy(loadingDevice = false) } }
        else if (mutable.value.session == null && !mutable.value.deviceReady && !mutable.value.loadingDevice &&
            mutable.value.error == null && account != null) pendingDeviceId?.let(::loadDevice)
    }
    fun environment(active: Boolean) {
        foreground = active
        if (active) resumeSessions() else { pollTask?.cancel(); pollTask = null; mutable.update { it.copy(confirmedSessions = emptySet()) } }
    }
    fun startWater() {
        val value = mutable.value; val device = value.device ?: return; val owner = account ?: return
        if (value.busy || value.loadingDevice || !value.deviceReady || value.session != null) return
        if (owner.userId == null || recoveryInProgress > 0) {
            mutable.update { it.copy(error = "正在核对本账号的用水设备，请稍后刷新") }; return
        }
        if (value.sessions.any { it.active }) {
            mutable.update { it.copy(error = "还有设备正在用水，请先从付款页查看进度") }; return
        }
        runCommand(owner) {
            val reading = api.status(device.id, owner.token)
            if (account != owner) return@runCommand
            if (reading.status == 1) {
                mutable.update { it.copy(deviceReady = false, deviceInUse = true, error = null) }
                return@runCommand
            }
            if (reading.status != 99) throw Hui798Exception("暂时无法确认设备状态，请刷新")
            val sent = Hui798Session(device, attemptedAtMillis = now())
            saveSession(sent, select = true)
            try { api.start(device.id, owner.token) }
            catch (e: Hui798Exception) {
                if (e.rejected && !e.loginExpired) {
                    mutable.update { it.copy(sessions = it.sessions.filterNot { item -> item.sessionId == sent.sessionId }, selectedSessionId = null) }
                    persist()
                }
                throw e
            }
            if (account != owner) return@runCommand
            saveSession(sent.copy(startAcknowledged = true))
            waitForDevice(owner, sent.sessionId, allowBind = true)
        }
    }
    fun stopWater() {
        val session = mutable.value.session ?: return; val owner = account ?: return
        if (mutable.value.busy || session.phase != WaterPhase.Running) return
        runCommand(owner) {
            readSession(owner, session.sessionId, verifyUser = true)
            val current = mutable.value.sessions.firstOrNull { it.sessionId == session.sessionId } ?: return@runCommand
            if (current.phase != WaterPhase.Running || session.sessionId !in mutable.value.confirmedSessions) return@runCommand
            saveSession(current.copy(phase = WaterPhase.Stopping))
            try { api.end(current.device.id, owner.token) }
            catch (e: Hui798Exception) {
                if (e.rejected && !e.loginExpired) saveSession(current)
                throw e
            }
            waitForDevice(owner, session.sessionId)
        }
    }
    /** Retrying a read never replays a start or end command. */
    fun refreshState() {
        val session = mutable.value.session ?: return refreshDevice(); val owner = account ?: return
        if (mutable.value.busy || !session.active) return
        runCommand(owner) {
            readSession(owner, session.sessionId)
            val current = mutable.value.sessions.firstOrNull { it.sessionId == session.sessionId }
            if (current?.phase == WaterPhase.Stopping && current.sessionId in mutable.value.confirmedSessions)
                saveSession(current.copy(phase = WaterPhase.Running))
        }
    }
    private fun runCommand(owner: Hui798Account, block: suspend () -> Unit) {
        if (commandTask?.isActive == true) return
        pollTask?.cancel(); pollTask = null
        commandTask = viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (account == owner) { error(failure(e), mutable.value.selectedSessionId); expired(e, owner) }
            } finally { commandTask = null; mutable.update { it.copy(busy = false) }; resumeSessions(waitBeforeFirstRead = true) }
        }
    }
    private suspend fun waitForDevice(owner: Hui798Account, id: String, allowBind: Boolean = false) {
        repeat(10) {
            delay(3000)
            readSession(owner, id, allowBind)
            val current = mutable.value.sessions.firstOrNull { it.sessionId == id } ?: return
            if (current.phase in setOf(WaterPhase.Running, WaterPhase.Ended)) return
        }
        throw Hui798Exception("设备响应较慢，请留意现场状态并刷新")
    }
    /** Try candidates once and retain only verified use; failed records never form a retry queue. */
    private suspend fun recoverSessions(owner: Hui798Account, cached: List<Hui798Session>) {
        recoveryInProgress++
        try { recoverOnce(owner, cached) } finally { recoveryInProgress-- }
    }
    private suspend fun recoverOnce(owner: Hui798Account, cached: List<Hui798Session>) {
        if (account != owner) return
        persist()
        val overview = api.overview(owner.token)
        currentCoroutineContext().ensureActive()
        if (account != owner) return
        if (owner.userId != null && owner.userId != overview.userId)
            throw Hui798Exception("慧生活798账号信息不一致，请重新登录", loginExpired = true)
        val verifiedOwner = owner.copy(userId = overview.userId)
        persistence.withLock {
            if (account != owner) return
            if (verifiedOwner != owner) withContext(io) { store.saveAccount(verifiedOwner) }
            if (account != owner) return
            account = verifiedOwner
        }
        val candidates = (overview.devices + cached.map { it.device }).distinctBy { it.id }
        var failed = false
        for (device in candidates) {
            try {
                val use = api.deviceUse(device.id, verifiedOwner.token)
                currentCoroutineContext().ensureActive()
                if (account != verifiedOwner) return
                val existing = mutable.value.sessions.firstOrNull { it.active && it.device.id == device.id }
                if (use.reading.status == 99 || use.userId != null && use.userId != verifiedOwner.userId) {
                    if (existing != null) saveSession(existing.copy(phase = WaterPhase.Ended, endedAtMillis = now()))
                    continue
                }
                if (use.userId == null) throw Hui798Exception("设备未返回使用人，暂时无法恢复接水进度")
                val time = use.reading.sessionTime ?: throw Hui798Exception("设备未返回用水记录，请核对现场状态")
                // A recovery response must not replace a command that was started in this app session.
                if (existing != null) continue
                val previous = cached.firstOrNull { it.device.id == device.id && it.serverTime == time }
                val session = previous?.copy(device = use.device, phase = WaterPhase.Running, endedAtMillis = null)
                    ?: Hui798Session(use.device, WaterPhase.Running, time, true, time, time)
                mutable.update { it.copy(sessions = it.sessions + session, confirmedSessions = it.confirmedSessions + session.sessionId) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (account != verifiedOwner) return
                failed = true
                mutable.update { it.copy(error = failure(e)) }
                expired(e, verifiedOwner)
                if (account != verifiedOwner) return
            }
        }
        if (account != verifiedOwner) return
        if (!failed) {
            if (mutable.value.selectedSessionId == null) error(null, null)
        }
        persist()
    }
    private suspend fun readSession(owner: Hui798Account, id: String, allowBind: Boolean = false, verifyUser: Boolean = false) {
        val before = mutable.value.sessions.firstOrNull { it.sessionId == id && it.active } ?: return
        var use = if (verifyUser || id !in mutable.value.confirmedSessions || before.serverTime == null)
            api.deviceUse(before.device.id, owner.token) else null
        var reading = use?.reading ?: api.status(before.device.id, owner.token)
        // A changed device marker can belong to a later user. Confirm through the home response.
        if (reading.status == 1 && before.serverTime != null && before.serverTime != reading.sessionTime && use == null) {
            use = api.deviceUse(before.device.id, owner.token)
            reading = use.reading
        }
        if (account != owner) return
        val current = mutable.value.sessions.firstOrNull { it.sessionId == id && it.active } ?: return
        if (reading.status == 1) {
            val time = reading.sessionTime ?: throw Hui798Exception("设备未返回用水记录，请核对现场状态")
            if (use != null) {
                if (owner.userId == null || use.userId == null) {
                    mutable.update { it.copy(confirmedSessions = it.confirmedSessions - id) }
                    throw Hui798Exception("暂时无法核对设备使用人，请刷新")
                }
                if (use.userId != owner.userId || current.serverTime != null && current.serverTime != time) {
                    saveSession(current.copy(phase = WaterPhase.Ended, endedAtMillis = now()))
                    mutable.update { it.copy(confirmedSessions = it.confirmedSessions - id) }
                    error(null, id)
                    return
                }
            }
            if (current.serverTime == null &&
                (!current.startAcknowledged || !allowBind && abs(time - current.attemptedAtMillis) > 60_000)) {
                mutable.update { it.copy(confirmedSessions = it.confirmedSessions - id) }
                throw Hui798Exception("设备用水记录已变化，请在慧生活798核对")
            }
            saveSession(current.copy(phase = if (current.phase == WaterPhase.Stopping) WaterPhase.Stopping else WaterPhase.Running,
                serverTime = time, startedAtMillis = current.startedAtMillis ?: now()))
            mutable.update { it.copy(confirmedSessions = it.confirmedSessions + id) }; error(null, id)
        } else if (reading.status == 99 && current.serverTime != null && current.phase != WaterPhase.Starting) {
            saveSession(current.copy(phase = WaterPhase.Ended, endedAtMillis = now()))
            mutable.update { it.copy(confirmedSessions = it.confirmedSessions - id) }; error(null, id)
        }
    }
    private suspend fun saveSession(session: Hui798Session, select: Boolean = false) {
        if (!select && mutable.value.sessions.none { it.sessionId == session.sessionId }) return
        mutable.update { it.copy(sessions = if (select) it.sessions + session else it.sessions.map { item -> if (item.sessionId == session.sessionId) session else item },
            selectedSessionId = if (select) session.sessionId else it.selectedSessionId) }
        persist()
    }
    private suspend fun persist() = persistence.withLock { withContext(io) {
        store.saveSessions(mutable.value.sessions)
    } }
    private fun error(message: String?, id: String?) {
        if (id != null) { if (message == null) sessionErrors.remove(id) else sessionErrors[id] = message }
        mutable.update { if (id == null || it.selectedSessionId == id) it.copy(error = message) else it }
    }
    private suspend fun expired(e: Exception, owner: Hui798Account) {
        if (e !is Hui798Exception || !e.loginExpired || account != owner) return
        persistence.withLock {
            if (account != owner) return
            account = null; generation++
            withContext(io) { store.clearAccount() }
            mutable.update { it.copy(connectedPhone = "", confirmedSessions = emptySet(), deviceReady = false, deviceInUse = false) }
        }
    }
    private fun pollIntervalMillis(): Long = if (mutable.value.sessions.any { it.active && it.phase != WaterPhase.Running }) 3000 else 6000
    private fun resumeSessions(waitBeforeFirstRead: Boolean = false) {
        val owner = account ?: return
        if (!foreground || mutable.value.restoring || commandTask?.isActive == true || pollTask?.isActive == true ||
            mutable.value.sessions.none { it.active }) return
        pollTask = viewModelScope.launch {
            if (waitBeforeFirstRead) delay(pollIntervalMillis())
            while (isActive && foreground && account?.token == owner.token) {
                val currentOwner = account ?: return@launch
                val sessions = mutable.value.sessions.filter { it.active }
                if (sessions.isEmpty()) break
                for (session in sessions) {
                    try { readSession(currentOwner, session.sessionId) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        mutable.update { it.copy(confirmedSessions = it.confirmedSessions - session.sessionId) }
                        error(failure(e), session.sessionId); expired(e, currentOwner)
                        if (account != currentOwner) return@launch
                    }
                }
                delay(pollIntervalMillis())
            }
        }
    }
    private fun failure(e: Exception) = when (e) {
        is Hui798Exception -> e.message ?: "操作失败，请重试"
        is com.mercury.yktwx.data.YktException -> "无法保存账号或用水进度，请检查设备存储"
        else -> "连接失败，请检查网络后重试"
    }
}
