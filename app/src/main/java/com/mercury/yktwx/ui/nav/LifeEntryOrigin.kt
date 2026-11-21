package com.mercury.yktwx.ui.nav

/**
 * Why a [AppRoute.LifeDevice] page is being entered.
 *
 * The scanner window and state restoration reveal the page without an in-app page transition, so
 * only user-initiated entries play the normal navigation motion. Keeping this as navigation state
 * avoids inferring the transition from the destination route type, which cannot distinguish an
 * island tap from a scanner return.
 */
internal enum class LifeEntryOrigin {
    /** The user opened the page from a status island or another in-app entry. */
    User,

    /** The page is revealed after ScannerActivity finishes. */
    Scanner,

    /** The page is restored after process or activity recreation. */
    Restored,
}
