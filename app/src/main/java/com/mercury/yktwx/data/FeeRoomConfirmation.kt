package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Only an authoritative configuration read can confirm a room mutation. */
internal class FeeRoomConfirmation(
    configuration: FeeConfiguration, target: FeeRoomBinding?, val writeFailure: Exception?,
) {
    val binding = configuration.roomBinding
    val bindingKnown = configuration.roomBindingEnabled && !configuration.invalidRoomBinding
    val confirmed = bindingKnown && if (target == null) binding == null else target.sameTarget(binding)
    override fun toString() = "FeeRoomConfirmation([redacted])"
}

/** A lost response is reconciled by a read; this function never replays the mutation. */
internal suspend fun confirmFeeRoomBinding(
    target: FeeRoomBinding?, write: suspend () -> Unit, read: suspend () -> FeeConfiguration,
    isCurrent: () -> Boolean,
): FeeRoomConfirmation {
    suspend fun checkCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Room binding operation superseded")
    }
    checkCurrent()
    val failure = try { write(); null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { e }
    checkCurrent()
    val configuration = read()
    checkCurrent()
    return FeeRoomConfirmation(configuration, target, failure)
}
