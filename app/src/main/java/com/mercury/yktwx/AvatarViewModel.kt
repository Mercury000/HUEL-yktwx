package com.mercury.yktwx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.mercury.yktwx.data.AvatarCrop
import com.mercury.yktwx.data.AvatarDecoder
import com.mercury.yktwx.data.AvatarImage
import com.mercury.yktwx.data.LocalAvatarStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AvatarUiState(
    val owner: String = "", val preview: Boolean = false, val bytes: ByteArray? = null,
    val visible: Boolean = false, val loading: Boolean = false, val saving: Boolean = false,
    val draft: Bitmap? = null, val crop: AvatarCrop = AvatarCrop(), val error: String? = null,
    val restoring: Boolean = false,
    val image: Bitmap? = null,
) { override fun toString() = "AvatarUiState([redacted])" }

@HiltViewModel
internal class AvatarViewModel internal constructor(
    private val store: LocalAvatarStore,
    private val decode: (Uri) -> Bitmap,
) : ViewModel() {
    @Inject internal constructor(store: LocalAvatarStore, decoder: AvatarDecoder) : this(store, decoder::decode)
    private val mutable = MutableStateFlow(AvatarUiState())
    val state = mutable.asStateFlow()
    private var restoreJob: Job? = null
    private var editJob: Job? = null
    private var generation = 0L
    private var session = 0L
    private fun storageOwner(state: AvatarUiState) = if (state.preview) "preview:${state.owner}" else state.owner

    private var accountGeneration = 0L

    fun accountChanged(owner: String, preview: Boolean = false, accountGeneration: Long = this.accountGeneration) {
        if (state.value.owner == owner && state.value.preview == preview && this.accountGeneration == accountGeneration) return
        this.accountGeneration = accountGeneration
        generation++; session++; restoreJob?.cancel(); editJob?.cancel()
        mutable.value = AvatarUiState(owner, preview, restoring = owner.isNotBlank())
        if (owner.isBlank()) return
        val attempt = generation
        val key = storageOwner(state.value)
        restoreJob = viewModelScope.launch {
            val image = withContext(Dispatchers.IO) { readImage(store.read(key)) }
            if (attempt == generation) mutable.value = mutable.value.copy(bytes = image.first, image = image.second, restoring = false)
        }
    }

    fun open() {
        if (state.value.owner.isBlank()) return
        session++; editJob?.cancel()
        mutable.value = state.value.copy(visible = true, loading = false, saving = false, draft = null,
            crop = AvatarCrop(), error = null)
    }

    fun dismiss() {
        session++; editJob?.cancel()
        mutable.value = state.value.copy(visible = false, loading = false, saving = false, draft = null, error = null)
    }

    fun beginPick(): Long? {
        if (!state.value.visible || state.value.loading || state.value.saving || state.value.restoring) return null
        session++
        mutable.value = state.value.copy(loading = true, error = null)
        return session
    }

    fun pickFailed(token: Long) {
        if (token == session) mutable.value = state.value.copy(loading = false, error = "无法打开相册，请重试")
    }

    fun imagePicked(uri: Uri?, token: Long) {
        if (token != session || !state.value.visible) return
        if (uri == null) { mutable.value = state.value.copy(loading = false); return }
        editJob = viewModelScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decode(uri) }
                if (token == session && state.value.visible) mutable.value = state.value.copy(
                    loading = false, draft = bitmap, crop = AvatarCrop(), error = null)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == session) mutable.value = state.value.copy(loading = false, error = "无法读取这张图片，请重新选择")
            }
        }
    }

    fun cropChanged(crop: AvatarCrop) {
        val draft = state.value.draft ?: return
        if (!state.value.saving) mutable.value = state.value.copy(crop = crop.clamped(draft.width, draft.height))
    }

    fun save() {
        val current = state.value
        val bitmap = current.draft ?: return
        mutateAvatar(current) {
            val bytes = AvatarImage.encode(bitmap, current.crop)
            val context = currentCoroutineContext()
            context.ensureActive()
            store.write(storageOwner(current), bytes) { context.ensureActive() }
            bytes
        }
    }

    fun restoreOfficial() {
        val current = state.value
        if (current.bytes == null) return
        mutateAvatar(current) { store.remove(storageOwner(current)); null }
    }

    private fun mutateAvatar(current: AvatarUiState, write: suspend () -> ByteArray?) {
        if (!current.visible || current.saving || current.loading || current.restoring) return
        val token = session
        mutable.value = current.copy(saving = true, error = null)
        editJob = viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { readImage(write()) }
                if (token == session) mutable.value = state.value.copy(bytes = image.first, image = image.second,
                    saving = false, visible = false, draft = null, error = null, restoring = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == session) mutable.value = state.value.copy(saving = false, error = "头像保存失败，请重试")
            }
        }
    }

    // Publish the stored bytes and decoded image together: all avatar locations draw the first ready frame directly.
    private fun readImage(bytes: ByteArray?): Pair<ByteArray?, Bitmap?> {
        val image = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        return if (image != null) bytes to image else null to null
    }
}
