package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.ProfileAvatar
import com.mercury.yktwx.ui.pages.PaymentResultContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconBoundaryAuditTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private val output = File("../tools/icon-boundary-audit").absoluteFile

    @Test fun failureAndServiceIconsAcrossThemes() {
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var accent by mutableIntStateOf(0)
        var unavailable by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode, accent) {
                    key(unavailable) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(20.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                if (unavailable) {
                                    Box(Modifier.fillMaxWidth().height(320.dp)) {
                                        InitialReadTransition(ReadError("网络连接失败", com.mercury.yktwx.data.ReadFailureKind.Network)) { ReadUnavailable(checkNotNull(it)) }
                                    }
                                } else Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                        for (icon in listOf(AppIcons.Electricity, AppIcons.AirConditioning,
                                            AppIcons.Water, AppIcons.Network, AppIcons.OthersRecharge)) {
                                            Icon(icon, null, Modifier.size(24.dp), tint = DashboardPalette.lightingAccent)
                                        }
                                    }
                                    PaymentResultContent(RechargePhase.Failed, false, 1000, "支付失败", {})
                                }
                            }
                        }
                    }
                }
            }
        }
        output.mkdirs()
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (dynamic in 0..1) for (failure in listOf(true, false)) {
            compose.runOnIdle { mode = theme; accent = dynamic; unavailable = failure }
            compose.mainClock.advanceTimeBy(1200)
            compose.waitForIdle()
            val prefix = (if (dynamic == 1) "monet-" else "") + if (theme == ColorSchemeMode.Dark) "dark" else "light"
            val scene = if (failure) "read-unavailable" else "payment-failure"
            compose.onNodeWithText(if (failure) "网络连接失败，请下拉刷新" else "充值失败").assertIsDisplayed()
            File(output, "$prefix-$scene.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun everyBillIconKeepsVisibleTileEdgesAcrossThemesAndSizes() {
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var accent by mutableIntStateOf(0)
        val icons = listOf("qrCode-payment", "consume", "recharge", "refund", "subsidy", "unknown")
        var card = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode, accent) {
                    val activeCard = MiuixTheme.colorScheme.surfaceContainer
                    SideEffect { card = activeCard }
                    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("扫码 · 消费 · 充值 · 退款 · 补贴 · 其他", fontSize = 13.sp)
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (income in listOf(false, true)) for (size in listOf(34, 40, 56)) {
                                    Text("${if (income) "收入" else "支出"} · $size dp", fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        for (icon in icons) BillIcon(icon, Modifier.testTag("$income-$size-$icon"), size.dp, income)
                                    }
                                }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ProfileAvatar(null, 48.dp)
                                    Text("默认头像", fontSize = 13.sp)
                                }
                                InlineError("金额超出范围")
                                ReadUnavailable(ReadError("网络连接失败", com.mercury.yktwx.data.ReadFailureKind.Network))
                            }
                        }
                    }
                }
            }
        }
        output.mkdirs()
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (dynamic in 0..1) {
            compose.runOnIdle { mode = theme; accent = dynamic }
            compose.mainClock.advanceTimeBy(1200)
            compose.waitForIdle()
            for (income in listOf(false, true)) for (size in listOf(34, 40, 56)) for (icon in icons) {
                val tile = compose.onNodeWithTag("$income-$size-$icon").captureToImage().asAndroidBitmap()
                val edge = tile.getPixel(tile.width / 2, 3)
                if (theme == ColorSchemeMode.Dark || income) {
                    assertNotEquals("Tile must be distinct from card: $theme / $dynamic / $income / $size / $icon", card.toArgb(), edge)
                }
                assertEquals("Rounded corner must preserve surrounding card", card.toArgb(), tile.getPixel(0, 0))
            }
            val prefix = (if (dynamic == 1) "monet-" else "") + if (theme == ColorSchemeMode.Dark) "dark" else "light"
            File(output, "$prefix-icons.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
