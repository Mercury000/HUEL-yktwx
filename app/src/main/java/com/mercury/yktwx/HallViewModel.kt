package com.mercury.yktwx

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.preview.UiPreview
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HallViewModel internal constructor(
    app: Application, private val repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.Hall),
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, AccountRepository.shared(app), initialEnvironment = ReadEnvironment.Inactive)
    private val mutable = MutableStateFlow(DormUiState())
    val state = mutable.asStateFlow()
    private var owner = ""
    private var preview = false
    private var generation = 0L
    private var refreshedAt = 0L
    private val reads = ReadController(viewModelScope, setOf(ReadPage.Hall), initialEnvironment, ::onActivate)
    private fun readQuery() = ReadQuery(owner, ReadQuery.Resource.Hall)
    private var task: Job? = null
    private var displayTask: Job? = null
    private var displayPrepared = false
    private var refreshPending = false
    private var managementError: String? = null
    private val projectReadErrors = mutableMapOf<FeeProject, String>()
    private var lastSelectedTargets: Set<String> = emptySet()
    private val transactionProjects = mutableSetOf<FeeProject>()
    private fun transactionKey(project: FeeProject) = "hall:${project.id}"
    private fun transactionProjectPending(project: FeeProject) = transactionRefresh.needsRead(transactionKey(project))
    private fun transactionPending(): Boolean = !preview && owner.isNotBlank() &&
        transactionProjects.any(::transactionProjectPending)
    private val transactionRefresh: TransactionRefresh = TransactionRefresh(viewModelScope, retryDelaysMillis,
        needsRefresh = { transactionPending() }, isReading = { task?.isActive == true },
        retry = { startRefresh(transactionRetry = true, silent = true, trigger = ReadTrigger.Transaction) })
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
    init {
        repository.observeAccount(viewModelScope) { username, renewed ->
            if (!preview) accountChanged(username.orEmpty(), renewed = renewed)
        }
    }

    fun accountChanged(username: String, uiPreview: Boolean = false, renewed: Boolean = false) {
        val enabledPreview = UiPreview.enabled && uiPreview
        if (!renewed && owner == username && preview == enabledPreview) return
        generation++; task?.cancel(); task = null; displayTask?.cancel(); refreshedAt = 0L
        transactionRefresh.clear(); transactionProjects.clear()
        reads.clear()
        projectReadErrors.clear()
        refreshPending = false; managementError = null
        owner = username; preview = enabledPreview
        displayPrepared = false
        lastSelectedTargets = if (owner.isBlank()) emptySet() else
            displayPreferences.getStringSet(displayKey(), emptySet()).orEmpty().toSet()
        mutable.value = if (UiPreview.enabled && preview && username.isNotBlank()) PreviewData.hallState()
            else DormUiState(restoringDisplay = username.isNotBlank() && !preview)
        if (mutable.value.rooms.isNotEmpty()) update(selectedIndex = rememberedRoomIndex(mutable.value.rooms).coerceAtLeast(0))
        if (!preview && username.isNotBlank()) {
            displayTask = viewModelScope.launch { restoreDisplay(username) }
        }
    }
    fun onActivate() {
        if (mutable.value.rooms.isNotEmpty()) update(selectedIndex = rememberedRoomIndex(mutable.value.rooms).coerceAtLeast(0))
        val current = mutable.value
        if (refreshPending) { startRefresh(trigger = ReadTrigger.Visible); return }
        if (transactionPending() && current.loaded) { startRefresh(transactionRetry = true, trigger = ReadTrigger.Visible); return }
        if (!current.loaded || !ReadCachePolicy.isFresh(refreshedAt) ||
            current.error != null || current.networkError != null || current.rooms.any { room -> room.meters.any { it.error != null } })
            startRefresh(trigger = ReadTrigger.Visible)
    }
    fun invalidate() { refreshedAt = 0L; if (task?.isActive == true) { refreshPending = true; reads.reevaluateWhenIdle() } }
    fun invalidateTransaction(project: FeeProject?) {
        transactionProjects.removeAll { !transactionProjectPending(it) }
        transactionProjects += project?.let { listOf(it) } ?: FeeProject.entries
        transactionRefresh.invalidate()
        refreshedAt = 0L
        if (mutable.value.managing) { refreshPending = true; return }
        generation++; task?.cancel(); task = null
        reads.cancel(readQuery())
        update(loading = false)
    }

    fun onReadEnvironment(environment: ReadEnvironment) {
        reads.update(environment)
        val visible = reads.isVisible
        transactionRefresh.setVisible(visible && environment.online != false && !preview && owner.isNotBlank())
        if (!visible) {
            if (transactionPending() && !mutable.value.managing) {
                generation++; reads.cancel(readQuery()); task?.cancel(); task = null
                update(loading = false)
            }
            return
        }
        if (environment.online == false) {
            return
        }
        onActivate()
    }

    fun setPageVisible(visible: Boolean, online: Boolean = true, recoveryId: Long = 0) {
        onReadEnvironment(ReadEnvironment(
            page = if (visible) ReadPage.Hall else null,
            foreground = true,
            online = online,
            epoch = recoveryId,
        ))
    }
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
        update(managing = true, error = null, errorKind = null)
        launchTask {
            try {
                if (!preview) {
                    for (source in room.bindings) {
                        val current = repository.feeConfiguration(username, source.project)
                        if (attempt != generation) return@launchTask
                        if (!current.roomBindingEnabled || current.invalidRoomBinding || !source.binding.sameTarget(current.roomBinding)) throw YktException("绑定已变化，请刷新后核实")
                        val confirmation = confirmFeeRoomBinding(null,
                            write = { repository.setFeeRoom(username, source.project, null) },
                            read = { repository.feeConfiguration(username, source.project) },
                            isCurrent = { attempt == generation })
                        if (!confirmation.confirmed) throw confirmation.writeFailure ?: YktException("解绑结果未确认，请刷新后核实")
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
                    update(error = managementError, errorKind = ReadFailureKind.Other)
                }
            } finally {
                if (attempt == generation) { update(managing = false); invalidate(); refreshPending = !preview }
            }
        }
    }
    fun refresh() {
        managementError = null
        startRefresh(trigger = ReadTrigger.Pull)
    }
    fun recoverRead(recoveryId: Long) = reads.recover(readQuery(), recoveryId) {
        startRefresh(trigger = ReadTrigger.Reconnect, silent = true)
    }
    private fun startRefresh(transactionRetry: Boolean = false, silent: Boolean = false,
        trigger: ReadTrigger = ReadTrigger.Visible) {
        if (owner.isBlank() || preview || mutable.value.managing) return
        val query = readQuery()
        val readTrigger = if (trigger.automatic && transactionPending()) ReadTrigger.Transaction else trigger
        if (!reads.canStart(query, readTrigger)) return

        val attempt = generation
        val username = owner
        val read = transactionRefresh.started()
        val previousState = mutable.value
        val projects = if (transactionRetry) transactionProjects.filter(::transactionProjectPending)
            else FeeProject.entries
        val errors = if (transactionRetry) projectReadErrors.filterKeys { it !in projects }.toMutableMap()
            else mutableMapOf<FeeProject, String>()
        val errorKinds = if (transactionRetry) mutable.value.projectErrorKinds.filterKeys { it !in projects }.toMutableMap()
            else mutableMapOf<FeeProject, ReadFailureKind>()
        var networkError = mutable.value.networkError.takeUnless { FeeProject.Network in projects }
        var networkErrorKind = mutable.value.networkErrorKind.takeUnless { FeeProject.Network in projects }
        fun blockProjectError() = errors.entries.sortedWith(
            compareBy<Map.Entry<FeeProject, String>> { errorKinds[it.key] == ReadFailureKind.Network }.thenBy { it.key.ordinal })
            .firstOrNull()
        fun blockError() = listOfNotNull(managementError, blockProjectError()?.value)
            .takeIf { it.isNotEmpty() }?.joinToString("\n")
        fun blockErrorKind() = if (managementError != null) ReadFailureKind.Other else blockProjectError()?.let { errorKinds[it.key] }
        refreshPending = false
        update(loading = !silent, error = blockError(), errorKind = blockErrorKind(), networkError = networkError, networkErrorKind = networkErrorKind)
        task = reads.launch(query, readTrigger, retry = { startRefresh(silent = true, trigger = ReadTrigger.Retry) }) {
            displayTask?.join()
            if (attempt != generation) return@launch
            if (mutable.value.rooms.isEmpty() && mutable.value.network == null) {
                restoreDisplay(username)
            }
            if (!startNetwork()) {
                update(loading = false, error = previousState.error, errorKind = previousState.errorKind,
                    networkError = previousState.networkError, networkErrorKind = previousState.networkErrorKind)
                return@launch
            }
            var failure: ReadFailureKind? = null
            var cancelled = true
            try {
            val previous = mutable.value
            val rooms = previous.rooms.flatMap { it.bindings }.associateBy { it.project }.toMutableMap()
            val bindingProjects = if (transactionRetry) previous.bindingProjects.toMutableSet() else mutableSetOf<FeeProject>()
            val completed = (FeeProject.entries - projects.toSet()).toMutableSet()
            var bindingsAvailable = previous.roomsAvailable
            var bindingsKnown = false
            var network: FeeQuery? = previous.network
            fun publish(finished: Boolean = false) {
                if (attempt != generation) return
                projectReadErrors.clear(); projectReadErrors.putAll(errors)
                val selectedRoom = mutable.value.selectedRoom
                // Until all binding configurations are known, keep the prior room or its placeholder.
                // A faster detail response must not choose a lower-priority room for the user.
                val merged = if (bindingsKnown) mergeFamilyRooms(FeeProject.entries.mapNotNull { rooms[it] })
                    .sortedBy(::roomOrder) else previous.rooms
                val exactIndex = merged.indexOfFirst { current -> selectedRoom?.bindings?.any { old ->
                    current.bindings.any { it.project == old.project && it.binding.sameTarget(old.binding) }
                } == true }
                mutable.value = DormUiState(merged, loading = !finished && !silent, loaded = previous.loaded || finished,
                    error = blockError(), errorKind = blockErrorKind(),
                    selectedIndex = if (exactIndex >= 0) exactIndex else rememberedRoomIndex(merged).coerceAtLeast(0),
                    network = network, networkError = networkError, bindingProjects = bindingProjects.toSet(),
                    networkErrorKind = networkErrorKind, projectErrorKinds = errorKinds.toMap(),
                    displayRestored = previous.displayRestored,
                    roomsLoaded = previous.roomsLoaded || FeeProject.entries.filterNot { it.network }.all { it in completed },
                    networkLoaded = previous.networkLoaded || FeeProject.Network in completed,
                    roomsAvailable = previous.roomsAvailable || bindingsAvailable)
            }
            coroutineScope {
                val configurationProjects = projects.toMutableSet().apply {
                    if (FeeProject.FamilyWater in this) add(FeeProject.FamilyElectric)
                    if (FeeProject.FamilyElectric in this) add(FeeProject.FamilyWater)
                }
                val configurations = configurationProjects.associateWith { project -> async {
                    try { Result.success(repository.feeConfiguration(username, project)) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure(e) }
                } }
                launch {
                    val bound = projects.filterNot { it.network }.associateWith {
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
                    bindingsAvailable = bound.values.any { it != null }
                    bindingsKnown = true
                    publish()
                }
                projects.forEach { project -> launch {
                    if (project.network) {
                        try {
                            configurations.getValue(project).await().getOrThrow()
                            val query = repository.feeQuery(username, project, 0, emptyMap(), true)
                            query.tip?.let { throw YktException(it) }
                            network = query
                            networkError = null
                            networkErrorKind = null
                            transactionRefresh.observe(read, transactionKey(project), query.display)
                            repository.saveDisplay(username, DisplayKeys.Network, query, DisplayCodecs.Query)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            transactionRefresh.failed(read, transactionKey(project))
                            networkError = (e as? YktException)?.message ?: "校园网读取失败"
                            networkErrorKind = e.readFailureKind()
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
                                            }.map { DormMeter(it.kind, it.room, it.query, null, it.project ?: project, updating = true) }
                                            rooms[project] = retainReadings(DormRoom(partial.project, partial.binding, partial.meters + retained,
                                                partial.updatedAtMillis, partial.notice, pendingMeters = partial.pendingMeters), old)
                                            publish()
                                            if (partial.meters.any { it.query != null && it.error == null }) {
                                                val snapshot = rooms.getValue(project)
                                                viewModelScope.launch {
                                                    if (attempt == generation) repository.saveDisplay(username, DisplayKeys.room(project), snapshot, DisplayCodecs.Room)
                                                }
                                            }
                                        }
                                    } })
                                if (room != null) {
                                    rooms[project] = retainReadings(room, rooms[project])
                                    if (room.meters.all { it.query != null && it.error == null } && room.pendingMeters.isEmpty())
                                        transactionRefresh.observe(read, transactionKey(project), room.meters.map {
                                            Triple(it.kind, it.room.selections, it.query!!.display)
                                        }.sortedBy { it.first.ordinal })
                                    else if (room.meters.any { it.error != null }) transactionRefresh.failed(read, transactionKey(project))
                                    if (room.meters.any { it.query != null && it.error == null }) {
                                        repository.saveDisplay(username, DisplayKeys.room(project), rooms.getValue(project), DisplayCodecs.Room)
                                    }
                                } else {
                                    transactionRefresh.succeeded(read, transactionKey(project))
                                    rooms.remove(project)
                                    repository.removeDisplay(username, DisplayKeys.room(project))
                                }
                            } else {
                                transactionRefresh.succeeded(read, transactionKey(project))
                                rooms.remove(project)
                                repository.removeDisplay(username, DisplayKeys.room(project))
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            transactionRefresh.failed(read, transactionKey(project))
                            errors[project] = (e as? YktException)?.message ?: "读取失败，请重试"
                            errorKinds[project] = e.readFailureKind()
                            val old = rooms[project]
                            if (old != null) rooms[project] = DormRoom(old.project, old.binding,
                                old.meters.map { DormMeter(it.kind, it.room, it.query, null, it.project ?: old.project) }, old.updatedAtMillis, old.notice)
                            else rooms.remove(project)
                        }
                    }
                    completed += project
                    publish()
                } }
            }
            if (attempt != generation) return@launch
            publish(finished = true)
            if (errors.isEmpty() && networkError == null && rooms.values.all { room -> room.meters.all { it.error == null } })
                refreshedAt = if (transactionPending()) 0L else ReadCachePolicy.now()
            failure = dormFailure(mutable.value)
            cancelled = false
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (!cancelled) complete(failure)
                transactionRefresh.finished(read)
            }
        }
    }

    private fun dormFailure(state: DormUiState): ReadFailureKind? = readFailureKind(
        state.networkErrorKind, state.errorKind,
        *state.projectErrorKinds.values.toTypedArray(),
        *state.rooms.flatMap { room -> room.meters.map { it.errorKind } }.toTypedArray(),
    )

    /** Retain only readings for the exact same verified meter after a failed refresh. */
    private fun retainReadings(fresh: DormRoom, previous: DormRoom?): DormRoom {
        if (previous == null || !previous.binding.sameTarget(fresh.binding)) return fresh
        val meters = fresh.meters.map { meter ->
            val old = previous.meters.firstOrNull { it.kind == meter.kind &&
                (it.project ?: previous.project) == (meter.project ?: fresh.project) && it.room.sameTarget(meter.room) }
            if (meter.query == null && meter.error != null && old?.query != null)
                DormMeter(meter.kind, meter.room, old.query, meter.error, meter.project ?: fresh.project,
                    errorKind = meter.errorKind)
            else meter
        }
        // A failed bound-meter read ends discovery before the sibling is queried. Keep an
        // already verified sibling of this same project/target, without claiming a fresh read.
        val failedBound = fresh.meters.firstOrNull { it.room.sameTarget(fresh.binding) &&
            (it.project ?: fresh.project) == fresh.project && it.query == null && it.error != null }
        val siblings = if (failedBound == null) emptyList() else previous.meters.filter { old ->
            old.query != null && (old.project ?: previous.project) == fresh.project &&
                meters.none { it.kind == old.kind && (it.project ?: fresh.project) == fresh.project }
        }.map { DormMeter(it.kind, it.room, it.query, failedBound.error, it.project ?: fresh.project,
            errorKind = failedBound.errorKind) }
        val updatedAt = if (fresh.meters.any { it.query != null && it.error == null })
            fresh.updatedAtMillis else previous.updatedAtMillis
        return DormRoom(fresh.project, fresh.binding, meters + siblings, updatedAt, fresh.notice,
            pendingMeters = fresh.pendingMeters)
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
        error: String? = mutable.value.error, selectedIndex: Int = mutable.value.selectedIndex, managing: Boolean = mutable.value.managing,
        networkError: String? = mutable.value.networkError, errorKind: ReadFailureKind? = mutable.value.errorKind,
        networkErrorKind: ReadFailureKind? = mutable.value.networkErrorKind) {
        val s = mutable.value
        mutable.value = DormUiState(rooms, loading, s.loaded, error,
            selectedIndex.coerceIn(0, (rooms.size - 1).coerceAtLeast(0)), s.network, networkError, managing, s.bindingProjects,
            s.roomsLoaded, s.networkLoaded, s.roomsAvailable, s.displayRestored, s.projectErrorKinds,
            networkErrorKind.takeIf { networkError != null }, errorKind.takeIf { error != null }, s.restoringDisplay)
    }
    private suspend fun restoreDisplay(username: String) {
        if (displayPrepared) return
        val cachedRooms = FeeProject.entries.filterNot { it.network }.mapNotNull { project ->
            repository.cachedDisplay(username, DisplayKeys.room(project), DisplayCodecs.Room)
        }
        val network = repository.cachedDisplay(username, DisplayKeys.Network, DisplayCodecs.Query)
        currentCoroutineContext().ensureActive()
        if (owner != username) return
        displayPrepared = true
        val merged = mergeFamilyRooms(cachedRooms).sortedBy(::roomOrder)
        mutable.value = DormUiState(rooms = merged, loading = mutable.value.loading, network = network,
            selectedIndex = rememberedRoomIndex(merged).coerceAtLeast(0), roomsAvailable = merged.isNotEmpty(),
            roomsLoaded = merged.isNotEmpty(), networkLoaded = network != null,
            displayRestored = merged.isNotEmpty() || network != null)
    }
}
