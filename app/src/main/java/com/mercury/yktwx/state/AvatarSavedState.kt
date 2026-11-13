package com.mercury.yktwx.state

import android.net.Uri
import com.mercury.yktwx.data.AvatarCrop
import kotlinx.serialization.Serializable

@Serializable
internal data class AvatarSavedState(
    val visible: Boolean, val uri: String?, val zoom: Float, val x: Float, val y: Float,
    val session: Long,
) {
    val valid get() = session >= 0 && zoom.isFinite() && zoom in 1f..4f && x.isFinite() && y.isFinite() &&
        x in 0f..1f && y in 0f..1f && (uri == null || uri.length <= 4096 && Uri.parse(uri).scheme == "content")
    fun crop() = AvatarCrop(zoom, x, y)
}
