package com.mercury.yktwx.data

/** Server sceneinfo contains personal location data; never include it in logs. */
class FeeRoomBinding private constructor(
    val selections: Map<String, String>, val names: Map<String, String>,
) {
    val label get() = names.values.joinToString(" · ")
    /** Prefer the meter label; only add location when it is not already part of that label. */
    val dialogLabel: String get() {
        val room = names["room"] ?: names["room_id"] ?: return names.values.lastOrNull().orEmpty()
        val building = names["building"] ?: names["loudong_id"]
        val buildingStem = building?.removeSuffix("号楼")?.removeSuffix("楼")
        if (buildingStem.isNullOrBlank() || room.contains(buildingStem)) return room
        val floor = names["floor"] ?: names["louceng_id"]
        return listOfNotNull(building, floor?.takeUnless { room.contains(it) }, room).distinct().joinToString(" · ")
    }
    fun sameTarget(other: FeeRoomBinding?) = other != null && selections == other.selections
    fun sceneInfo() = selections.entries.joinToString(";") { (key, value) -> "$key:$value#\$#${names.getValue(key)}" }
    override fun toString() = "FeeRoomBinding([redacted])"

    companion object {
        private val keyPattern = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private fun safe(value: String) = value.isNotBlank() && value.length <= 512 &&
            value.none { it.isISOControl() || it in ";:#" }

        fun parse(sceneInfo: String): FeeRoomBinding? {
            if (sceneInfo.isBlank() || sceneInfo.length > 8192) return null
            val selections = linkedMapOf<String, String>()
            val names = linkedMapOf<String, String>()
            // Older server records used a single # separator.
            val normalized = if ("#\$#" !in sceneInfo) sceneInfo.replace("#", "#\$#") else sceneInfo
            val parts = normalized.split(';')
            if (parts.size !in 1..8) return null
            for (part in parts) {
                val colon = part.indexOf(':')
                if (colon < 1) return null
                val key = part.substring(0, colon)
                val pair = part.substring(colon + 1).split("#\$#")
                if (!keyPattern.matches(key) || key in selections || pair.size !in 1..2) return null
                val value = pair[0]
                val name = pair.getOrElse(1) { value }
                if (!safe(value) || !safe(name)) return null
                selections[key] = value
                names[key] = name
            }
            return FeeRoomBinding(selections, names)
        }

        fun from(levels: List<FeeLevel>, selections: Map<String, String>, names: Map<String, String>): FeeRoomBinding? {
            if (levels.size !in 1..8 || levels.map { it.key }.toSet().size != levels.size ||
                selections.keys != levels.map { it.key }.toSet()) return null
            val values = linkedMapOf<String, String>()
            val labels = linkedMapOf<String, String>()
            for (level in levels.sortedBy { it.number }) {
                val value = selections[level.key] ?: return null
                val name = names[level.key] ?: return null
                if (!keyPattern.matches(level.key) || !safe(value) || !safe(name)) return null
                values[level.key] = value
                labels[level.key] = name
            }
            return FeeRoomBinding(values, labels)
        }
    }
}
