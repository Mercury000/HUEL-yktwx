package com.mercury.yktwx

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.DashboardPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-hdpi")
class ThemeBehaviorTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun explicitAndSystemThemesKeepPaletteConfigurationAndPageStateConsistent() {
        val mode = mutableStateOf(ColorSchemeMode.System)
        val accent = mutableIntStateOf(0)
        val systemNight = mutableStateOf(false)
        var configuredDark = false
        var paletteDark = false
        var dashboardDark = false
        var scrollIndex = -1
        var screenWidth = -1
        compose.setContent {
            val deviceConfig = LocalConfiguration.current
            val systemConfig = Configuration(deviceConfig).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemNight.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides systemConfig) {
                AppTheme(mode.value, accent.intValue) {
                    var draft by remember { mutableStateOf("校园卡") }
                    val listState = rememberLazyListState()
                    val dark = isSystemInDarkTheme()
                    val darkSurface = MiuixTheme.colorScheme.background.luminance() < .5f
                    val lightInk = DashboardPalette.ink.luminance() > .5f
                    val width = LocalConfiguration.current.screenWidthDp
                    SideEffect {
                        configuredDark = dark
                        paletteDark = darkSurface
                        dashboardDark = lightInk
                        scrollIndex = listState.firstVisibleItemIndex
                        screenWidth = width
                    }
                    Column(Modifier.fillMaxSize()) {
                        BasicTextField(draft, { draft = it }, Modifier.testTag("theme-draft"))
                        LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("theme-list")) {
                            items(40) { Text("条目 $it", Modifier.height(48.dp)) }
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("theme-draft").performTextReplacement("校园卡备注")
        compose.onNodeWithTag("theme-list").performScrollToIndex(8)
        for (night in listOf(false, true)) for (accentStyle in 0..1) {
            for (theme in listOf(ColorSchemeMode.Dark, ColorSchemeMode.Light, ColorSchemeMode.System)) {
                compose.runOnIdle { systemNight.value = night; accent.intValue = accentStyle; mode.value = theme }
                compose.waitForIdle()
                val expectedDark = when (theme) {
                    ColorSchemeMode.Dark -> true
                    ColorSchemeMode.Light -> false
                    else -> night
                }
                val scenario = "$theme / accent=$accentStyle / systemNight=$night"
                assertEquals("Configuration must follow the selected theme: $scenario", expectedDark, configuredDark)
                assertEquals("Miuix palette must match the selected theme: $scenario", expectedDark, paletteDark)
                assertEquals("Dashboard colors must match the same theme: $scenario", expectedDark, dashboardDark)
                assertEquals("Theme changes must keep the scroll position: $scenario", 8, scrollIndex)
                assertEquals("Theme overrides must retain screen configuration", 411, screenWidth)
                compose.onNodeWithTag("theme-draft").assertTextEquals("校园卡备注")
            }
        }
    }
}
