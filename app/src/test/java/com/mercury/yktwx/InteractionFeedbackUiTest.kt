package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.MyPage
import org.junit.Assert.*
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
class InteractionFeedbackUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun settle() {
        repeat(20) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
    }
    private fun pixels(node: SemanticsNodeInteraction) = node.captureToImage().asAndroidBitmap()
    private fun save(name: String) {
        val file = File("build/reports/interaction-feedback/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { pixels(compose.onRoot()).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun content(page: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme {
                    Column(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.surface).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) { page() }
                }
            }
        }
    }

    @Test fun backgroundFeedbackLeavesOpaqueContentUntintedInLibraryAndAppControls() {
        content {
            Button({}, Modifier.fillMaxWidth().testTag("button"), cornerRadius = 28.dp,
                colors = ButtonDefaults.buttonColorsPrimary()) {
                Box(Modifier.size(20.dp).background(Color.White).testTag("button-white"))
                Box(Modifier.size(20.dp).background(Color.Black).testTag("button-black"))
            }
            PaymentSelectionCard(selected = true, onClick = {}, modifier = Modifier.testTag("selection")) {
                Row {
                    Box(Modifier.size(20.dp).background(Color.White).testTag("selection-white"))
                    Box(Modifier.size(20.dp).background(Color.Black).testTag("selection-black"))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        for (tag in listOf("button", "selection")) {
            val target = compose.onNodeWithTag(tag)
            val white = compose.onNodeWithTag("$tag-white", useUnmergedTree = true)
            val black = compose.onNodeWithTag("$tag-black", useUnmergedTree = true)
            val before = pixels(target)
            val whiteBefore = pixels(white)
            val blackBefore = pixels(black)
            target.performTouchInput { down(center) }
            settle()
            val pressed = pixels(target)
            assertFalse("$tag background must react", before.sameAs(pressed))
            assertTrue("$tag must preserve white content", whiteBefore.sameAs(pixels(white)))
            assertTrue("$tag must preserve black content", blackBefore.sameAs(pixels(black)))
            assertEquals("$tag must follow its original rounded outline", before.getPixel(1, 1), pressed.getPixel(1, 1))
            assertNotEquals("$tag state must reach its left edge", before.getPixel(2, before.height / 2), pressed.getPixel(2, before.height / 2))
            save("$tag-pressed")
            target.performTouchInput { cancel() }
            settle()
            assertTrue(before.sameAs(pixels(target)))
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun groupedRowsFillOnlyTheirRowAndInheritTheCardOutline() {
        content {
            Card(Modifier.fillMaxWidth().testTag("card"), cornerRadius = 24.dp) {
                for (index in 0..1) {
                    ActionSurface({}, Modifier.fillMaxWidth().height(56.dp).testTag("row-$index"), shape = RectangleShape) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("列表 $index") }
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("card")
        val first = compose.onNodeWithTag("row-0")
        val second = compose.onNodeWithTag("row-1")
        val before = pixels(card)
        val firstBefore = pixels(first)
        val rowBefore = pixels(second)
        compose.mainClock.autoAdvance = false
        second.performTouchInput { down(center) }
        settle()
        val pressed = pixels(card)
        val rowPressed = pixels(second)
        assertTrue("A sibling row must not light up", firstBefore.sameAs(pixels(first)))
        assertNotEquals("The inner row boundary must not have a second rounded corner", rowBefore.getPixel(2, 2), rowPressed.getPixel(2, 2))
        assertEquals("The card's outer corner must stay clear", before.getPixel(1, before.height - 2), pressed.getPixel(1, pressed.height - 2))
        save("grouped-row-pressed")
        second.performTouchInput { cancel() }
        settle()
        assertTrue(before.sameAs(pixels(card)))
        compose.mainClock.autoAdvance = true
    }

    @Test fun nestedButtonsDoNotTriggerOrHighlightTheContainingAction() {
        var parentClicks = 0
        var buttonClicks = 0
        content {
            ActionSurface({ parentClicks++ }, Modifier.fillMaxWidth().testTag("parent")) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(50.dp).testTag("parent-background"))
                    CompactButton("充值", { buttonClicks++ }, Modifier.testTag("child"))
                }
            }
        }
        val background = compose.onNodeWithTag("parent-background", useUnmergedTree = true)
        val before = pixels(background)
        val child = compose.onNodeWithTag("child")
        compose.mainClock.autoAdvance = false
        child.performTouchInput { down(center) }
        settle()
        assertTrue("Only the child action may show feedback", before.sameAs(pixels(background)))
        child.performTouchInput { up() }
        settle()
        assertEquals(1, buttonClicks)
        assertEquals(0, parentClicks)
        compose.mainClock.autoAdvance = true
    }

    @Test fun myTransparentActionsDoNotPaintTilesInLightDarkAndMonetThemes() {
        val mode = mutableStateOf(ColorSchemeMode.Light)
        val settings = ThemeSettings(compose.activity.application)
        var profileClicks = 0
        var accountClicks = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode.value) {
                    MyPage(PreviewData.myState(), PreviewData.paymentState(false), settings, 0.dp,
                        isActive = false, onActivate = {}, onRefresh = {},
                        onProfile = { profileClicks++ }, onAccount = { accountClicks++ }, onBills = {},
                        onRecharge = {}, rechargeLoading = false, onCardAction = {}, manageLoading = false,
                        passwordLoading = false, onSettings = {}, onAbout = {})
                }
            }
        }
        val profile = compose.onNodeWithContentDescription("个人信息")
        val account = compose.onNode(hasText("账户详情") and hasClickAction())
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark, ColorSchemeMode.MonetLight, ColorSchemeMode.MonetDark)) {
            compose.runOnIdle { mode.value = theme }
            settle()
            save("${theme.name.lowercase()}-my-rest")
            compose.mainClock.autoAdvance = false
            for ((name, target) in listOf("profile" to profile, "account" to account)) {
                val before = pixels(target)
                target.performTouchInput { down(center) }
                settle()
                val pressed = pixels(target)
                assertFalse("$theme $name must show content feedback", before.sameAs(pressed))
                for (x in 0 until before.width) {
                    assertEquals("$name must not paint a tile across the upper padding", before.getPixel(x, 1), pressed.getPixel(x, 1))
                    assertEquals("$name must not paint a tile across the lower padding", before.getPixel(x, before.height - 2), pressed.getPixel(x, before.height - 2))
                }
                save("${theme.name.lowercase()}-my-$name-pressed")
                target.performTouchInput { cancel() }
                settle()
                assertTrue("$name must restore after cancellation", before.sameAs(pixels(target)))
                target.performTouchInput { click() }
                settle()
            }
            compose.mainClock.autoAdvance = true
        }
        assertEquals(4, profileClicks)
        assertEquals(4, accountClicks)
    }
}
