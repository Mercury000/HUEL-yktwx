package com.mercury.yktwx.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All requests share one lock, so multiple expired-token callers cannot trigger a login storm. */
class AccountRepository(private val service: YktService, private val vault: Vault) {
    private val mutex = Mutex()
    fun hasCredentials() = vault.credentials() != null
    fun username() = vault.credentials()?.username.orEmpty()
    suspend fun login(username: String, password: String): PaymentBatch = mutex.withLock {
        val credentials = Credentials(username.trim(), password)
        val session = service.login(credentials)
        vault.saveCredentials(credentials); vault.saveSession(session)
        // Don't re-login if the newly issued token is rejected; present a login error instead.
        try { service.payment(session.accessToken) }
        catch (e: AuthenticationRequired) { vault.clear(); throw e }
    }
    suspend fun refresh(): PaymentBatch = mutex.withLock {
        val credentials = vault.credentials() ?: throw AuthenticationRequired()
        var session = vault.session()
        var loggedIn = false
        suspend fun relogin(): Session {
            try { return service.login(credentials).also { vault.saveSession(it) } }
            catch (e: InvalidCredentials) { vault.clear(); throw e }
            catch (e: CaptchaRequired) { vault.clear(); throw e }
        }
        if (session == null || !session.isFresh()) { session = relogin(); loggedIn = true }
        try { service.payment(session.accessToken) }
        catch (e: AuthenticationRequired) {
            vault.clearSession()
            if (loggedIn) { vault.clear(); throw e }
            val next = relogin()
            try { service.payment(next.accessToken) }
            catch (e: AuthenticationRequired) { vault.clear(); throw e }
        }
    }
    suspend fun logout() = mutex.withLock { vault.clear() }
}
