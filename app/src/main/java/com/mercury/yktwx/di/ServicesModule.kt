package com.mercury.yktwx.di

import android.content.Context
import com.mercury.yktwx.BuildConfig
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object ServicesModule {
    @Provides @Singleton @ServiceClient fun serviceClient(http: AppHttp): Call.Factory = http.service
    @Provides @Singleton @PublicClient fun publicClient(http: AppHttp): Call.Factory = http.publicRead
    @Provides @Singleton @UpdateClient fun updateClient(http: AppHttp): Call.Factory = http.update
    @Provides @Singleton fun campus(@ServiceClient client: Call.Factory): YktService = YktApi(client)
    @Provides @Singleton fun recharge(@ServiceClient client: Call.Factory): RechargeService = RechargeApi(client)
    @Provides @Singleton fun fees(@ServiceClient client: Call.Factory): FeeService = FeeApi(client)
    @Provides @Singleton fun card(@ServiceClient client: Call.Factory): CardService = CardServiceApi(client)
    @Provides @Singleton fun profile(@ServiceClient client: Call.Factory, @PublicClient imageClient: Call.Factory): ProfileService = ProfileApi(client, imageClient)
    @Provides @Singleton fun lifeApi(@ServiceClient client: Call.Factory): LifeDeviceApi = LifeDeviceApi(BuildConfig.PANGGUI_API_SECRET, client)
    @Provides fun life(api: LifeDeviceApi): LifeDeviceService = api
    @Provides fun laundry(api: LifeDeviceApi): LifeLaundryService = api
    @Provides fun lifeAccount(api: LifeDeviceApi): LifeAccountService = api
    @Provides @Singleton fun hui(@ServiceClient client: Call.Factory): Hui798Service = Hui798Api(BuildConfig.HUI798_API_GATEWAY, BuildConfig.HUI798_SIGN_SALT, client)
    @Provides @Singleton fun hellobike(@ServiceClient client: Call.Factory, @ApplicationContext context: Context): HellobikeService {
        val identity = HellobikeClientIdentity(context)
        return HellobikeApi(client, userAgent = identity::userAgent, ssid = identity::ssid)
    }
    @Provides @Singleton fun hellobikeLocation(@ApplicationContext context: Context): HellobikeLocationService = HellobikeLocation(context)
    @Provides @Singleton fun updateHttp(@UpdateClient client: Call.Factory): UpdateHttp = UpdateHttp(client = client)
    @Provides @Singleton fun manifestVerifier(): UpdateManifestVerifier = UpdateManifestVerifier()
    @Provides @Singleton fun updateApi(http: UpdateHttp, verifier: UpdateManifestVerifier): AppUpdateApi = AppUpdateApi(http, verifier)
    @Provides @Singleton fun installer(@ApplicationContext context: Context, http: UpdateHttp): AppUpdateInstaller = AppUpdateInstaller(context, http)
}
