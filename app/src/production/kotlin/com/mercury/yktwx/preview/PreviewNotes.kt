package com.mercury.yktwx.preview

import androidx.compose.runtime.Composable

// Matching signatures let shared screens compile without any preview text.
internal object PreviewNotes {
    @Composable fun Payment(hasProblem: Boolean) {}
    @Composable fun Recharge() {}
    @Composable fun Bill() {}
    @Composable fun CardPassword() {}
    @Composable fun Captcha() {}
}
