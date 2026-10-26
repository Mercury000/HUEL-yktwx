package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DormUiState(val rooms: List<DormRoom> = emptyList(), val loading: Boolean = false, val loaded: Boolean = false,
    val error: String? = null, val needsLogin: Boolean = false, val selectedIndex: Int = 0,
    val network: FeeQuery? = null, val networkError: String? = null, val managing: Boolean = false,
    val bindingProjects: Set<FeeProject> = emptySet()) {
    val selectedRoom get() = rooms.getOrNull(selectedIndex) ?: rooms.firstOrNull()
    override fun toString() = "DormUiState([redacted])"
}
class DormViewModel internal constructor(app: Application, private val repository: AccountRepository) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(DormUiState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var preview = false
    private var generation = 0L
    private var refreshedAt = 0L
    private var task: Job? = null
    fun accountChanged(username: String, uiPreview: Boolean = false) {
        if (owner == username && preview == uiPreview) return
        generation++; task?.cancel(); task = null; refreshedAt = 0L
        owner = username; preview = uiPreview
        mutable.value = if (preview && username.isNotBlank()) previewState() else DormUiState()
    }
    fun onActivate() { if (!mutable.value.loaded || System.currentTimeMillis() - refreshedAt > 60_000) refresh() }
    fun invalidate() { refreshedAt = 0L }
    fun refresh() {
        if (owner.isBlank() || preview || task?.isActive == true) return
        val attempt = generation
        val username = owner
        val existing = mutable.value.rooms
        mutable.value = DormUiState(existing, loading = true, loaded = mutable.value.loaded)
        task = viewModelScope.launch {
            val rooms = mutableListOf<DormRoom>()
            val errors = mutableListOf<String>()
            var needsLogin = false
            for (project in listOf(FeeProject.Zhengdong, FeeProject.Wenbei)) {
                try {
                    val config = repository.feeConfiguration(username, project)
                    if (attempt != generation) return@launch
                    if (config.invalidRoomBinding) throw YktException("已保存的房间信息无法识别，请在缴费页核实")
                    if (config.roomBindingEnabled && config.roomBinding != null) repository.dormRoom(username, config)?.let(rooms::add)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    needsLogin = needsLogin || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                    errors += project.label + "：" + ((e as? YktException)?.message ?: "读取失败，请稍后重试")
                    existing.filter { it.project == project }.forEach { old ->
                        rooms += DormRoom(old.project, old.binding,
                            old.meters.map { DormMeter(it.kind, it.room, it.query, "更新失败") }, old.updatedAtMillis, old.notice)
                    }
                }
            }
            if (attempt != generation) return@launch
            mutable.value = DormUiState(rooms, loaded = true, error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                needsLogin = needsLogin)
            refreshedAt = System.currentTimeMillis()
        }
    }
    private fun previewState(): DormUiState {
        val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("building", 2, "楼栋"), FeeLevel("floor", 3, "楼层"), FeeLevel("room", 4, "电表"))
        fun room(kind: DormMeterKind) = FeeRoomBinding.from(levels,
            mapOf("campus" to "demo-campus", "building" to "demo-building", "floor" to "demo-floor", "room" to "demo-${kind.name}"),
            mapOf("campus" to "示例校区", "building" to "示例A栋", "floor" to "示例楼层", "room" to "示例A栋-房间A${kind.label}"))!!
        val lighting = room(DormMeterKind.Lighting)
        return DormUiState(listOf(DormRoom(FeeProject.Zhengdong, lighting, listOf(
            DormMeter(DormMeterKind.Lighting, lighting, FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "86.4 度"))),
            DormMeter(DormMeterKind.AirConditioning, room(DormMeterKind.AirConditioning), FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "32.8 度"))),
        ), System.currentTimeMillis())), loaded = true)
    }
}
