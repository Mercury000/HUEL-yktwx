package com.mercury.yktwx.preview

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal object PreviewNotes {
    @Composable
    fun Payment(hasProblem: Boolean) {
        Text(if (hasProblem) "界面预览 · 非真实账户" else "界面预览 · 测试码不可用于支付",
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }

    @Composable
    fun Recharge() {
        Text("界面预览 · 不发起支付", color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
    }

    @Composable
    fun Bill() {
        Text("账单预览 · 以下交易为演示数据", modifier = Modifier.padding(horizontal = 4.dp),
            fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
    }

    @Composable
    fun CardPassword() {
        Text("预览模式不会修改密码", fontSize = 13.sp)
    }

    @Composable
    fun Captcha() {
        Text("预览", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
