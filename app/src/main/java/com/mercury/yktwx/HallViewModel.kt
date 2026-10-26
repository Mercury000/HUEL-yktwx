package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HallViewModel internal constructor(
    app: Application, private val repository: AccountRepository,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app))
    private val mutable = MutableStateFlow(DormUiState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var preview = false
    private var generation = 0L
    private var refreshedAt = 0L
    private var task: Job? = null
    private var refreshPending = false
    private var managementError: String? = null
    private val displayPreferences = app.getSharedPreferences("hall_display", Application.MODE_PRIVATE)
    private fun displayKey() = java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun accountChanged(username: String, uiPreview: Boolean = false) {
        if (owner == username && preview == uiPreview) return
        generation++; task?.cancel(); task = null; refreshedAt = 0L
        refreshPending = false; managementError = null
        owner = username; preview = uiPreview
        mutable.value = if (preview && username.isNotBlank()) previewState() else DormUiState()
    }
    fun onActivate() { if (!mutable.value.loaded || System.currentTimeMillis() - refreshedAt > 60_000) refresh() }
    fun invalidate() { refreshedAt = 0L; if (task?.isActive == true) refreshPending = true }
    fun selectRoom(index: Int) { if (!mutable.value.managing && index in mutable.value.rooms.indices) update(selectedIndex = index) }
    fun setDefault(room: DormRoom) {
        if (owner.isBlank() || mutable.value.managing) return
        displayPreferences.edit().putString(displayKey(), room.project.id).apply()
        selectRoom(mutable.value.rooms.indexOf(room))
    }
    fun isDefault(room: DormRoom) = room.bindings.any { displayPreferences.getString(displayKey(), null) == it.project.id }
    fun remove(room: DormRoom) {
        if (owner.isBlank() || mutable.value.managing || task?.isActive == true || mutable.value.rooms.none { it === room }) return
        val attempt = generation
        val username = owner
        managementError = null
        update(managing = true, error = null)
        launchTask {
            try {
                if (!preview) {
                    for (source in room.bindings) {
                        val current = repository.feeConfiguration(username, source.project)
                        if (attempt != generation) return@launchTask
                        if (!current.roomBindingEnabled || current.invalidRoomBinding || !source.binding.sameTarget(current.roomBinding)) throw YktException("绑定已变化，请刷新后核实")
                        var failure: Exception? = null
                        try { repository.setFeeRoom(username, source.project, null) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = e }
                        if (attempt != generation) return@launchTask
                        val fresh = repository.feeConfiguration(username, source.project)
                        if (attempt != generation) return@launchTask
                        if (!fresh.roomBindingEnabled || fresh.invalidRoomBinding || fresh.roomBinding != null) throw failure ?: YktException("解绑结果未确认，请刷新后核实")
                    }
                }
                if (attempt != generation) return@launchTask
                val selected = mutable.value.selectedRoom
                val remaining = mutable.value.rooms.filterNot { it === room }
                update(rooms = remaining, selectedIndex = remaining.indexOfFirst { it === selected }.coerceAtLeast(0))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (attempt == generation) {
                    managementError = (e as? YktException)?.message ?: "解绑结果未确认，请刷新后核实"
                    update(error = managementError)
                }
            } finally {
                if (attempt == generation) { update(managing = false); invalidate(); refreshPending = !preview }
            }
        }
    }
    fun refresh() {
        managementError = null
        startRefresh()
    }
    private fun startRefresh() {
        if (owner.isBlank() || preview || task?.isActive == true) return
        val attempt = generation
        val username = owner
        val previous = mutable.value
        refreshPending = false
        update(loading = true, error = managementError ?: previous.error)
        launchTask {
            val rooms = mutableListOf<DormRoom>()
            val errors = mutableListOf<String>()
            val bindingProjects = mutableSetOf<FeeProject>()
            var needsLogin = false
            var network: FeeQuery? = null
            var networkError: String? = null
            coroutineScope {
                val projects = FeeProject.entries.map { project -> async {
                    if (project.network) {
                        try {
                            repository.feeConfiguration(username, project)
                            val query = repository.feeQuery(username, project, 0, emptyMap(), true)
                            query.tip?.let { throw YktException(it) }
                            network = query
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            needsLogin = needsLogin || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                            networkError = (e as? YktException)?.message ?: "校园网读取失败"
                        }
                        emptyList()
                    } else {
                        try {
                            val config = repository.feeConfiguration(username, project)
                            if (attempt != generation) return@async emptyList()
                            if (config.roomBindingEnabled) bindingProjects += project
                            if (config.invalidRoomBinding) throw YktException("已绑定房间无法识别，请在管理中核实")
                            if (config.roomBindingEnabled && config.roomBinding != null)
                                listOfNotNull(repository.dormRoom(username, config)) else emptyList()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            needsLogin = needsLogin || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                            errors += project.label + "：" + ((e as? YktException)?.message ?: "读取失败，请重试")
                            previous.rooms.flatMap { it.bindings }.filter { it.project == project }.map { old -> DormRoom(old.project, old.binding,
                                old.meters.map { DormMeter(it.kind, it.room, null, "更新失败", it.project ?: old.project) }, old.updatedAtMillis, old.notice) }
                        }
                    }
                } }
                rooms += projects.awaitAll().flatten()
            }
            if (attempt != generation) return@launchTask
            val selectedRoom = mutable.value.selectedRoom
            val selected = selectedRoom?.project?.id ?: displayPreferences.getString(displayKey(), null)
            val merged = mergeFamilyRooms(rooms)
            val blockError = listOfNotNull(managementError, errors.takeIf { it.isNotEmpty() && merged.isEmpty() }?.joinToString("\n"))
                .takeIf { it.isNotEmpty() }?.joinToString("\n")
            val exactIndex = merged.indexOfFirst { current -> selectedRoom?.bindings?.any { old ->
                current.bindings.any { it.project == old.project && it.binding.sameTarget(old.binding) }
            } == true }
            mutable.value = DormUiState(merged, loaded = true, error = blockError,
                needsLogin = needsLogin, selectedIndex = if (exactIndex >= 0) exactIndex else
                    merged.indexOfFirst { it.bindings.any { source -> source.project.id == selected } }.coerceAtLeast(0),
                network = network, networkError = networkError, bindingProjects = bindingProjects)
            refreshedAt = System.currentTimeMillis()
        }
    }
    private fun launchTask(block: suspend CoroutineScope.() -> Unit) {
        val attempt = generation
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { block() }
            finally {
                if (attempt == generation) {
                    task = null
                    if (refreshPending) { refreshPending = false; startRefresh() }
                }
            }
        }
        task = job
        job.start()
    }
    private fun update(rooms: List<DormRoom> = mutable.value.rooms, loading: Boolean = mutable.value.loading,
        error: String? = mutable.value.error, selectedIndex: Int = mutable.value.selectedIndex, managing: Boolean = mutable.value.managing) {
        val s = mutable.value
        mutable.value = DormUiState(rooms, loading, s.loaded, error, s.needsLogin,
            selectedIndex.coerceIn(0, (rooms.size - 1).coerceAtLeast(0)), s.network, s.networkError, managing, s.bindingProjects)
    }
    internal fun previewState(multiple: Boolean = false): DormUiState {
        val levels = listOf(FeeLevel("campus", 1, "校区"), FeeLevel("building", 2, "楼栋"), FeeLevel("floor", 3, "楼层"), FeeLevel("room", 4, "电表"))
        fun demo(project: FeeProject, letter: String, light: String, air: String): DormRoom {
            fun binding(kind: DormMeterKind) = FeeRoomBinding.from(levels,
                mapOf("campus" to "demo-campus", "building" to if (project == FeeProject.Wenbei) "demo-building-$letter-${kind.name}" else "demo-building-$letter",
                    "floor" to if (project == FeeProject.Wenbei) "demo-floor-${kind.name}" else "demo-floor", "room" to "demo-$letter-${kind.name}"),
                mapOf("campus" to project.label.removeSuffix("电费"), "building" to "示例${letter}栋" + if (project == FeeProject.Wenbei) kind.label else "",
                    "floor" to "示例楼层", "room" to "示例${letter}栋-房间${letter}${kind.label}"))!!
            return DormRoom(project, binding(DormMeterKind.Lighting), listOf(
                DormMeter(DormMeterKind.Lighting, binding(DormMeterKind.Lighting), FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "$light 度"))),
                DormMeter(DormMeterKind.AirConditioning, binding(DormMeterKind.AirConditioning), FeeQuery(thirdParty = "{}", display = listOf("剩余电量" to "$air 度"))),
            ), System.currentTimeMillis())
        }
        val rooms = listOf(demo(FeeProject.Zhengdong, "A", "86.4", "32.8")) +
            if (multiple) listOf(demo(FeeProject.Wenbei, "B", "58.2", "21.6")) else emptyList()
        return DormUiState(rooms, loaded = true, network = FeeQuery(thirdParty = "{}", display = listOf("用户套餐" to "学生宽带套餐", "用户状态" to "正常")),
            bindingProjects = setOf(FeeProject.Zhengdong, FeeProject.Wenbei, FeeProject.FamilyElectric, FeeProject.FamilyWater))
    }
}
