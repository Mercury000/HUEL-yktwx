package com.mercury.yktwx.state

import com.mercury.yktwx.data.DormRoom
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.FeeRoomBinding
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only identity is saved. Names and payment query data must be read again. */
@Serializable
internal data class FeeTargetReference(val project: FeeProject, val selections: Map<String, String>) {
    val valid get() = selections.size in 1..8 && selections.all { (key, value) ->
        key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) && value.isNotBlank() && value.length <= 512 &&
            value.none { it.isISOControl() || it in ";:#" }
    }
    fun matches(project: FeeProject, binding: FeeRoomBinding) = this.project == project && selections == binding.selections
}

@Serializable
internal data class HallRoomReference(val targets: List<FeeTargetReference>) {
    fun matches(room: DormRoom) = targets.size == room.bindings.size && targets.all { target ->
        room.bindings.any { target.matches(it.project, it.binding) }
    }
    fun encode(): String = Json.encodeToString(serializer(), this)
    companion object {
        fun from(room: DormRoom) = HallRoomReference(room.bindings.map { FeeTargetReference(it.project, it.binding.selections) })
        fun decode(value: String?): HallRoomReference? = value?.takeIf { it.length <= 16384 }?.let {
            runCatching { Json.decodeFromString(serializer(), it).also { reference ->
                require(reference.targets.size in 1..2 && reference.targets.all { target -> target.valid })
            } }.getOrNull()
        }
    }
}
