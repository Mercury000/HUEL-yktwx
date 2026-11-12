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
        settings.setFloatingStyle(1); runCurrent()
        assertEquals(1, settings.floatingBottomBarStyle.value)
        assertEquals(99, repository.snapshot().ui.themeMode)
    }

    @Test fun everySettingIsReadFromThePersistedSharedSnapshot() = runTest {
        val store = MemoryPreferences()
        val repository = appPreferencesRepositoryFixture(store, backgroundScope)
        runCurrent()
        val settings = themeSettingsFixture(repository)
        val other = themeSettingsFixture(repository)
        assertTrue(settings.preventScreenshots.value)
        assertTrue(settings.automaticUpdates.value)
        assertTrue(settings.paymentBrightness.value)
        assertFalse(settings.swipeBack.value)
        settings.setTheme(2); settings.setAccent(1); settings.setFloating(true)
        settings.setFloatingStyle(1); settings.setBlurBottom(true); settings.setBlurTop(true)
        settings.setSwipeBack(true); settings.setNavTransitionStyle(1)
        settings.setPaymentMotionDetection(true); settings.setPaymentBrightness(false)
        settings.setPreventScreenshots(false); settings.setAutomaticUpdates(false)
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
        assertFalse(other.preventScreenshots.value); assertFalse(other.automaticUpdates.value)
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
        settings.setPreventScreenshots(false)
        runCurrent()
        assertTrue(settings.preventScreenshots.value)
        assertEquals(PreferencesFailureKind.Storage, failures.single().kind)
        assertTrue(failures.single().detail.contains("IOException"))
        store.failure = null
        settings.setPreventScreenshots(false); runCurrent()
        assertFalse(settings.preventScreenshots.value)
    }
}
