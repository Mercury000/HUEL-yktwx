package com.mercury.yktwx

import android.app.Activity
import android.os.Bundle

/** A signing return URL only returns focus. The SDK result and original cloud order are queried separately. */
class HellobikePayReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never derive payment success from an externally callable URI.
        finish()
    }
}
