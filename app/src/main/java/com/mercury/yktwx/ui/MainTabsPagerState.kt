// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package com.mercury.yktwx.ui

import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.utils.springAnimateToPage

@Stable
internal class MainTabsPagerState(
    val pagerState: PagerState,
    private val coroutineScope: CoroutineScope,
) {
    var selectedPage by mutableIntStateOf(pagerState.currentPage)
        private set

    var isNavigating by mutableStateOf(false)
        private set

    private var navJob: Job? = null

    fun snapToPage(targetPage: Int) {
        navJob?.cancel()
        navJob = null
        selectedPage = targetPage
        isNavigating = false
        if (pagerState.currentPage != targetPage || pagerState.currentPageOffsetFraction != 0f ||
            pagerState.isScrollInProgress) {
            pagerState.requestScrollToPage(targetPage)
        }
    }

    fun animateToPage(targetPage: Int) {
        if (targetPage !in 0 until pagerState.pageCount) return
        if (targetPage == selectedPage) return

        navJob?.cancel()
        selectedPage = targetPage
        isNavigating = true

        navJob = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(targetPage)
            } finally {
                if (navJob == currentJob) {
                    isNavigating = false
                    if (pagerState.currentPage != targetPage) {
                        selectedPage = pagerState.currentPage
                    }
                }
            }
        }
    }

    fun syncPage() {
        if (!isNavigating && selectedPage != pagerState.currentPage) {
            selectedPage = pagerState.currentPage
        }
    }
}
