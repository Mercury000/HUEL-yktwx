package com.mercury.yktwx.ui

import android.content.Context
import android.content.SharedPreferences
import com.mercury.yktwx.analytics.AnalyticsFixtures
import com.mercury.yktwx.data.*
import kotlinx.coroutines.*
import java.util.WeakHashMap

private val preferences = WeakHashMap<Context, AppPreferencesRepository>()
internal fun testPreferences(context: Context): AppPreferencesRepository = preferences.getOrPut(context.applicationContext) {
    val migration = LegacyPreferencesMigration(context)
    val initial = runBlocking { migration.migrate(AppPreferences()) }
    appPreferencesRepositoryFixture(MemoryPreferences(initial), CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
}
internal fun themeSettingsFixture(context: Context) = ThemeSettings(testPreferences(context))
internal fun themeSettingsFixture(repository: AppPreferencesRepository) = ThemeSettings(repository)
internal fun usageConsentFixture(context: Context) = UsageConsent(context.getSharedPreferences("usage_consent", Context.MODE_PRIVATE),
    UsageNotice.hash, context.getSharedPreferences("encrypted_account", Context.MODE_PRIVATE).contains("credentials"),
    AnalyticsFixtures.from(context)::revoke)
internal fun usageConsentFixture(prefs: SharedPreferences, hash: String, previousLogin: Boolean = false, onRevoked: () -> Unit = {}) =
    UsageConsent(prefs, hash, previousLogin, onRevoked)
