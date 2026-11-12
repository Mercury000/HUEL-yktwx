package com.mercury.yktwx

import android.app.Application
import com.mercury.yktwx.analytics.AppAnalytics
import com.mercury.yktwx.data.RetiredStorageCleanup
import com.mercury.yktwx.di.ApplicationScope
import com.mercury.yktwx.ui.UsageConsent
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class YktApplication : Application() {
    @Inject internal lateinit var analytics: AppAnalytics
    @Inject internal lateinit var consent: UsageConsent
    @Inject @ApplicationScope internal lateinit var applicationScope: CoroutineScope
    @Inject internal lateinit var retiredStorage: RetiredStorageCleanup
    override fun onCreate() {
        super.onCreate()
        // Providers only construct dependencies; cold-start authorization still controls SDK initialization.
        analytics.initialize(consent.accepted)
        applicationScope.launch(Dispatchers.IO) { retiredStorage.cleanUp() }
    }
}
