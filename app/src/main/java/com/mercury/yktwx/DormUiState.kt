package com.mercury.yktwx

import com.mercury.yktwx.data.*

class DormUiState(val rooms: List<DormRoom> = emptyList(), val loading: Boolean = false, val loaded: Boolean = false,
    val error: String? = null, val selectedIndex: Int = 0,
    val network: FeeQuery? = null, val networkError: String? = null, val managing: Boolean = false,
    val bindingProjects: Set<FeeProject> = emptySet(),
    val roomsLoaded: Boolean = loaded, val networkLoaded: Boolean = loaded,
    val roomsAvailable: Boolean = roomsLoaded && error == null,
    val displayRestored: Boolean = false,
    val projectErrorKinds: Map<FeeProject, ReadFailureKind> = emptyMap(),
    val networkErrorKind: ReadFailureKind? = null,
    val errorKind: ReadFailureKind? = null,
    val restoringDisplay: Boolean = false) {
    val selectedRoom get() = rooms.getOrNull(selectedIndex) ?: rooms.firstOrNull()
    override fun toString() = "DormUiState([redacted])"
}
