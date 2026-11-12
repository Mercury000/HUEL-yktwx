package com.mercury.yktwx.di

import com.mercury.yktwx.data.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object RepositoryModule {
    @Provides @Singleton @OverviewReads fun overviewReads(@ApplicationScope scope: CoroutineScope) = SharedReadStore(scope)
    @Provides @Singleton @CardReads fun cardReads(@ApplicationScope scope: CoroutineScope) = SharedReadStore(scope)
    @Provides @Singleton fun changes() = AccountDataChanges()
    @Provides @Singleton fun repository(service: YktService, vault: Vault, recharge: RechargeService,
        fees: FeeService, card: CardService, profile: ProfileService, display: DisplayCache,
        @OverviewReads overview: SharedReadStore, @CardReads cards: SharedReadStore, changes: AccountDataChanges): AccountRepository =
        AccountRepository(service, vault, recharge, fees, card, profile, display,
            overviewReads = overview, cardReads = cards, dataChanges = changes)
}
