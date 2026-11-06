package com.mercury.yktwx

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.ThemeSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun transitionStyleDefaultsToMiuixAndPersistsTheSelection() {
        val settings = ThemeSettings(context)
        assertEquals(0, settings.navTransitionStyle.value)
        settings.setNavTransitionStyle(1)
        assertEquals(1, settings.navTransitionStyle.value)
        assertEquals(1, ThemeSettings(context).navTransitionStyle.value)
        settings.setNavTransitionStyle(0)
        assertEquals(0, ThemeSettings(context).navTransitionStyle.value)
    }

    @Test fun swipeBackUsesTheDemoDefaultAndIgnoresRemovedPredictiveSettings() {
        prefs.edit().putBoolean("predictive_back", true).putInt("predictive_back_distance", 25).commit()
        val settings = ThemeSettings(context)
        assertFalse(settings.swipeBack.value)
        settings.setSwipeBack(true)
        assertTrue(settings.swipeBack.value)
        assertTrue(ThemeSettings(context).swipeBack.value)
        settings.setSwipeBack(false)
        assertFalse(ThemeSettings(context).swipeBack.value)
    }

    @Test fun automaticUpdatesDefaultOnAndCanBePersistentlyDisabled() {
        val settings = ThemeSettings(context)
        assertTrue(settings.automaticUpdates.value)
        settings.setAutomaticUpdates(false)
        assertFalse(settings.automaticUpdates.value)
        assertFalse(ThemeSettings(context).automaticUpdates.value)
        settings.setAutomaticUpdates(true)
        assertTrue(ThemeSettings(context).automaticUpdates.value)
    }

    @Test fun paymentMotionDetectionIsOffForNewAndExistingUsersWithoutThePreference() {
        assertFalse(ThemeSettings(context).paymentMotionDetection.value)
        prefs.edit().putInt("theme_mode", 2).putBoolean("prevent_screenshots", false).commit()
        val settings = ThemeSettings(context)
        assertFalse(settings.paymentMotionDetection.value)
        assertEquals(2, settings.themeMode.value)
        assertFalse(settings.preventScreenshots.value)
    }
    @Test fun enablingPersistsAndUpdatesObservableState() {
        val settings = ThemeSettings(context)
        settings.setPaymentMotionDetection(true)
        assertTrue(settings.paymentMotionDetection.value)
        assertTrue(ThemeSettings(context).paymentMotionDetection.value)
    }
    @Test fun disablingPersistsWithoutChangingOtherPreferences() {
        val settings = ThemeSettings(context)
        settings.setTheme(2)
        settings.setPaymentMotionDetection(true)
        settings.setPaymentMotionDetection(false)
        assertFalse(settings.paymentMotionDetection.value)
        val restored = ThemeSettings(context)
        assertFalse(restored.paymentMotionDetection.value)
        assertEquals(2, restored.themeMode.value)
        assertTrue(restored.preventScreenshots.value)
    }
}
