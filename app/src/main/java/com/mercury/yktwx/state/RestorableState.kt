package com.mercury.yktwx.state

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Only explicit, small presentation DTOs belong here. Business records stay in their repositories. */
internal class RestorableState<T>(
    handle: SavedStateHandle,
    key: String,
    private val serializer: KSerializer<T>,
    private val valid: (T) -> Boolean = { true },
    private val capture: () -> T,
) {
    private var pending = runCatching { handle.get<Bundle>(key) }.getOrNull()
    private var bound = false
    private var owner: String? = null
    private var preview = false

    init {
        handle.remove<Bundle>(key)
        handle.setSavedStateProvider(key) {
            if (!bound) pending?.let(::Bundle) ?: Bundle()
            else if (owner == null) Bundle()
            else runCatching {
                val snapshot = capture()
                require(valid(snapshot))
                val encoded = json.encodeToString(serializer, snapshot)
                require(encoded.toByteArray(Charsets.UTF_8).size <= MaxBytes)
                Bundle().apply {
                    putInt("version", 1); putString("owner", owner); putBoolean("preview", preview)
                    putString("value", encoded)
                }
            }.getOrElse { Bundle() }
        }
    }

    /** Call only after identity restoration. Runtime generations are deliberately never serialized. */
    fun bind(owner: String?, preview: Boolean = false, renewed: Boolean = false): T? {
        if (bound && this.owner == owner && this.preview == preview && !renewed) return null
        val saved = pending
        pending = null; bound = true; this.owner = owner?.takeIf(String::isNotBlank); this.preview = preview
        if (renewed || this.owner == null || saved == null) return null
        return runCatching {
            require(saved.getInt("version") == 1 && saved.getString("owner") == this.owner && saved.getBoolean("preview") == preview)
            val encoded = requireNotNull(saved.getString("value"))
            require(encoded.toByteArray(Charsets.UTF_8).size <= MaxBytes)
            json.decodeFromString(serializer, encoded).also { require(valid(it)) }
        }.getOrNull()
    }

    private companion object {
        const val MaxBytes = 64 * 1024
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
