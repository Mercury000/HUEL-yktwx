package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.BarBackdropContent
import com.mercury.yktwx.ui.components.BarBlurHost
import com.mercury.yktwx.ui.components.ScannerControls
import com.mercury.yktwx.ui.components.ScannerSweep
import com.mercury.yktwx.ui.pages.LifeScannerShell
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScannerStartupUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun lightweightShellMatchesScaffoldPixelsAndInsets() = compareShell()

    @Test @Config(qualifiers = "zh-rCN-w891dp-h411dp-xxhdpi", fontScale = 1.3f)
    fun lightweightShellMatchesScaffoldInLandscapeAndLargeFont() = compareShell()

    private fun compareShell() {
        var lightweight by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { AppTheme {
                // The app already supplies the root popup host outside the scanner shell.
                Scaffold {
                    val bar: @Composable () -> Unit = {
                        Box(Modifier.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .3f), Color.Transparent)))) {
                            SmallTopAppBar("扫一扫", color = Color.Transparent, titleColor = Color.White,
                                modifier = Modifier.testTag("bar"))
                        }
                    }
                    val body: @Composable (PaddingValues) -> Unit = { padding ->
                        Box(Modifier.fillMaxSize().background(Color(0xFF1B2025)).testTag("body")) {
                            Text("相机暂不可用", Modifier.align(Alignment.TopCenter)
                                .padding(top = padding.calculateTopPadding() + 16.dp).testTag("error"), color = Color.White)
                            Text("底部", Modifier.align(Alignment.BottomCenter)
                                .padding(bottom = padding.calculateBottomPadding()).testTag("bottom"), color = Color.White)
                        }
                    }
                    if (lightweight) LifeScannerShell(bar, body)
                    else BarBlurHost(false, false) {
                        Scaffold(topBar = bar) { padding ->
                            BarBackdropContent(Modifier.fillMaxSize().background(Color(0xFF1B2025))) { body(padding) }
                        }
                    }
                }
            } }
        }
        compose.waitForIdle()
        val baseline = compose.onRoot().captureToImage().asAndroidBitmap()
        val tags = listOf("bar", "body", "error", "bottom")
        val bounds = tags.associateWith { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        compose.runOnIdle { lightweight = true }
        compose.waitForIdle()
        val result = compose.onRoot().captureToImage().asAndroidBitmap()
        for (tag in tags) assertEquals("The scanner must retain $tag geometry", bounds[tag],
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot)
        assertPixelsMatch(baseline, result)
        val output = File("../tools/scanner-startup-ui/shell-${result.width}x${result.height}.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { result.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun cameraCapabilitiesKeepManualActionStillAndUnknownTorchInaccessible() {
        var flash by mutableStateOf<Boolean?>(null)
        var active by mutableStateOf(true)
        var manual = 0
        var torch = 0
        compose.setContent {
            AppTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ScannerControls(flash, false, { torch++ }, { manual++ }, active)
                }
            }
        }
        val initial = compose.onNodeWithText("输入设备编号").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("手电筒").assertDoesNotExist()
        compose.onNodeWithText("输入设备编号").performClick()
        assertEquals(1, manual)
        compose.runOnIdle { flash = true }
        assertEquals(initial, compose.onNodeWithText("输入设备编号").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("手电筒").assertIsEnabled().performClick()
        assertEquals(1, torch)
        compose.runOnIdle { active = false }
        compose.onNodeWithText("手电筒").assertIsNotEnabled()
        compose.onNodeWithText("输入设备编号").assertIsNotEnabled()
        compose.runOnIdle { flash = false; active = true }
        compose.onNodeWithText("手电筒").assertDoesNotExist()
        compose.onNodeWithText("输入设备编号").assertIsEnabled()
    }

    @Test fun cachedSweepPreservesGradientPixelsAcrossMotionAndFading() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Row {
                Box(Modifier.size(180.dp, 260.dp).background(Color.Black).testTag("original")) { OriginalSweep() }
                Box(Modifier.size(180.dp, 260.dp).background(Color.Black).testTag("cached")) { ScannerSweep() }
            }
        }
        compose.waitForIdle()
        // Initial delay, both fade ends, the middle, and the next cycle.
        for (delta in listOf(32L, 400L, 96L, 200L, 700L, 1100L, 200L, 400L)) {
            compose.mainClock.advanceTimeBy(delta)
            compose.waitForIdle()
            assertPixelsMatch(compose.onNodeWithTag("original").captureToImage().asAndroidBitmap(),
                compose.onNodeWithTag("cached").captureToImage().asAndroidBitmap(), tolerance = 1)
        }
        compose.mainClock.autoAdvance = true
    }

    private fun assertPixelsMatch(expected: Bitmap, actual: Bitmap, tolerance: Int = 0) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        var mismatches = 0
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            val left = expected.getPixel(x, y)
            val right = actual.getPixel(x, y)
            if (listOf(0, 8, 16, 24).any { shift ->
                kotlin.math.abs(((left ushr shift) and 255) - ((right ushr shift) and 255)) > tolerance
            }) mismatches++
        }
        assertEquals("Rendering must retain the original pixels", 0, mismatches)
    }

    @Composable private fun OriginalSweep() {
        val transition = rememberInfiniteTransition(label = "original-sweep")
        val progress = transition.animateFloat(0f, 1f,
            infiniteRepeatable(tween(2400, delayMillis = 400, easing = LinearEasing)), label = "original-progress")
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            val fraction = progress.value
            val y = size.height * fraction
            val alpha = minOf(fraction * 10f, (1f - fraction) * 10f, 1f).coerceAtLeast(0f)
            val ink = Color(0xFF7CE2B0)
            val trail = 48.dp.toPx()
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, ink.copy(alpha = .12f * alpha)),
                startY = y - trail, endY = y), topLeft = Offset(0f, y - trail), size = Size(size.width, trail))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, ink.copy(alpha = .85f * alpha), Color.Transparent)),
                topLeft = Offset(0f, y), size = Size(size.width, 2.dp.toPx()))
        }
    }
}
