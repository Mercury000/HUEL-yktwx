package com.mercury.yktwx

import android.content.Intent
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityConfigurationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>

    @Before fun setup() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java)
            .putExtra("ui_preview", true)
        controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        compose.waitForIdle()
    }

    @After fun cleanup() {
        controller.pause().stop().destroy()
    }

    @Test fun displayChangesKeepThemePageSettingsAndScrollInPlace() {
        val activity = controller.get()
        compose.onNode(hasText("我的") and isSelectable()).performClick()
        compose.onNodeWithTag("my-page-list")
            .performScrollToNode(hasText("应用设置"))
        compose.onNodeWithText("应用设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        val swipeTitle = activity.getString(R.string.swipe_back)
        compose.onNodeWithText(swipeTitle).performScrollTo()
        val list = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(swipeTitle)))
        list.performTouchInput { swipeUp() }
        val listId = list.fetchSemanticsNode().id
        val scrollBefore = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("The fixture must have scrolled the actual theme page", scrollBefore > 0f)

        fun change(update: Configuration.() -> Unit) {
            compose.runOnIdle {
                controller.configurationChange(Configuration(controller.get().resources.configuration).apply(update))
            }
            compose.waitForIdle()
            assertSame("Display changes must retain the Activity", activity, controller.get())
            assertEquals("The theme page must remain composed", listId, list.fetchSemanticsNode().id)
            compose.onNodeWithText(swipeTitle).assertExists()
        }
        change { fontScale = 1.15f }
        change {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            screenWidthDp = 840
            screenHeightDp = 411
            smallestScreenWidthDp = 411
        }
        change {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 411
            screenHeightDp = 480
            fontScale = 1f
        }
        assertEquals("Returning to the original display must preserve scroll", scrollBefore,
            list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .00001f)
        compose.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
        compose.onNodeWithText("主题").assertIsDisplayed()
    }

    @Test fun navigationBarContrastProtectionStaysDisabledAcrossThemeChanges() {
        val activity = controller.get()
        assertFalse(activity.window.isNavigationBarContrastEnforced)
        for (night in listOf(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)) {
            compose.runOnIdle {
                controller.configurationChange(Configuration(activity.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                })
            }
            compose.waitForIdle()
            assertFalse("Theme changes must not restore the system navigation scrim",
                controller.get().window.isNavigationBarContrastEnforced)
        }
    }
}
