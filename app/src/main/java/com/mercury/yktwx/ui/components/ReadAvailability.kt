package com.mercury.yktwx.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import com.mercury.yktwx.BillUiState
import com.mercury.yktwx.DormUiState
import com.mercury.yktwx.MyUiState
import com.mercury.yktwx.PaymentUiState
import com.mercury.yktwx.data.ReadFailureKind

internal val LocalReadOffline = staticCompositionLocalOf { false }

/** Display-only failures for unread regions. Never write these into ViewModel or cache state. */
private fun offlineError(error: String?, kind: ReadFailureKind?, unread: Boolean): String? = when {
    error == null -> FeedbackText.Offline.takeIf { unread }
    // A stale network failure must not outrank the connection state the user can see right now.
    kind == ReadFailureKind.Network -> FeedbackText.Offline
    else -> error
}

private fun offlineKind(error: String?, kind: ReadFailureKind?, unread: Boolean): ReadFailureKind? = when {
    error == null && unread -> ReadFailureKind.Network
    kind == ReadFailureKind.Network -> ReadFailureKind.Network
    else -> kind
}

internal fun MyUiState.offlineDisplay(): MyUiState {
    val profileUnread = !restoringDisplay && profile == null && homeIdentity == null && !profileLoaded
    val identityUnread = !restoringDisplay && homeIdentity == null &&
        profile?.professionName.isNullOrBlank() && !homeIdentityLoaded
    val summaryUnread = !restoringDisplay && summary == null && !summaryLoaded
    val countsUnread = !restoringDisplay && counts == null && !countsLoaded
    return copy(
        profileError = offlineError(profileError, profileErrorKind, profileUnread),
        homeIdentityError = offlineError(homeIdentityError, homeIdentityErrorKind, identityUnread),
        summaryError = offlineError(summaryError, summaryErrorKind, summaryUnread),
        countsError = offlineError(countsError, countsErrorKind, countsUnread),
        profileErrorKind = offlineKind(profileError, profileErrorKind, profileUnread),
        homeIdentityErrorKind = offlineKind(homeIdentityError, homeIdentityErrorKind, identityUnread),
        summaryErrorKind = offlineKind(summaryError, summaryErrorKind, summaryUnread),
        countsErrorKind = offlineKind(countsError, countsErrorKind, countsUnread),
    )
}

internal fun PaymentUiState.offlineDisplay(): PaymentUiState {
    val unread = account == null
    return copy(accountError = offlineError(accountError, accountErrorKind, unread),
        accountErrorKind = offlineKind(accountError, accountErrorKind, unread))
}

internal fun DormUiState.offlineDisplay(): DormUiState {
    val roomsUnread = !restoringDisplay && rooms.isEmpty() && !roomsAvailable && !roomsLoaded
    val networkUnread = !restoringDisplay && network == null && !networkLoaded
    return DormUiState(
        rooms = rooms, loading = loading, loaded = loaded, needsLogin = needsLogin, selectedIndex = selectedIndex,
        network = network, managing = managing, bindingProjects = bindingProjects,
        roomsLoaded = roomsLoaded, networkLoaded = networkLoaded, roomsAvailable = roomsAvailable,
        displayRestored = displayRestored,
        restoringDisplay = restoringDisplay,
        error = offlineError(error, errorKind, roomsUnread),
        networkError = offlineError(networkError, networkErrorKind, networkUnread),
        errorKind = offlineKind(error, errorKind, roomsUnread),
        networkErrorKind = offlineKind(networkError, networkErrorKind, networkUnread),
        projectErrorKinds = projectErrorKinds,
    )
}

internal fun BillUiState.offlineDisplay(): BillUiState {
    val listUnread = !restoringDisplay && items.isEmpty() && !listAvailable && !listLoaded
    val summaryUnread = !restoringDisplay && summary == null && !summaryLoaded
    val countsUnread = !restoringDisplay && counts == null && !countsLoaded
    val dailyUnread = !stats.restoringDisplay && !stats.dailyLoaded && stats.period.trendSupported
    val categoriesUnread = !stats.restoringDisplay && !stats.categoriesLoaded
    val rankingUnread = !stats.restoringDisplay && !stats.rankingLoaded
    return copy(
        error = offlineError(error, errorKind, listUnread),
        summaryError = offlineError(summaryError, summaryErrorKind, summaryUnread),
        countsError = offlineError(countsError, countsErrorKind, countsUnread),
        errorKind = offlineKind(error, errorKind, listUnread),
        summaryErrorKind = offlineKind(summaryError, summaryErrorKind, summaryUnread),
        countsErrorKind = offlineKind(countsError, countsErrorKind, countsUnread),
        stats = stats.copy(
            dailyError = offlineError(stats.dailyError, stats.dailyErrorKind, dailyUnread),
            categoriesError = offlineError(stats.categoriesError, stats.categoriesErrorKind, categoriesUnread),
            rankingError = offlineError(stats.rankingError, stats.rankingErrorKind, rankingUnread),
            dailyErrorKind = offlineKind(stats.dailyError, stats.dailyErrorKind, dailyUnread),
            categoriesErrorKind = offlineKind(stats.categoriesError, stats.categoriesErrorKind, categoriesUnread),
            rankingErrorKind = offlineKind(stats.rankingError, stats.rankingErrorKind, rankingUnread),
        ),
    )
}
