package com.mercury.yktwx.state

import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.Serializable

@Serializable
internal data class LifeOverlaySavedState(
    val login: Boolean = false, val changing: Boolean = false, val phone: String = "", val verification: Boolean = false,
    val payment: Boolean = false, val session: String? = null, val device: String? = null, val sku: String? = null,
    val method: String? = null,
    val cancelSession: String? = null,
) {
    val valid get() = phone.length <= 11 && phone.all { it in '0'..'9' } && listOf(session, device, sku, method, cancelSession).all {
        it == null || it.length in 1..256 && it.none(Char::isISOControl)
    }
}

/** Campus and platform identities both own the drafts; credentials remain in their encrypted store. */
internal class LifeOverlayRestoration(handle: SavedStateHandle, capture: () -> LifeOverlaySavedState) {
    private val saved = RestorableState(handle, "life-overlays", LifeOverlaySavedState.serializer(), { it.valid }, capture)
    private var binding: Triple<String, Boolean, Long>? = null
    fun bind(campus: String, platform: String?, preview: Boolean, generation: Long,
        onReplacement: () -> Unit): LifeOverlaySavedState? {
        val owner = "$campus|${platform ?: "signed-out"}"
        val next = Triple(owner, preview, generation)
        if (binding == next) return null
        val previous = binding
        val restored = saved.bind(owner.takeIf { campus.isNotBlank() }, preview,
            renewed = previous != null && previous.third != generation)
        binding = next
        if (previous != null) onReplacement()
        return restored
    }
}
