package com.taehagen.spotifygood.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SavedStateStoreTest {
    @Test
    fun mutationIsVisibleAndRollsBack() {
        val store = SavedStateStore()
        store.applyLookup(mapOf("a" to false), store.currentSeq())
        val mutation = store.mutate(listOf("a", "b"), true)
        assertEquals(true, store.get("a"))
        assertEquals(true, store.get("b"))
        store.rollback(mutation)
        assertEquals(false, store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun rollbackOnlyTouchesUrisNotMutatedAgain() {
        val store = SavedStateStore()
        val first = store.mutate(listOf("a", "b"), true)
        store.mutate(listOf("b"), false)
        store.rollback(first)
        assertNull(store.get("a"))
        assertEquals(false, store.get("b"))
    }

    @Test
    fun partialRollback() {
        val store = SavedStateStore()
        val mutation = store.mutate(listOf("a", "b"), true)
        store.rollback(mutation, listOf("b"))
        assertEquals(true, store.get("a"))
        assertNull(store.get("b"))
    }

    @Test
    fun staleLookupDoesNotOverrideAnOptimisticMutation() {
        val store = SavedStateStore()
        val seqBeforeQuery = store.currentSeq()
        store.mutate(listOf("a"), true) // user taps like while the query is in flight
        store.applyLookup(mapOf("a" to false, "b" to true), seqBeforeQuery)
        assertEquals(true, store.get("a"))
        assertEquals(true, store.get("b"))
        // A query started after the mutation is authoritative again.
        store.applyLookup(mapOf("a" to false), store.currentSeq())
        assertEquals(false, store.get("a"))
    }

    @Test
    fun evictsLeastRecentlyUsed() {
        val store = SavedStateStore(maxEntries = 2)
        store.applyLookup(mapOf("a" to true), 0)
        store.applyLookup(mapOf("b" to true), 0)
        store.get("a") // touch a
        store.applyLookup(mapOf("c" to true), 0)
        assertEquals(true, store.get("a"))
        assertNull(store.get("b"))
        assertEquals(true, store.get("c"))
        assertEquals(listOf("a", "c"), store.recentKeys(10))
    }

    @Test
    fun versionChangesOnEveryUpdate() {
        val store = SavedStateStore()
        val v0 = store.version.value
        store.mutate(listOf("a"), true)
        val v1 = store.version.value
        store.clear()
        assertTrue(v1 > v0)
        assertTrue(store.version.value > v1)
        assertNull(store.get("a"))
    }
}

class CoalescingBatcherTest {
    @Test
    fun requestsWithinTheWindowShareOneCall() = runTest {
        val calls = mutableListOf<List<String>>()
        val batcher = CoalescingBatcher<String, Boolean>(backgroundScope, windowMs = 50, maxBatch = 50) { keys ->
            calls += keys
            keys.associateWith { it.startsWith("y") }
        }
        val a = async { batcher.get("yes") }
        val b = async { delay(30); batcher.get("no") }
        val c = async { delay(30); batcher.get("yes") } // duplicate joins the in-flight lookup
        advanceUntilIdle()
        assertTrue(a.await())
        assertFalse(b.await())
        assertTrue(c.await())
        assertEquals(listOf(listOf("yes", "no")), calls)
    }

    @Test
    fun requestsAfterTheWindowStartANewBatch() = runTest {
        val calls = mutableListOf<List<Int>>()
        val batcher = CoalescingBatcher<Int, Int>(backgroundScope, windowMs = 50, maxBatch = 50) { keys ->
            calls += keys
            keys.associateWith { it * 2 }
        }
        val first = async { batcher.get(1) }
        advanceTimeBy(60)
        runCurrent()
        val second = async { batcher.get(2) }
        advanceUntilIdle()
        assertEquals(2, first.await())
        assertEquals(4, second.await())
        assertEquals(listOf(listOf(1), listOf(2)), calls)
    }

    @Test
    fun largeBurstsAreSplitIntoMaxBatchCalls() = runTest {
        val calls = mutableListOf<List<Int>>()
        val batcher = CoalescingBatcher<Int, Int>(backgroundScope, windowMs = 50, maxBatch = 50) { keys ->
            calls += keys
            keys.associateWith { it }
        }
        val results = (1..120).map { key -> async { batcher.get(key) } }
        advanceUntilIdle()
        assertEquals((1..120).toList(), results.map { it.await() })
        assertEquals(listOf(50, 50, 20), calls.map { it.size })
    }

    @Test
    fun failureFailsOnlyThatBatchAndLaterLookupsRecover() = runTest {
        var fail = true
        val batcher = CoalescingBatcher<String, Boolean>(backgroundScope, windowMs = 10, maxBatch = 50) { keys ->
            if (fail) throw IllegalStateException("offline")
            keys.associateWith { true }
        }
        val failed = async { runCatching { batcher.get("a") } }
        advanceUntilIdle()
        assertTrue(failed.await().exceptionOrNull() is IllegalStateException)
        fail = false
        val ok = async { batcher.get("a") }
        advanceUntilIdle()
        assertTrue(ok.await())
    }

    @Test
    fun missingResultFailsTheWaiter() = runTest {
        val batcher = CoalescingBatcher<String, Boolean>(backgroundScope, windowMs = 10, maxBatch = 50) { emptyMap() }
        val result = async { runCatching { batcher.get("a") } }
        advanceUntilIdle()
        assertTrue(result.await().exceptionOrNull() is NoSuchElementException)
    }

    @Test
    fun requestEnqueuesWithoutWaiting() = runTest {
        val seen = CompletableDeferred<List<String>>()
        val batcher = CoalescingBatcher<String, Boolean>(backgroundScope, windowMs = 50, maxBatch = 50) { keys ->
            seen.complete(keys)
            keys.associateWith { false }
        }
        batcher.request(listOf("a", "b", "a"))
        advanceUntilIdle()
        assertEquals(listOf("a", "b"), seen.await())
    }

    @Test
    fun cancelledWaiterDoesNotBreakTheBatch() = runTest {
        val batcher = CoalescingBatcher<String, Boolean>(backgroundScope, windowMs = 50, maxBatch = 50) { keys ->
            keys.associateWith { true }
        }
        val cancelled = async { batcher.get("a") }
        val kept = async { batcher.get("b") }
        runCurrent()
        cancelled.cancel()
        advanceUntilIdle()
        assertTrue(kept.await())
        try {
            cancelled.await()
            fail("expected cancellation")
        } catch (e: kotlinx.coroutines.CancellationException) {
            // expected
        }
    }
}

class SavedStateObserveTest {
    private class NotConnected : Exception("NOT_CONNECTED")

    @Test
    fun knownStateEmitsAtOnceWithoutALookup() = runTest {
        val store = SavedStateStore()
        store.applyLookup(mapOf("a" to true), store.currentSeq())
        var calls = 0
        val values = mutableListOf<Boolean?>()
        val job = launch { store.observe("a", { calls++; false }, MutableStateFlow(true)).toList(values) }
        runCurrent()
        assertEquals(listOf<Boolean?>(true), values)
        assertEquals(0, calls)
        job.cancel()
    }

    @Test
    fun failedLookupIsUnknownNotFalseAndIsRetriedWhenTheSessionComesOnline() = runTest {
        // Cold start / airplane mode: the session is not online, `library.contains` fails.
        val online = MutableStateFlow(false)
        val store = SavedStateStore()
        var serverConnected = false
        var calls = 0
        val lookup: suspend (String) -> Boolean = {
            calls++
            if (!serverConnected) throw NotConnected()
            true // the song is liked
        }
        val values = mutableListOf<Boolean?>()
        val job = launch { store.observe("track", lookup, online).toList(values) }
        runCurrent()
        assertEquals("unknown, never a definitive false", listOf<Boolean?>(null), values)
        assertEquals(1, calls)

        // Nothing retries while the session is down (no polling).
        advanceTimeBy(600_000)
        assertEquals(1, calls)

        serverConnected = true
        online.value = true
        runCurrent()
        assertEquals(listOf(null, true), values)
        assertEquals(2, calls)
        assertEquals(true, store.get("track"))
        job.cancel()
    }

    @Test
    fun failureWhileOnlineRetriesWithBackoff() = runTest {
        val store = SavedStateStore()
        val attempts = mutableListOf<Long>()
        val lookup: suspend (String) -> Boolean = {
            attempts += testScheduler.currentTime
            if (attempts.size < 3) throw IllegalStateException("RATE_LIMITED")
            false
        }
        val values = mutableListOf<Boolean?>()
        val job = launch { store.observe("a", lookup, MutableStateFlow(true)).toList(values) }
        advanceTimeBy(60_000)
        assertEquals(listOf(0L, 5_000L, 15_000L), attempts)
        assertEquals(listOf(null, false), values)
        job.cancel()
    }

    @Test
    fun reconnectRetriesBeforeTheBackoffEnds() = runTest {
        val online = MutableStateFlow(true)
        val store = SavedStateStore()
        var calls = 0
        val lookup: suspend (String) -> Boolean = {
            calls++
            if (calls == 1) throw NotConnected()
            true
        }
        val values = mutableListOf<Boolean?>()
        val job = launch { store.observe("a", lookup, online, retryDelayMs = { 60_000 }).toList(values) }
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1_000)
        online.value = false
        runCurrent()
        online.value = true
        runCurrent()
        assertEquals(2, calls)
        assertEquals(listOf(null, true), values)
        job.cancel()
    }

    @Test
    fun aStateThatBecomesUnknownIsLookedUpAgain() = runTest {
        val store = SavedStateStore()
        store.applyLookup(mapOf("a" to true), store.currentSeq())
        var calls = 0
        val values = mutableListOf<Boolean?>()
        val job = launch { store.observe("a", { calls++; false }, MutableStateFlow(true)).toList(values) }
        runCurrent()
        store.clear() // logout
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf(true, null, false), values)
        job.cancel()
    }

    @Test
    fun localMutationWinsOverAnUnknownState() = runTest {
        val online = MutableStateFlow(false)
        val store = SavedStateStore()
        val values = mutableListOf<Boolean?>()
        val job = launch { store.observe("a", { throw NotConnected() }, online).toList(values) }
        runCurrent()
        store.mutate(listOf("a"), true) // e.g. liked from the notification
        runCurrent()
        assertEquals(listOf(null, true), values)
        job.cancel()
    }

    @Test
    fun toggleWritesTheOppositeOfWhatWasShown() {
        // The heart showed "not liked" (the user saw it empty): the tap saves, whatever the server
        // state is (the old toggle flipped the server's `true` and removed the song).
        assertEquals(true, toggleTarget(false))
        assertEquals(false, toggleTarget(true))
        // Unknown: nothing is written.
        assertNull(toggleTarget(null))
    }

    @Test
    fun retryDelayDoublesUpToFiveMinutes() {
        assertEquals(5_000L, savedLookupRetryDelayMs(0))
        assertEquals(10_000L, savedLookupRetryDelayMs(1))
        assertEquals(300_000L, savedLookupRetryDelayMs(6))
        assertEquals(300_000L, savedLookupRetryDelayMs(1_000))
    }
}
