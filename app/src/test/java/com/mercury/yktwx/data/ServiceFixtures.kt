package com.mercury.yktwx.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.mercury.yktwx.BuildConfig
import com.mercury.yktwx.data.life.*
import okhttp3.Call
import java.io.File
import java.time.Instant

// Default transports are test conveniences; every production service receives its client from Hilt.
internal val testHttpClients = AppHttp()

internal fun yktApiFixture(
    client: Call.Factory = testHttpClients.service,
): YktApi = YktApi(client = client)

internal fun rechargeApiFixture(client: Call.Factory = testHttpClients.service): RechargeApi = RechargeApi(client = client)

internal fun feeApiFixture(client: Call.Factory = testHttpClients.service): FeeApi = FeeApi(client = client)

internal fun cardServiceApiFixture(client: Call.Factory = testHttpClients.service): CardServiceApi = CardServiceApi(client = client)

internal fun profileApiFixture(client: Call.Factory = testHttpClients.service,
    imageClient: Call.Factory = testHttpClients.publicRead): ProfileApi = ProfileApi(client = client, imageClient = imageClient)

internal fun paymentHttpFixture(client: Call.Factory = testHttpClients.service): PaymentHttp = PaymentHttp(client = client)

internal fun lifeDeviceApiFixture(
    secret: String = BuildConfig.PANGGUI_API_SECRET,
    client: Call.Factory = testHttpClients.service,
    clock: () -> Long = System::currentTimeMillis,
): LifeDeviceApi = LifeDeviceApi(secret = secret, client = client, clock = clock)

internal fun hui798ApiFixture(
    gateway: String = BuildConfig.HUI798_API_GATEWAY,
    salt: String = BuildConfig.HUI798_SIGN_SALT,
    client: Call.Factory = testHttpClients.service,
): Hui798Api = Hui798Api(gateway = gateway, salt = salt, client = client)

internal fun updateHttpFixture(metadataTimeoutMillis: Long = 1_500,
    client: Call.Factory = testHttpClients.update): UpdateHttp = UpdateHttp(metadataTimeoutMillis = metadataTimeoutMillis, client = client)

internal fun appUpdateApiFixture(http: UpdateHttp = updateHttpFixture(),
    verifier: UpdateManifestVerifier = UpdateManifestVerifier(),
    sourceTimeoutMillis: Long = 2_500,
    now: () -> Instant = Instant::now): AppUpdateApi = AppUpdateApi(http = http, verifier = verifier, sourceTimeoutMillis = sourceTimeoutMillis, now = now)

internal fun appUpdateInstallerFixture(context: Context, http: UpdateHttp = updateHttpFixture(),
    contentUri: (File) -> Uri = {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", it)
    }): AppUpdateInstaller = AppUpdateInstaller(context = context, http = http, contentUri = contentUri)
