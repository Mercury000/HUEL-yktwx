package com.mercury.yktwx.data.life

/** Dismissing progress only removes the local record; it sends no device or payment command. */
fun HellobikeSession.canDismissRecord(error: String? = null): Boolean =
    dismissible || uncertain || rideGuid.isBlank() || bikeNo.isBlank() || error != null ||
        phase == HellobikePhase.Ended && settlement in setOf(HellobikeSettlement.Unknown, HellobikeSettlement.Paying)
