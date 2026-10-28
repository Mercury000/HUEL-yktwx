package com.mercury.yktwx.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Project-specific traversal. Every target keeps the IDs and names returned for its own service. */
internal class DormMeterPairing(private val service: FeeService) {
    /** Resolve the other family service from one household without writing an official binding. */
    suspend fun family(token: String, targetProject: FeeProject,
        sourceBinding: FeeRoomBinding, configuration: FeeConfiguration? = null): DormMeter? {
        val targetConfig = configuration ?: service.feeConfiguration(token, targetProject)
        if (targetConfig.project != targetProject) return null
        if (!targetConfig.roomBindingEnabled || targetConfig.invalidRoomBinding ||
            targetConfig.roomBinding?.let { familyHouseholdKey(it) == familyHouseholdKey(sourceBinding) } == true) return null
        val initial = service.feeQuery(token, targetProject, 0, emptyMap(), false)
        if (initial.tip != null) return null
        val levels = initial.levels.sortedBy { it.number }
        if (levels.isEmpty() || levels.map { it.key }.toSet() != sourceBinding.selections.keys) return null
        var options = initial.options
        val selections = linkedMapOf<String, String>()
        val names = linkedMapOf<String, String>()
        for ((index, level) in levels.withIndex()) {
            val sourceName = sourceBinding.names[level.key] ?: return null
            val option = options.singleOrNull { physicalLabel(it.name) == physicalLabel(sourceName) } ?: return null
            selections[level.key] = option.id
            names[level.key] = option.name
            if (index == levels.lastIndex) break
            val query = service.feeQuery(token, targetProject, level.number, selections.toMap(), false)
            if (query.tip != null) return null
            options = query.options
        }
        val binding = FeeRoomBinding.from(levels, selections, names) ?: return null
        val detail = service.feeQuery(token, targetProject, levels.last().number, selections, true)
        return DormMeter(DormMeterKind.Other, binding, detail, detail.tip, targetProject)
    }

    suspend fun wenbei(token: String, project: FeeProject, levels: List<FeeLevel>,
        binding: FeeRoomBinding, optionsByKey: Map<String, List<FeeOption>>): Pair<DormMeterKind, FeeRoomBinding>? {
        val campus = levels.firstOrNull { it.key in setOf("campus", "xiaoqu_id") } ?: return null
        val buildingIndex = levels.indexOfFirst { it.key in setOf("building", "loudong_id") }
        val floor = levels.firstOrNull { it.key in setOf("floor", "louceng_id") } ?: return null
        val room = levels.lastOrNull() ?: return null
        if (buildingIndex < 0 || room == floor) return null
        val building = levels[buildingIndex]
        val selectedName = binding.names.getValue(building.key)
        val selectedKind = kind(selectedName)
        if (selectedKind == DormMeterKind.Other) return null
        val counterpart = optionsByKey[building.key].orEmpty().singleOrNull {
            kind(it.name) != DormMeterKind.Other && kind(it.name) != selectedKind && physicalLabel(it.name) == physicalLabel(selectedName)
        } ?: return null
        val targetKind = kind(counterpart.name)
        val selections = linkedMapOf(campus.key to binding.selections.getValue(campus.key),
            building.key to counterpart.id)
        val names = linkedMapOf(campus.key to binding.names.getValue(campus.key),
            building.key to counterpart.name)
        val floorQuery = service.feeQuery(token, project, building.number, selections.toMap(), false)
        if (floorQuery.tip != null || floorQuery.options.isEmpty()) return null
        // Confirm uniqueness across this building; a floor label alone does not identify the meter.
        val limit = Semaphore(3)
        val floors = coroutineScope { floorQuery.options.map { floorOption -> async { limit.withPermit {
            val floorSelections = selections + (floor.key to floorOption.id)
            val floorNames = names + (floor.key to floorOption.name)
            val roomQuery = service.feeQuery(token, project, floor.number, floorSelections, false)
            if (roomQuery.tip != null) return@withPermit null
            val wantedRoom = roomKey(binding.names.getValue(room.key))
            val matches = roomQuery.options.filter {
                kind(it.name) == targetKind && roomKey(it.name) == wantedRoom
            }
            if (matches.size > 1) return@withPermit null
            val roomOption = matches.singleOrNull() ?: return@withPermit emptyList()
            val target = FeeRoomBinding.from(levels,
                floorSelections + (room.key to roomOption.id), floorNames + (room.key to roomOption.name))
                ?: return@withPermit null
            listOf(targetKind to target)
        } } }.awaitAll() }
        if (floors.any { it == null }) return null
        return floors.filterNotNull().flatten().singleOrNull()
    }
    private fun kind(name: String) = DormMeterDiscovery.kind(name)
    private fun roomKey(name: String): String {
        // Some official AC names repeat the building prefix (e.g. 77-). Only use this
        // room number after the campus and counterpart building branch have been fixed.
        val groups = Regex("\\d+").findAll(physicalLabel(name)).map { match ->
            match.value.toIntOrNull()?.toString() ?: match.value
        }.toList()
        return groups.lastOrNull() ?: physicalLabel(name)
    }
}
