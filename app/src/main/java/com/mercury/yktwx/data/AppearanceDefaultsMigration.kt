package com.mercury.yktwx.data

import androidx.datastore.core.DataMigration

/** Replace each v1 appearance default once, without resetting other preferences. */
internal object AppearanceDefaultsMigration : DataMigration<AppPreferences> {
    override suspend fun shouldMigrate(currentData: AppPreferences): Boolean = currentData.schemaVersion == 1

    override suspend fun migrate(currentData: AppPreferences): AppPreferences {
        if (!shouldMigrate(currentData)) return currentData
        val ui = currentData.ui
        // These four switches used false as the old default; true already matches the new default.
        return currentData.copy(schemaVersion = 2, ui = ui.copy(
            floatingBottomBar = true,
            floatingBottomBarStyle = if (ui.floatingBottomBarStyle == 0) 2 else ui.floatingBottomBarStyle,
            glassStyle = true,
            blurBottomBar = true,
            blurTopBar = true,
        )).validate()
    }

    override suspend fun cleanUp() = Unit
}
