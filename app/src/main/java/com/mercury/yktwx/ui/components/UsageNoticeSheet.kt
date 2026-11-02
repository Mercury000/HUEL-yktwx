package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.UsageNotice
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun noticeParagraph(text: String) = buildAnnotatedString {
    append(text)
    UsageNotice.emphasis.forEach { phrase ->
        val start = text.indexOf(phrase)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, start + phrase.length)
    }
}

@Composable
internal fun UsageNoticeSheet(show: Boolean, onDismiss: () -> Unit, onAgree: (() -> Unit)? = null,
    saving: Boolean = false, error: String? = null) {
    val session = rememberOverlaySession(show)
    val currentAgree by rememberUpdatedState(onAgree)
    OverlayBottomSheet(show = show, startAction = { SheetTitle(UsageNotice.Title) },
        onDismissRequest = { if (!saving) onDismiss() },
        allowDismiss = !saving, renderInRootScaffold = true) {
        key(session) {
            val scroll = rememberLazyListState()
            val readToEnd by remember(scroll) {
                derivedStateOf {
                    val layout = scroll.layoutInfo
                    val last = layout.visibleItemsInfo.lastOrNull()
                    layout.totalItemsCount == UsageNotice.paragraphs.size && last?.index == layout.totalItemsCount - 1 &&
                        last.offset + last.size <= layout.viewportEndOffset && !scroll.canScrollForward
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = LocalWindowInfo.current.containerDpSize.height * .75f)
                .bottomSheetSafeArea(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("usage-notice-body"),
                    state = scroll, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(UsageNotice.paragraphs) { index, paragraph ->
                        Text(noticeParagraph(paragraph), Modifier.fillMaxWidth().testTag("usage-notice-paragraph-$index"),
                            fontSize = 14.sp, lineHeight = 23.sp)
                    }
                }
                InlineErrorGroup(error, 18.dp) {
                    if (onAgree != null) {
                        val measurer = rememberTextMeasurer()
                        val style = MiuixTheme.textStyles.button
                        val width = measurer.measure(AnnotatedString("同意并继续"), style).size.width
                        val density = LocalDensity.current
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val sideBySide = with(density) { ((maxWidth - 12.dp) / 2 - 32.dp).toPx() >= width }
                            val confirm: @Composable (Modifier) -> Unit = { modifier ->
                                TextButton(if (saving) "保存中" else if (readToEnd) "同意并继续" else "上滑并阅读",
                                    { if (show && readToEnd && !saving) currentAgree?.invoke() }, modifier,
                                    enabled = show && readToEnd && !saving, colors = ButtonDefaults.textButtonColorsPrimary())
                            }
                            if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton("不同意", onDismiss, Modifier.weight(1f), enabled = show && !saving)
                                confirm(Modifier.weight(1f))
                            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                confirm(Modifier.fillMaxWidth())
                                TextButton("不同意", onDismiss, Modifier.fillMaxWidth(), enabled = show && !saving)
                            }
                        }
                    } else TextButton("关闭", onDismiss, Modifier.fillMaxWidth(), enabled = show)
                }
            }
        }
    }
}
