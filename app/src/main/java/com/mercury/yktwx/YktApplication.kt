package com.mercury.yktwx

import android.app.Application
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraXConfig
import com.mercury.yktwx.analytics.AppAnalytics
import com.mercury.yktwx.data.RetiredStorageCleanup
import com.mercury.yktwx.di.ApplicationScope
import com.mercury.yktwx.ui.UsageConsent
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import java.util.concurrent.Executors

@HiltAndroidApp
class YktApplication : Application(), CameraXConfig.Provider {
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

    // CameraX reads this config lazily when the provider is first requested. Keep capture-session
    // setup off the UI thread without adding camera work to application startup.
    override fun getCameraXConfig(): CameraXConfig = cameraConfig

    private companion object {
        val cameraConfig: CameraXConfig by lazy {
            CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                .setCameraExecutor(Executors.newSingleThreadExecutor { task -> Thread(task, "camerax-core") })
                .build()
        }
    }
}
