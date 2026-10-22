package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.ThemeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import java.util.Locale

@Composable
fun PaymentPage(state: PaymentUiState, settings: ThemeSettings, bottomPadding: Dp, onRefresh: () -> Unit, onLogout: () -> Unit, onRecharge: () -> Unit = {}, rechargeLoading: Boolean = false) {
    var showLogoutConfirmation by remember(state.username, state.needsLogin) { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(
                    title = "付款",
                    largeTitle = "付款",
                    scrollBehavior = scrollBehavior, color = Color.Transparent,
                    actions = {
                        IconButton(
                            onClick = { showLogoutConfirmation = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(AppIcons.Logout, contentDescription = "退出登录", tint = MiuixTheme.colorScheme.onSurface)
                        }
                    },
                )
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                PullToRefresh(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp),
                    topAppBarScrollBehavior = scrollBehavior,
                    color = MiuixTheme.colorScheme.primary,
                    refreshTexts = AppRefreshTexts,
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().overScrollVertical().imePadding(),
                        contentPadding = PaddingValues(top = padding.calculateTopPadding() + 16.dp, bottom = bottomPadding + 24.dp, start = 16.dp, end = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (state.preview) item { Text("界面预览 · 测试码不可用于支付", fontSize = 13.sp, color = MiuixTheme.colorScheme.primary) }
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text("付款码", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.weight(1f))
                                        Text(if (state.code != null) "${state.secondsLeft}s 后刷新" else if (state.busy) "正在获取" else "等待刷新", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    // Keep identical slots in success/loading/error states.
                                    // Only swap their content: old codes must not remain visible.
                                    Box(Modifier.fillMaxWidth().height(88.dp), contentAlignment = Alignment.Center) {
                                        state.code?.let { code ->
                                            key(code) {
                                                CodeArtwork(code, BarcodeFormat.CODE_128, 960, 220, Modifier.fillMaxSize(), "付款条形码")
                                            }
                                        }
                                    }
                                    Box(Modifier.size(232.dp), contentAlignment = Alignment.Center) {
                                        val code = state.code
                                        if (code != null) {
                                            key(code) {
                                                CodeArtwork(code, BarcodeFormat.QR_CODE, 600, 600, Modifier.fillMaxSize(), "付款二维码")
                                            }
                                        } else if (state.busy) {
                                            CircularProgressIndicator()
                                        } else {
                                            Column(
                                                Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                            ) {
                                                Text(
                                                    state.error ?: "付款码暂不可用，请刷新",
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                                    fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                )
                                                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColorsPrimary()) { Text("重新获取") }
                                            }
                                        }
                                    }
                                    Text(
                                        state.code?.let { it.take(4) + " •••• •••• ••••" } ?: " ",
                                        fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                    Text("向商户出示，切勿分享或截图", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                        item {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Text(state.account?.name ?: "统一账户", fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.weight(1f))
                                        Text(if (state.preview) "202600000000" else state.username, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                            // A fixed text baseline reserves the balance row's
                                            // height even while only a small spinner is shown.
                                            Text("¥ 0.00", fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                                                color = Color.Transparent, modifier = Modifier.clearAndSetSemantics {})
                                            if (state.busy) {
                                                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                                            } else {
                                                Text(
                                                    state.account?.let { "¥ " + String.format(Locale.CHINA, "%.2f", it.balanceCents / 100.0) } ?: "¥ —",
                                                    fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
                                                )
                                            }
                                        }
                                        TextButton(if (rechargeLoading) "读取中…" else "充值", onClick = onRecharge,
                                            enabled = !rechargeLoading && state.account != null)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Match the miuix demo: the root popup host renders above the app's
            // bottom navigation, with only the component's system-safe insets.
            OverlayDialog(
                show = showLogoutConfirmation && !state.needsLogin,
                title = "退出登录",
                summary = "退出后将清除本机保存的登录信息。",
                onDismissRequest = { showLogoutConfirmation = false },
                defaultWindowInsetsPadding = true,
                renderInRootScaffold = true,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = { showLogoutConfirmation = false }, modifier = Modifier.weight(1f)) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            if (showLogoutConfirmation) {
                                showLogoutConfirmation = false
                                onLogout()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun CodeArtwork(code: String, format: BarcodeFormat, width: Int, height: Int, modifier: Modifier, description: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, code, format) {
        value = withContext(Dispatchers.Default) {
            val hints = mapOf(EncodeHintType.MARGIN to if (format == BarcodeFormat.QR_CODE) 4 else 12)
            val matrix = MultiFormatWriter().encode(code, format, width, height, hints)
            val pixels = IntArray(width * height) { index -> if (matrix[index % width, index / width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        }
    }
    Box(modifier.background(Color.White), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = description, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None) }
    }
}
