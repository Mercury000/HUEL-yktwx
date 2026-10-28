package com.mercury.yktwx

import com.mercury.yktwx.data.*

class DormUiState(val rooms: List<DormRoom> = emptyList(), val loading: Boolean = false, val loaded: Boolean = false,
    val error: String? = null, val needsLogin: Boolean = false, val selectedIndex: Int = 0,
    val network: FeeQuery? = null, val networkError: String? = null, val managing: Boolean = false,
    val bindingProjects: Set<FeeProject> = emptySet(),
    val roomsLoaded: Boolean = loaded, val networkLoaded: Boolean = loaded) {
    val selectedRoom get() = rooms.getOrNull(selectedIndex) ?: rooms.firstOrNull()
    override fun toString() = "DormUiState([redacted])"
}
