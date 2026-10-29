package com.mercury.yktwx.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Never save a number or animate an old number; only visibility belongs to this active view. */
@Composable
internal fun PaymentCodeNumber(code: String?, isActive: Boolean, modifier: Modifier = Modifier) {
    var visible by remember(isActive, code == null) { mutableStateOf(false) }
    val text = code?.let { if (visible) it.chunked(4).joinToString(" ") else it.take(4) + " •••• •••• ••••" } ?: " "
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier.fillMaxWidth().testTag("payment-code-number"), contentAlignment = Alignment.Center) {
        val width = with(density) { (maxWidth - 30.dp).coerceAtLeast(1.dp).toPx() }
        val measured = measurer.measure(AnnotatedString(text), TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
        val fontSize = 14.sp * (width / measured.size.width.coerceAtLeast(1)).coerceAtMost(1f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, fontSize = fontSize, lineHeight = 20.sp, maxLines = 1, textAlign = TextAlign.Center,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (code != null) VisibilityToggle(visible, "完整付款码号", { if (isActive) visible = !visible })
        }
    }
}
