package com.mercury.yktwx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataMigration
import kotlinx.coroutines.CancellationException
import java.io.File

/** The only reader of the three retired preference files. See docs/architecture.md. */
internal class LegacyPreferencesMigration internal constructor(
    private val ui: SharedPreferences,
    private val hall: SharedPreferences,
    private val updates: SharedPreferences,
    private val retiredFiles: List<File> = emptyList(),
    private val deletePreferences: () -> Unit = {},
) : DataMigration<AppPreferences> {
    constructor(context: Context) : this(context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE),
        context.getSharedPreferences("hall_display", Context.MODE_PRIVATE),
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE),
        names.flatMap { name -> listOf("$name.xml", "$name.xml.bak").map {
            File(context.applicationInfo.dataDir, "shared_prefs/$it")
        } },
        { names.forEach { name -> migrationStep("删除旧设置文件（$name.xml）") {
            requirePreferences(context.deleteSharedPreferences(name), PreferencesFailureKind.Migration,
                "迁移后无法删除旧设置文件（$name.xml），请重试")
        } } })

    override suspend fun shouldMigrate(currentData: AppPreferences): Boolean = !currentData.legacyMigrated ||
        legacyValues(ui, "ui_settings").isNotEmpty() || legacyValues(hall, "hall_display").isNotEmpty() ||
        legacyValues(updates, "app_updates").isNotEmpty() || retiredFiles.any { it.exists() }

    override suspend fun migrate(currentData: AppPreferences): AppPreferences {
        // Cleanup may have failed after the target was persisted. Never re-import over newer settings.
        if (currentData.legacyMigrated) return currentData
        val values = legacyValues(ui, "ui_settings")
        fun integer(key: String, default: Int = 0) = values[key] as? Int ?: default
        fun boolean(key: String, default: Boolean = false) = values[key] as? Boolean ?: default
        val storedUi = if (values.isEmpty()) currentData.ui else UiPreferences(
            themeMode = integer("theme_mode").coerceIn(0, 2), accentMode = integer("accent_mode").coerceIn(0, 1),
            floatingBottomBar = boolean("floating_bottom_bar"), floatingBottomBarStyle = integer("floating_bottom_bar_style").coerceIn(0, 1),
            blurBottomBar = boolean("blur_bottom_bar"), blurTopBar = boolean("blur_top_bar"),
            glassStyle = false,
            swipeBack = boolean("swipe_back"), navTransitionStyle = integer("nav_transition_style").coerceIn(0, 1),
            paymentMotionDetection = boolean("payment_motion_detection"), paymentBrightness = boolean("payment_brightness", true),
            preventScreenshots = boolean("prevent_screenshots", true), automaticUpdates = boolean("automatic_updates", true),
            pangguiLoginNoticeDismissed = boolean("panggui_login_notice_dismissed"), hui798LoginNoticeDismissed = boolean("hui798_login_notice_dismissed"),
            navigationRailExpanded = values["navigation_rail_expanded"] as? Boolean,
        )
        val rooms = legacyValues(hall, "hall_display").mapNotNull { (key, value) ->
            if (!key.matches(Regex("[a-f0-9]{64}\\.selection"))) null
            else (value as? Set<*>)?.filterIsInstance<String>()?.toSet()?.let { key to it }
        }.toMap()
        val oldUpdates = legacyValues(updates, "app_updates")
        val reminders = oldUpdates.keys.mapNotNull { key ->
            Regex("^(.*:(?:stable|beta):[1-9][0-9]*):(?:checked|check_interval|dismissed_version|dismissed_at)$")
                .matchEntire(key)?.groupValues?.get(1)
        }.distinct().associateWith { prefix -> UpdateReminder(
            checkedAt = oldUpdates["$prefix:checked"] as? Long ?: 0,
            checkInterval = oldUpdates["$prefix:check_interval"] as? Long ?: UpdateCheckStore.DAY,
            dismissedVersion = oldUpdates["$prefix:dismissed_version"] as? Int ?: 0,
            dismissedAt = oldUpdates["$prefix:dismissed_at"] as? Long ?: 0,
        ) }
        // Only released 1.4.1 data is imported. Its unsigned update cache is discardable.
        // Channel, skip and signed-policy SharedPreferences fields were never released.
        // Imported appearance values use v1 defaults and must pass through the v2 migration.
        return currentData.copy(schemaVersion = 1, legacyMigrated = true, ui = storedUi, roomSelections = rooms,
            reminders = reminders).validate()
    }

    override suspend fun cleanUp() {
        names.zip(listOf(ui, hall, updates)).forEach { (name, prefs) ->
            migrationStep("清理旧设置内容（$name.xml）") {
                if (prefs.all.isNotEmpty()) requirePreferences(prefs.edit().clear().commit(), PreferencesFailureKind.Migration,
                    "迁移后无法清理旧设置内容（$name.xml），请重试")
            }
        }
        migrationStep("删除旧设置文件") { deletePreferences() }
    }

    private companion object {
        val names = listOf("ui_settings", "hall_display", "app_updates")
        fun legacyValues(prefs: SharedPreferences, name: String) = migrationStep("读取旧设置（$name.xml）") { prefs.all }
        inline fun <T> migrationStep(operation: String, action: () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: PreferencesDataException) { throw error }
        catch (error: Exception) {
            throw PreferencesDataException(PreferencesFailure(PreferencesFailureKind.Migration,
                "$operation 失败（${error.javaClass.simpleName}），请重试"), error)
        }
    }
}
