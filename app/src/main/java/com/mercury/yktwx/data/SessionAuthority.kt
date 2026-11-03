package com.mercury.yktwx.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Public account lifetime. Token renewal does not start a new account generation. */
data class SessionState(
    val restored: Boolean = false,
    val username: String? = null,
    val generation: Long = 0,
    val clearing: Boolean = false,
    val endReason: SessionEndReason? = null,
) {
    override fun toString() = "SessionState(restored=$restored, authenticated=${username != null}, generation=$generation, clearing=$clearing, endReason=$endReason)"
}

enum class SessionEndReason(val message: String?, val errorKind: ReadFailureKind?) {
    Logout(null, null),
    Authentication("登录已失效，请重新登录", ReadFailureKind.Authentication),
    InvalidCredentials("学号或密码无效，请重新输入", ReadFailureKind.Authentication),
    CaptchaRequired("官网要求验证码，请在官方页面完成验证后重新登录", ReadFailureKind.Authentication),
    PasswordChanged("查询密码修改成功，请重新登录", null),
    LoginFailed("登录失败，请重试", ReadFailureKind.Other),
}

internal fun YktException.sessionEndReason(): SessionEndReason = when (this) {
    is InvalidCredentials -> SessionEndReason.InvalidCredentials
    is CaptchaRequired -> SessionEndReason.CaptchaRequired
    is AuthenticationRequired -> SessionEndReason.Authentication
    else -> SessionEndReason.LoginFailed
}

/** The repository alone restores, publishes and revokes account identity. */
interface SessionAuthority {
    val sessionState: StateFlow<SessionState>
    suspend fun restoreSession(): SessionState
    suspend fun logout()
}

/** Also resets same-account re-logins when collectors skip the intermediate signed-out state. */
internal fun SessionAuthority.observeAccount(scope: CoroutineScope, changed: (String?, Boolean) -> Unit) = scope.launch {
    var previous: SessionState? = null
    sessionState.filter { it.restored }.collect { current ->
        val old = previous
        previous = current
        if (old == null || old.username != current.username || old.generation != current.generation)
            changed(current.username, old != null && old.generation != current.generation)
    }
}
