package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.MyPage
import com.mercury.yktwx.ui.pages.PaymentPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardTopBarScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun titleBounds(title: String): List<Rect> = compose.onAllNodesWithText(title, useUnmergedTree = true)
        .fetchSemanticsNodes().map { it.boundsInRoot }.filter { it.width > 0f && it.height > 0f }

    private fun capture(name: String) {
        val file = File("../tools/topbar-scroll/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun dashboardTitlesCollapseToCenterAndStillAllowPullRefresh() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(app)
        val payment = PreviewData.paymentState(false)
        val bills = PreviewData.billState()
        var page by mutableIntStateOf(0)
        var refreshes = 0
        val refresh = { refreshes++; Unit }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light) {
                    key(page) {
                        when (page) {
                            0 -> PaymentPage(payment, settings, 100.dp, onRefresh = refresh)
                            1 -> HallPage(settings, 100.dp, dorm = PreviewData.hallState(), onRefreshDorm = refresh)
                            2, 4 -> BillPage(bills.copy(statsVisible = page == 4), settings,
                                onBack = {}, onRefresh = refresh, onLoadMore = {}, onDateSelected = {},
                                onFiltersApplied = { _, _, _ -> }, onSearch = {}, onOpenStats = {},
                                onOpenDetail = {}, showBack = false, bottomPadding = 100.dp)
                            3 -> MyPage(PreviewData.myState(), payment, settings, 100.dp,
                                isActive = true, onRefresh = refresh, onProfile = {}, onAccount = {},
                                onBills = {}, onRecharge = {}, rechargeLoading = false, onCardAction = {},
                                manageLoading = false, passwordLoading = false, onSettings = {}, onAbout = {})
                        }
                    }
                }
            }
        }

        for ((index, title) in listOf("付款", "大厅", "账单", "我的", "账单").withIndex()) {
            compose.runOnIdle { page = index }
            val expanded = titleBounds(title).maxBy { it.height }
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("$title large title must start on the left", expanded.center.x < root.center.x)
            if (index == 0) capture("payment-expanded")

            val list = compose.onNode(hasScrollToIndexAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            list.performTouchInput { swipeUp() }
            compose.waitForIdle()
            val collapsed = titleBounds(title).minBy { it.height }
            assertEquals("$title collapsed title must be centered", root.center.x, collapsed.center.x, 2f)
            assertTrue("$title collapsed title must be smaller", collapsed.height < expanded.height)
            assertTrue("$title collapsed title must be above the large title", collapsed.top < expanded.top)
            if (index == 0) capture("payment-collapsed")

            list.performScrollToIndex(0)
            val beforeRefresh = refreshes
            repeat(2) {
                list.performTouchInput {
                    swipe(start = center.copy(y = height * .2f), end = center.copy(y = height * .9f), durationMillis = 600)
                }
                compose.waitForIdle()
            }
            assertTrue("$title must still allow pull refresh", refreshes > beforeRefresh)
            val restored = titleBounds(title).maxBy { it.height }
            assertEquals("$title large title must expand again", expanded.top, restored.top, 2f)
        }
    }
}
