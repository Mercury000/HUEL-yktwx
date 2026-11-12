package com.mercury.yktwx.analytics

import android.content.Context
import com.mercury.yktwx.BuildConfig
import com.umeng.analytics.MobclickAgent
import com.umeng.commonsdk.UMConfigure

internal class UmengAnalyticsBackend(private val app: Context) : AnalyticsBackend {
    override fun initialize(): Boolean {
        if (!BuildConfig.UMENG_ENABLED) return false
        if (BuildConfig.UMENG_APP_KEY.isBlank() || BuildConfig.UMENG_CHANNEL.isBlank()) return false
        UMConfigure.setLogEnabled(false)
        UMConfigure.enableImeiCollection(false)
        UMConfigure.enableImsiCollection(false)
        UMConfigure.enableIccidCollection(false)
        UMConfigure.enableWiFiMacCollection(false)
        // common 10.0.1 的该接口仍设置应用列表采集开关，暂无替代接口，保留显式关闭。
        @Suppress("DEPRECATION")
        UMConfigure.enableAplCollection(false)
        UMConfigure.enableInstallReferrerCollection(false)
        MobclickAgent.setPageCollectionMode(MobclickAgent.PageMode.MANUAL)
        MobclickAgent.setCatchUncaughtExceptions(false)
        UMConfigure.preInit(app, BuildConfig.UMENG_APP_KEY, BuildConfig.UMENG_CHANNEL)
        UMConfigure.submitPolicyGrantResult(app, true)
        UMConfigure.init(app, BuildConfig.UMENG_APP_KEY, BuildConfig.UMENG_CHANNEL, UMConfigure.DEVICE_TYPE_PHONE, null)
        return true
    }

    override fun resume(context: Context) { MobclickAgent.onResume(context) }
    override fun pause(context: Context) { MobclickAgent.onPause(context) }
    override fun disable() {
        MobclickAgent.disable()
        UMConfigure.submitPolicyGrantResult(app, false)
    }
    override fun event(id: String, properties: Map<String, String>) {
        if (properties.isEmpty()) MobclickAgent.onEvent(app, id)
        else MobclickAgent.onEvent(app, id, properties)
    }
}
