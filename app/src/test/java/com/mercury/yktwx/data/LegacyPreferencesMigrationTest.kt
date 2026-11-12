package com.mercury.yktwx.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyPreferencesMigrationTest {
    @get:Rule val files = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ui = context.getSharedPreferences("migration-ui", Context.MODE_PRIVATE)
    private val hall = context.getSharedPreferences("migration-hall", Context.MODE_PRIVATE)
    private val updates = context.getSharedPreferences("migration-updates", Context.MODE_PRIVATE)

    @Test fun released141SettingsMigrateOnceAndUnreleasedFieldsAndDisplayCacheAreDiscarded() = runBlocking {
        val owner = "a".repeat(64) + ".selection"
        val key = "com.mercury.yktwx:stable:3"
        ui.edit().putInt("theme_mode", 2).putBoolean("prevent_screenshots", false)
            .putBoolean("payment_brightness", false).putBoolean("swipe_back", true).putBoolean("predictive_back", true).commit()
        hall.edit().putStringSet(owner, setOf("target")).putString("raw-account", "ignored").commit()
        updates.edit().putLong("$key:checked", 123).putLong("$key:check_interval", UpdateCheckStore.HOUR)
            .putInt("$key:dismissed_version", 9).putLong("$key:dismissed_at", 456)
            .putString("$key:release", "unsigned discarded")
            .putString("com.mercury.yktwx:channel", "beta").putInt("com.mercury.yktwx:skipped_version", 9)
            .putString("com.mercury.yktwx:policy:stable:manifest", "unreleased invalid data").commit()
        val migration = LegacyPreferencesMigration(ui, hall, updates)
        val target = java.io.File(files.root, "settings.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = DataStoreFactory.create(AppPreferencesSerializer, migrations = listOf(migration), scope = scope) { target }
            val saved = store.data.first()
            assertTrue(saved.legacyMigrated)
            assertEquals(2, saved.ui.themeMode); assertFalse(saved.ui.preventScreenshots)
            assertFalse(saved.ui.paymentBrightness); assertTrue(saved.ui.swipeBack)
            assertEquals(setOf("target"), saved.roomSelections[owner])
            assertEquals(UpdateReminder(123, UpdateCheckStore.HOUR, 9, 456), saved.reminders[key])
            assertEquals(com.mercury.yktwx.BuildConfig.UPDATE_CHANNEL, saved.updateChannel)
            assertTrue(saved.policies.isEmpty()); assertTrue(saved.skippedVersions.isEmpty())
            assertTrue(target.isFile); assertTrue(ui.all.isEmpty()); assertTrue(hall.all.isEmpty()); assertTrue(updates.all.isEmpty())
            ui.edit().putInt("theme_mode", 1).commit()
            assertEquals(saved, migration.migrate(saved))
            migration.cleanUp(); assertTrue(ui.all.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun cleanupFailurePreservesDurableTargetAndRetryDoesNotReimportOldValues() = runBlocking {
        ui.edit().putInt("theme_mode", 2).commit()
        hall.edit().putStringSet("b".repeat(64) + ".selection", setOf("target")).commit()
        var failCleanup = true
        val unreliable = object : SharedPreferences by hall {
            override fun edit(): SharedPreferences.Editor {
                val editor = hall.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun clear(): SharedPreferences.Editor { editor.clear(); return this }
                    override fun commit(): Boolean = if (failCleanup) false else editor.commit()
                }
            }
        }
        val migration = LegacyPreferencesMigration(ui, unreliable, updates)
        val target = java.io.File(files.root, "retry.json")
        suspend fun read(): AppPreferences {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try { return DataStoreFactory.create(AppPreferencesSerializer, migrations = listOf(migration), scope = scope) { target }.data.first() }
            finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { read() } }
        assertTrue(target.isFile)
        val durable = target.inputStream().use { AppPreferencesSerializer.readFrom(it) }
        assertTrue(durable.legacyMigrated)
        ui.edit().putInt("theme_mode", 1).commit()
        failCleanup = false
        assertEquals(durable, read())
        assertTrue(ui.all.isEmpty()); assertTrue(hall.all.isEmpty())
    }

    @Test fun productionMigrationDeletesRetiredFilesAfterSavingAndKeepsActiveStorage() = runBlocking {
        val names = listOf("ui_settings", "hall_display", "app_updates")
        context.getSharedPreferences(names[0], Context.MODE_PRIVATE).edit().putInt("theme_mode", 2).commit()
        context.getSharedPreferences(names[1], Context.MODE_PRIVATE).edit()
            .putStringSet("a".repeat(64) + ".selection", setOf("room")).commit()
        context.getSharedPreferences(names[2], Context.MODE_PRIVATE).edit().putString("old:release", "discard").commit()
        val active = context.getSharedPreferences("usage_consent", Context.MODE_PRIVATE)
        active.edit().putString("accepted_hash", "keep").commit()
        val target = java.io.File(files.root, "production.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val saved = DataStoreFactory.create(AppPreferencesSerializer,
                migrations = listOf(LegacyPreferencesMigration(context)), scope = scope) { target }.data.first()
            assertEquals(2, saved.ui.themeMode)
            assertEquals(setOf("room"), saved.roomSelections["a".repeat(64) + ".selection"])
            assertEquals(saved, target.inputStream().use { AppPreferencesSerializer.readFrom(it) })
            names.forEach { name ->
                assertFalse(java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").exists())
                assertFalse(java.io.File(context.applicationInfo.dataDir, "shared_prefs/$name.xml.bak").exists())
            }
            assertEquals("keep", active.getString("accepted_hash", null))
            assertFalse(LegacyPreferencesMigration(context).shouldMigrate(saved))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun fileDeletionFailureRetriesEmptyFilesWithoutChangingDurableSettings() = runBlocking {
        val retiredUi = context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE)
        retiredUi.edit().putInt("theme_mode", 2).commit()
        var failDeletion = true
        val unreliable = object : ContextWrapper(context) {
            override fun deleteSharedPreferences(name: String): Boolean =
                if (name == "ui_settings" && failDeletion) false else super.deleteSharedPreferences(name)
        }
        val target = java.io.File(files.root, "delete-retry.json")
        suspend fun read(): AppPreferences {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                return DataStoreFactory.create(AppPreferencesSerializer,
                    migrations = listOf(LegacyPreferencesMigration(unreliable)), scope = scope) { target }.data.first()
            } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        }
        assertThrows(IllegalStateException::class.java) { runBlocking { read() } }
        val durable = target.inputStream().use { AppPreferencesSerializer.readFrom(it) }
        assertEquals(2, durable.ui.themeMode)
        assertTrue(durable.legacyMigrated)
        assertTrue(retiredUi.all.isEmpty())
        assertTrue(LegacyPreferencesMigration(unreliable).shouldMigrate(durable))
        failDeletion = false
        assertEquals(durable, read())
        assertFalse(java.io.File(context.applicationInfo.dataDir, "shared_prefs/ui_settings.xml").exists())
        assertFalse(LegacyPreferencesMigration(context).shouldMigrate(durable))
    }

    @Test fun alreadyMigratedSettingsAlsoReclaimAnEmptyBackupWithoutReimporting() = runBlocking {
        val saved = AppPreferences(legacyMigrated = true, ui = UiPreferences(themeMode = 2))
        val migration = LegacyPreferencesMigration(context)
        val backup = java.io.File(context.applicationInfo.dataDir, "shared_prefs/hall_display.xml.bak")
        backup.parentFile!!.mkdirs()
        backup.writeText("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map />")
        assertTrue(migration.shouldMigrate(saved))
        assertEquals(saved, migration.migrate(saved))
        migration.cleanUp()
        assertFalse(backup.exists())
        assertFalse(LegacyPreferencesMigration(context).shouldMigrate(saved))
    }
}
