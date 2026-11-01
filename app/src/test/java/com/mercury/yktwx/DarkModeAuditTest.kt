package com.mercury.yktwx

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Show
import java.io.File

/** Offline color audit renderer. All fixtures are fictional; no submissions or network calls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DarkModeAuditTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var output = File("../tools/dark-mode-adapted").absoluteFile

    private fun settle() {
        repeat(2) { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
    }
    private fun capture(name: String) {
        settle()
        when {
            name.endsWith("-payment") -> compose.onNodeWithTag("payment-code-card").assertIsDisplayed()
            name.endsWith("-hall") -> compose.onNodeWithTag("hall-utility-card").assertIsDisplayed()
            name.endsWith("-bills") -> compose.onNodeWithTag("bill-list").assertIsDisplayed()
            name.endsWith("-mine") -> compose.onNodeWithTag("my-summary-card").assertIsDisplayed()
            name.endsWith("-profile") -> compose.onNodeWithTag("profile-details-card").assertIsDisplayed()
            name.endsWith("-account") -> compose.onNodeWithTag("profile-field-余额").assertIsDisplayed()
            name.endsWith("-bill-detail") -> compose.onNodeWithText("交易成功").assertIsDisplayed()
            name.endsWith("-about") -> compose.onNodeWithText("关于").assertExists()
            name.endsWith("-about-footer") -> compose.onNodeWithText("引用").assertIsDisplayed()
            name.endsWith("-settings") -> compose.onNodeWithText("退出登录").assertIsDisplayed()
            name.endsWith("-theme-top") -> compose.onNodeWithText("主题颜色").assertIsDisplayed()
        }
        File(output, "$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        println("AUDITED $name")
    }
    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        settle()
    }
    private fun tab(label: String) {
        compose.onNode((hasText(label) or hasContentDescription(label)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick) { it() }
        settle()
    }
    private fun text(label: String) {
        compose.onAllNodesWithText(label).onLast().performSemanticsAction(SemanticsActions.OnClick) { it() }
        settle()
    }

    @Test fun dashboardPagesAcrossThemes() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(app).apply { setTheme(1); setFloating(true); setFloatingStyle(1) }
        val payment = PaymentViewModel(app).apply { initialize(true) }
        val bills = BillViewModel(app).apply { initialize(true) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payment, bills) } }
        for (theme in 1..2) for (accent in 0..1) {
            compose.runOnIdle { settings.setTheme(theme); settings.setAccent(accent) }
            val prefix = (if (accent == 1) "monet-" else "") + if (theme == 1) "light" else "dark"
            tab("大厅"); capture("$prefix-hall")
            tab("我的"); capture("$prefix-mine")
        }
    }

    @Test fun billPagesAndProfileIcons() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(app).apply { setTheme(2); setFloating(true); setFloatingStyle(1) }
        val payment = PaymentViewModel(app).apply { initialize(true) }
        val bills = BillViewModel(app).apply { initialize(true) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payment, bills) } }
        for (accent in 0..1) {
            compose.runOnIdle { settings.setAccent(accent); bills.showDetails() }
            val prefix = if (accent == 0) "dark" else "monet-dark"
            tab("账单"); capture("$prefix-bills")
            compose.onAllNodesWithText("第一餐厅一楼-扫码支付").onFirst()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-bill-detail"); back()
            text("统计"); capture("$prefix-statistics")
            compose.onNodeWithTag("bill-stats-list").performScrollToIndex(6)
            capture("$prefix-stats-ranking")
            tab("我的"); capture("$prefix-mine")
        }
    }

    @Test fun billModeTabsKeepWholeContainerAcrossThemesAndSelection() {
        var theme by mutableStateOf(ColorSchemeMode.Light)
        var accent by mutableIntStateOf(0)
        var statistics by mutableStateOf(false)
        val state = PreviewData.billState()
        val settings = ThemeSettings(compose.activity.application)
        var surface = Color.Unspecified
        var track = Color.Unspecified
        var selected = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(theme, accent) {
                    val dark = androidx.compose.foundation.isSystemInDarkTheme()
                    val c = MiuixTheme.colorScheme
                    val trackColor = if (dark) DashboardPalette.card else DashboardPalette.field
                    val selectedColor = if (dark) c.secondaryVariant else DashboardPalette.card
                    SideEffect { surface = c.surface; track = trackColor; selected = selectedColor }
                    BillPage(state.copy(statsVisible = statistics), settings,
                        {}, {}, {}, {}, { _, _, _ -> }, {}, { statistics = true }, {},
                        showBack = false, onShowDetails = { statistics = false })
                }
            }
        }
        val folder = File("../tools/bill-tabs-review").absoluteFile.apply { mkdirs() }
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (dynamic in 0..1) {
            compose.runOnIdle { theme = mode; accent = dynamic; statistics = false }
            settle()
            val tabs = compose.onNodeWithTag("bill-mode-tabs")
            val initialBounds = tabs.fetchSemanticsNode().boundsInRoot
            for ((index, title) in listOf("明细", "统计").withIndex()) {
                compose.onNode(hasText(title) and hasClickAction()).performClick()
                settle()
                compose.onNode(hasText(title) and hasClickAction()).assertIsSelected()
                org.junit.Assert.assertEquals("Switching must keep the whole control's bounds", initialBounds,
                    tabs.fetchSemanticsNode().boundsInRoot)
                val bitmap = tabs.captureToImage().asAndroidBitmap()
                // Sample the continuous outer strip, away from squircle corners and text.
                org.junit.Assert.assertEquals("Whole track must keep its background", track.toArgb(),
                    bitmap.getPixel(bitmap.width / 2, 3))
                org.junit.Assert.assertNotEquals("Track must remain distinct from page", surface.toArgb(), track.toArgb())
                org.junit.Assert.assertNotEquals("Selection must remain distinct from track", track.toArgb(), selected.toArgb())
                org.junit.Assert.assertEquals("Selected area must paint inside the track", selected.toArgb(),
                    bitmap.getPixel(bitmap.width * (if (index == 0) 1 else 3) / 4, bitmap.height / 5))
                val prefix = (if (dynamic == 1) "monet-" else "") + if (mode == ColorSchemeMode.Dark) "dark" else "light"
                File(folder, "$prefix-${if (index == 0) "details" else "statistics"}.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                File(folder, "$prefix-${if (index == 0) "details" else "statistics"}-tabs.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    @Test fun allNavigationSurfaceLayers() {
        output = File("../tools/surface-hierarchy-audit").absoluteFile
        navigationPagesAndTheirOverlays()
    }

    @Test fun transparentAvatarCropAcrossThemes() {
        var theme by mutableStateOf(ColorSchemeMode.Light)
        var accent by mutableIntStateOf(0)
        val avatar = AvatarViewModel(compose.activity.application)
        val blue = android.graphics.Color.rgb(75, 128, 183)
        val draft = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply {
            android.graphics.Canvas(this).drawRect(120f, 120f, 280f, 280f,
                android.graphics.Paint().apply { color = blue })
        }
        var originalLightFill = Color.Unspecified
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(theme, accent) {
                    val lightFill = MiuixTheme.colorScheme.surfaceContainerHigh
                    SideEffect { originalLightFill = lightFill }
                    Scaffold {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                            AvatarEditor(avatar, AvatarUiState(owner = "fictional", visible = true, draft = draft), null, true)
                        }
                    }
                }
            }
        }
        val folder = File("../tools/surface-hierarchy-audit").absoluteFile.apply { mkdirs() }
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (dynamic in 0..1) {
            compose.runOnIdle { theme = mode; accent = dynamic }
            settle()
            val crop = compose.onNodeWithTag("avatar-crop")
            crop.assertIsDisplayed()
            val bounds = crop.fetchSemanticsNode().boundsInRoot
            val bitmap = crop.captureToImage().asAndroidBitmap()
            val fill = bitmap.getPixel(bitmap.width / 2, bitmap.height / 10)
            val root = compose.onRoot().captureToImage().asAndroidBitmap()
            val sheet = root.getPixel(bounds.center.x.toInt(), (bounds.top - 8).toInt())
            org.junit.Assert.assertNotEquals("Transparent crop must retain its boundary against the sheet", sheet, fill)
            org.junit.Assert.assertEquals("Opaque image pixels must remain unchanged", blue,
                bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
            if (mode == ColorSchemeMode.Light) org.junit.Assert.assertEquals(
                "Light crop keeps its original background", originalLightFill.toArgb(), fill)
            val prefix = (if (dynamic == 1) "monet-" else "") + if (mode == ColorSchemeMode.Dark) "dark" else "light"
            File(folder, "$prefix-avatar-transparent.png").outputStream().use {
                root.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun floatingBarsKeepSeparateSurfaceOverCards() {
        var theme by mutableStateOf(ColorSchemeMode.Light)
        var accent by mutableIntStateOf(0)
        val settings = ThemeSettings(compose.activity.application).apply {
            setFloating(true); setFloatingStyle(0); setBlurBottom(false)
        }
        var card = Color.Unspecified
        val folder = File("../tools/surface-hierarchy-audit").absoluteFile.apply { mkdirs() }
        compose.setContent {
            AppTheme(theme, accent) {
                val c = MiuixTheme.colorScheme
                SideEffect { card = c.surfaceContainer }
                BarBlurHost(settings.blurBottomBar.value, false) {
                    MainNavigationScaffold(settings, 0, {}) {
                        BarBackdropContent(Modifier.fillMaxSize().background(c.surfaceContainer)) {
                            Text("导航覆盖卡片", Modifier.padding(24.dp))
                        }
                    }
                }
            }
        }
        for (mode in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (dynamic in 0..1) for (style in 0..1) {
            compose.runOnIdle { theme = mode; accent = dynamic; settings.setFloatingStyle(style) }
            var initial: android.graphics.Bitmap? = null
            var initialBounds: androidx.compose.ui.geometry.Rect? = null
            for ((index, blur) in listOf(false, true, false).withIndex()) {
                compose.runOnIdle { settings.setBlurBottom(blur) }
                settle()
                val nav = compose.onNodeWithTag("main-bottom-navigation")
                val bounds = nav.fetchSemanticsNode().boundsInRoot
                val bitmap = nav.captureToImage().asAndroidBitmap()
                if (mode == ColorSchemeMode.Dark) org.junit.Assert.assertNotEquals(
                    "Floating surface must stay distinct from the card below (accent=$dynamic, style=$style, blur=$blur)",
                    card.toArgb(), bitmap.getPixel(bitmap.width / 2, bitmap.height * 35 / 100))
                if (index == 0) { initial = bitmap.copy(Bitmap.Config.ARGB_8888, false); initialBounds = bounds }
                else org.junit.Assert.assertEquals("Blur must retain navigation geometry", initialBounds, bounds)
                if (index == 2) org.junit.Assert.assertEquals("Disabling blur must restore the same surface",
                    initial!!.getPixel(bitmap.width / 2, bitmap.height * 35 / 100),
                    bitmap.getPixel(bitmap.width / 2, bitmap.height * 35 / 100))
                val prefix = (if (dynamic == 1) "monet-" else "") + if (mode == ColorSchemeMode.Dark) "dark" else "light"
                File(folder, "$prefix-floating-$style-${if (blur) "blur" else "solid"}.png").outputStream().use {
                    compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }

    @Test fun navigationPagesAndTheirOverlays() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val settings = ThemeSettings(app).apply { setTheme(2); setFloating(true); setFloatingStyle(1) }
        val payment = PaymentViewModel(app).apply { initialize(true) }
        val bills = BillViewModel(app).apply { initialize(true) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payment, bills) } }
        for (accent in 0..1) {
            compose.runOnIdle { settings.setAccent(accent) }
            val prefix = if (accent == 0) "dark" else "monet-dark"
            tab("付款"); capture("$prefix-payment")
            compose.onNodeWithContentDescription("付款二维码").performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-qr-enlarged"); back()
            compose.onNodeWithContentDescription("付款条形码").performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-barcode-enlarged"); back()
            compose.onAllNodesWithText("充值").onFirst().performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-recharge-empty")
            text("¥ 10"); capture("$prefix-recharge-selected")
            compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement("12.34")
            capture("$prefix-recharge-input"); back()
            tab("大厅"); capture("$prefix-hall")
            text("照明充值"); capture("$prefix-lighting-fee"); back()
            text("空调充值"); capture("$prefix-air-fee"); back()
            text("切换"); capture("$prefix-room-switch")
            text("解绑"); capture("$prefix-room-unbind-confirm"); back(); back()
            compose.onNodeWithTag("network-recharge").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }; capture("$prefix-network-fee"); back()
            compose.onNodeWithText("代人充值").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-recipient-query")
            compose.onNode(hasSetTextAction() and hasText("学工号")).performTextInput("202600000002")
            text("查询"); capture("$prefix-recipient-selected"); back()
            tab("账单"); capture("$prefix-bills")
            compose.onNodeWithContentDescription("筛选账单").performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-bill-filter")
            back()
            compose.onNodeWithContentDescription("选择日期").performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-date-month")
            for (mode in listOf("按日", "按年", "自定义时间段")) {
                text("日期范围"); capture("$prefix-date-mode-dropdown")
                text(mode); capture("$prefix-date-$mode")
            }
            back()
            compose.onNodeWithText("统计").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithTag("bill-stats-list").performScrollToIndex(0); capture("$prefix-stats-top")
            compose.onNodeWithTag("bill-trend-plot").performTouchInput { click(center) }
            capture("$prefix-stats-tooltip")
            compose.onNodeWithTag("bill-stats-category").performScrollTo(); capture("$prefix-stats-category")
            compose.onNodeWithTag("bill-stats-list").performScrollToIndex(6); capture("$prefix-stats-ranking")
            compose.onAllNodes(hasClickAction() and hasText("餐厅", substring = true)).onFirst()
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onNodeWithText("交易成功").assertIsDisplayed()
            capture("$prefix-bill-detail"); back()
            compose.runOnIdle { bills.showDetails() }
            tab("我的"); capture("$prefix-mine")
            compose.onNodeWithContentDescription("个人信息").performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-profile")
            compose.onNodeWithContentDescription("更换头像").performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-avatar-choose"); back(); back()
            text("账户详情"); capture("$prefix-account"); back()
            compose.onNodeWithText("挂失 / 解挂").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-card-manage"); back()
            compose.onNodeWithText("修改查询密码").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-card-password"); back()
            compose.onNodeWithText("关于财小通").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-about")
            compose.onAllNodes(hasScrollToIndexAction()).onLast().performScrollToIndex(1)
            compose.onNodeWithText("引用").performScrollTo(); capture("$prefix-about-footer")
            text("引用"); capture("$prefix-references"); back(); back()
            compose.onNodeWithText("应用设置").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            capture("$prefix-settings")
            text("主题"); capture("$prefix-theme-top")
            text("主题颜色"); capture("$prefix-accent-dropdown"); back()
            compose.onNodeWithText("预测性返回动画").performScrollTo(); capture("$prefix-theme-bottom")
            back(); text("退出登录"); capture("$prefix-logout-confirm"); back(); back()
            tab("付款")
            for (style in 0..2) {
                compose.runOnIdle { settings.setFloating(style > 0); settings.setFloatingStyle(if (style == 2) 1 else 0) }
                capture("$prefix-navigation-$style")
            }
        }
    }

    @Test fun loadingErrorsFormsAndPaymentResults() {
        var accent by mutableIntStateOf(0)
        var screen by mutableIntStateOf(0)
        val app = compose.activity.application
        val settings = ThemeSettings(app)
        val consent = UsageConsent(app)
        val avatar = AvatarViewModel(app)
        val draft = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(95, 130, 160)) }
        val payment = PreviewData.paymentState(false)
        val lost = PreviewData.paymentState(true)
        val room = PreviewData.hallState(true)
        val card = payment.accountCard!!
        val names = listOf("login", "login-error", "notice-top", "avatar-crop", "payment-lost", "payment-loading", "payment-error",
            "hall-unbound", "hall-loading", "hall-error", "room-add", "room-temporary", "card-unlost", "card-error", "card-password-error",
            "fee-password", "fee-password-error", "payment-awaiting", "payment-checking", "payment-paid", "payment-failed", "payment-timeout",
            "bills-loading", "bills-error", "mine-loading", "mine-error", "recharge-error", "payment-success-sheet", "login-busy", "bills-empty")
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Dark, accent) {
                    key(screen) {
                    Scaffold { _ ->
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                            when (screen) {
                                0 -> LoginPage(PaymentUiState()) { _, _ -> }
                                1 -> LoginPage(PaymentUiState(error = "账号或密码错误"), consent) { _, _ -> }
                                2 -> UsageNoticeSheet(true, {}, onAgree = {})
                                3 -> AvatarEditor(avatar, AvatarUiState(owner = "fictional", visible = true, draft = draft), null, true)
                                4 -> PaymentPage(lost, settings, 0.dp, {})
                                5 -> PaymentPage(PaymentUiState(needsLogin = false, busy = true, username = "fictional"), settings, 0.dp, {})
                                6 -> PaymentPage(PaymentUiState(needsLogin = false, username = "fictional", error = "网络连接失败"), settings, 0.dp, {})
                                7 -> HallPage(settings, 0.dp, dorm = DormUiState(loaded = true), isActive = true)
                                8 -> HallPage(settings, 0.dp, dorm = DormUiState(loading = true), isActive = true)
                                9 -> HallPage(settings, 0.dp, dorm = DormUiState(loaded = true, error = "网络连接失败", networkError = "网络连接失败"), isActive = true)
                                10, 11 -> HallRoomSheet(room, true, {}, {}, {}, {}, {}, initialTab = if (screen == 11) 1 else 0, initialAdding = screen == 10)
                                12, 13 -> CardServiceDialog(CardServiceUiState(visible = true, action = CampusCardAction.Unlost,
                                    cards = listOf(lost.accountCard!!), selectedCard = lost.accountCard, error = if (screen == 13) "查询密码错误" else null),
                                    {}, {}, {}, {}, {}, { _, _, _ -> }, {}, {})
                                14 -> CardPasswordDialog(CardPasswordUiState(visible = true, cards = listOf(card), selectedCard = card, error = "两次新密码不一致"), {}, {}, { _, _, _ -> })
                                15, 16 -> FeePasswordDialog(true, 1000, FeeAccountPreparation("fictional", "fictional", listOf(FeeAccount("1", "统一账户", 18650)), "fixture", "0123456789"), false,
                                    if (screen == 16) "余额不足" else null, {}, { _, _ -> })
                                in 17..21 -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp)) {
                                    PaymentResultContent(listOf(RechargePhase.AwaitingPayment, RechargePhase.Checking, RechargePhase.Paid, RechargePhase.Failed, RechargePhase.TimedOut)[screen - 17],
                                        screen == 18, 1000, if (screen == 20) "持卡人已挂失！" else null, {}, onCheck = {})
                                }
                                22, 23 -> BillPage(BillUiState(needsLogin = false, loading = screen == 22, error = if (screen == 23) "网络连接失败" else null), settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {})
                                24, 25 -> MyPage(MyUiState(loading = screen == 24, profileError = if (screen == 25) "网络连接失败" else null),
                                    PaymentUiState(needsLogin = false, accountLoading = screen == 24, accountError = if (screen == 25) "网络连接失败" else null), settings, 0.dp,
                                    true, {}, {}, {}, {}, {}, false, {}, false, false, {}, {})
                                26 -> RechargeDialog(RechargeUiState(visible = true, config = PreviewData.rechargeConfiguration(), error = "充值金额超出限制"), {}, {}, {})
                                27 -> PaymentSuccessDialog(1000, {})
                                28 -> LoginPage(PaymentUiState(busy = true, username = "fictional")) { _, _ -> }
                                29 -> BillPage(BillUiState(needsLogin = false, loaded = true), settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {})
                            }
                        }
                    }
                    }
                }
            }
        }
        for (mode in 0..1) {
            compose.runOnIdle { accent = mode }
            val prefix = if (mode == 0) "dark" else "monet-dark"
            for (index in names.indices) {
                compose.runOnIdle { screen = index }
                capture("$prefix-${names[index]}")
                if (index == 2) {
                    compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
                    capture("$prefix-notice-end")
                }
            }
        }
    }

    @Test fun everyFeeProjectAndDropdown() {
        var accent by mutableIntStateOf(0)
        var project by mutableStateOf(FeeProject.Zhengdong)
        var ready by mutableStateOf(false)
        val levels = PreviewData.feeQuery(FeeProject.Zhengdong, false, 0, emptyMap()).levels
        val room = PreviewData.hallState().rooms.first().binding
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Dark, accent) {
                    key(project, ready) {
                        Scaffold { _ ->
                            val state = FeeUiState(config = PreviewData.feeConfiguration(project), levels = if (project.network) emptyList() else levels,
                                selections = if (ready) room.selections else emptyMap(), names = if (ready) room.names else emptyMap(),
                                options = levels.associate { it.number to PreviewData.feeQuery(project, false, it.number - 1, room.selections).options },
                                query = if (ready || project.network) PreviewData.feeQuery(project, true, 0, room.selections) else null)
                            HallFeeDialog(state, true, project, if (ready && !project.network) room else null,
                                onDismiss = {}, onSelect = { _, _ -> }, onRetry = {}, onCreate = { _, _ -> }, onMethod = {},
                                onPayAccount = { _, _ -> }, onCancelPayment = {}, onFinish = {}, onContinuePayment = {}, onRoomAction = {},
                                onConfirmRoomAction = {}, onDismissRoomDialog = {}, onConsumeRoomNotice = {})
                        }
                    }
                }
            }
        }
        for (mode in 0..1) {
            compose.runOnIdle { accent = mode }
            val prefix = if (mode == 0) "dark" else "monet-dark"
            for (item in FeeProject.entries) {
                compose.runOnIdle { project = item; ready = false }
                capture("$prefix-fee-${item.name}-target")
                if (!item.network) {
                    compose.onNodeWithText("校区").performSemanticsAction(SemanticsActions.OnClick) { it() }; capture("$prefix-fee-${item.name}-dropdown")
                    back()
                    compose.runOnIdle { ready = true }; capture("$prefix-fee-${item.name}-amount")
                }
            }
        }
    }

    @Test fun colorRolesAcrossLightDarkAndMonet() {
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var accent by mutableIntStateOf(0)
        var roles = emptyList<Pair<String, Color>>()
        compose.setContent { AppTheme(mode, accent) {
            val c = MiuixTheme.colorScheme
            val palette = listOf("primary" to c.primary, "onPrimary" to c.onPrimary, "actionFill" to c.primary, "actionContent" to c.onPrimary, "primaryVariant" to c.primaryVariant,
                "onPrimaryVariant" to c.onPrimaryVariant, "secondary" to c.secondary, "secondaryVariant" to c.secondaryVariant,
                "card" to CardDefaults.defaultColors().color, "surface" to c.surface, "background" to c.background,
                "summary" to c.onSurfaceVariantSummary, "field" to DashboardPalette.field,
                "lightingAccent" to DashboardPalette.lightingAccent, "lightingButton" to DashboardPalette.lightingButton,
                "lightingInk" to DashboardPalette.lightingInk, "airAccent" to DashboardPalette.airAccent, "airButton" to DashboardPalette.airButton,
                "onSurface" to c.onSurface, "onSecondaryContainer" to c.onSecondaryContainer, "secondaryContainer" to c.secondaryContainer)
            SideEffect { roles = palette }
            Box(Modifier.fillMaxSize().background(c.surface))
        } }
        output.mkdirs()
        File(output, "color-roles.txt").bufferedWriter().use { file ->
            for (a in 0..1) for (m in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark, ColorSchemeMode.System)) {
                compose.runOnIdle { mode = m; accent = a }; settle()
                file.appendLine("mode=$m accent=$a")
                roles.forEach { (name, color) -> file.appendLine("$name=%08X".format(color.toArgb())) }
            }
        }
    }

    @Test fun sixCategoryPalette() {
        var accent by mutableIntStateOf(0)
        val categories = listOf("餐饮", "超市", "热水", "电费", "网络", "其他")
            .mapIndexed { index, name -> BillCategory(name, (6L - index) * 1000) }
        compose.setContent {
            AppTheme(ColorSchemeMode.Dark, accent) {
                Scaffold { _ ->
                    Card(Modifier.fillMaxWidth().padding(20.dp)) {
                        CategoryDonutChart(categories, 2, Modifier.padding(20.dp))
                    }
                }
            }
        }
        for (mode in 0..1) {
            compose.runOnIdle { accent = mode }
            capture(if (mode == 0) "dark-six-categories" else "monet-dark-six-categories")
        }
    }

    @Test fun statisticsViewportPositions() {
        var accent by mutableIntStateOf(0)
        val app = compose.activity.application
        val settings = ThemeSettings(app)
        val state = PreviewData.billState().copy(statsVisible = true)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Dark, accent) {
                    BillPage(state, settings, {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {})
                }
            }
        }
        for (mode in 0..1) {
            compose.runOnIdle { accent = mode }
            val prefix = if (mode == 0) "dark" else "monet-dark"
            compose.onNodeWithTag("bill-stats-list").performScrollToIndex(0)
            capture("$prefix-stats-top")
            compose.onNodeWithTag("bill-stats-list").performScrollToIndex(6)
            capture("$prefix-stats-ranking")
        }
    }

    /** Cover the state combinations missing from the page fixtures, using real shared controls. */
    @Test fun controlStatesAndBoundaries() {
        var accent by mutableIntStateOf(0)
        var theme by mutableStateOf(ColorSchemeMode.Dark)
        var screen by mutableIntStateOf(0)
        var amount by mutableStateOf("")
        var roles = emptyList<Pair<String, Color>>()
        val firstRoom = PreviewData.hallState().rooms.first()
        val secondBinding = FeeRoomBinding.parse(firstRoom.binding.sceneInfo()
            .replace("房间A", "房间B").replace("demo-room", "demo-second-room"))!!
        val rooms = DormUiState(rooms = listOf(firstRoom,
            DormRoom(firstRoom.project, secondBinding, emptyList(), firstRoom.updatedAtMillis)),
            loaded = true, bindingProjects = setOf(firstRoom.project))
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(theme, accent) {
                    val c = MiuixTheme.colorScheme
                    val palette = listOf("primary" to c.primary, "onPrimary" to c.onPrimary, "actionFill" to c.primary, "actionContent" to c.onPrimary,
                        "primaryContainer" to c.primaryContainer, "onPrimaryContainer" to c.onPrimaryContainer,
                        "secondary" to c.secondary, "onSecondary" to c.onSecondary,
                        "secondaryVariant" to c.secondaryVariant, "onSecondaryVariant" to c.onSecondaryVariant,
                        "secondaryContainer" to c.secondaryContainer, "onSecondaryContainer" to c.onSecondaryContainer,
                        "tertiaryContainer" to c.tertiaryContainer, "onTertiaryContainer" to c.onTertiaryContainer,
                        "surface" to c.surface, "surfaceContainer" to c.surfaceContainer,
                        "surfaceContainerHigh" to c.surfaceContainerHigh, "onSurface" to c.onSurface,
                        "onSurfaceContainer" to c.onSurfaceContainer, "summary" to c.onSurfaceVariantSummary,
                        "actions" to c.onSurfaceVariantActions, "outline" to c.outline, "dividerLine" to c.dividerLine,
                        "error" to c.error, "errorContainer" to c.errorContainer, "onErrorContainer" to c.onErrorContainer,
                        "sliderBackground" to c.sliderBackground, "sliderKeyPoint" to c.sliderKeyPoint,
                        "sliderKeyPointForeground" to c.sliderKeyPointForeground,
                        "switchOffThumb" to if (accent == 1) c.onSurface.copy(alpha = .38f) else c.onSecondary,
                        "field" to DashboardPalette.field, "card" to DashboardPalette.card)
                    SideEffect { roles = palette }
                    key(screen) {
                        Scaffold { _ ->
                            Box(Modifier.fillMaxSize().background(c.surface)) {
                                if (screen == 0) HallRoomSheet(rooms, true, {}, {}, {}, {}, {})
                                else if (screen == 1) OverlayBottomSheet(true, title = "控件状态核对", onDismissRequest = {}) {
                                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        PaymentSelectionCard(true, {}) { Text("已选候选", color = it) }
                                        PaymentSelectionCard(false, {}) { Text("未选候选", color = it) }
                                        PaymentSelectionCard(false, {}, enabled = false) { Text("禁用候选", color = it) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Button({}, colors = ButtonDefaults.buttonColorsPrimary()) { Text("¥ 10") }
                                            Button({}) { Text("¥ 50") }
                                            Button({}, enabled = false) { Text("禁用") }
                                        }
                                        HallNeutralTabs(listOf("已选方式", "未选方式"), 0, {})
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            BillFilterPill("全部", true, onClick = {})
                                            BillFilterPill("消费", false, onClick = {})
                                            BillFilterPill("充值", false, enabled = false, onClick = {})
                                        }
                                        CompactButton("紧凑操作", {})
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            BillIcon("consume")
                                            BillIcon("consume", income = true)
                                        }
                                    }
                                } else Card(Modifier.fillMaxWidth().padding(20.dp)) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        if (screen == 2) {
                                            FeeLocationDropdown("位置", listOf("示例园区"), 1, true, {})
                                            AmountTextField(amount, { amount = it }, "金额", Modifier.fillMaxWidth())
                                            TextField("", {}, label = "密码", modifier = Modifier.fillMaxWidth(),
                                                trailingIcon = { Icon(MiuixIcons.Show, "显示密码", tint = c.onSurfaceVariantActions) })
                                            TextField("", {}, label = "禁用输入", enabled = false, modifier = Modifier.trackInputFocus(false).fillMaxWidth().testTag("disabled-input"))
                                            InlineError("金额超出范围")
                                        } else {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Checkbox(ToggleableState.Off, {})
                                                Text("未勾选", Modifier.padding(horizontal = 12.dp), fontSize = 14.sp)
                                                Checkbox(ToggleableState.On, {})
                                                Text("已勾选", Modifier.padding(start = 12.dp), fontSize = 14.sp)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                Switch(false, {})
                                                Switch(true, {})
                                                Switch(false, {}, enabled = false)
                                            }
                                            Slider(50f, {}, valueRange = 0f..100f, steps = 19,
                                                showKeyPoints = true, keyPoints = listOf(0f, 25f, 50f, 75f, 100f))
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text("余额读取", fontSize = 14.sp)
                                                BalanceReadIndicator(true)
                                            }
                                            PageReadNotice(null, 12.dp, loading = true) { Text("内容刷新中", fontSize = 14.sp) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        output.mkdirs()
        File(output, "control-color-roles.txt").bufferedWriter().use { file ->
            for (scheme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) for (mode in 0..1) {
                compose.runOnIdle { theme = scheme; accent = mode; amount = "" }
                val prefix = (if (mode == 1) "monet-" else "") + if (scheme == ColorSchemeMode.Dark) "dark" else "light"
                for ((index, name) in listOf("multiple-rooms", "control-candidates", "control-inputs", "control-indicators").withIndex()) {
                    compose.runOnIdle { screen = index }
                    if (index == 0) compose.onNodeWithTag("hall-room-1").assertExists()
                    capture("$prefix-$name")
                    if (index == 1) compose.onNodeWithText("禁用候选").assertIsNotEnabled()
                    if (index == 2) compose.onNodeWithTag("disabled-input").assertIsNotEnabled()
                    if (index == 2) {
                        compose.onNode(hasSetTextAction() and hasText("金额")).performTextInput("123")
                        capture("$prefix-control-input-focused")
                    }
                }
                file.appendLine("mode=$scheme accent=$mode")
                roles.forEach { (name, color) -> file.appendLine("$name=%08X".format(color.toArgb())) }
            }
        }
    }
}
