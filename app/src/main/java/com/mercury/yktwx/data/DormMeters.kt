package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException

enum class DormMeterKind(val label: String) { Lighting("照明"), AirConditioning("空调"), Other("电表") }
internal data class DormMeterReading(val label: String, val value: String, val unit: String)

/** Format only units supplied by the query; billing_unit is a payment unit, not a meter balance. */
internal fun meterReading(label: String, value: String): DormMeterReading {
    if (label == "信息") DormMeterDiscovery.remainingKwh(value)?.let {
        return DormMeterReading("剩余电量", it, "度")
    }
    val labelUnit = Regex("[（(]([^）)]+)[）)]").find(label)?.groupValues?.get(1).orEmpty()
    val number = Regex("^(-?\\d+(?:\\.\\d+)?)\\s*(元|吨|度|kWh)?$").matchEntire(value.trim())
        ?: return DormMeterReading(label, value, "")
    return DormMeterReading(label.replace(Regex("[（(][^）)]*[）)]"), ""),
        number.groupValues[1], number.groupValues[2].ifBlank { labelUnit })
}

class DormMeter(val kind: DormMeterKind, val room: FeeRoomBinding, val query: FeeQuery?, val error: String? = null,
    val project: FeeProject? = null) {
    internal val readings get() = query?.display.orEmpty().map { (label, value) -> meterReading(label, value) }

    override fun toString() = "DormMeter([redacted])"
}
class DormRoom(
    val project: FeeProject, val binding: FeeRoomBinding, val meters: List<DormMeter>,
    val updatedAtMillis: Long, val notice: String? = null, val sources: List<DormRoom> = emptyList(),
) {
    internal val currentBinding get() = meters.firstOrNull {
        (it.project ?: project) == project && it.room.sameTarget(binding)
    }?.room ?: binding
    val label get() = physicalLabel(currentBinding.dialogLabel)
    val bindings get() = sources.ifEmpty { listOf(this) }
    override fun toString() = "DormRoom([redacted])"
}

internal fun physicalLabel(name: String) = name.replace(Regex("(照明|空调|水表|电表)(?=\\s*(?:·|$))"), "").trim()
internal fun familyHouseholdKey(binding: FeeRoomBinding) = binding.names.toSortedMap().map { (key, name) -> key to physicalLabel(name) }

/** Family water and electricity are one hall entry; each meter keeps its own official target. */
internal fun mergeFamilyRooms(rooms: List<DormRoom>): List<DormRoom> {
    val family = setOf(FeeProject.FamilyElectric, FeeProject.FamilyWater)
    val result = rooms.filterNot { it.project in family }.toMutableList()
    val sources = rooms.filter { it.project in family }.flatMap { it.bindings }.sortedBy { it.project.ordinal }
    for (household in sources.groupBy { familyHouseholdKey(it.currentBinding) }.values) {
        val candidates = household.flatMap { source ->
            source.meters.map { DormMeter(it.kind, it.room, it.query, it.error, it.project ?: source.project) }
        }
        val meters = listOf(FeeProject.FamilyElectric, FeeProject.FamilyWater).mapNotNull { project ->
            val matches = candidates.filter { it.project == project }
            val official = household.firstOrNull { it.project == project }?.binding
            matches.firstOrNull { it.room.sameTarget(official) && it.error == null && it.query != null }
                ?: matches.firstOrNull { it.error == null && it.query != null } ?: matches.firstOrNull()
        }
        result += DormRoom(household.first().project, household.first().binding,
            meters,
            household.minOf { it.updatedAtMillis }, null, household)
    }
    return result
}

