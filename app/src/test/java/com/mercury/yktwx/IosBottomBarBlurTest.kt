package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.MainNavigationScaffold
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.ui.components.BarBackdropContent
import com.mercury.yktwx.ui.components.BarBlurHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IosBottomBarBlurTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private lateinit var settings: ThemeSettings

    @Before fun setup() {
        compose.activity.getSharedPreferences("ui_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = themeSettingsFixture(compose.activity).apply {
            setTheme(1)
            setFloating(true)
            setFloatingStyle(1)
            setBlurBottom(false)
        }
    }

    @Test fun appBlurSwitchChangesIosSurfaceAndRestoresItInBothThemes() {
        val app = compose.activity.application
        val payments = paymentViewModelFixture(app).apply { initialize(true) }
        val bills = billViewModelFixture(app).apply { initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) }
        }
        compose.onNode(hasText("我的") and isSelectable()).performClick()
        for (mode in listOf(1, 2)) {
            compose.runOnIdle { settings.setTheme(mode) }
            val opaque = capture("app-$mode-off")
            val bounds = compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot
            compose.runOnIdle { settings.setBlurBottom(true) }
            val glass = capture("app-$mode-on")
            assertTrue("Bottom blur must visibly change the iOS surface (theme $mode)",
                changedFraction(opaque, glass) > .03f)
            assertEquals("Toggling blur must preserve the floating bar's position and size", bounds,
                compose.onNodeWithTag("main-bottom-navigation").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { settings.setBlurBottom(false) }
            val restored = capture("app-$mode-restored")
            assertTrue("Disabling blur must restore the opaque surface (theme $mode)",
                changedFraction(opaque, restored) < .005f)
            compose.onNode(hasText("我的") and isSelectable()).assertIsSelected()
        }
    }

    @Test fun enablingBarBlurChangesTheOpaqueSurface() {
        compose.setContent {
            AppTheme(ColorSchemeMode.Light) {
                BarBlurHost(settings.blurBottomBar.value, false) {
                    MainNavigationScaffold(settings, selected = 0, onSelected = {}) {
                        BarBackdropContent(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxSize()) {
                                for (color in listOf(Color.Red, Color.Green, Color.Blue)) {
                                    Box(Modifier.weight(1f).fillMaxHeight().background(color))
                                }
                            }
                        }
                    }
                }
            }
        }
        val opaque = capture("bar-blur-off")
        compose.runOnIdle { settings.setBlurBottom(true) }
        assertTrue("Enabling bar blur must sample the page background",
            changedFraction(opaque, capture("bar-blur-on")) > .03f)
    }

    private fun capture(name: String): Bitmap {
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.runOnIdle { compose.activity.window.decorView.invalidate() }
        val bitmap = compose.onNodeWithTag("main-bottom-navigation").captureToImage()
            .asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
        System.getenv("NAVIGATION_BLUR_SCREENSHOTS")?.let { directory ->
            File(directory).mkdirs()
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return bitmap
    }

    private fun changedFraction(before: Bitmap, after: Bitmap): Float {
        assertEquals(before.width, after.width)
        assertEquals(before.height, after.height)
        var changed = 0
        for (y in 0 until before.height) for (x in 0 until before.width) {
            val a = before.getPixel(x, y)
            val b = after.getPixel(x, y)
            if (maxOf(kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
                    kotlin.math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
                    kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))) > 8) changed++
        }
        return changed.toFloat() / (before.width * before.height)
    }
}
