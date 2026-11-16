package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import com.mercury.yktwx.data.RechargeConfiguration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassAppearanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun everyBottomStyleKeepsTheSelectedPageAndOs4ScanUsesTheExistingAction() {
        val settings = themeSettingsFixture(compose.activity).apply {
            setFloating(true); setFloatingStyle(2); setBlurBottom(true)
        }
        var scans = 0
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var scanEnabled by mutableStateOf(true)
        var selected by mutableIntStateOf(0)
        compose.setContent {
            AppTheme(mode, glassStyle = settings.glassStyle.value) {
                BarBlurHost(settings.blurBottomBar.value, false) {
                    MainNavigationScaffold(settings, selected, { selected = it }, onScan = { scans++ }, scanEnabled = scanEnabled) {
                        BarBackdropContent(Modifier.fillMaxSize().background(Color(0xFFCADFFF))) {
                            Text("当前页面：$selected")
                        }
                    }
                }
            }
        }
        compose.onNode((hasText("我的") or hasContentDescription("我的")) and isSelectable()).performClick()
        compose.onNodeWithTag("main-floating-scan").assertDoesNotExist()
        compose.onNode((hasText("付款") or hasContentDescription("付款")) and isSelectable()).performClick()
        compose.onNodeWithTag("main-floating-scan").performClick()
        assertEquals(1, scans)
        for (style in listOf(1, 0, 2)) {
            compose.runOnIdle { settings.setFloatingStyle(style); settings.setGlassStyle(style != 1) }
            compose.onNode((hasText("付款") or hasContentDescription("付款")) and isSelectable()).assertIsSelected()
            compose.onNodeWithText("当前页面：0").assertExists()
            val scan = compose.onNodeWithTag("main-floating-scan").fetchSemanticsNode().boundsInRoot
            val tabs = compose.onAllNodes(isSelectable()).fetchSemanticsNodes()
            assertTrue("Scan must sit beside the navigation capsule", tabs.all { it.boundsInRoot.right <= scan.left })
            val centerY = tabs.map { it.boundsInRoot.center.y }.average().toFloat()
            assertEquals("Scan and navigation controls must share the same center", centerY, scan.center.y, 3f)
            capture("navigation-style-$style")
            val barId = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().id
            val lastTab = compose.onNode((hasText("我的") or hasContentDescription("我的")) and isSelectable())
            val withActionLeft = lastTab.fetchSemanticsNode().boundsInRoot.left
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { selected = 3 }
            val positions = (0 until 45).map {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                lastTab.fetchSemanticsNode().boundsInRoot.left
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            val withoutActionLeft = lastTab.fetchSemanticsNode().boundsInRoot.left
            assertTrue("Navigation must grow through intermediate widths for style $style", positions.any {
                it > minOf(withActionLeft, withoutActionLeft) + 1f && it < maxOf(withActionLeft, withoutActionLeft) - 1f
            })
            assertEquals(barId, compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().id)
            compose.onNodeWithTag("main-floating-scan").assertDoesNotExist()
            compose.runOnIdle { selected = 0 }
        }
        compose.runOnIdle { scanEnabled = false }
        compose.onNodeWithTag("main-floating-scan").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, scans)
        compose.runOnIdle { mode = ColorSchemeMode.Dark; scanEnabled = true }
        capture("navigation-os4-dark")
    }

    @Test fun changingAppearanceKeepsAnOpenDialogAndItsContentState() {
        var glass by mutableStateOf(true)
        var show by mutableStateOf(false)
        var count by mutableIntStateOf(0)
        compose.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = glass) {
                Scaffold {
                    AppearanceBackdropContent {
                        Column(Modifier.fillMaxSize().background(Color(0xFFCBDFFF))) {
                            TextButton("打开弹层", onClick = { show = true })
                        }
                        AppOverlayDialog(show = show, title = "确认内容", onDismissRequest = { show = false }) {
                            Column {
                                Text("已选择 $count 次")
                                TextButton("选择", onClick = { count++ })
                                TextButton("关闭", onClick = { show = false })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("打开弹层").performClick()
        compose.onNodeWithText("选择").performClick()
        capture("glass-dialog")
        compose.runOnIdle { glass = false }
        compose.onNodeWithText("确认内容").assertIsDisplayed()
        compose.onNodeWithText("已选择 1 次").assertIsDisplayed()
        compose.runOnIdle { glass = true }
        compose.onNodeWithText("已选择 1 次").assertIsDisplayed()
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithText("确认内容").assertDoesNotExist()
    }

    @Test fun glassDropdownAndSegmentedTabsCommitSelections() {
        var selected by mutableIntStateOf(0)
        compose.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = true) {
                Scaffold {
                    AppearanceBackdropContent {
                        Column(Modifier.fillMaxSize().background(Color.White).padding(12.dp)) {
                            RestorableDropdownPreference("主题选项", listOf("浅色", "深色"), selected, { selected = it })
                            AppTabRowWithContour(listOf("明细", "统计"), selected, { selected = it })
                            Text("选择索引：$selected")
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("主题选项").performClick()
        capture("glass-dropdown")
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("选择索引：1").assertExists()
        compose.onNodeWithText("明细").performClick()
        compose.onNodeWithText("选择索引：0").assertExists()
        capture("glass-tabs")
    }

    @Test fun expandedGlassChoiceRestoresAndDismissalReleasesThePage() {
        val restorer = StateRestorationTester(compose)
        var glass by mutableStateOf(true)
        var selected by mutableIntStateOf(0)
        var writes = 0
        var pageClicks = 0
        restorer.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = glass) {
                Scaffold {
                    AppearanceBackdropContent {
                        Column(Modifier.fillMaxSize()) {
                            RestorableDropdownPreference("显示模式", listOf("自动", "浅色", "深色"),
                                selected, { selected = it; writes++ })
                            TextButton("页面操作", onClick = { pageClicks++ })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("显示模式").performClick()
        restorer.emulateSavedInstanceStateRestore()
        capture("glass-dropdown-restored")
        compose.onNodeWithText("深色").assertIsDisplayed()
        assertEquals(0, writes)
        compose.runOnIdle { glass = false }
        compose.onNodeWithText("深色").assertIsDisplayed()
        compose.runOnIdle { glass = true }
        compose.onNodeWithText("深色").assertIsDisplayed().performClick()
        assertEquals(1, writes)
        assertEquals(2, selected)
        compose.onNodeWithText("页面操作").performTouchInput { click() }
        assertEquals(1, pageClicks)
    }

    @Test fun glassTopBarsKeepBackActionsAndReadableTitlesInBothThemes() {
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var small by mutableStateOf(false)
        var backs = 0
        compose.setContent {
            AppTheme(mode, glassStyle = true) {
                BarBlurHost(false, false) {
                    val scroll = MiuixScrollBehavior(rememberTopAppBarState())
                    Scaffold(topBar = {
                        val back: @Composable () -> Unit = {
                            AppIconButton(onClick = { backs++ }, modifier = Modifier.testTag("glass-back-button")) {
                                AppBackIcon()
                            }
                        }
                        if (small) AppSmallTopAppBar("小标题页面", navigationIcon = back, scrollBehavior = scroll)
                        else AppTopAppBar("玻璃页面", navigationIcon = back, scrollBehavior = scroll)
                    }) { padding ->
                        BarBackdropContent(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                                AppTabRowWithContour(listOf("明细", "统计"), 0, {})
                                Spacer(Modifier.height(16.dp))
                                AppTabRow(listOf("支出", "收入"), 1, {})
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (compact in listOf(false, true)) {
            compose.runOnIdle { mode = theme; small = compact }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithTag("glass-back-button").assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
            if (compact) compose.onAllNodesWithText("小标题页面").assertCountEquals(1)
            compose.onNodeWithTag("glass-top-bar").assertIsDisplayed()
            capture("glass-top-${theme.name}-$compact")
        }
        assertEquals(4, backs)
    }

    @Test fun paymentGlassControlsKeepDraftAndSelectionAndBlockActionsWhileBusy() {
        val config = RechargeConfiguration("test-fee", "test-target", listOf(1000L, 5000L, 10000L),
            100L, 100000L, allowCustom = true, integerOnly = false)
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var glass by mutableStateOf(true)
        var amount by mutableStateOf("37.25")
        var selected by mutableIntStateOf(0)
        var busy by mutableStateOf(false)
        var submits = 0
        compose.setContent {
            AppTheme(mode, glassStyle = glass) {
                Scaffold {
                    AppearanceBackdropContent {
                        Box(Modifier.fillMaxSize().background(Color(0xFFB9D8F4)))
                        PaymentSheet(true, "照明充值", {}) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                PaymentAmountForm(config, amount, { amount = it }, listOf("一卡通", "微信"),
                                    selected, { selected = it }, busy, true, null, { submits++ })
                            }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("微信").performClick()
        assertEquals(1, selected)
        assertEquals("37.25", amount)
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            compose.runOnIdle { mode = theme }
            compose.mainClock.advanceTimeBy(600)
            // LazyRow's relayout schedules a native draw after the theme's composition.
            repeat(2) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
            compose.runOnIdle { compose.activity.window.decorView.invalidate() }
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            capture("glass-payment-${theme.name}", bitmap)
            assertTextIsPainted("一卡通", bitmap)
            assertTextIsPainted("微信", bitmap)
            compose.runOnIdle { busy = true }
            compose.onNodeWithText("充值 ¥ 37.25").assertIsNotEnabled()
            compose.onNode(hasText("微信") and isSelectable()).assertIsNotEnabled().assertIsSelected()
            capture("glass-payment-${theme.name}-busy")
            compose.runOnIdle { busy = false }
        }
        compose.runOnIdle { glass = false }
        compose.onNodeWithText("37.25").assertExists()
        compose.onNode(hasText("微信") and isSelectable()).assertIsSelected()
        assertEquals(0, submits)
    }

    @Test fun billFilterChoicesMatchRechargeContrastOverGlassInBothThemes() {
        val config = RechargeConfiguration("test-fee", "test-target", listOf(1000L, 5000L),
            100L, 100000L, allowCustom = false, integerOnly = false)
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var selected by mutableStateOf(true)
        var enabled by mutableStateOf(true)
        var amount by mutableStateOf("10")
        var choices = 0
        compose.setContent {
            AppTheme(mode, glassStyle = true) {
                Scaffold {
                    AppearanceBackdropContent {
                        Box(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface))
                        PaymentSheet(true, "筛选选项与充值金额", {}) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BillFilterPill("全部", selected, enabled) { selected = true; choices++ }
                                    BillFilterPill("消费", !selected, enabled) { selected = false; choices++ }
                                }
                                PaymentAmountInput(config, amount, { amount = it }, enabled)
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark, ColorSchemeMode.MonetLight, ColorSchemeMode.MonetDark)) {
            compose.runOnIdle { mode = theme; selected = true; amount = "10"; enabled = true }
            settleDrawing()
            val bitmap = renderedRoot()
            val filter = compose.onNodeWithText("消费").fetchSemanticsNode().boundsInRoot
            val recharge = compose.onNodeWithText("¥ 50").fetchSemanticsNode().boundsInRoot
            // Compare the actual neutral fills, away from rounded edges and glyphs.
            val filterFill = bitmap.getPixel(filter.center.x.roundToInt(), (filter.top + 3).roundToInt())
            val rechargeFill = bitmap.getPixel(recharge.center.x.roundToInt(), (recharge.top + 3).roundToInt())
            assertEquals("Filter and recharge choices must share the glass tonal fill in $theme", rechargeFill, filterFill)
            capture("glass-filter-contrast-${theme.name}", bitmap)
            assertTextContrast("消费", bitmap)
            compose.onNodeWithText("消费").performClick()
            assertFalse(selected)
            compose.runOnIdle { enabled = false }
            compose.onNodeWithText("全部").assertIsNotEnabled().performTouchInput { click() }
            assertFalse(selected)
        }
        assertEquals(4, choices)
    }

    @Test fun neutralPaymentTabsAndSelectionCardsRemainReadableOverGlass() {
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var selected by mutableIntStateOf(0)
        var enabled by mutableStateOf(true)
        compose.setContent {
            AppTheme(mode, glassStyle = true) {
                Scaffold {
                    AppearanceBackdropContent {
                        Box(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface))
                        PaymentSheet(true, "房间与支付方式", {}) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                HallNeutralTabs(listOf("一卡通", "微信"), selected, enabled) { selected = it }
                                PaymentSelectionCard(false, {}, enabled = enabled) { color -> Text("未选房间", color = color) }
                                CompactButton("购买", {}, enabled = enabled)
                                RestorableDropdownPreference("校区", listOf("郑东", "文北"), 0, {}, enabled = enabled)
                                AmountTextField("", {}, "最低金额", enabled = enabled)
                                Text("金额范围说明", color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                Text("设备使用说明", color = DashboardPalette.muted)
                            }
                        }
                    }
                }
            }
        }
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark, ColorSchemeMode.MonetLight, ColorSchemeMode.MonetDark)) for (index in listOf(0, -1)) {
            compose.runOnIdle { mode = theme; selected = index; enabled = true }
            settleDrawing()
            val bitmap = renderedRoot()
            capture("glass-neutral-choices-${theme.name}-$index", bitmap)
            assertTextContrast("微信", bitmap)
            assertTextContrast("未选房间", bitmap)
            assertTextContrast("购买", bitmap)
            assertTextContrast("郑东", bitmap)
            assertTextContrast("最低金额", bitmap)
            assertTextContrast("金额范围说明", bitmap)
            assertTextContrast("设备使用说明", bitmap)
            compose.onNodeWithText("微信").performClick()
            assertEquals(1, selected)
            compose.runOnIdle { enabled = false }
            compose.onNodeWithText("一卡通").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(1, selected)
        }
    }

    @Test fun iosAndOs4IslandDetailsStayReadableWithAndWithoutBlurInEveryTheme() {
        val settings = themeSettingsFixture(compose.activity).apply { setFloating(true) }
        val entry = LifeDeviceStatusEntry(com.mercury.yktwx.data.life.LifeFeature.Laundry,
            "状态异常", "设备信息", "", "contrast-island", completed = false, dismissible = true)
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var enabled by mutableStateOf(true)
        var dismissals = 0
        compose.setContent {
            AppTheme(mode) {
                BarBlurHost(settings.blurBottomBar.value, false) {
                    Box(Modifier.fillMaxSize()) {
                        BarBackdropContent(Modifier.fillMaxSize().background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface)) {}
                        LifeDeviceStatusIslands(emptyList(), emptyList(), { _, _ -> }, { _, _ -> dismissals++ }, settings,
                            Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth(), enabled,
                            additionalEntries = listOf(entry))
                    }
                }
            }
        }
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark, ColorSchemeMode.MonetLight, ColorSchemeMode.MonetDark))
            for (style in listOf(1, 2)) for (blur in listOf(false, true)) {
                compose.runOnIdle { mode = theme; settings.setFloatingStyle(style); settings.setBlurBottom(blur); enabled = true }
                settleDrawing()
                val bitmap = renderedRoot()
                capture("glass-island-contrast-${theme.name}-$style-$blur", bitmap)
                assertTextContrast("设备信息", bitmap)
            }
        val dismiss = compose.onNodeWithTag("dismiss-life-session-contrast-island")
        compose.runOnIdle { enabled = false }
        dismiss.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(0, dismissals)
        compose.runOnIdle { enabled = true }
        dismiss.performClick()
        assertEquals(1, dismissals)
    }

    @Test fun aboutGlassHeaderUsesOfficialScrollMaterialAndKeepsItsHeroWithIndependentBlur() {
        var blur by mutableStateOf(true)
        compose.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = true) {
                Scaffold {
                    AppearanceBackdropContent {
                        AboutPage(PaddingValues(0.dp), false, {}, onBack = {}, blurTopBar = blur)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("返回").assertWidthIsEqualTo(44.dp)
        val backBounds = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
        settleDrawing()
        val topButton = compose.onNodeWithContentDescription("返回").captureToImage().asAndroidBitmap()
        capture("glass-about-top")
        compose.onNodeWithTag("about-page-list").performScrollToIndex(1)
        settleDrawing()
        assertFalse("Actual list overlap must raise native material even after programmatic scrolling",
            topButton.sameAs(compose.onNodeWithContentDescription("返回").captureToImage().asAndroidBitmap()))
        capture("glass-about-scrolled")
        compose.runOnIdle { blur = false }
        assertEquals(backBounds, compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot)
        capture("glass-about-blur-off")
        compose.onNodeWithTag("about-page-list").performScrollToIndex(0)
        settleDrawing()
        assertTrue("Returning to the top must remove native material and its shadow",
            topButton.sameAs(compose.onNodeWithContentDescription("返回").captureToImage().asAndroidBitmap()))
        capture("glass-about-returned-top")
    }

    @Test fun everyFloatingStyleReplacesThePaymentHeaderScanWithoutDuplicatingIt() {
        val settings = themeSettingsFixture(compose.activity).apply {
            setTheme(1); setFloating(true); setGlassStyle(true); setBlurTop(true)
        }
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        for (style in listOf(0, 1, 2)) {
            compose.runOnIdle { settings.setFloatingStyle(style) }
            compose.onNodeWithTag("main-floating-scan").assertIsDisplayed()
            compose.onNodeWithTag("payment-scan-button").assertDoesNotExist()
            compose.onNode((hasText("大厅") or hasContentDescription("大厅")) and isSelectable()).performClick()
            compose.onNodeWithTag("main-floating-scan").assertDoesNotExist()
            compose.onNode((hasText("付款") or hasContentDescription("付款")) and isSelectable()).performClick()
        }
        compose.runOnIdle { settings.setFloating(false) }
        compose.onNodeWithTag("main-floating-scan").assertDoesNotExist()
        compose.onNodeWithTag("payment-scan-button").assertIsDisplayed()
    }

    @Test fun incomeExpenseTabsKeepTheirOriginalBoundsAcrossAppearanceChanges() {
        var glass by mutableStateOf(false)
        var selected by mutableIntStateOf(2)
        compose.setContent { AppTheme(ColorSchemeMode.Light, glassStyle = glass) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) { BillStatsTypeTabs(selected) { selected = it } }
        } }
        fun bounds() = listOf("支出", "收入").map { compose.onNode(hasText(it) and isSelectable())
            .fetchSemanticsNode().boundsInRoot }
        val original = bounds()
        compose.runOnIdle { glass = true }
        assertEquals(original, bounds())
        compose.onNode(hasText("收入") and isSelectable()).performClick()
        assertEquals(1, selected)
        assertEquals(original, bounds())
        capture("glass-income-expense-tabs")
    }

    @Test fun actualThemePageKeepsGlassAndBlurControlsIndependentWhileChangingTheme() {
        val settings = themeSettingsFixture(compose.activity).apply {
            setTheme(1); setFloating(true); setFloatingStyle(2); setGlassStyle(true)
            setBlurTop(false); setBlurBottom(false)
        }
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        compose.onNode((hasText("我的") or hasContentDescription("我的")) and isSelectable()).performClick()
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        capture("glass-theme-Light")
        val topBlur = compose.activity.getString(R.string.blur_top_bar)
        val glassStyle = compose.activity.getString(R.string.glass_style)
        compose.onNodeWithText(compose.activity.getString(R.string.blur_glass_top_bar_summary)).assertExists()
        compose.onNodeWithText(topBlur).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(settings.blurTopBar.value)
            assertFalse(settings.blurBottomBar.value)
            assertTrue(settings.glassStyle.value)
            assertEquals(2, settings.floatingBottomBarStyle.value)
        }
        compose.onNodeWithText(glassStyle).performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(settings.glassStyle.value)
            assertTrue(settings.blurTopBar.value)
            assertFalse(settings.blurBottomBar.value)
            assertEquals(2, settings.floatingBottomBarStyle.value)
        }
        compose.onNodeWithText(compose.activity.getString(R.string.blur_top_bar_summary)).assertExists()
        compose.onNodeWithText(glassStyle).performClick()
        compose.runOnIdle { settings.setTheme(2) }
        compose.onNodeWithText(topBlur).assertExists()
        capture("glass-theme-Dark")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w411dp-h1000dp-xhdpi")
    fun shortThemePageActuallyScrollsAndBlurSwitchChangesRenderedHeaderInBothStyles() {
        val settings = themeSettingsFixture(compose.activity).apply {
            setTheme(1); setFloating(true); setGlassStyle(true); setBlurTop(false)
        }
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        compose.onNode((hasText("我的") or hasContentDescription("我的")) and isSelectable()).performClick()
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        val list = compose.onNodeWithTag("theme-page-list")
        val originalId = list.fetchSemanticsNode().id
        settleDrawing()
        capture("theme-regression-top")
        list.performTouchInput { swipeUp() }
        settleDrawing()
        assertTrue("A short theme page must move content beneath the header, not only collapse its title",
            list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        themeBack().assertWidthIsEqualTo(44.dp)
        for (glass in listOf(true, false, true)) {
            compose.runOnIdle { settings.setGlassStyle(glass); settings.setBlurTop(false) }
            settleDrawing()
            val off = headerBackgroundPixels()
            capture("theme-regression-glass-$glass-blur-off")
            compose.runOnIdle { settings.setBlurTop(true) }
            settleDrawing()
            val on = headerBackgroundPixels()
            assertTrue("Blur must visibly change the theme header background with glass=$glass",
                off.indices.count { off[it] != on[it] } > off.size / 100)
            assertEquals("Changing appearance must preserve the theme list", originalId, list.fetchSemanticsNode().id)
            capture("theme-regression-glass-$glass-blur-on")
        }
    }

    @Test fun glassTopBarsMatchNativeGradientAndProgressiveBlurInBothThemes() {
        var blur by mutableStateOf(false)
        var native by mutableStateOf(false)
        var small by mutableStateOf(false)
        var mode by mutableStateOf(ColorSchemeMode.Light)
        lateinit var state: TopAppBarState
        val stripeWidth = 40.dp
        val colors = listOf(Color(0xFF80B4ED), Color(0xFFDB947C))
        compose.setContent {
            AppTheme(mode, glassStyle = true) {
                BarBlurHost(false, blur) {
                    val scroll = MiuixScrollBehavior(rememberTopAppBarState(initialContentOffset = -200f)).also { state = it.state }
                    Scaffold(topBar = {
                        // Compare our wrappers to the actual library used by GlassPage, including
                        // its background band, inherited button underlay, stroke and shadow.
                        if (native) GlassTopAppBar("", scrollBehavior = scroll, expandable = !small,
                            backdrop = LocalBarBlurBackdrop.current, progressiveBlur = blur,
                            navigationIcon = { GlassIconButton({}) {
                                Icon(MiuixIcons.Glass.ChevronBackward, "返回", Modifier.size(28.dp))
                            } })
                        else if (small) AppSmallTopAppBar("", scrollBehavior = scroll,
                            navigationIcon = { AppIconButton({}) { AppBackIcon() } })
                        else AppTopAppBar("", scrollBehavior = scroll,
                            navigationIcon = { AppIconButton({}) { AppBackIcon() } })
                    }) {
                        BarBackdropContent(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().drawBehind {
                                val stripe = stripeWidth.toPx()
                                repeat((size.width / stripe).toInt() + 1) { index ->
                                    drawRect(colors[index % 2], Offset(index * stripe, 0f), Size(stripe, size.height))
                                }
                            })
                        }
                    }
                }
            }
        }
        settleDrawing()
        compose.runOnIdle { state.heightOffset = state.heightOffsetLimit }
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (compact in listOf(false, true)) {
            compose.runOnIdle { mode = theme; small = compact; native = false; blur = false }
            settleDrawing()
            val gradient = renderedRoot()
            val gradientPixels = headerPixels(compose.onNodeWithContentDescription("返回"))
            val pointX = (gradient.width * .82f).roundToInt()
            val pointY = compose.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot.center.y.roundToInt()
            val stripePx = compose.activity.resources.displayMetrics.density * stripeWidth.value
            val pagePixel = colors[(pointX / stripePx).toInt() % 2].toArgb()
            assertNotEquals("Progressive blur off must retain the native gradient", pagePixel, gradient.getPixel(pointX, pointY))
            capture("native-mapping-${theme.name}-$compact-gradient", gradient)
            for (progressive in listOf(false, true)) {
                compose.runOnIdle { native = false; blur = progressive }
                settleDrawing()
                val actual = renderedRoot()
                val actualPixels = headerPixels(compose.onNodeWithContentDescription("返回"))
                compose.onNodeWithContentDescription("返回").assertWidthIsEqualTo(44.dp)
                capture("native-mapping-${theme.name}-$compact-progressive-$progressive", actual)
                compose.runOnIdle { native = true }
                settleDrawing()
                val expected = renderedRoot()
                assertTrue("Top bar must match native GlassPage defaults (theme=$theme, small=$compact, progressive=$progressive)",
                    actual.sameAs(expected))
                if (progressive) assertTrue("Progressive blur must change the rendered background",
                    gradientPixels.indices.count { gradientPixels[it] != actualPixels[it] } > gradientPixels.size / 100)
            }
            compose.runOnIdle { native = false; blur = false }
            settleDrawing()
            assertTrue("Switching back must restore the same native gradient and material", gradient.sameAs(renderedRoot()))
        }
    }

    @Test fun whiteTitleDoesNotDisableGlassUnlessTheCameraExplicitlyOptsOut() {
        var camera by mutableStateOf(false)
        compose.setContent {
            AppTheme(ColorSchemeMode.Dark, glassStyle = true) {
                BarBlurHost(false, true) {
                    AppSmallTopAppBar("白色标题", titleColor = Color.White, glassStyle = !camera,
                        navigationIcon = { AppIconButton({}) { AppBackIcon() } })
                }
            }
        }
        compose.onNodeWithTag("glass-top-bar").assertExists()
        compose.onNodeWithContentDescription("返回").assertWidthIsEqualTo(44.dp)
        compose.runOnIdle { camera = true }
        compose.onNodeWithTag("glass-top-bar").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").assertExists()
    }

    @Test fun ordinaryAboutHeaderBlurOnlyAppearsAfterTheHeroCollapses() {
        var blur by mutableStateOf(false)
        compose.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = false) {
                Scaffold {
                    AppearanceBackdropContent {
                        AboutPage(PaddingValues(0.dp), false, {}, onBack = {}, blurTopBar = blur)
                    }
                }
            }
        }
        settleDrawing()
        val top = renderedRoot()
        compose.runOnIdle { blur = true }
        settleDrawing()
        assertTrue("Blur must leave the resting hero and transparent header intact", top.sameAs(renderedRoot()))
        val list = compose.onNodeWithTag("about-page-list")
        list.performScrollToIndex(1)
        settleDrawing()
        val blurred = renderedRoot()
        capture("ordinary-about-collapsed-blur-on", blurred)
        // Bring card content under the header so a real background sample is observable.
        list.performTouchInput {
            swipe(start = center, end = center.copy(y = center.y - 72f * compose.activity.resources.displayMetrics.density),
                durationMillis = 600)
        }
        settleDrawing()
        val on = aboutHeaderPixels()
        compose.runOnIdle { blur = false }
        settleDrawing()
        val off = aboutHeaderPixels()
        assertTrue("Ordinary About must sample the cards after hero collapse", on.indices.count { on[it] != off[it] } > on.size / 100)
        capture("ordinary-about-collapsed-blur-off")
        list.performScrollToIndex(0)
        settleDrawing()
        assertTrue("Returning to the top must restore the original hero", top.sameAs(renderedRoot()))
    }

    @Test fun shortPageElasticMotionDrivesGlassAndReturnsToTheOfficialRestingState() {
        lateinit var state: TopAppBarState
        compose.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = true) {
                BarBlurHost(false, true) {
                    val scroll = MiuixScrollBehavior(rememberTopAppBarState()).also { state = it.state }
                    Scaffold(topBar = {
                        AppTopAppBar("短页面", scrollBehavior = scroll,
                            navigationIcon = { AppIconButton({}) { AppBackIcon() } })
                    }) { padding ->
                        BarBackdropContent(Modifier.fillMaxSize()) {
                            androidx.compose.foundation.lazy.LazyColumn(
                                modifier = Modifier.fillMaxSize().testTag("short-elastic-list")
                                    .topBarOverscroll(scroll).nestedScroll(scroll.nestedScrollConnection),
                                contentPadding = padding,
                            ) { item { Box(Modifier.height(100.dp)) } }
                        }
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val list = compose.onNodeWithTag("short-elastic-list")
        list.performTouchInput {
            down(center.copy(y = height * .8f))
            repeat(8) { moveBy(Offset(0f, -height * .06f), delayMillis = 40) }
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertTrue("The demo's elastic displacement must reach the glass renderer", state.overscrollOffset < 0f)
        assertEquals("Elastic movement must not invent list scroll", 0f, state.contentOffset, .1f)
        capture("short-header-held")
        list.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(2000)
        compose.waitForIdle()
        assertEquals("The demo returns short-page material to its resting state after rebound", 0f, state.overscrollOffset, .1f)
        capture("short-header-released")
        compose.mainClock.autoAdvance = true
    }

    @Test fun disabledGlassHeaderActionRetainsOfficialMaterialAndRejectsActivation() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        compose.setContent {
            AppTheme(ColorSchemeMode.Light, glassStyle = true) {
                BarBlurHost(false, true) {
                    val scroll = MiuixScrollBehavior(rememberTopAppBarState(initialContentOffset = -200f))
                    Scaffold(topBar = {
                        AppTopAppBar("", scrollBehavior = scroll, navigationIcon = {
                            AppIconButton({ clicks++ }, Modifier.testTag("disabled-glass-action"), enabled = enabled) {
                                AppBackIcon()
                            }
                        })
                    }) { BarBackdropContent(Modifier.fillMaxSize().background(Color(0xFF86B8EB))) {} }
                }
            }
        }
        settleDrawing()
        val action = compose.onNodeWithTag("disabled-glass-action")
        val original = action.captureToImage().asAndroidBitmap()
        compose.runOnIdle { enabled = false }
        settleDrawing()
        action.assertIsNotEnabled().assertWidthIsEqualTo(44.dp)
        assertTrue("Disabled actions must use the same official material, underlay and shadow",
            original.sameAs(action.captureToImage().asAndroidBitmap()))
        action.performTouchInput { click() }
        action.performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, clicks)
        compose.runOnIdle { enabled = true }
        action.assertIsEnabled().performClick()
        assertEquals(1, clicks)
    }

    private fun settleDrawing() {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        compose.waitForIdle()
    }

    private fun renderedRoot() = compose.onRoot().captureToImage().asAndroidBitmap()

    private fun headerBackgroundPixels(): IntArray {
        return headerPixels(themeBack())
    }

    private fun aboutHeaderPixels(): IntArray {
        return headerPixels(compose.onNodeWithContentDescription("返回"))
    }

    private fun headerPixels(backButton: SemanticsNodeInteraction): IntArray {
        val bitmap = renderedRoot()
        val back = backButton.fetchSemanticsNode().boundsInRoot
        // Sample the right side of the header, excluding the button and centered title.
        val startX = (bitmap.width * .76f).roundToInt()
        val endX = (bitmap.width * .94f).roundToInt()
        val startY = back.top.roundToInt().coerceAtLeast(0)
        val endY = back.bottom.roundToInt().coerceAtMost(bitmap.height)
        return IntArray((endX - startX) * (endY - startY)).also {
            bitmap.getPixels(it, 0, endX - startX, startX, startY, endX - startX, endY - startY)
        }
    }

    private fun themeBack() = compose.onNode(hasContentDescription("返回") and
        hasAnyAncestor(hasTestTag("theme-top-bar")))

    private fun assertTextIsPainted(label: String, bitmap: Bitmap) {
        val text = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val padding = (bounds.width - layouts.single().size.width) / 2
        // Sample blank padding or the immediate text gutter, avoiding anti-aliased glyph edges.
        val sampleX = if (padding >= 3) bounds.left + padding / 2 else bounds.left - 3
        val background = Color(bitmap.getPixel(sampleX.roundToInt(), bounds.center.y.roundToInt()))
        val color = layouts.single().layoutInput.style.color.compositeOver(background).toArgb()
        var ink = 0
        for (y in bounds.top.roundToInt().coerceAtLeast(0) until bounds.bottom.roundToInt().coerceAtMost(bitmap.height)) {
            for (x in bounds.left.roundToInt().coerceAtLeast(0) until bounds.right.roundToInt().coerceAtMost(bitmap.width)) {
                val pixel = bitmap.getPixel(x, y)
                if (listOf(16, 8, 0).all { shift -> abs(((pixel shr shift) and 255) - ((color shr shift) and 255)) < 15 }) ink++
            }
        }
        assertTrue("$label must paint visible glyphs after a theme change (bounds=$bounds, ink=$ink)", ink > 20)
    }

    private fun assertTextContrast(label: String, bitmap: Bitmap) {
        val text = compose.onNode(hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true)
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val bounds = text.fetchSemanticsNode().boundsInRoot
        val background = Color(bitmap.getPixel(bounds.left.roundToInt(), bounds.top.roundToInt()))
        val foreground = layouts.single().layoutInput.style.color.compositeOver(background)
        val luminances = listOf(background.luminance(), foreground.luminance()).sorted()
        val contrast = (luminances[1] + .05f) / (luminances[0] + .05f)
        assertTrue("$label must retain 4.5:1 contrast (actual=$contrast, background=$background, foreground=$foreground)", contrast >= 4.5f)
    }

    private fun capture(name: String, renderedBitmap: Bitmap? = null) {
        val directory = System.getenv("NAVIGATION_BLUR_SCREENSHOTS") ?: return
        compose.waitForIdle()
        val bitmap = renderedBitmap ?: compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory).mkdirs()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