/** Discover siblings from current server options. No guessed IDs and no sceneBind writes. */
internal class DormMeterDiscovery(private val service: FeeService) {
    private val pairing = DormMeterPairing(service)
    suspend fun discover(token: String, config: FeeConfiguration): DormRoom? {
        val binding = config.roomBinding ?: return null
        if (config.invalidRoomBinding) throw YktException("已保存的房间信息无法识别，请先核实")
        val initial = service.feeQuery(token, config.project, 0, emptyMap(), false)
        initial.tip?.let { throw YktException(it) }
        val levels = initial.levels.sortedBy { it.number }
        if (levels.isEmpty() || levels.map { it.key }.toSet() != binding.selections.keys) throw YktException("已保存的房间选项已变化，请重新选择")
        var options = initial.options
        val selections = linkedMapOf<String, String>()
        val names = linkedMapOf<String, String>()
        var roomOptions = emptyList<FeeOption>()
        val optionsByKey = mutableMapOf<String, List<FeeOption>>()
        for ((index, level) in levels.withIndex()) {
            optionsByKey[level.key] = options
            val option = options.singleOrNull { it.id == binding.selections[level.key] }
                ?: throw YktException("已保存的房间选项已失效，请重新选择")
            if (index == levels.lastIndex) { roomOptions = options; break }
            selections[level.key] = option.id
            names[level.key] = option.name
            val next = service.feeQuery(token, config.project, level.number, selections, false)
            next.tip?.let { throw YktException(it) }
            options = next.options
        }
        val last = levels.last()
        val selected = roomOptions.single { it.id == binding.selections[last.key] }
        val pair = siblings(selected, roomOptions, config.project)
        val targets = pair.first.map { option ->
            val room = FeeRoomBinding.from(levels, selections + (last.key to option.id), names + (last.key to option.name))
                ?: throw YktException("房间信息异常")
            kind(option.name) to room
        }.toMutableList()
        val notice = if (config.project == FeeProject.Wenbei) null else pair.second
        if (config.project == FeeProject.Wenbei && targets.size == 1) {
            val sibling = try { pairing.wenbei(token, config.project, levels, targets.single().second, optionsByKey) }
            catch (e: CancellationException) { throw e }
            catch (e: AuthenticationRequired) { throw e }
            catch (e: InvalidCredentials) { throw e }
            catch (e: CaptchaRequired) { throw e }
            catch (_: Exception) { null }
            if (sibling != null) targets += sibling
        }
        val meters = targets.sortedBy { it.first.ordinal }.map { (kind, room) ->
            try {
                val query = service.feeQuery(token, config.project, last.number, room.selections, true)
                DormMeter(kind, room, query, query.tip, config.project)
            } catch (e: CancellationException) { throw e }
            catch (e: AuthenticationRequired) { throw e }
            catch (e: InvalidCredentials) { throw e }
            catch (e: CaptchaRequired) { throw e }
            catch (e: Exception) { DormMeter(kind, room, null, (e as? YktException)?.message ?: "读取电量失败", config.project) }
        }.toMutableList()
        if (config.project == FeeProject.FamilyWater || config.project == FeeProject.FamilyElectric) {
            val otherProject = if (config.project == FeeProject.FamilyWater) FeeProject.FamilyElectric else FeeProject.FamilyWater
            try {
                pairing.family(token, otherProject, targets.single().second)?.let { meters += it }
            } catch (e: CancellationException) { throw e }
            catch (e: AuthenticationRequired) { throw e }
            catch (e: InvalidCredentials) { throw e }
            catch (e: CaptchaRequired) { throw e }
            catch (_: Exception) { }
        }
        return DormRoom(config.project, binding, meters, System.currentTimeMillis(), notice)
    }

    companion object {
        internal fun kind(name: String): DormMeterKind = when {
            name.endsWith("照明") -> DormMeterKind.Lighting
            name.endsWith("空调") -> DormMeterKind.AirConditioning
            else -> DormMeterKind.Other
        }
        internal fun siblings(selected: FeeOption, options: List<FeeOption>, project: FeeProject): Pair<List<FeeOption>, String?> {
            if (project !in setOf(FeeProject.Zhengdong, FeeProject.Wenbei) || kind(selected.name) == DormMeterKind.Other) return listOf(selected) to null
            val stem = selected.name.removeSuffix(kind(selected.name).label).trim()
            val matches = options.filter { kind(it.name) != DormMeterKind.Other && it.name.removeSuffix(kind(it.name).label).trim() == stem }
            if (matches.groupBy { kind(it.name) }.any { it.value.size > 1 }) return listOf(selected) to "同宿舍有多个同类电表，当前仅显示已绑定电表"
            return matches.sortedBy { kind(it.name).ordinal } to
                if (matches.size == 1) "同宿舍未检测到另一类电表，当前仅显示已绑定电表" else null
        }
        // Only the exact observed textual unit is normalized. Unknown formats stay as official display text.
        internal fun remainingKwh(text: String): String? {
            val match = Regex("^当前剩余电量为[：:]?\\s*(-?\\d+(?:\\.\\d+)?)\\s*度[。.]?$|^(-?\\d+(?:\\.\\d+)?)\\s*度$").matchEntire(text.trim()) ?: return null
            return (match.groupValues[1].ifBlank { match.groupValues[2] }).toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        }
    }
}
