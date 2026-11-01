package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException

/** A display message never decides whether a failed request is eligible for network recovery. */
enum class ReadFailureKind { Network, Authentication, Other }

fun Throwable.isAuthenticationFailure() =
    this is AuthenticationRequired || this is InvalidCredentials || this is CaptchaRequired

fun Throwable.readFailureKind(): ReadFailureKind {
    if (this is CancellationException) throw this
    return when {
        this is NetworkFailure -> ReadFailureKind.Network
        isAuthenticationFailure() -> ReadFailureKind.Authentication
        else -> ReadFailureKind.Other
    }
}

fun hasNetworkFailure(vararg errors: Pair<String?, ReadFailureKind?>) =
    errors.any { (message, kind) -> message != null && kind == ReadFailureKind.Network }
