package com.mercury.yktwx.data.life

/** Dismissing progress only removes the local record; it sends no device or payment command. */
fun WaterSession.canDismissRecord(error: String? = null): Boolean =
    !active || fault != null || creationUncertain || orderNo.isBlank() || error != null

fun Hui798Session.canDismissRecord(error: String? = null): Boolean =
    !active || uncertain || serverTime == null || error != null

fun LaundrySession.canDismissRecord(error: String? = null): Boolean =
    !active || creationUncertain || orderNo.isBlank() || orderId.isBlank() ||
        serverStatus?.let { it !in 0..5 } == true || error != null

fun HellobikeSession.canDismissRecord(error: String? = null): Boolean =
    dismissible || uncertain || rideGuid.isBlank() || bikeNo.isBlank() || error != null ||
        phase == HellobikePhase.Ended && settlement in setOf(HellobikeSettlement.Unknown, HellobikeSettlement.Paying)
