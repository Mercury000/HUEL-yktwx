package com.mercury.yktwx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun PreferencesEntry(failed: Boolean, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).testTag("preferences-entry"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (failed) "无法读取本机设置与更新信息" else "正在准备应用")
        if (failed) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = retry) { Text("重试") }
        }
    }
}
