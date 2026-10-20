package com.mercury.yktwx.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/** All SonyPods theme controls, with exactly the same defaults and persistent keys. */
class ThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
    val themeMode = mutableStateOf(prefs.getInt("theme_mode", 0).coerceIn(0, 2))
    val accentMode = mutableStateOf(prefs.getInt("accent_mode", 0).coerceIn(0, 1))
    val floatingBottomBar = mutableStateOf(prefs.getBoolean("floating_bottom_bar", false))
    val floatingBottomBarStyle = mutableStateOf(prefs.getInt("floating_bottom_bar_style", 0).coerceIn(0, 1))
    val blurBottomBar = mutableStateOf(prefs.getBoolean("blur_bottom_bar", false))
    val blurTopBar = mutableStateOf(prefs.getBoolean("blur_top_bar", false))
    val predictiveBack = mutableStateOf(prefs.getBoolean("predictive_back", true))
    val predictiveBackDistance = mutableStateOf(prefs.getInt("predictive_back_distance", 50).coerceIn(0, 100))
    fun setTheme(v: Int) { themeMode.value = v; prefs.edit().putInt("theme_mode", v).apply() }
    fun setAccent(v: Int) { accentMode.value = v; prefs.edit().putInt("accent_mode", v).apply() }
    fun setFloating(v: Boolean) { floatingBottomBar.value = v; prefs.edit().putBoolean("floating_bottom_bar", v).apply() }
    fun setFloatingStyle(v: Int) { floatingBottomBarStyle.value = v; prefs.edit().putInt("floating_bottom_bar_style", v).apply() }
    fun setBlurBottom(v: Boolean) { blurBottomBar.value = v; prefs.edit().putBoolean("blur_bottom_bar", v).apply() }
    fun setBlurTop(v: Boolean) { blurTopBar.value = v; prefs.edit().putBoolean("blur_top_bar", v).apply() }
    fun setPredictive(v: Boolean) { predictiveBack.value = v; prefs.edit().putBoolean("predictive_back", v).apply() }
    fun setDistance(v: Int) { predictiveBackDistance.value = v.coerceIn(0, 100); prefs.edit().putInt("predictive_back_distance", predictiveBackDistance.value).apply() }
}
