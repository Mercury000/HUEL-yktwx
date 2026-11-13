package com.mercury.yktwx.state

import kotlinx.serialization.Serializable

@Serializable
internal data class UpdateOverlaySavedState(
    val visible: Boolean = false, val channel: String = "stable", val version: Int = 0,
    val digest: String = "", val interrupted: Boolean = false,
) {
    val valid get() = channel in setOf("stable", "beta") && version >= 0 &&
        (digest.isEmpty() || digest.matches(Regex("[a-fA-F0-9]{64}")))
}
