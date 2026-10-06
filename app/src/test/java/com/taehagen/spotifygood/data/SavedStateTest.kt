package com.taehagen.spotifygood.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
