package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.HallNeutralTabs
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PressFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    private fun settle() {
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
    private fun pixels(node: SemanticsNodeInteraction) = node.captureToImage().asAndroidBitmap()
    private fun save(name: String) {
        val file = File("build/reports/press-feedback/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { pixels(compose.onRoot()).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun content(mode: State<ColorSchemeMode> = mutableStateOf(ColorSchemeMode.Light),
        page: @Composable ColumnScope.() -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode.value) {
                    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp), content = page)
                }
            }
        }
    }

    @Test fun textLinksFadeOnlyTheirInkInBothThemes() {
        val mode = mutableStateOf(ColorSchemeMode.Light)
        content(mode) {
            DashboardLink("查看账单", {}, Modifier.testTag("link"))
            VisibilityToggle(false, "余额", {}, Modifier.testTag("icon"))
            ActionSurface({}, Modifier.fillMaxWidth().height(56.dp).testTag("row")) {
                Box(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    Text("个人信息")
                }
            }
            CompactButton("充值", {}, Modifier.testTag("button"))
        }
        val link = compose.onNodeWithTag("link")
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            compose.runOnIdle { mode.value = theme }
            settle()
            save("${theme.name.lowercase()}-rest")
            val before = pixels(link)
            val background = before.getPixel(0, 0)
            compose.mainClock.autoAdvance = false
            link.performTouchInput { down(center) }
            settle()
            val pressed = pixels(link)
            var changedInk = 0
            for (y in 0 until before.height) for (x in 0 until before.width) {
                if (before.getPixel(x, y) == background) {
                    assertEquals("Press must not paint behind a text link", background, pressed.getPixel(x, y))
                } else if (before.getPixel(x, y) != pressed.getPixel(x, y)) changedInk++
            }
            assertTrue("The link must still have visible feedback", changedInk > 0)
            save("${theme.name.lowercase()}-link-pressed")
            link.performTouchInput { cancel() }
            settle()
            assertTrue("Cancel must fully restore the link", before.sameAs(pixels(link)))
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun circularIconsAndRoundedRowsKeepTheirCornersClearAndTheirTouchTargetsLarge() {
        val mode = mutableStateOf(ColorSchemeMode.Light)
        content(mode) {
            VisibilityToggle(false, "余额", {}, Modifier.testTag("icon"))
            ActionSurface({}, Modifier.fillMaxWidth().height(56.dp).testTag("row")) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("个人信息") }
            }
            CompactButton("充值", {}, Modifier.testTag("button"))
        }
        val density = compose.activity.resources.displayMetrics.density
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            compose.runOnIdle { mode.value = theme }
            settle()
            compose.mainClock.autoAdvance = false
            for (tag in listOf("icon", "row", "button")) {
                val target = compose.onNodeWithTag(tag)
                assertTrue("$tag must keep a 48dp touch target",
                    target.fetchSemanticsNode().touchBoundsInRoot.height >= 48 * density - 1)
                val before = pixels(target)
                target.performTouchInput { down(center) }
                settle()
                val pressed = pixels(target)
                assertFalse("$tag must react to pressing", before.sameAs(pressed))
                for ((x, y) in listOf(1 to 1, before.width - 2 to 1,
                    1 to before.height - 2, before.width - 2 to before.height - 2)) {
                    assertEquals("$tag must not draw rectangular corners", before.getPixel(x, y), pressed.getPixel(x, y))
                }
                save("${theme.name.lowercase()}-$tag-pressed")
                target.performTouchInput { cancel() }
                settle()
                assertTrue("$tag must recover after cancel", before.sameAs(pixels(target)))
            }
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun scrollingAndDisablingDuringAPressCancelTheFeedbackAndTheClick() {
        var enabled by mutableStateOf(true)
        var clicks = 0
        content {
            Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()).testTag("list")) {
                Spacer(Modifier.height(120.dp))
                ActionSurface({ clicks++ }, Modifier.fillMaxWidth().height(56.dp).testTag("row"), enabled = enabled) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("个人信息") }
                }
                Spacer(Modifier.height(500.dp))
            }
        }
        val row = compose.onNodeWithTag("row")
        val list = compose.onNodeWithTag("list")
        val before = pixels(row)
        compose.mainClock.autoAdvance = false
        row.performTouchInput { down(center) }
        settle()
        assertFalse(before.sameAs(pixels(row)))
        row.performTouchInput { moveTo(center.copy(y = -30f), delayMillis = 150); up() }
        settle()
        assertEquals("Scrolling must not invoke the row", 0, clicks)
        assertTrue("The gesture must actually scroll the list", list.fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value() > 0f)
        assertTrue("A scrolling gesture must clear press feedback", before.sameAs(pixels(row)))
        row.performTouchInput { down(center) }
        settle()
        compose.runOnIdle { enabled = false }
        settle()
        row.assertIsNotEnabled()
        assertTrue("Disabling a held action must clear its feedback", before.sameAs(pixels(row)))
        row.performTouchInput { up() }
        assertEquals(0, clicks)
        compose.runOnIdle { enabled = true }
        row.performClick()
        assertEquals(1, clicks)
        list.assertExists()
        compose.mainClock.autoAdvance = true
    }

    @Test fun overlappingPressesHoverAndFocusHaveBalancedLifetimes() {
        val source = MutableInteractionSource()
        content {
            Surface(onClick = {}, interactionSource = source, indication = rememberPressFeedback(),
                modifier = Modifier.size(100.dp, 56.dp).testTag("target"), color = Color.Transparent) {
                Box(Modifier.fillMaxSize())
            }
        }
        val target = compose.onNodeWithTag("target")
        val before = pixels(target)
        compose.mainClock.autoAdvance = false
        fun emit(interaction: Interaction) { compose.runOnIdle { assertTrue(source.tryEmit(interaction)) }; settle() }
        val first = PressInteraction.Press(Offset.Zero)
        val second = PressInteraction.Press(Offset.Zero)
        emit(first)
        val pressed = pixels(target)
        assertFalse(before.sameAs(pressed))
        emit(second)
        emit(PressInteraction.Cancel(first))
        assertTrue("One cancelled pointer must not clear another pointer", pressed.sameAs(pixels(target)))
        val hover = HoverInteraction.Enter()
        val focus = FocusInteraction.Focus()
        emit(hover); emit(PressInteraction.Release(second))
        val hovered = pixels(target)
        assertFalse("Hover must remain visible", before.sameAs(hovered))
        assertFalse("Hover must be subtler than pressing", pressed.sameAs(hovered))
        emit(focus)
        val focused = pixels(target)
        assertFalse("Keyboard focus must be distinct from hover", hovered.sameAs(focused))
        assertFalse("Keyboard focus must be distinct from pressing", pressed.sameAs(focused))
        emit(HoverInteraction.Exit(hover))
        assertTrue("Focus feedback must outlive hover", focused.sameAs(pixels(target)))
        val focusedPress = PressInteraction.Press(Offset.Zero)
        emit(focusedPress)
        assertFalse("A focused control must still react to another press", focused.sameAs(pixels(target)))
        emit(PressInteraction.Release(focusedPress))
        assertTrue("Release must restore keyboard focus feedback", focused.sameAs(pixels(target)))
        emit(FocusInteraction.Unfocus(focus))
        assertTrue(before.sameAs(pixels(target)))
        compose.mainClock.autoAdvance = true
    }

    @Test fun nativeTabsReusePressFeedbackAndCancellationKeepsSelectionInBothThemes() {
        val mode = mutableStateOf(ColorSchemeMode.Light)
        var selected by mutableIntStateOf(0)
        content(mode) { HallNeutralTabs(listOf("一卡通", "微信"), selected) { selected = it } }
        val target = compose.onNode(hasText("微信") and hasClickAction())
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            compose.runOnIdle { mode.value = theme }
            settle()
            val before = pixels(target)
            compose.mainClock.autoAdvance = false
            target.performTouchInput { down(center) }
            settle()
            assertFalse("Native tabs must retain shared press feedback", before.sameAs(pixels(target)))
            target.performTouchInput { cancel() }
            settle()
            assertEquals("A cancelled press must not select the tab", 0, selected)
            assertTrue("Cancellation must fully restore tab paint", before.sameAs(pixels(target)))
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun quickTapsShowFeedbackInsideAndOutsideScrollingContainers() {
        var clicks = 0
        content {
            for (scrolling in listOf(false, true)) {
                val location = if (scrolling) "scroll" else "static"
                val scroll = if (scrolling) Modifier.height(360.dp).verticalScroll(rememberScrollState()) else Modifier
                Column(Modifier.fillMaxWidth().then(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardLink("查看账单", { clicks++ }, Modifier.testTag("$location-link"))
                    VisibilityToggle(false, "余额", { clicks++ }, Modifier.testTag("$location-icon"))
                    ActionSurface({ clicks++ }, Modifier.fillMaxWidth().height(48.dp).testTag("$location-row")) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("个人信息") }
                    }
                    CompactButton("充值", { clicks++ }, Modifier.testTag("$location-button"))
                    IconButton({ clicks++ }, Modifier.size(40.dp).testTag("$location-library-icon")) { Text("×") }
                    Box(Modifier.testTag("$location-preference")) {
                        DashboardPreference("应用设置", { clicks++ })
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        for (location in listOf("static", "scroll")) for (action in listOf("link", "icon", "row", "button", "library-icon", "preference")) {
            val target = compose.onNodeWithTag("$location-$action")
            val before = pixels(target)
            val previousClicks = clicks
            target.performTouchInput { down(center); advanceEventTime(16); up() }
            compose.waitForIdle()
            var feedbackSeen = false
            repeat(10) {
                compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                feedbackSeen = feedbackSeen || !before.sameAs(pixels(target))
            }
            assertEquals("A quick tap must still click once", previousClicks + 1, clicks)
            assertTrue("$location-$action must show feedback for a 16ms tap", feedbackSeen)
            settle()
            assertTrue("$location-$action must clear feedback after release", before.sameAs(pixels(target)))
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun sameFrameReleaseShowsAShortPulseWhileCancellationDoesNotFlash() {
        val source = MutableInteractionSource()
        content {
            Surface(onClick = {}, interactionSource = source, indication = rememberPressFeedback(),
                modifier = Modifier.size(100.dp, 56.dp).testTag("target"), color = Color.Transparent) {
                Box(Modifier.fillMaxSize())
            }
        }
        val target = compose.onNodeWithTag("target")
        val before = pixels(target)
        compose.mainClock.autoAdvance = false
        val press = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle {
            assertTrue(source.tryEmit(press))
            assertTrue(source.tryEmit(PressInteraction.Release(press)))
        }
        var feedbackSeen = false
        repeat(10) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            feedbackSeen = feedbackSeen || !before.sameAs(pixels(target))
        }
        assertTrue("A press and release delivered together must remain visible", feedbackSeen)
        settle()
        assertTrue(before.sameAs(pixels(target)))
        val cancelled = PressInteraction.Press(Offset.Zero)
        compose.runOnIdle {
            assertTrue(source.tryEmit(cancelled))
            assertTrue(source.tryEmit(PressInteraction.Cancel(cancelled)))
        }
        repeat(10) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            assertTrue("A cancelled quick press must not flash", before.sameAs(pixels(target)))
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun paymentCodesStayVisuallyUnchangedWhileStillClickableAndDecodable() {
        val code = "00000000000000000000"
        var clicks = 0
        content {
            CodeArtwork(code, BarcodeFormat.CODE_128, 1440, 330,
                Modifier.fillMaxWidth().height(88.dp).testTag("barcode")
                    .paymentCodeClickable(true, "放大条形码") { clicks++ }, "演示条形码")
            CodeArtwork(code, BarcodeFormat.QR_CODE, 900, 900,
                Modifier.size(232.dp).testTag("qr")
                    .paymentCodeClickable(true, "放大二维码") { clicks++ }, "演示二维码")
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("演示二维码").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithContentDescription("演示条形码").fetchSemanticsNodes().isNotEmpty()
        }
        fun decode(bitmap: Bitmap): String {
            val rgb = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(rgb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(
                RGBLuminanceSource(bitmap.width, bitmap.height, rgb)))).text
        }
        compose.mainClock.autoAdvance = false
        for (tag in listOf("barcode", "qr")) {
            val target = compose.onNodeWithTag(tag)
            val before = pixels(target)
            assertEquals(code, decode(before))
            target.performTouchInput { down(center) }
            settle()
            val pressed = pixels(target)
            assertTrue("Pressing must not decorate or dim any part of a payment code", before.sameAs(pressed))
            assertEquals(code, decode(pressed))
            save("light-$tag-pressed")
            target.performTouchInput { cancel() }
            settle()
            assertTrue(before.sameAs(pixels(target)))
            val previousClicks = clicks
            target.performTouchInput { click() }
            settle()
            assertEquals(previousClicks + 1, clicks)
            assertTrue(before.sameAs(pixels(target)))
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun inactivePaymentNumbersKeepTheirVisibilityActionDisabled() {
        content { PaymentCodeNumber("00000000000000000000", isActive = false) }
        compose.onNodeWithContentDescription("显示完整付款码号").assertIsNotEnabled()
    }
}
