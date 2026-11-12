package com.mercury.yktwx.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.WeakHashMap

private val defaultRepositories = WeakHashMap<Context, AccountRepository>()
internal fun defaultRepositoryFixture(context: Context): AccountRepository =
    defaultRepositories.getOrPut(context.applicationContext) {
        accountRepositoryFixture(yktApiFixture(), SecureVault(context), displayCache = DisplayCache(context))
    }

internal fun sharedReadStoreFixture(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)) = SharedReadStore(scope)
internal fun accountRepositoryFixture(service: YktService, vault: Vault,
    rechargeService: RechargeService = rechargeApiFixture(), feeService: FeeService = feeApiFixture(),
    cardService: CardService = cardServiceApiFixture(), profileService: ProfileService = profileApiFixture(),
    displayCache: DisplayCache? = null, readNow: () -> Long = ReadCachePolicy::now): AccountRepository =
    AccountRepository(service, vault, rechargeService, feeService, cardService, profileService, displayCache, readNow,
        sharedReadStoreFixture(), sharedReadStoreFixture(), AccountDataChanges())
