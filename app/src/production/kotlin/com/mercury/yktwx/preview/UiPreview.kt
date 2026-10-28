package com.mercury.yktwx.preview

import android.content.Intent

internal object UiPreview {
    const val enabled = false

    fun requested(intent: Intent): Boolean = false
    fun lostRequested(intent: Intent): Boolean = false
}
