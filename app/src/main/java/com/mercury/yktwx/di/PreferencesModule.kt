package com.mercury.yktwx.di

import androidx.datastore.core.DataStore
import com.mercury.yktwx.data.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object PreferencesModule {
    @Provides @Singleton
    fun preferences(store: DataStore<AppPreferences>, @PreferencesScope scope: CoroutineScope,
        verifier: UpdateManifestVerifier): AppPreferencesRepository = AppPreferencesRepository(store, scope, verifier::manifest)
}
