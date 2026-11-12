package com.mercury.yktwx

import com.mercury.yktwx.data.appUpdateInstallerFixture
import com.mercury.yktwx.ui.testPreferences
import com.mercury.yktwx.analytics.AnalyticsFixtures
import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import com.mercury.yktwx.analytics.AppAnalytics
import com.mercury.yktwx.data.*
import com.mercury.yktwx.data.life.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

// Unit fixtures construct the same models without the Android component or service locator.
internal fun appUpdateViewModelFixture(
    app: Application, store: UpdateCheckStore,
    checkUpdate: suspend () -> UpdateCheckResult,
    installer: AppUpdateInstaller = appUpdateInstallerFixture(app),
    now: () -> Long = System::currentTimeMillis,
): AppUpdateViewModel =
    AppUpdateViewModel(store = store, checkUpdate = checkUpdate, installer = installer, now = now, analytics = AnalyticsFixtures.from(app))

internal fun avatarViewModelFixture(
    app: Application, store: LocalAvatarStore = LocalAvatarStore(app),
    decode: (Uri) -> Bitmap = { AvatarImage.decode(app.contentResolver, it) },
): AvatarViewModel =
    AvatarViewModel(store = store, decode = decode)

internal fun billViewModelFixture(
    app: Application,
    repository: AccountRepository = defaultRepositoryFixture(app),
    now: () -> Long = ReadCachePolicy::now,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.Bill),
): BillViewModel =
    BillViewModel(repository = repository, now = now, retryDelaysMillis = retryDelaysMillis, initialEnvironment = initialEnvironment, analytics = AnalyticsFixtures.from(app))

internal fun cardPasswordViewModelFixture(app: Application, repository: AccountRepository): CardPasswordViewModel =
    CardPasswordViewModel(repository = repository)

internal fun cardServiceViewModelFixture(app: Application, repository: AccountRepository = defaultRepositoryFixture(app)): CardServiceViewModel =
    CardServiceViewModel(repository = repository)

internal fun feeViewModelFixture(
    app: Application,
    repository: AccountRepository,
    checkDelayMillis: Long = 5000,
    checkTimeoutMillis: Long = 15_000,
): FeeViewModel =
    FeeViewModel(repository = repository, checkDelayMillis = checkDelayMillis, checkTimeoutMillis = checkTimeoutMillis, analytics = AnalyticsFixtures.from(app))

internal fun hallViewModelFixture(
    app: Application, repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.Hall),
    preferences: AppPreferencesRepository = testPreferences(app),
): HallViewModel =
    HallViewModel(repository = repository, retryDelaysMillis = retryDelaysMillis, initialEnvironment = initialEnvironment, preferences = preferences)

internal fun hui798ViewModelFixture(
    app: Application, api: Hui798Service, store: Hui798Store,
    io: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = System::currentTimeMillis,
): Hui798ViewModel =
    Hui798ViewModel(api = api, store = store, io = io, now = now, analytics = AnalyticsFixtures.from(app))

internal fun lifeDevicesViewModelFixture(
    app: Application, api: LifeDeviceService, store: LifeDeviceStore,
    io: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = System::currentTimeMillis,
): LifeDevicesViewModel =
    LifeDevicesViewModel(api = api, store = store, io = io, now = now, analytics = AnalyticsFixtures.from(app))

internal fun myViewModelFixture(app: Application, repository: AccountRepository,
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.My)): MyViewModel =
    MyViewModel(repository = repository, retryDelaysMillis = retryDelaysMillis, initialEnvironment = initialEnvironment)

internal fun paymentViewModelFixture(app: Application, repository: AccountRepository = defaultRepositoryFixture(app),
    retryDelaysMillis: List<Long> = TransactionRefresh.DefaultDelaysMillis,
    initialEnvironment: ReadEnvironment = ReadEnvironment.standalone(ReadPage.Payment)): PaymentViewModel =
    PaymentViewModel(repository = repository, retryDelaysMillis = retryDelaysMillis, initialEnvironment = initialEnvironment, analytics = AnalyticsFixtures.from(app))

internal fun rechargeViewModelFixture(
    app: Application,
    repository: AccountRepository,
    checkDelayMillis: Long = 5_000,
): RechargeViewModel =
    RechargeViewModel(repository = repository, checkDelayMillis = checkDelayMillis, analytics = AnalyticsFixtures.from(app))
