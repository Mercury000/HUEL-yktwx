package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.AccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private var lastSelectedTargets: Set<String> = emptySet()
    private val displayPreferences = app.getSharedPreferences("hall_display", Application.MODE_PRIVATE)
    private fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun displayKey() = digest(owner) + ".selection"
    private fun targetKey(project: FeeProject, binding: FeeRoomBinding) = digest(project.id + "\n" +
        binding.selections.toSortedMap().entries.joinToString("\n") { (key, value) -> "$key:$value" })
    private fun rememberedRoomIndex(rooms: List<DormRoom>) = rooms.indexOfFirst { room ->
        room.bindings.any { targetKey(it.project, it.binding) in lastSelectedTargets }
    }
    private fun roomOrder(room: DormRoom) = when (room.project) {
        FeeProject.Zhengdong -> 0
        FeeProject.Wenbei -> 1
        FeeProject.FamilyElectric, FeeProject.FamilyWater -> 2
        else -> 3
    }
    fun accountChanged(username: String, uiPreview: Boolean = false) {
        val enabledPreview = UiPreview.enabled && uiPreview
        if (owner == username && preview == enabledPreview) return
        generation++; task?.cancel(); task = null; refreshedAt = 0L
        refreshPending = false; managementError = null
        owner = username; preview = enabledPreview
        lastSelectedTargets = if (owner.isBlank()) emptySet() else
            displayPreferences.getStringSet(displayKey(), emptySet()).orEmpty().toSet()
        mutable.value = if (UiPreview.enabled && preview && username.isNotBlank()) PreviewData.hallState() else DormUiState()
        if (mutable.value.rooms.isNotEmpty()) update(selectedIndex = rememberedRoomIndex(mutable.value.rooms).coerceAtLeast(0))
    }
    fun onActivate() {
        if (mutable.value.rooms.isNotEmpty()) update(selectedIndex = rememberedRoomIndex(mutable.value.rooms).coerceAtLeast(0))
        if (!mutable.value.loaded || System.currentTimeMillis() - refreshedAt > 60_000) refresh()
    }
    fun invalidate() { refreshedAt = 0L; if (task?.isActive == true) refreshPending = true }
    fun selectRoom(index: Int) {
        val s = mutable.value
        if (owner.isBlank() || s.managing) return
        val room = s.rooms.getOrNull(index) ?: return
        lastSelectedTargets = room.bindings.map { targetKey(it.project, it.binding) }.toSet()
        displayPreferences.edit().putStringSet(displayKey(), lastSelectedTargets).apply()
        update(selectedIndex = index)
    }

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
            val rooms = previous.rooms.flatMap { it.bindings }.associateBy { it.project }.toMutableMap()
            val errors = mutableMapOf<FeeProject, String>()
            val bindingProjects = mutableSetOf<FeeProject>()
            val completed = mutableSetOf<FeeProject>()
            var bindingsKnown = false
            var needsLogin = false
            var network: FeeQuery? = previous.network
            var networkError: String? = previous.networkError
            fun publish(finished: Boolean = false) {
                if (attempt != generation) return
                val selectedRoom = mutable.value.selectedRoom
                // Until all binding configurations are known, keep the prior room or its placeholder.
                // A faster detail response must not choose a lower-priority room for the user.
                val merged = if (bindingsKnown) mergeFamilyRooms(FeeProject.entries.mapNotNull { rooms[it] })
                    .sortedBy(::roomOrder) else previous.rooms
                val blockError = listOfNotNull(managementError,
                    errors.takeIf { it.isNotEmpty() && merged.isEmpty() }?.entries?.sortedBy { it.key.ordinal }
                        ?.joinToString("\n") { it.key.label + "：" + it.value })
                    .takeIf { it.isNotEmpty() }?.joinToString("\n")
                    ?: previous.error.takeIf { !finished }
                val exactIndex = merged.indexOfFirst { current -> selectedRoom?.bindings?.any { old ->
                    current.bindings.any { it.project == old.project && it.binding.sameTarget(old.binding) }
                } == true }
                mutable.value = DormUiState(merged, loading = !finished, loaded = previous.loaded || finished,
                    error = blockError, needsLogin = needsLogin,
                    selectedIndex = if (exactIndex >= 0) exactIndex else rememberedRoomIndex(merged).coerceAtLeast(0),
                    network = network, networkError = networkError, bindingProjects = bindingProjects.toSet(),
                    roomsLoaded = previous.roomsLoaded || FeeProject.entries.filterNot { it.network }.all { it in completed },
                    networkLoaded = previous.networkLoaded || FeeProject.Network in completed)
            }
            coroutineScope {
                val configurations = FeeProject.entries.associateWith { project -> async {
                    try { Result.success(repository.feeConfiguration(username, project)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                } }
                launch {
                    val bound = FeeProject.entries.filterNot { it.network }.associateWith {
                        configurations.getValue(it).await().getOrNull()
                    }
                    if (attempt != generation) return@launch
                    for ((project, config) in bound) {
                        if (config == null || config.invalidRoomBinding) continue
                        val binding = config.roomBinding.takeIf { config.roomBindingEnabled }
                        if (binding == null) rooms.remove(project)
                        else if (rooms[project]?.binding?.sameTarget(binding) != true) {
                            val kind = DormMeterDiscovery.kind(binding.names.values.last())
                            val pending = when (project) {
                                FeeProject.Zhengdong, FeeProject.Wenbei -> if (kind == DormMeterKind.Other)
                                    listOf(DormMeterSlot(kind, project)) else listOf(
                                        DormMeterSlot(DormMeterKind.Lighting, project), DormMeterSlot(DormMeterKind.AirConditioning, project))
                                FeeProject.FamilyElectric, FeeProject.FamilyWater -> listOf(
                                    DormMeterSlot(DormMeterKind.Other, FeeProject.FamilyElectric),
                                    DormMeterSlot(DormMeterKind.Other, FeeProject.FamilyWater))
                                else -> listOf(DormMeterSlot(kind, project))
                            }
                            rooms[project] = DormRoom(project, binding, emptyList(), 0, pendingMeters = pending)
                        }
                    }
                    bindingsKnown = true
                    publish()
                }
                FeeProject.entries.forEach { project -> launch {
                    if (project.network) {
                        try {
                            configurations.getValue(project).await().getOrThrow()
                            val query = repository.feeQuery(username, project, 0, emptyMap(), true)
                            query.tip?.let { throw YktException(it) }
                            network = query
                            networkError = null
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            needsLogin = needsLogin || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                            network = null
                            networkError = (e as? YktException)?.message ?: "校园网读取失败"
                        }
                    } else {
                        try {
                            val config = configurations.getValue(project).await().getOrThrow()
                            if (attempt != generation) return@launch
                            if (config.roomBindingEnabled) bindingProjects += project
                            if (config.invalidRoomBinding) throw YktException("已绑定房间无法识别，请在管理中核实")
                            if (config.roomBindingEnabled && config.roomBinding != null) {
                                val other = if (project == FeeProject.FamilyWater) FeeProject.FamilyElectric else FeeProject.FamilyWater
                                val room = repository.dormRoom(username, config,
                                    otherConfiguration = { configurations.getValue(other).await().getOrNull() },
                                    onUpdate = { partial -> withContext(Dispatchers.Main.immediate) {
                                        if (attempt == generation && repository.ownsAccount(username)) {
                                            val old = rooms[project]?.takeIf { it.binding.sameTarget(partial.binding) }
                                            val retained = old?.meters.orEmpty().filter { meter ->
                                                partial.pendingMeters.any { it.kind == meter.kind && it.project == (meter.project ?: project) }
                                            }.map { DormMeter(it.kind, it.room, it.query, it.error, it.project ?: project, updating = true) }
                                            rooms[project] = DormRoom(partial.project, partial.binding, partial.meters + retained,
                                                partial.updatedAtMillis, partial.notice, pendingMeters = partial.pendingMeters)
                                            publish()
                                        }
                                    } })
                                if (room != null) rooms[project] = room else rooms.remove(project)
                            } else rooms.remove(project)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            needsLogin = needsLogin || e is AuthenticationRequired || e is InvalidCredentials || e is CaptchaRequired
                            errors[project] = (e as? YktException)?.message ?: "读取失败，请重试"
                            val old = previous.rooms.flatMap { it.bindings }.firstOrNull { it.project == project }
                            if (old != null) rooms[project] = DormRoom(old.project, old.binding,
                                old.meters.map { DormMeter(it.kind, it.room, null, "更新失败", it.project ?: old.project) }, old.updatedAtMillis, old.notice)
                            else rooms.remove(project)
                        }
                    }
                    completed += project
                    publish()
                } }
            }
            if (attempt != generation) return@launchTask
            publish(finished = true)
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
            selectedIndex.coerceIn(0, (rooms.size - 1).coerceAtLeast(0)), s.network, s.networkError, managing, s.bindingProjects,
            s.roomsLoaded, s.networkLoaded)
    }
}
