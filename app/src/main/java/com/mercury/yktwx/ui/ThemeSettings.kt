package com.mercury.yktwx.ui

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.mercury.yktwx.data.life.LifeProvider

/** Persistent UI preferences, including SonyPods theme controls with their original defaults. */
class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
    val themeMode = mutableStateOf(prefs.getInt("theme_mode", 0).coerceIn(0, 2))
    val accentMode = mutableStateOf(prefs.getInt("accent_mode", 0).coerceIn(0, 1))
    val floatingBottomBar = mutableStateOf(prefs.getBoolean("floating_bottom_bar", false))
    val floatingBottomBarStyle = mutableStateOf(prefs.getInt("floating_bottom_bar_style", 0).coerceIn(0, 1))
    val blurBottomBar = mutableStateOf(prefs.getBoolean("blur_bottom_bar", false))
    val blurTopBar = mutableStateOf(prefs.getBoolean("blur_top_bar", false))
    val swipeBack = mutableStateOf(prefs.getBoolean("swipe_back", false))
    val navTransitionStyle = mutableStateOf(prefs.getInt("nav_transition_style", 0).coerceIn(0, 1))
    val paymentMotionDetection = mutableStateOf(prefs.getBoolean("payment_motion_detection", false))
    val preventScreenshots = mutableStateOf(prefs.getBoolean("prevent_screenshots", true))
    val automaticUpdates = mutableStateOf(prefs.getBoolean("automatic_updates", true))
    val pangguiLoginNoticeDismissed = mutableStateOf(prefs.getBoolean("panggui_login_notice_dismissed", false))
    val hui798LoginNoticeDismissed = mutableStateOf(prefs.getBoolean("hui798_login_notice_dismissed", false))
    fun dismissLifeLoginNotice(provider: LifeProvider) {
        val hui = provider == LifeProvider.Hui798
        (if (hui) hui798LoginNoticeDismissed else pangguiLoginNoticeDismissed).value = true
        prefs.edit().putBoolean(if (hui) "hui798_login_notice_dismissed" else "panggui_login_notice_dismissed", true).apply()
    }
    fun getNavigationRailExpanded(default: Boolean): Boolean = prefs.getBoolean("navigation_rail_expanded", default)
    fun setNavigationRailExpanded(v: Boolean) { prefs.edit().putBoolean("navigation_rail_expanded", v).apply() }
    fun setTheme(v: Int) { themeMode.value = v; prefs.edit().putInt("theme_mode", v).apply() }
    fun setAccent(v: Int) { accentMode.value = v; prefs.edit().putInt("accent_mode", v).apply() }
    fun setFloating(v: Boolean) { floatingBottomBar.value = v; prefs.edit().putBoolean("floating_bottom_bar", v).apply() }
    fun setFloatingStyle(v: Int) { floatingBottomBarStyle.value = v; prefs.edit().putInt("floating_bottom_bar_style", v).apply() }
    fun setBlurBottom(v: Boolean) { blurBottomBar.value = v; prefs.edit().putBoolean("blur_bottom_bar", v).apply() }
    fun setBlurTop(v: Boolean) { blurTopBar.value = v; prefs.edit().putBoolean("blur_top_bar", v).apply() }
    fun setSwipeBack(v: Boolean) { swipeBack.value = v; prefs.edit().putBoolean("swipe_back", v).apply() }
    fun setNavTransitionStyle(v: Int) { navTransitionStyle.value = v.coerceIn(0, 1); prefs.edit().putInt("nav_transition_style", navTransitionStyle.value).apply() }
    fun setPaymentMotionDetection(v: Boolean) { paymentMotionDetection.value = v; prefs.edit().putBoolean("payment_motion_detection", v).apply() }
    fun setPreventScreenshots(v: Boolean) { preventScreenshots.value = v; prefs.edit().putBoolean("prevent_screenshots", v).apply() }
    fun setAutomaticUpdates(v: Boolean) { automaticUpdates.value = v; prefs.edit().putBoolean("automatic_updates", v).apply() }
}
