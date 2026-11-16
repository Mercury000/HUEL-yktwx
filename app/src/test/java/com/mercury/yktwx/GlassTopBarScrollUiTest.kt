package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.life.LifeProvider
import com.mercury.yktwx.data.life.WaterPhase
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.*
import com.mercury.yktwx.ui.pages.*
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
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h1000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassTopBarScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun paymentScanMaterialReturnsToRestAfterGestureAndProgrammaticScrollInBothThemes() {
        val settings = themeSettingsFixture(compose.activity).apply { setGlassStyle(true) }
        var mode by mutableStateOf(ColorSchemeMode.Light)
        var scans = 0
        var refreshes = 0
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(mode, glassStyle = true) {
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        PaymentPage(PreviewData.paymentState(false), settings, 100.dp,
                            onRefresh = { refreshes++ }, onScan = { scans++ }, isActive = true)
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("payment-page-list")
        val action = compose.onNodeWithTag("payment-scan-button")
        for (theme in listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark)) {
            for (blur in listOf(false, true)) {
                compose.runOnIdle { mode = theme; settings.setBlurTop(blur) }
                settle()
                val resting = pixels(action)
                list.performTouchInput { swipeUp() }
                settle()
                assertTrue("The payment content must actually leave the top", position(list) > 0f)
                assertFalse("Scrolled $theme/$blur scan must have native glass", resting.sameAs(pixels(action)))
                list.performScrollToIndex(0)
                settle()
                assertEquals(0f, position(list), .1f)
                assertTrue("Returning $theme/$blur must remove the scan material", resting.sameAs(pixels(action)))
                capture("payment-$theme-blur-$blur-returned")
                list.performScrollToIndex(1)
                settle()
                assertFalse("Programmatic scrolling must also raise glass", resting.sameAs(pixels(action)))
                list.performScrollToIndex(0)
                repeat(2) {
                    list.performTouchInput {
                        swipe(start = center.copy(y = height * .5f), end = center.copy(y = height * .95f), durationMillis = 600)
                    }
                }
                settle()
                assertTrue("After pull rebound the scan must return to the resting icon", resting.sameAs(pixels(action)))
            }
        }
        assertTrue("Pull refresh must remain available", refreshes > 0)
        action.performClick()
        assertEquals(1, scans)
    }

    @Test fun actualSettingsThemeAndReferencesRemoveBackMaterialAtTheTop() {
        val settings = themeSettingsFixture(compose.activity).apply {
            setTheme(1); setGlassStyle(true); setBlurTop(true); setFloating(true); setFloatingStyle(2)
        }
        val payments = paymentViewModelFixture(compose.activity.application).apply { initialize(true) }
        val bills = billViewModelFixture(compose.activity.application).apply { initialize(true) }
        var compact by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Box(if (compact) Modifier.fillMaxWidth().height(360.dp) else Modifier.fillMaxSize()) {
                    YktApp(settings, payments, bills)
                }
            }
        }
        settle()
        compose.onNode((hasText("我的") or hasContentDescription("我的")) and isSelectable()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("my-page-list").fetchSemanticsNodes().isNotEmpty() }
        settle()
        val myList = compose.onNodeWithTag("my-page-list")
        myList.performScrollToIndex(myList.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount - 2)
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        settle()
        checkReturn(compose.onNodeWithTag("settings-page-list"), "settings")
        compose.onNodeWithText("主题").performScrollTo().performClick()
        settle()
        checkReturn(compose.onNodeWithTag("theme-page-list"), "theme")
        back().performClick()
        settle()
        checkReturn(compose.onNodeWithTag("settings-page-list"), "settings-after-theme")
        back().performClick()
        settle()
        compose.onNodeWithText("关于财小通").performScrollTo().performClick()
        settle()
        compose.onNodeWithText(compose.activity.getString(R.string.about_references)).performScrollTo().performClick()
        settle()
        compose.runOnIdle { compact = true }
        settle()
        checkReturn(compose.onNodeWithTag("references-page-list"), "references")
    }

    @Test fun detailsAndSharedWaterShellRecoverAfterSavedScrollAndStageReplacement() {
        val settings = themeSettingsFixture(compose.activity).apply { setGlassStyle(true); setBlurTop(true) }
        var page by mutableIntStateOf(0)
        var phase by mutableStateOf(WaterPhase.Ready)
        val restorer = StateRestorationTester(compose)
        val payment = PreviewData.paymentState(false)
        val action = WaterDeviceAction("操作", true, {})
        restorer.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light, glassStyle = true) {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        key(page) {
                            when (page) {
                                0 -> BillDetailPage(PreviewData.billState().items.first(), settings, onBack = {})
                                1 -> PersonalProfilePage(PreviewData.myState(), settings, {}, {})
                                2 -> AccountDetailPage(payment, settings, {}, {})
                                else -> WaterDevicePage(WaterDeviceUiState(
                                    if (page == 3) LifeProvider.Panggui else LifeProvider.Hui798,
                                    "设备", location = "学校", deviceId = "device", phase = phase),
                                    settings, true, action, action, action, {}, "费用以实际记录为准")
                            }
                        }
                    }
                }
            }
        }
        for (index in 0..4) {
            compose.runOnIdle { page = index; phase = WaterPhase.Ready }
            settle()
            val list = compose.onNode(hasScrollToIndexAction() and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            val resting = pixels(back())
            list.performTouchInput { swipeUp() }
            settle()
            if (index == 2) {
                assertEquals("The short account page has no list overlap", 0f, position(list), .1f)
                assertTrue("A short page must remove elastic material after rebound", resting.sameAs(pixels(back())))
                compose.mainClock.autoAdvance = false
                list.performTouchInput { down(center); moveBy(Offset(0f, -120f), delayMillis = 32) }
                compose.waitForIdle()
                assertFalse("A held overscroll must retain the official elastic material", resting.sameAs(pixels(back())))
                list.performTouchInput { up() }
                compose.mainClock.autoAdvance = true
                settle()
                restorer.emulateSavedInstanceStateRestore()
                settle()
                assertTrue("A restored short account page must remain at rest", resting.sameAs(pixels(back())))
                capture("secondary-$index-returned")
                continue
            }
            assertTrue("Secondary page $index must really scroll", position(list) > 0f)
            assertFalse("Secondary page $index must show glass", resting.sameAs(pixels(back())))
            restorer.emulateSavedInstanceStateRestore()
            settle()
            assertTrue("Secondary page $index must restore scroll", position(list) > 0f)
            assertFalse("Restored page $index must retain glass", resting.sameAs(pixels(back())))
            list.performScrollToIndex(0)
            settle()
            assertTrue("Restored page $index must remove glass at top", resting.sameAs(pixels(back())))
            if (index >= 3) {
                list.performTouchInput { swipeUp() }
                settle()
                compose.runOnIdle { phase = WaterPhase.Running }
                settle()
                assertEquals("A new device stage starts with its own list", 0f, position(list), .1f)
                assertTrue("Outgoing stage scroll must not keep the new header glass", resting.sameAs(pixels(back())))
            }
            capture("secondary-$index-returned")
        }
    }

    @Test fun billGlassTracksOuterControlsAndBodyWhileInactiveAndAfterReturning() {
        val settings = themeSettingsFixture(compose.activity).apply { setGlassStyle(true); setBlurTop(true) }
        var statistics by mutableStateOf(false)
        var active by mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme(ColorSchemeMode.Light, glassStyle = true) {
                    key(statistics) {
                        BillPage(PreviewData.billState().copy(statsVisible = statistics), settings,
                            {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, isActive = active, showBack = true)
                    }
                }
            }
        }
        for (stats in listOf(false, true)) {
            compose.runOnIdle { statistics = stats }
            settle()
            val surface = compose.onNodeWithTag("bill-scroll-surface")
            val body = compose.onNodeWithTag(if (stats) "bill-stats-list" else "bill-list")
            val resting = pixels(back())
            surface.performTouchInput { swipeUp() }
            settle()
            assertTrue(position(surface) > 0f)
            val scrolled = pixels(back())
            assertFalse("Bill $stats must show glass when controls scroll", resting.sameAs(scrolled))
            compose.runOnIdle { active = false }
            settle()
            assertTrue("Disabling gestures must not change header overlap", scrolled.sameAs(pixels(back())))
            compose.runOnIdle { active = true }
            body.performScrollToIndex(0)
            settle()
            assertFalse("Returning only the body must not hide glass over collapsed controls", resting.sameAs(pixels(back())))
            repeat(4) {
                surface.performTouchInput {
                    swipe(start = center.copy(y = height * .3f), end = center.copy(y = height * .9f), durationMillis = 600)
                }
            }
            settle()
            assertEquals(0f, position(surface), .1f)
            assertTrue("Bill $stats must remove glass only after the whole page returns", resting.sameAs(pixels(back())))
            capture("bill-$stats-returned")
        }
    }

    private fun checkReturn(list: SemanticsNodeInteraction, name: String) {
        list.performScrollToIndex(0)
        settle()
        val resting = pixels(back())
        list.performTouchInput { swipeUp() }
        settle()
        assertTrue("$name must scroll", position(list) > 0f)
        assertFalse("$name must show native back material", resting.sameAs(pixels(back())))
        list.performScrollToIndex(0)
        settle()
        assertTrue("$name must remove material at top", resting.sameAs(pixels(back())))
        capture("$name-returned")
    }

    private fun back() = compose.onAllNodesWithContentDescription("返回").onLast()
    private fun pixels(node: SemanticsNodeInteraction) = node.captureToImage().asAndroidBitmap()
    private fun position(node: SemanticsNodeInteraction) =
        node.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun settle() {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val file = File("../tools/glass-topbar-scroll/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap()
            .compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
