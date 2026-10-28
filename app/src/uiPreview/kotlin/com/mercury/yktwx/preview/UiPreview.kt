package com.mercury.yktwx.preview

import android.content.Intent

internal object UiPreview {
    const val enabled = true

    fun requested(intent: Intent): Boolean = intent.getBooleanExtra("ui_preview", false)
    fun lostRequested(intent: Intent): Boolean = intent.getBooleanExtra("ui_preview_lost", false)
}
