package com.mercury.yktwx.analytics

import android.app.Application
import com.mercury.yktwx.ui.UsageConsent

class UmengApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppAnalytics.from(this).initialize(UsageConsent(this).accepted)
    }
}
