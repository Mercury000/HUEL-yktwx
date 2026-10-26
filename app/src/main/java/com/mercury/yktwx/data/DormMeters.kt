package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException

enum class DormMeterKind(val label: String) { Lighting("照明"), AirConditioning("空调"), Other("电表") }
class DormMeter(val kind: DormMeterKind, val room: FeeRoomBinding, val query: FeeQuery?, val error: String? = null) {
    val remainingKwh: String? get() = query?.display?.singleOrNull()?.second?.let(DormMeterDiscovery::remainingKwh)
    override fun toString() = "DormMeter([redacted])"
}
class DormRoom(
    val project: FeeProject, val binding: FeeRoomBinding, val meters: List<DormMeter>,
    val updatedAtMillis: Long, val notice: String? = null,
) {
    val label get() = binding.dialogLabel.replace(Regex("(照明|空调)$"), "").trim()
    override fun toString() = "DormRoom([redacted])"
}

/** Discover siblings from current server options. No guessed IDs and no sceneBind writes. */
internal class DormMeterDiscovery(private val service: FeeService) {
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
        for ((index, level) in levels.withIndex()) {
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
        val meters = pair.first.map { option ->
            val room = FeeRoomBinding.from(levels, selections + (last.key to option.id), names + (last.key to option.name))
                ?: throw YktException("房间信息异常")
            try {
                val query = service.feeQuery(token, config.project, last.number, room.selections, true)
                DormMeter(kind(option.name), room, query, query.tip)
            } catch (e: CancellationException) { throw e }
            catch (e: AuthenticationRequired) { throw e }
            catch (e: InvalidCredentials) { throw e }
            catch (e: CaptchaRequired) { throw e }
            catch (e: Exception) { DormMeter(kind(option.name), room, null, (e as? YktException)?.message ?: "读取电量失败") }
        }
        return DormRoom(config.project, binding, meters, System.currentTimeMillis(), pair.second)
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
            if (matches.groupBy { kind(it.name) }.any { it.value.size > 1 }) return listOf(selected) to "同宿舍有多个同类电表，请在缴费页核实后选择"
            return matches.sortedBy { kind(it.name).ordinal } to
                if (matches.size == 1) "未找到同宿舍的另一类电表，可在缴费页手动选择" else null
        }
        // Only the exact observed textual unit is normalized. Unknown formats stay as official display text.
        internal fun remainingKwh(text: String): String? {
            val match = Regex("^当前剩余电量为[：:]?\\s*(-?\\d+(?:\\.\\d+)?)\\s*度[。.]?$|^(-?\\d+(?:\\.\\d+)?)\\s*度$").matchEntire(text.trim()) ?: return null
            return (match.groupValues[1].ifBlank { match.groupValues[2] }).toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
        }
    }
}
