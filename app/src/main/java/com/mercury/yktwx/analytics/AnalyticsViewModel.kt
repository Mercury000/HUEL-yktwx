package com.mercury.yktwx.analytics

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Gives standalone Compose shells the same application analytics binding as the Activity. */
@HiltViewModel
internal class AnalyticsViewModel @Inject constructor(val analytics: AppAnalytics) : ViewModel()
