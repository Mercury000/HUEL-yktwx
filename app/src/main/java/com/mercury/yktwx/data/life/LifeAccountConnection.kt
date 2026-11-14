package com.mercury.yktwx.data.life

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LifeAccountStatus { NotLoggedIn, Checking, Verified, Unverified, Expired }

data class LifeAccountConnection(
    val phone: String = "",
    val status: LifeAccountStatus = LifeAccountStatus.NotLoggedIn,
    val verifiedAtMillis: Long? = null,
    val error: String? = null,
) {
    val hasAccount: Boolean get() = phone.isNotBlank()
}

data class LifeAccountProfile(val id: String, val phone: String)

interface LifeAccountService {
    suspend fun profile(token: String): LifeAccountProfile
}

/** A read belongs to one credential generation, even when a cancelled transport returns late. */
internal class LifeAccountVerifier<T>(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val account: () -> T?,
    private val verify: suspend (T) -> Unit,
    private val onExpired: suspend (Exception, T) -> Unit,
    private val failure: (Exception) -> String,
) {
    private val mutable = MutableStateFlow(LifeAccountConnection())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var attemptedAt: Long? = null

    fun restored(phone: String) {
        reset()
        mutable.value = LifeAccountConnection(phone,
            if (phone.isBlank()) LifeAccountStatus.NotLoggedIn else LifeAccountStatus.Unverified)
    }

    fun verified(phone: String) {
        mutable.value = LifeAccountConnection(phone, LifeAccountStatus.Verified, now())
    }

    fun expired(error: String = "登录已失效，请重新登录") {
        reset()
        mutable.value = mutable.value.copy(status = LifeAccountStatus.Expired, error = error)
    }

    fun cleared() { reset(); mutable.value = LifeAccountConnection() }

    fun failed(error: String) {
        attemptedAt = now()
        if (mutable.value.status != LifeAccountStatus.Expired)
            mutable.value = mutable.value.copy(status = LifeAccountStatus.Unverified, verifiedAtMillis = null, error = error)
    }

    fun reset() { generation++; job?.cancel(); job = null; attemptedAt = null }

    fun stop() {
        reset()
        if (mutable.value.status == LifeAccountStatus.Checking)
            mutable.value = mutable.value.copy(status = LifeAccountStatus.Unverified)
    }

    fun check(force: Boolean = false) {
        val owner = account() ?: return
        if (job?.isActive == true) return
        val value = mutable.value
        if (!force && (value.status == LifeAccountStatus.Verified && value.verifiedAtMillis?.let { now() - it < 300_000 } == true ||
            attemptedAt?.let { now() - it < 30_000 } == true)) return
        val request = generation
        attemptedAt = now()
        job = scope.launch {
            mutable.value = mutable.value.copy(status = LifeAccountStatus.Checking, error = null)
            try {
                verify(owner)
                currentCoroutineContext().ensureActive()
                if (request == generation && account() == owner) verified(mutable.value.phone)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (request != generation || account() != owner) return@launch
                onExpired(e, owner)
                if (request == generation && account() == owner)
                    mutable.value = mutable.value.copy(status = LifeAccountStatus.Unverified, error = failure(e))
            }
        }
    }
}
