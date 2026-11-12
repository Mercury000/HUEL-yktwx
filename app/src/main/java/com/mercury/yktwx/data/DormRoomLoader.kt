package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Read the official binding first; discovering an additional meter must not delay its display. */
internal class DormRoomLoader(private val service: FeeService) {
    private val pairing = DormMeterPairing(service)

    suspend fun load(token: String, config: FeeConfiguration,
        otherConfiguration: (suspend () -> FeeConfiguration?)? = null,
        onUpdate: suspend (DormRoom) -> Unit = {}): DormRoom? = coroutineScope {
        val binding = config.roomBinding ?: return@coroutineScope null
        if (config.invalidRoomBinding) throw YktException("已保存的房间信息无法识别，请先核实")
        // The official frontend uses the number of sceneinfo fields as the IEC level.
        val levels = binding.selections.keys.mapIndexed { index, key -> FeeLevel(key, index + 1, key) }
        val kind = DormMeterDiscovery.kind(binding.names.values.last())
        val family = config.project in setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
        val needsSibling = config.project in setOf(FeeProject.Zhengdong, FeeProject.Wenbei) && kind != DormMeterKind.Other
        val otherProject = if (config.project == FeeProject.FamilyWater) FeeProject.FamilyElectric else FeeProject.FamilyWater
        val pending = when {
            family -> listOf(DormMeterSlot(DormMeterKind.Other, otherProject))
            needsSibling -> listOf(DormMeterSlot(if (kind == DormMeterKind.Lighting) DormMeterKind.AirConditioning else DormMeterKind.Lighting, config.project))
            else -> emptyList()
        }
        // Selecting options is independent of the bound meter's IEC read. Capture ordinary failures
        // locally so a slow or unavailable picker cannot cancel the usable meter result.
        val options = if (needsSibling) async { attempt {
            when (config.project) {
                FeeProject.Zhengdong -> {
                    val last = levels.last()
                    service.feeQuery(token, config.project, levels[levels.lastIndex - 1].number,
                        binding.selections - last.key, false)
                }
                else -> {
                    val campus = levels.first()
                    service.feeQuery(token, config.project, campus.number,
                        mapOf(campus.key to binding.selections.getValue(campus.key)), false)
                }
            }
        } } else null
        val bound = readMeter(token, config.project, levels.size, kind, binding)
        val first = DormRoom(config.project, binding, listOf(bound), System.currentTimeMillis(),
            pendingMeters = if (bound.query != null && bound.error == null) pending else emptyList())
        onUpdate(first)
        if (bound.query == null || bound.error != null) {
            options?.cancel()
            return@coroutineScope first
        }
        var notice: String? = null
        val additional = when {
            family -> {
                val other = if (otherConfiguration != null) otherConfiguration() else
                    attempt { service.feeConfiguration(token, otherProject) }.getOrNull()
                if (other == null) null else attempt {
                    pairing.family(token, otherProject, bound.room, other)
                }.getOrNull()
            }
            needsSibling -> {
                val query = options!!.await().getOrNull()
                if (config.project == FeeProject.Zhengdong) {
                    try {
                        if (query == null || query.tip != null ||
                            query.levels.map { it.key } != levels.map { it.key } ||
                            query.levels.map { it.number } != levels.map { it.number }) throw YktException("房间选项查询失败")
                        val last = levels.last()
                        val selected = query.options.singleOrNull { it.id == binding.selections[last.key] }
                            ?: throw YktException("已保存的房间选项已失效，请重新选择")
                        val siblings = DormMeterDiscovery.siblings(selected, query.options, config.project)
                        notice = siblings.second
                        siblings.first.singleOrNull { it.id != selected.id }?.let { option ->
                            val target = FeeRoomBinding.from(query.levels,
                                binding.selections + (last.key to option.id), bound.room.names + (last.key to option.name))
                                ?: throw YktException("房间信息异常")
                            readMeter(token, config.project, last.number, DormMeterDiscovery.kind(option.name), target)
                        }
                    } catch (e: Exception) {
                        propagate(e)
                        // Retry the existing complete option traversal only after the short picker fails.
                        val restored = attempt { DormMeterDiscovery(service).discover(token, config) }.getOrNull()
                        notice = restored?.notice ?: "另一类电表未能确认，请核实房间"
                        restored?.meters?.singleOrNull { !it.room.sameTarget(binding) }
                    }
                } else if (query != null && query.tip == null && query.levels.map { it.key } == levels.map { it.key } &&
                    query.levels.map { it.number } == levels.map { it.number }) {
                    attempt {
                        val building = levels.firstOrNull { it.key in setOf("building", "loudong_id") }
                            ?: return@attempt null
                        val selected = query.options.singleOrNull { it.id == binding.selections[building.key] }
                            ?: return@attempt null
                        // IEC display names may omit the branch's meter suffix. Pair with the
                        // current building option identified by its exact official value.
                        val source = FeeRoomBinding.from(query.levels, bound.room.selections,
                            bound.room.names + (building.key to selected.name)) ?: return@attempt null
                        val sibling = pairing.wenbei(token, config.project, query.levels, source,
                            mapOf(building.key to query.options)) ?: return@attempt null
                        readMeter(token, config.project, query.levels.last().number, sibling.first, sibling.second)
                    }.getOrNull()
                } else null
            }
            else -> null
        }
        DormRoom(config.project, binding, (listOfNotNull(bound, additional)).sortedBy { it.kind.ordinal },
            System.currentTimeMillis(), notice)
    }

    private suspend fun readMeter(token: String, project: FeeProject, level: Int,
        kind: DormMeterKind, binding: FeeRoomBinding): DormMeter = try {
        val query = service.feeQuery(token, project, level, binding.selections, true)
        DormMeter(kind, resolvedBinding(binding, query), query, query.tip, project)
    } catch (e: Exception) {
        propagate(e)
        DormMeter(kind, binding, null, (e as? YktException)?.message ?: "读取电量失败", project,
            errorKind = e.readFailureKind())
    }

    private fun resolvedBinding(binding: FeeRoomBinding, query: FeeQuery): FeeRoomBinding {
        val data = query.thirdParty?.let { parseObject(it) } ?: return binding
        val nameFields = mapOf("campus" to "areaName", "building" to "buildingName", "floor" to "floorName", "room" to "roomName")
        val names = binding.names.toMutableMap()
        for ((key, value) in binding.selections) {
            val returned = data.rechargeText(key)
            if (returned.isNotBlank() && returned.substringBefore('&') != value.substringBefore('&'))
                throw YktException("返回电表与绑定房间不匹配，请核实")
            val name = nameFields[key]?.let { data.rechargeText(it) }.orEmpty()
                .ifBlank { returned.substringAfter('&', "") }
            if (name.isNotBlank()) names[key] = name
        }
        val levels = binding.selections.keys.mapIndexed { index, key -> FeeLevel(key, index + 1, key) }
        return FeeRoomBinding.from(levels, binding.selections, names) ?: throw YktException("返回房间信息异常")
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (e: Exception) { propagate(e); Result.failure(e) }

    private fun propagate(e: Exception) {
        if (e is CancellationException || e.isAuthenticationFailure()) throw e
    }
}
