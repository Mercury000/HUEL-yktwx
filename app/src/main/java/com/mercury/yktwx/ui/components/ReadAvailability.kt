package com.mercury.yktwx.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.PaymentUiState

internal val LocalReadOffline = staticCompositionLocalOf { false }

/** Display-only failures for unread regions. Never write these into ViewModel or cache state. */
private fun offlineError(error: String?, unread: Boolean) = error ?: FeedbackText.Offline.takeIf { unread }

internal fun MyUiState.offlineDisplay() = copy(
    profileError = offlineError(profileError, profile == null && homeIdentity == null && !profileLoaded),
    homeIdentityError = offlineError(homeIdentityError, homeIdentity == null && profile?.professionName.isNullOrBlank() && !homeIdentityLoaded),
    summaryError = offlineError(summaryError, summary == null && !summaryLoaded),
    countsError = offlineError(countsError, counts == null && !countsLoaded),
)

internal fun PaymentUiState.offlineDisplay() = copy(accountError = offlineError(accountError, account == null))

internal fun DormUiState.offlineDisplay() = DormUiState(
    rooms = rooms, loading = loading, loaded = loaded, needsLogin = needsLogin, selectedIndex = selectedIndex,
    network = network, managing = managing, bindingProjects = bindingProjects,
    roomsLoaded = roomsLoaded, networkLoaded = networkLoaded, roomsAvailable = roomsAvailable,
    displayRestored = displayRestored,
    error = offlineError(error, rooms.isEmpty() && !roomsAvailable && !roomsLoaded),
    networkError = offlineError(networkError, network == null && !networkLoaded),
)

internal fun BillUiState.offlineDisplay() = copy(
    error = offlineError(error, items.isEmpty() && !listAvailable && !listLoaded),
    summaryError = offlineError(summaryError, summary == null && !summaryLoaded),
    countsError = offlineError(countsError, counts == null && !countsLoaded),
    stats = stats.copy(
        dailyError = offlineError(stats.dailyError, !stats.dailyLoaded && stats.period.trendSupported),
        categoriesError = offlineError(stats.categoriesError, !stats.categoriesLoaded),
        rankingError = offlineError(stats.rankingError, !stats.rankingLoaded),
    ),
)
