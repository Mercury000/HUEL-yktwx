package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.components.AppTextButton
import com.mercury.yktwx.ui.components.InlineErrorGroup
import com.mercury.yktwx.ui.components.UsageNoticeBody
import com.mercury.yktwx.ui.components.rememberUsageNoticeReadToEnd
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UsageConsentPage(saving: Boolean, error: String?, onReject: () -> Unit,
    onBack: (() -> Unit)? = null, accepted: Boolean = false, onAgree: () -> Unit) {
    val scroll = rememberLazyListState()
    val readToEnd by rememberUsageNoticeReadToEnd(scroll)
    OobePage(UsageNotice.Title, Modifier.testTag("usage-consent-page"), onBack = onBack, footer = {
        InlineErrorGroup(error, 10.dp) {
            if (accepted && !saving) OobePrimaryButton("继续", onAgree, Modifier.fillMaxWidth())
            else ConsentActions(readToEnd, saving, onReject, onAgree)
        }
    }) {
        Card(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(CardDefaults.CornerRadius)).testTag("oobe-consent-panel")) {
            UsageNoticeBody(scroll, Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun ConsentActions(readToEnd: Boolean, saving: Boolean, onReject: () -> Unit, onAgree: () -> Unit) {
    val rejectLabel = "拒绝并退出"
    val agreeLabel = "同意并继续"
    val readLabel = "上滑并阅读"
    val textStyle = MiuixTheme.textStyles.button
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val minButtonWidth = with(density) {
        listOf(rejectLabel, agreeLabel, readLabel).maxOf { measurer.measure(it, textStyle).size.width }.toDp() + 32.dp
    }
    val reject: @Composable (Modifier) -> Unit = { modifier ->
        AppTextButton(rejectLabel, onReject, modifier, enabled = !saving, minHeight = 50.dp)
    }
    val agree: @Composable (Modifier) -> Unit = { modifier ->
        val label = when {
            saving -> "保存中"
            readToEnd -> agreeLabel
            else -> readLabel
        }
        OobePrimaryButton(label, { if (readToEnd && !saving) onAgree() }, modifier.testTag("usage-consent-agree"),
            enabled = readToEnd && !saving)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= minButtonWidth * 2 + 12.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                reject(Modifier.weight(1f)); agree(Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reject(Modifier.fillMaxWidth()); agree(Modifier.fillMaxWidth())
            }
        }
    }
}
