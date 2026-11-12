package com.mercury.yktwx.di

import android.content.Context
import com.mercury.yktwx.analytics.*
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.UsageConsent
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ApplicationModule {
    @Provides @Singleton
    fun analyticsBackend(@ApplicationContext context: Context): AnalyticsBackend = UmengAnalyticsBackend(context)

    @Provides @Singleton
    fun analytics(backend: AnalyticsBackend): AppAnalytics = AppAnalytics(backend)

    @Provides @Singleton
    fun consent(@ApplicationContext context: Context, analytics: AppAnalytics): UsageConsent = UsageConsent(
        context.getSharedPreferences("usage_consent", Context.MODE_PRIVATE), UsageNotice.hash,
        context.getSharedPreferences("encrypted_account", Context.MODE_PRIVATE).contains("credentials"), analytics::revoke)
}
