package com.mercury.yktwx.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.LifeProvider

/** Read-only Compose projections of the single persisted settings snapshot. */
class ThemeSettings internal constructor(internal val repository: AppPreferencesRepository) {
    private fun <T> ui(select: (UiPreferences) -> T): State<T> = derivedStateOf {
        select((repository.composeState.value as? PreferencesLoadState.Ready)?.preferences?.ui ?: UiPreferences())
    }
    val themeMode = ui { it.themeMode }
    val accentMode = ui { it.accentMode }
    val floatingBottomBar = ui { it.floatingBottomBar }
    val floatingBottomBarStyle = ui { it.floatingBottomBarStyle }
    val blurBottomBar = ui { it.blurBottomBar }
    val blurTopBar = ui { it.blurTopBar }
    val swipeBack = ui { it.swipeBack }
    val navTransitionStyle = ui { it.navTransitionStyle }
    val paymentMotionDetection = ui { it.paymentMotionDetection }
    val paymentBrightness = ui { it.paymentBrightness }
    val preventScreenshots = ui { it.preventScreenshots }
    val automaticUpdates = ui { it.automaticUpdates }
    val pangguiLoginNoticeDismissed = ui { it.pangguiLoginNoticeDismissed }
    val hui798LoginNoticeDismissed = ui { it.hui798LoginNoticeDismissed }
    val updateChannel = derivedStateOf {
        (repository.composeState.value as? PreferencesLoadState.Ready)?.preferences?.updateChannel
            ?: com.mercury.yktwx.BuildConfig.UPDATE_CHANNEL
    }
    private fun change(transform: (UiPreferences) -> UiPreferences) = repository.enqueueUpdate { it.copy(ui = transform(it.ui)) }
    fun dismissLifeLoginNotice(provider: LifeProvider) = change {
        if (provider == LifeProvider.Hui798) it.copy(hui798LoginNoticeDismissed = true)
        else it.copy(pangguiLoginNoticeDismissed = true)
    }
    fun getNavigationRailExpanded(default: Boolean): Boolean =
        (repository.composeState.value as? PreferencesLoadState.Ready)?.preferences?.ui?.navigationRailExpanded ?: default
    fun setNavigationRailExpanded(v: Boolean) = change { it.copy(navigationRailExpanded = v) }
    fun setTheme(v: Int) = change { it.copy(themeMode = v.coerceIn(0, 2)) }
    fun setAccent(v: Int) = change { it.copy(accentMode = v.coerceIn(0, 1)) }
    fun setFloating(v: Boolean) = change { it.copy(floatingBottomBar = v) }
    fun setFloatingStyle(v: Int) = change { it.copy(floatingBottomBarStyle = v.coerceIn(0, 1)) }
    fun setBlurBottom(v: Boolean) = change { it.copy(blurBottomBar = v) }
    fun setBlurTop(v: Boolean) = change { it.copy(blurTopBar = v) }
    fun setSwipeBack(v: Boolean) = change { it.copy(swipeBack = v) }
    fun setNavTransitionStyle(v: Int) = change { it.copy(navTransitionStyle = v.coerceIn(0, 1)) }
    fun setPaymentMotionDetection(v: Boolean) = change { it.copy(paymentMotionDetection = v) }
    fun setPaymentBrightness(v: Boolean) = change { it.copy(paymentBrightness = v) }
    fun setPreventScreenshots(v: Boolean) = change { it.copy(preventScreenshots = v) }
    fun setAutomaticUpdates(v: Boolean) = change { it.copy(automaticUpdates = v) }
    fun setUpdateChannel(value: String) {
        require(value in setOf("stable", "beta"))
        repository.enqueueUpdate { it.copy(updateChannel = value) }
    }
}
