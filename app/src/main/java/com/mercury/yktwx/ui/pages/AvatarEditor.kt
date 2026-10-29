package com.mercury.yktwx.ui.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.AvatarUiState
import com.mercury.yktwx.AvatarViewModel
import com.mercury.yktwx.ui.components.InlineError
import com.mercury.yktwx.ui.components.bottomSheetSafeArea
import com.mercury.yktwx.ui.components.rememberWhileShown
import com.mercury.yktwx.ui.components.rememberOverlaySession
import com.mercury.yktwx.ui.components.SheetPageContent
import com.mercury.yktwx.ui.components.SheetTitle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.min

private enum class AvatarEditorPage { Choose, Crop }

@Composable
internal fun AvatarEditor(model: AvatarViewModel, state: AvatarUiState, official: ByteArray?, active: Boolean) {
    // Each system picker launch belongs to one account and editing session.
    var pickerToken by rememberSaveable { mutableStateOf<Long?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerToken?.let { model.imagePicked(uri, it) }
        pickerToken = null
    }
    fun pick() {
        val token = model.beginPick() ?: return
        pickerToken = token
        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: Exception) { pickerToken = null; model.pickFailed(token) }
    }
    LaunchedEffect(active) { if (!active) model.dismiss() }
    val show = active && state.visible
    // Root overlays keep rendering while exiting. Keep drawing data, but disable all actions.
    var exitingDisplay by remember { mutableStateOf(state) }
    if (show) SideEffect { exitingDisplay = state }
    val display = if (show) state else exitingDisplay
    val currentShow by rememberUpdatedState(show)
    val page = if (display.draft != null) AvatarEditorPage.Crop else AvatarEditorPage.Choose
    val session = rememberOverlaySession(show)
    OverlayBottomSheet(show = show, startAction = { SheetTitle(if (page == AvatarEditorPage.Crop) "裁剪头像" else "头像") },
        onDismissRequest = model::dismiss, renderInRootScaffold = true,
        onDismissFinished = { if (!currentShow) exitingDisplay = AvatarUiState() }) {
        key(session) {
            SheetPageContent(page) { displayedPage, interactive ->
                val content = rememberWhileShown(show && page == displayedPage, display)
                val enabled = interactive && show && !state.loading && !state.saving && !state.restoring
                Column(Modifier.fillMaxWidth().bottomSheetSafeArea().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val draft = if (displayedPage == AvatarEditorPage.Crop) content.draft else null
                    if (draft == null) {
                        ProfileAvatar(content.bytes ?: official, 88.dp, custom = content.bytes != null,
                            prepared = content.image, pending = content.restoring)
                        Text("仅在本机显示", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (content.loading || content.restoring) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                        content.error?.let { InlineError(it) }
                        TextButton("从相册选择", ::pick, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                        if (content.bytes != null) TextButton(if (content.saving) "恢复中" else "恢复校园头像",
                            model::restoreOfficial, enabled = enabled, modifier = Modifier.fillMaxWidth())
                    } else {
                        val crop by rememberUpdatedState(content.crop)
                        val bitmap = remember(draft) { draft.asImageBitmap() }
                        val limit = minOf(320.dp, LocalWindowInfo.current.containerDpSize.height * .42f)
                        Box(Modifier.widthIn(max = limit).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(percent = 28))
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                            .testTag("avatar-crop").semantics { contentDescription = "头像裁剪预览" }
                            .pointerInput(draft, enabled) {
                                if (enabled) detectTransformGestures { centroid, pan, zoom, _ ->
                                    model.cropChanged(model.state.value.crop.transform(draft.width, draft.height, size.width.toFloat(),
                                        pan.x, pan.y, zoom, centroid.x, centroid.y))
                                }
                            }, contentAlignment = Alignment.Center) {
                            Image(bitmap, null, Modifier.fillMaxSize().graphicsLayer {
                                val base = size.width / min(draft.width, draft.height)
                                scaleX = crop.zoom; scaleY = crop.zoom
                                translationX = (.5f - crop.centerX) * draft.width * base * crop.zoom
                                translationY = (.5f - crop.centerY) * draft.height * base * crop.zoom
                            }, contentScale = ContentScale.Crop)
                        }
                        Text("拖动或双指缩放", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        content.error?.let { InlineError(it) }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton("取消", model::dismiss, enabled = interactive && !state.saving && show, modifier = Modifier.weight(1f))
                            TextButton(if (content.saving) "保存中" else "使用", model::save, enabled = enabled,
                                modifier = Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                        }
                    }
                }
            }
        }
    }
}
