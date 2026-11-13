package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.ui.components.DashboardPalette
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.util.Locale

/** Common facts, feedback and progress for water, laundry and bicycle business content. */
@Composable
internal fun LifeDeviceInfoCard(rows: List<Pair<String, String>>, tag: String, extra: (@Composable () -> Unit)? = null) {
    Card(Modifier.fillMaxWidth().testTag(tag), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            rows.forEachIndexed { index, (label, value) ->
                LifeDeviceField(label) { Text(value, fontSize = 12.sp, lineHeight = 20.sp, textAlign = TextAlign.End) }
                if (index < rows.lastIndex || extra != null) HorizontalDivider(color = MiuixTheme.colorScheme.onSurface.copy(alpha = .04f))
            }
            extra?.invoke()
        }
    }
}

@Composable
internal fun LifeDeviceNoticeCard(title: String, detail: String?, tag: String, loading: Boolean = false, loadingTag: String = "$tag-loading") {
    Card(Modifier.fillMaxWidth().testTag(tag), cornerRadius = 24.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (loading) CircularProgressIndicator(size = 18.dp, strokeWidth = 2.dp, modifier = Modifier.testTag(loadingTag))
                Text(title, Modifier.weight(1f), fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
            if (detail != null) Text(detail, fontSize = 12.sp, lineHeight = 20.sp, color = DashboardPalette.muted)
        }
    }
}

@Composable
internal fun LifeDeviceProgress(ended: Boolean, status: String, completion: String, detail: String,
    startedAt: Long?, endedAt: Long?, tag: String, caption: String, feeNote: String?,
    nowMillis: () -> Long = System::currentTimeMillis) {
    var now by remember(startedAt) { mutableLongStateOf(nowMillis()) }
    LaunchedEffect(ended, startedAt, nowMillis) {
        while (!ended) { now = nowMillis(); delay(1000) }
    }
    Column(Modifier.fillMaxWidth().padding(top = if (ended) 30.dp else 36.dp, bottom = 24.dp).testTag("$tag-progress"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (ended) {
            DeviceCompletedIcon("$tag-ended-icon")
            Text(completion, Modifier.padding(top = 18.dp), fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, Modifier.padding(top = 7.dp), fontSize = 12.sp, color = DashboardPalette.muted, textAlign = TextAlign.Center)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(lifeSuccessInk(), CircleShape))
                Text(status, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
            startedAt?.let { started ->
                val seconds = ((endedAt ?: now) - started).coerceAtLeast(0) / 1000
                Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
                    Modifier.padding(top = 22.dp, bottom = 9.dp).testTag("$tag-elapsed"),
                    fontSize = 60.sp, lineHeight = 72.sp, fontWeight = FontWeight.SemiBold)
                Text(caption, fontSize = 12.sp, color = DashboardPalette.muted)
            }
            if (feeNote != null) Text(feeNote, Modifier.padding(top = 3.dp), fontSize = 10.sp, color = DashboardPalette.muted)
        }
    }
}
