package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.MyPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

/** Compare downstream card positions across real loading transitions, including large text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardLoadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settings(): ThemeSettings {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        return ThemeSettings(app)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun stable(expected: Rect, tag: String) {
        val actual = bounds(tag)
        assertEquals("$tag moved vertically", expected.top, actual.top, 1f)
        assertEquals("$tag changed height", expected.height, actual.height, 1f)
    }
    private fun capture(name: String) {
        val file = File("../tools/dashboard-loading/$name.png").absoluteFile
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun hallKeepsCardPositionsBeforeDuringAndAfterReadingRooms() {
        val settings = settings()
        val ready = PreviewData.hallState()
        var state by mutableStateOf(DormUiState())
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(density, scale)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    HallPage(settings, 0.dp, dorm = state)
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = fontScale; dark = fontScale > 1f; state = DormUiState() }
            val network = bounds("hall-network-card")
            val others = bounds("hall-others-card")
            compose.onNodeWithText("尚未添加").assertDoesNotExist()
            if (fontScale == 1f) capture("hall-initial")
            compose.runOnIdle { state = DormUiState(loading = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            compose.runOnIdle { state = DormUiState(loading = true, network = ready.network, networkLoaded = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            val partialRoom = ready.rooms.single()
            compose.runOnIdle { state = DormUiState(listOf(DormRoom(partialRoom.project, partialRoom.binding,
                partialRoom.meters.takeLast(1), 0,
                pendingMeters = listOf(DormMeterSlot(partialRoom.meters.first().kind, partialRoom.project)))),
                loading = true, network = ready.network, networkLoaded = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            compose.onNodeWithText("尚未添加").assertDoesNotExist()
            if (fontScale == 1f) capture("hall-bound-only")
            compose.runOnIdle { state = ready }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            capture(if (fontScale == 1f) "hall-loaded" else "hall-loaded-large-dark")
            compose.runOnIdle { state = DormUiState(ready.rooms, loading = true, loaded = true, network = ready.network) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            val room = ready.rooms.single()
            compose.runOnIdle { state = DormUiState(listOf(DormRoom(room.project, room.binding, room.meters.take(1), 0)),
                loaded = true, network = ready.network) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            compose.runOnIdle { state = DormUiState(loaded = true) }
            stable(network, "hall-network-card"); stable(others, "hall-others-card")
            compose.onNodeWithText("尚未添加").assertExists()
        }
    }

    @Test fun mineKeepsCardPositionsAsIdentityAndAmountsArriveAndRechargeLoads() {
        val settings = settings()
        var state by mutableStateOf(MyUiState())
        var payment by mutableStateOf(PaymentUiState(username = "202600000001"))
        var rechargeLoading by mutableStateOf(false)
        var scale by mutableFloatStateOf(1f)
        var dark by mutableStateOf(false)
        val ready = MyUiState(profile = PersonalProfile("示例同学", "202600000001", "示例部门", "示例专业", null),
            homeIdentity = HomeCardIdentity("示例同学", "202600000001", "示例书院", "示例专业"),
            summary = BillSummary(20000, 20540), counts = BillCounts(2, 6), loaded = true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalInspectionMode provides true, LocalDensity provides Density(density, scale)) {
                AppTheme(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) {
                    MyPage(state, payment, settings, 0.dp, false, {}, {}, {}, {}, {}, {}, rechargeLoading, {},
                        false, false, {}, {})
                }
            }
        }
        for (fontScale in listOf(1f, 1.5f)) {
            compose.runOnIdle { scale = fontScale; dark = fontScale > 1f; state = MyUiState(); rechargeLoading = false
                payment = PaymentUiState(username = "202600000001") }
            val balance = bounds("my-balance-card")
            val summary = bounds("my-summary-card")
            if (fontScale == 1f) capture("mine-initial")
            compose.runOnIdle { state = MyUiState(loading = true) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            compose.runOnIdle { state = ready
                payment = PaymentUiState(account = PaymentAccount("demo", "demo", "1", "统一账户", 18650)) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            capture(if (fontScale == 1f) "mine-loaded" else "mine-loaded-large-dark")
            compose.runOnIdle { rechargeLoading = true
                state = MyUiState(ready.profile, summary = ready.summary, counts = ready.counts,
                    homeIdentity = ready.homeIdentity, loaded = true, loading = true) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
            compose.onNodeWithText("+ 充值").assertIsNotEnabled()
            compose.runOnIdle { state = MyUiState(loaded = true) }
            stable(balance, "my-balance-card"); stable(summary, "my-summary-card")
        }
    }
}
