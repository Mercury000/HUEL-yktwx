package com.mercury.yktwx.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import com.mercury.yktwx.ui.ThemeSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object StorageModule {
    @Provides @Singleton @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Provides @Singleton @PreferencesScope
    fun preferencesScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides @Singleton
    fun dataStore(@ApplicationContext context: Context, @PreferencesScope scope: CoroutineScope): DataStore<AppPreferences> =
        DataStoreFactory.create(AppPreferencesSerializer, migrations = listOf(LegacyPreferencesMigration(context)), scope = scope) {
            context.dataStoreFile("app-preferences.json")
        }
    @Provides @Singleton fun settings(repository: AppPreferencesRepository): ThemeSettings = ThemeSettings(repository)
    @Provides @Singleton fun updateStore(repository: AppPreferencesRepository, verifier: UpdateManifestVerifier): UpdateCheckStore =
        UpdateCheckStore(repository, verifyCached = { body, channel -> verifier.manifest(body, channel).first })
    @Provides @Singleton fun vault(@ApplicationContext context: Context): Vault = SecureVault(context)
    @Provides @Singleton fun display(@ApplicationContext context: Context): DisplayCache = DisplayCache(context)
    @Provides @Singleton fun retiredStorage(@ApplicationContext context: Context): RetiredStorageCleanup = RetiredStorageCleanup(context)
    @Provides @Singleton fun avatars(@ApplicationContext context: Context): LocalAvatarStore = LocalAvatarStore(context)
    @Provides @Singleton fun lifeStore(@ApplicationContext context: Context): LifeDeviceStore = LifeDeviceAccountStore(context)
    @Provides @Singleton fun huiStore(@ApplicationContext context: Context): Hui798Store = Hui798AccountStore(context)
    @Provides fun sessionAuthority(repository: AccountRepository): SessionAuthority = repository
}
