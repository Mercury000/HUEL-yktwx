package com.mercury.yktwx.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class AccountIdentity(val username: String, val generation: Long) {
    override fun toString() = "AccountIdentity(generation=$generation)"
}

internal sealed interface AccountResource {
    enum class Data : AccountResource { Balance, IncomeBills, ExpenseBills, Profile, Cards, Rooms }
    data class Hall(val project: FeeProject) : AccountResource
}

internal data class AccountDataState(
    val identity: AccountIdentity? = null,
    val versions: Map<AccountResource, Long> = emptyMap(),
)

/** Durable invalidation, independent of page creation, visibility and transient UI events. */
internal class AccountDataChanges {
    private val mutable = MutableStateFlow(AccountDataState())
    val state = mutable.asStateFlow()
    private var revision = 0L

    @Synchronized fun reset(session: SessionState) {
        val identity = session.username?.let { AccountIdentity(it, session.generation) }
        if (mutable.value.identity != identity) mutable.value = AccountDataState(identity)
    }

    @Synchronized fun invalidate(identity: AccountIdentity?, resources: Set<AccountResource>) {
        if (identity == null || identity != mutable.value.identity || resources.isEmpty()) return
        val version = ++revision
        mutable.value = mutable.value.copy(versions = mutable.value.versions + resources.associateWith { version })
    }

    fun transaction(identity: AccountIdentity?, update: TransactionUpdate) {
        if (identity?.username != update.username) return
        val resources = ledger(update.income).toMutableSet()
        resources += AccountResource.Data.Profile
        if (update.balance) resources += AccountResource.Data.Balance
        if (update.hall) resources += (update.project?.let(::listOf) ?: FeeProject.entries).map(AccountResource::Hall)
        invalidate(identity, resources)
    }

    fun balance(identity: AccountIdentity?, change: BalanceChange) {
        if (identity?.username == change.username) invalidate(identity, ledger(change.increased))
    }

    private fun ledger(income: Boolean?): Set<AccountResource> = when (income) {
        true -> setOf(AccountResource.Data.IncomeBills)
        false -> setOf(AccountResource.Data.ExpenseBills)
        null -> setOf(AccountResource.Data.IncomeBills, AccountResource.Data.ExpenseBills)
    }

    fun observe(scope: CoroutineScope, changed: (Set<AccountResource>) -> Unit) = scope.launch {
        var previous = AccountDataState()
        state.collect { current ->
            val old = previous.takeIf { it.identity == current.identity }
            previous = current
            val resources = current.versions.filter { (key, version) -> old?.versions?.get(key) != version }.keys
            if (current.identity != null && resources.isNotEmpty()) changed(resources)
        }
    }
}
