package com.mercury.yktwx.di

import android.content.Context
import com.mercury.yktwx.data.AndroidNetworkMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped

@Module
@InstallIn(ViewModelComponent::class)
internal object ViewModelModule {
    @Provides @ViewModelScoped
    fun networkMonitor(@ApplicationContext context: Context): AndroidNetworkMonitor = AndroidNetworkMonitor(context)
}
