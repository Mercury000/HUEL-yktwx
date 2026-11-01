package com.mercury.yktwx.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.data.ReadFailureKind

internal val LocalReadOffline = staticCompositionLocalOf { false }

/** Display-only failures for unread regions. Never write these into ViewModel or cache state. */
private fun offlineError(error: String?, unread: Boolean) = error ?: FeedbackText.Offline.takeIf { unread }
private fun offlineKind(error: String?, kind: ReadFailureKind?, unread: Boolean) =
    if (error == null && unread) ReadFailureKind.Network else kind

internal fun MyUiState.offlineDisplay() = copy(
    profileError = offlineError(profileError, profile == null && homeIdentity == null && !profileLoaded),
    homeIdentityError = offlineError(homeIdentityError, homeIdentity == null && profile?.professionName.isNullOrBlank() && !homeIdentityLoaded),
    summaryError = offlineError(summaryError, summary == null && !summaryLoaded),
    countsError = offlineError(countsError, counts == null && !countsLoaded),
    profileErrorKind = offlineKind(profileError, profileErrorKind, profile == null && homeIdentity == null && !profileLoaded),
    homeIdentityErrorKind = offlineKind(homeIdentityError, homeIdentityErrorKind, homeIdentity == null && profile?.professionName.isNullOrBlank() && !homeIdentityLoaded),
    summaryErrorKind = offlineKind(summaryError, summaryErrorKind, summary == null && !summaryLoaded),
    countsErrorKind = offlineKind(countsError, countsErrorKind, counts == null && !countsLoaded),
)

internal fun PaymentUiState.offlineDisplay() = copy(accountError = offlineError(accountError, account == null),
    accountErrorKind = offlineKind(accountError, accountErrorKind, account == null))

internal fun DormUiState.offlineDisplay() = DormUiState(
    rooms = rooms, loading = loading, loaded = loaded, needsLogin = needsLogin, selectedIndex = selectedIndex,
    network = network, managing = managing, bindingProjects = bindingProjects,
    roomsLoaded = roomsLoaded, networkLoaded = networkLoaded, roomsAvailable = roomsAvailable,
    displayRestored = displayRestored,
    error = offlineError(error, rooms.isEmpty() && !roomsAvailable && !roomsLoaded),
    networkError = offlineError(networkError, network == null && !networkLoaded),
    errorKind = offlineKind(error, errorKind, rooms.isEmpty() && !roomsAvailable && !roomsLoaded),
    networkErrorKind = offlineKind(networkError, networkErrorKind, network == null && !networkLoaded),
    projectErrorKinds = projectErrorKinds,
)

internal fun BillUiState.offlineDisplay() = copy(
    error = offlineError(error, items.isEmpty() && !listAvailable && !listLoaded),
    summaryError = offlineError(summaryError, summary == null && !summaryLoaded),
    countsError = offlineError(countsError, counts == null && !countsLoaded),
    errorKind = offlineKind(error, errorKind, items.isEmpty() && !listAvailable && !listLoaded),
    summaryErrorKind = offlineKind(summaryError, summaryErrorKind, summary == null && !summaryLoaded),
    countsErrorKind = offlineKind(countsError, countsErrorKind, counts == null && !countsLoaded),
    stats = stats.copy(
        dailyError = offlineError(stats.dailyError, !stats.dailyLoaded && stats.period.trendSupported),
        categoriesError = offlineError(stats.categoriesError, !stats.categoriesLoaded),
        rankingError = offlineError(stats.rankingError, !stats.rankingLoaded),
        dailyErrorKind = offlineKind(stats.dailyError, stats.dailyErrorKind, !stats.dailyLoaded && stats.period.trendSupported),
        categoriesErrorKind = offlineKind(stats.categoriesError, stats.categoriesErrorKind, !stats.categoriesLoaded),
        rankingErrorKind = offlineKind(stats.rankingError, stats.rankingErrorKind, !stats.rankingLoaded),
    ),
)
