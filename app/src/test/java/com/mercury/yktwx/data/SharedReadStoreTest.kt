package com.mercury.yktwx.data

import com.mercury.yktwx.data.sharedReadStoreFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedReadStoreTest {
    private val owner = AccountIdentity("user", 1)

    @Test fun cancellingOnePageDoesNotCancelTheSameQueryObservedByAnotherPage() = runTest {
        val store = sharedReadStoreFixture(backgroundScope).apply { reset(owner) }
        val gate = CompletableDeferred<String>()
        var requests = 0
        suspend fun read() = store.read(owner, "summary", 0, false, now = { 10_000L }) {
            requests++; gate.await()
        }
        val first = async { read() }
        val second = async { read() }
        runCurrent()
        assertEquals(1, requests)
        first.cancel()
        runCurrent()
        gate.complete("result")
        assertEquals("result", second.await())
        assertTrue(store.snapshot<String>(owner, "summary", 0).fresh(10_000L))
        assertEquals("result", read())
        assertEquals(1, requests)
    }

    @Test fun unchangedTransactionReadRemainsStaleUntilTheEndpointChanges() = runTest {
        val store = sharedReadStoreFixture(backgroundScope).apply { reset(owner) }
        suspend fun read(stamp: Long, value: String) = store.read(owner, "summary", stamp, false, now = { 10_000L }) { value }
        assertEquals("before", read(0, "before"))
        assertTrue(store.snapshot<String>(owner, "summary", 1).pending)
        assertEquals("before", read(1, "before"))
        assertFalse(store.snapshot<String>(owner, "summary", 1).fresh(10_000L))
        assertEquals("after", read(1, "after"))
        assertTrue(store.snapshot<String>(owner, "summary", 1).fresh(10_000L))
    }

    @Test fun failedRefreshKeepsTheSuccessfulValueAndRequiresARecoveryRead() = runTest {
        val store = sharedReadStoreFixture(backgroundScope).apply { reset(owner) }
        store.read(owner, "counts", 0, false, now = { 10_000L }) { 1 }
        try {
            store.read<Int>(owner, "counts", 0, true, now = { 10_000L }) { throw IllegalStateException("offline") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        val failed = store.snapshot<Int>(owner, "counts", 0)
        assertEquals(1, failed.value)
        assertFalse(failed.fresh(10_000L))
        assertEquals(2, store.read(owner, "counts", 0, false, now = { 10_000L }) { 2 })
        assertTrue(store.snapshot<Int>(owner, "counts", 0).fresh(10_000L))
    }

    @Test fun aNewerConfirmedEndpointResultSupersedesAnOlderConcurrentRead() = runTest {
        val store = sharedReadStoreFixture(backgroundScope).apply { reset(owner) }
        val gate = CompletableDeferred<String>()
        val old = async { store.read(owner, "card", 0, false, now = { 10_000L }) { gate.await() } }
        runCurrent()
        store.put(owner, "card", 0, "new", 10_000L)
        gate.complete("old")
        assertEquals("new", old.await())
        assertEquals("new", store.snapshot<String>(owner, "card", 0).value)
    }

    @Test fun generationsAndQueryRangesHaveIndependentValues() = runTest {
        val store = sharedReadStoreFixture(backgroundScope).apply { reset(owner) }
        store.read(owner, "summary:october", 0, false) { "october" }
        store.read(owner, "summary:november", 0, false) { "november" }
        assertEquals("october", store.snapshot<String>(owner, "summary:october", 0).value)
        val newOwner = owner.copy(generation = 2)
        store.reset(newOwner)
        assertNull(store.snapshot<String>(owner, "summary:october", 0).value)
        assertNull(store.snapshot<String>(newOwner, "summary:november", 0).value)
    }
}
