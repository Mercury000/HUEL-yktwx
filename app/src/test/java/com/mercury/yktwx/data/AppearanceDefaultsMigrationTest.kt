package com.mercury.yktwx.data

import android.content.Context
import androidx.datastore.dataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.di.StorageModule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppearanceDefaultsMigrationTest {
    @Test fun productionUpgradePersistsDefaultsAndDoesNotReapplyAfterUserChanges() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = context.dataStoreFile("app-preferences.json")
        target.parentFile!!.mkdirs()
        val old = AppPreferences(schemaVersion = 1, legacyMigrated = true, writeRevision = 7,
            ui = UiPreferences(themeMode = 2, accentMode = 1,
                floatingBottomBar = false, floatingBottomBarStyle = 0, glassStyle = false,
                blurBottomBar = false, blurTopBar = false, swipeBack = true,
                paymentBrightness = false, automaticUpdates = false),
            roomSelections = mapOf("owner.selection" to setOf("room")), updateChannel = "beta",
            reminders = mapOf("reminder" to UpdateReminder(123, UpdateCheckStore.HOUR, 9, 456)),
            skippedVersions = mapOf("beta" to 9),
            policies = mapOf("beta" to AcceptedUpdatePolicy(1, "a".repeat(64), 3, 9, "signed-policy")))
        target.outputStream().use { AppPreferencesSerializer.writeTo(old, it) }
        val expected = old.copy(schemaVersion = 2, ui = old.ui.copy(
            floatingBottomBar = true, floatingBottomBarStyle = 2, glassStyle = true,
            blurBottomBar = true, blurTopBar = true))
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val customized: AppPreferences
        try {
            val store = StorageModule.dataStore(context, scope)
            assertEquals(expected, store.data.first())
            assertEquals(expected, target.inputStream().use { AppPreferencesSerializer.readFrom(it) })
            customized = store.updateData { it.copy(ui = it.ui.copy(
                floatingBottomBar = false, floatingBottomBarStyle = 0, glassStyle = false,
                blurBottomBar = false, blurTopBar = false)) }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(customized, StorageModule.dataStore(context, scope).data.first())
            assertEquals(customized, target.inputStream().use { AppPreferencesSerializer.readFrom(it) })
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun changedNavigationStyleDoesNotPreventOtherDefaultsFromMigrating() = runBlocking {
        for (style in listOf(1, 2, 99)) {
            val old = AppPreferences(schemaVersion = 1, legacyMigrated = true,
                ui = UiPreferences(themeMode = 2, accentMode = 1, floatingBottomBar = true,
                    floatingBottomBarStyle = style, glassStyle = false,
                    blurBottomBar = false, blurTopBar = true, swipeBack = true))
            assertTrue(AppearanceDefaultsMigration.shouldMigrate(old))
            val migrated = AppearanceDefaultsMigration.migrate(old)
            assertEquals(old.copy(schemaVersion = 2, ui = old.ui.copy(
                glassStyle = true, blurBottomBar = true)), migrated)
            assertFalse(AppearanceDefaultsMigration.shouldMigrate(migrated))
            assertEquals(migrated, AppearanceDefaultsMigration.migrate(migrated))
        }
    }
}
