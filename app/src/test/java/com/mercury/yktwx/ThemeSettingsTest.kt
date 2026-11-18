package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.data.life.LifeProvider
import kotlinx.coroutines.test.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ThemeSettingsTest {
    @Test fun choosingIosOrOs4EnablesTheNavigationWithoutClearingOtherPreferences() = runTest {
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        runCurrent()
        val settings = themeSettingsFixture(repository)
        settings.setBlurBottom(true)
        settings.setGlassStyle(true)
        for (style in listOf(1, 2)) {
            settings.setFloating(false)
            runCurrent()
            assertFalse(settings.floatingBottomBar.value)
            settings.setFloatingStyle(style)
            runCurrent()
            assertTrue(settings.floatingBottomBar.value)
            assertEquals(style, settings.floatingBottomBarStyle.value)
            assertTrue(settings.blurBottomBar.value)
            assertTrue(settings.glassStyle.value)
        }
        settings.setFloatingStyle(0)
        runCurrent()
        assertTrue("Returning to default keeps the existing default-floating preference", settings.floatingBottomBar.value)
    }

    @Test fun unsupportedAppearanceValuesRenderDefaultsAndSurviveUnrelatedWrites() = runTest {
        val unknown = UiPreferences(themeMode = 99, accentMode = -1, floatingBottomBarStyle = 99, navTransitionStyle = 7)
        val store = MemoryPreferences(AppPreferences(legacyMigrated = true, ui = unknown))
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        runCurrent()
        val settings = themeSettingsFixture(repository)
        assertTrue(repository.state.value is PreferencesLoadState.Ready)
        assertEquals(0, settings.themeMode.value)
        assertEquals(0, settings.accentMode.value)
        assertEquals(0, settings.floatingBottomBarStyle.value)
        assertEquals(0, settings.navTransitionStyle.value)
        settings.setBlurTop(true); runCurrent()
        assertEquals(unknown.copy(blurTopBar = true), store.values.value.ui)
        settings.setFloatingStyle(2); runCurrent()
        assertEquals(2, settings.floatingBottomBarStyle.value)
        assertEquals(99, repository.snapshot().ui.themeMode)
    }

    @Test fun glassAppearanceDoesNotReplaceThePersistedIosStyle() = runTest {
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        runCurrent()
        val settings = themeSettingsFixture(repository)
        val restored = themeSettingsFixture(repository)
        settings.setFloatingStyle(1)
        settings.setGlassStyle(true)
        runCurrent()
        assertTrue(restored.glassStyle.value)
        assertEquals(1, restored.floatingBottomBarStyle.value)
        settings.setFloatingStyle(2)
        settings.setGlassStyle(false)
        runCurrent()
        assertEquals(2, restored.floatingBottomBarStyle.value)
        assertFalse(restored.glassStyle.value)
        assertEquals(repository.snapshot(), store.values.value)
    }

    @Test fun everySettingIsReadFromThePersistedSharedSnapshot() = runTest {
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        runCurrent()
        val settings = themeSettingsFixture(repository)
        val other = themeSettingsFixture(repository)
        assertTrue(settings.floatingBottomBar.value)
        assertEquals(2, settings.floatingBottomBarStyle.value)
        assertTrue(settings.glassStyle.value)
        assertTrue(settings.blurBottomBar.value)
        assertTrue(settings.blurTopBar.value)
        assertTrue(settings.automaticUpdates.value)
        assertTrue(settings.paymentBrightness.value)
        assertFalse(settings.swipeBack.value)
        settings.setTheme(2); settings.setAccent(1); settings.setFloating(true)
        settings.setFloatingStyle(1); settings.setBlurBottom(true); settings.setBlurTop(true)
        settings.setSwipeBack(true); settings.setNavTransitionStyle(1)
        settings.setPaymentMotionDetection(true); settings.setPaymentBrightness(false)
        settings.setAutomaticUpdates(false)
        settings.setNavigationRailExpanded(true)
        settings.dismissLifeLoginNotice(LifeProvider.Hui798)
        settings.setUpdateChannel("beta")
        // No speculative UI state is published before persistence.
        assertEquals(0, settings.themeMode.value)
        runCurrent()
        assertEquals(2, other.themeMode.value)
        assertEquals(1, other.accentMode.value)
        assertTrue(other.floatingBottomBar.value)
        assertEquals(1, other.floatingBottomBarStyle.value)
        assertTrue(other.blurBottomBar.value); assertTrue(other.blurTopBar.value)
        assertTrue(other.swipeBack.value); assertEquals(1, other.navTransitionStyle.value)
        assertTrue(other.paymentMotionDetection.value); assertFalse(other.paymentBrightness.value)
        assertFalse(other.automaticUpdates.value)
        assertTrue(other.getNavigationRailExpanded(false)); assertTrue(other.hui798LoginNoticeDismissed.value)
        assertFalse(other.pangguiLoginNoticeDismissed.value); assertEquals("beta", other.updateChannel.value)
        assertEquals(repository.snapshot(), store.values.value)
        assertThrows(IllegalArgumentException::class.java) { settings.setUpdateChannel("unknown") }
    }

    @Test fun failedWritesKeepThePersistedValueAndReportFailure() = runTest {
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        runCurrent()
        val settings = themeSettingsFixture(repository)
        val failures = mutableListOf<PreferencesFailure>()
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            repository.failures.collect { failures += it }
        }
        store.failure = java.io.IOException("disk full")
        settings.setPaymentBrightness(false)
        runCurrent()
        assertTrue(settings.paymentBrightness.value)
        assertEquals(PreferencesFailureKind.Storage, failures.single().kind)
        assertTrue(failures.single().detail.contains("IOException"))
        store.failure = null
        settings.setPaymentBrightness(false); runCurrent()
        assertFalse(settings.paymentBrightness.value)
    }
}
