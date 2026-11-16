package com.mercury.yktwx.ui

import com.mercury.yktwx.ui.components.AppButton as Button
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.PreferencesFailure
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PreferencesEntry(failure: PreferencesFailure?, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).testTag("preferences-entry").safeDrawingPadding(),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().weight(1f)
            .padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (failure != null) "无法读取本机设置与更新信息" else "正在准备应用", textAlign = TextAlign.Center)
            if (failure != null) {
                Spacer(Modifier.height(12.dp))
                Text(failure.detail, textAlign = TextAlign.Center)
            }
        }
        if (failure != null) {
            Box(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                Button(onClick = retry) { Text("重试") }
            }
        }
    }
}
