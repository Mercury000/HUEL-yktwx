package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.components.AppBackIcon
import com.mercury.yktwx.ui.components.AppIconButton
import com.mercury.yktwx.ui.components.AppTextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OobePage(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    val colors = MiuixTheme.colorScheme
    Column(modifier.fillMaxSize().background(colors.surface).safeDrawingPadding(),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(start = 16.dp, top = 20.dp).height(48.dp)) {
            if (onBack != null) AppIconButton(onBack, modifier = Modifier.size(48.dp)) {
                AppBackIcon(tint = colors.onSurface)
            }
        }
        Text(title, Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 16.dp)
            .semantics { heading() }, style = MiuixTheme.textStyles.title1, textAlign = TextAlign.Center)
        Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().weight(1f)) { content() }
        if (footer != null) Box(Modifier.widthIn(max = 384.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            footer()
        }
    }
}

@Composable
internal fun OobePrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    AppTextButton(label, onClick, modifier, enabled = enabled, minHeight = 50.dp,
        colors = ButtonDefaults.textButtonColorsPrimary())
}
