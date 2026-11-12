package com.mercury.yktwx

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.components.DashboardPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-hdpi")
class MiuixDemoThemeTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    // Color's JVM getters return its packed Long. Read every public color role,
    // including disabled/error roles, without maintaining a partial sample list.
    private fun snapshot(colors: Colors): Map<String, Long> = Colors::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == Long::class.javaPrimitiveType }
        .associate { it.name.substringBefore('-') to (it.invoke(colors) as Long) }

    @Test fun everyThemeRoleMatchesUnmodifiedDemoController() {
        val mode = mutableStateOf(ColorSchemeMode.System)
        val referenceMode = mutableStateOf(ColorSchemeMode.System)
        val accent = mutableIntStateOf(0)
        val systemNight = mutableStateOf(false)
        var actual = emptyMap<String, Long>()
        var expected = emptyMap<String, Long>()
        var dashboard = emptyMap<String, Long>()
        var dashboardExpected = emptyMap<String, Long>()
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (systemNight.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                AppTheme(mode.value, accent.intValue) {
                    val demo = remember(referenceMode.value) { ThemeController(referenceMode.value) }.currentColors()
                    val app = MiuixTheme.colorScheme
                    val appRoles = snapshot(app)
                    val demoRoles = snapshot(demo)
                    val dashboardRoles = mapOf(
                        "ink" to DashboardPalette.ink.value.toLong(),
                        "muted" to DashboardPalette.muted.value.toLong(),
                        "card" to DashboardPalette.card.value.toLong(),
                        "field" to DashboardPalette.field.value.toLong(),
                    )
                    val dark = isSystemInDarkTheme()
                    val demoDashboardRoles = mapOf(
                        "ink" to (if (dark) demo.onSurface else Color(0xFF202329)).value.toLong(),
                        "muted" to (if (dark) demo.onSurfaceVariantSummary else Color(0xFF929CA9)).value.toLong(),
                        "card" to demo.surfaceContainer.value.toLong(),
                        "field" to (if (dark) demo.secondaryContainer else Color(0xFFF0F0F0)).value.toLong(),
                    )
                    SideEffect {
                        actual = appRoles; expected = demoRoles
                        dashboard = dashboardRoles; dashboardExpected = demoDashboardRoles
                    }
                }
            }
        }
        val scenarios = listOf(
            Triple(ColorSchemeMode.Light, 0, ColorSchemeMode.Light),
            Triple(ColorSchemeMode.Dark, 0, ColorSchemeMode.Dark),
            Triple(ColorSchemeMode.System, 0, ColorSchemeMode.System),
            Triple(ColorSchemeMode.Light, 1, ColorSchemeMode.MonetLight),
            Triple(ColorSchemeMode.Dark, 1, ColorSchemeMode.MonetDark),
            Triple(ColorSchemeMode.System, 1, ColorSchemeMode.MonetSystem),
        )
        for (night in listOf(false, true)) for ((appMode, appAccent, demoMode) in scenarios) {
            compose.runOnIdle {
                systemNight.value = night; mode.value = appMode
                accent.intValue = appAccent; referenceMode.value = demoMode
            }
            compose.waitForIdle()
            val scenario = "$appMode / accent=$appAccent / systemNight=$night"
            assertEquals("All 53 library color roles must be checked", 53, expected.size)
            assertEquals("AppTheme must preserve Demo colors: $scenario", expected, actual)
            assertEquals("Dashboard must preserve light colors and adapt dark foundation: $scenario", dashboardExpected, dashboard)
        }
    }
}
