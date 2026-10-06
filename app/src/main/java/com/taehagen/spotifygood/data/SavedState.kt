package com.taehagen.spotifygood.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coalesces point lookups into batched calls: the first request opens a [windowMs] window, every
 * request arriving meanwhile joins the batch (at most [maxBatch] distinct keys per call; the rest
 * spills into the next batch). Concurrent requests for the same key share one in-flight lookup.
 *
 * The worker coroutine is started lazily in [scope] and is suspended (not polling) while idle.
 * [resolve] must return a value for every key it is given; a missing key or an exception fails
 * exactly the waiters of that batch.
 */
internal class CoalescingBatcher<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val windowMs: Long,
    private val maxBatch: Int,
    private val resolve: suspend (List<K>) -> Map<K, V>,
) {
    private val lock = Any()
    private val inFlight = HashMap<K, CompletableDeferred<V>>()
    private val queue = Channel<K>(Channel.UNLIMITED)
    private var worker: Job? = null

    /** Looks [key] up (joining a pending batch when possible) and suspends for the result. */
    suspend fun get(key: K): V = enqueue(key).await()

    /** Enqueues lookups without waiting for them (e.g. refreshing known values). */
    fun request(keys: Collection<K>) {
        keys.forEach { enqueue(it) }
    }

    private fun enqueue(key: K): CompletableDeferred<V> {
        val deferred: CompletableDeferred<V>
        var isNew = false
        synchronized(lock) {
            deferred = inFlight.getOrPut(key) {
                isNew = true
                CompletableDeferred()
            }
            if (isNew && worker?.isActive != true) worker = scope.launch { drain() }
        }
        if (isNew) queue.trySend(key)
        return deferred
    }

    private suspend fun drain() {
        for (first in queue) {
            delay(windowMs)
            val batch = LinkedHashSet<K>()
            batch += first
            while (batch.size < maxBatch) {
                batch += queue.tryReceive().getOrNull() ?: break
            }
            val keys = batch.toList()
            val outcome: Result<Map<K, V>> = try {
                Result.success(resolve(keys))
            } catch (e: CancellationException) {
                complete(keys, Result.failure(e))
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            complete(keys, outcome)
        }
    }

    private fun complete(keys: List<K>, outcome: Result<Map<K, V>>) {
        val waiters = synchronized(lock) { keys.mapNotNull { k -> inFlight.remove(k)?.let { k to it } } }
        waiters.forEach { (key, deferred) ->
            outcome.fold(
                onSuccess = { values ->
                    val value = values[key]
                    if (value != null) deferred.complete(value) else deferred.completeExceptionally(NoSuchElementException("No result for $key"))
                },
                onFailure = { deferred.completeExceptionally(it) },
            )
        }
    }
}

/**
 * In-memory saved/followed state (uri → saved), LRU-bounded, with optimistic mutations.
 *
 * Every mutation gets a sequence number. Lookup results computed from a query that started before a
 * later mutation of the same URI are discarded ([applyLookup]), so a slow `library.contains` can never
 * overwrite an optimistic toggle. Rollbacks only apply while the URI still carries that mutation.
 * Observers watch [version] and read [get]; all methods are thread-safe.
 */
internal class SavedStateStore(private val maxEntries: Int = 5_000) {
    /** A local mutation, kept for [rollback]. */
    class Mutation internal constructor(val seq: Long, val value: Boolean, val previous: Map<String, Boolean?>)

    private val lock = Any()
    private val values = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > maxEntries
    }
    private val lastMutation = object : LinkedHashMap<String, Long>(16, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > MAX_TRACKED_MUTATIONS
    }
    private var seq = 0L
    private val _version = MutableStateFlow(0L)

    /** Bumped after every change; pair with [get]. */
    val version: StateFlow<Long> = _version.asStateFlow()

    fun get(uri: String): Boolean? = synchronized(lock) { values[uri] }

    /** Sequence number to pass to [applyLookup] for a query started now. */
    fun currentSeq(): Long = synchronized(lock) { seq }

    /** Stores lookup results unless the URI was mutated after [sinceSeq]. */
    fun applyLookup(results: Map<String, Boolean>, sinceSeq: Long) {
        if (results.isEmpty()) return
        synchronized(lock) {
            results.forEach { (uri, saved) ->
                if ((lastMutation[uri] ?: 0L) <= sinceSeq) values[uri] = saved
            }
        }
        bump()
    }

    /** Optimistically sets [uris] to [value]. */
    fun mutate(uris: Collection<String>, value: Boolean): Mutation {
        val mutation = synchronized(lock) {
            val s = ++seq
            val previous = uris.associateWith { values[it] }
            uris.forEach {
                lastMutation[it] = s
                values[it] = value
            }
            Mutation(s, value, previous)
        }
        bump()
        return mutation
    }

    /** Restores the pre-[mutation] values of [uris] that were not mutated again since. */
    fun rollback(mutation: Mutation, uris: Collection<String> = mutation.previous.keys) {
        synchronized(lock) {
            uris.forEach { uri ->
                if (lastMutation[uri] != mutation.seq) return@forEach
                when (val previous = mutation.previous[uri]) {
                    null -> values.remove(uri)
                    else -> values[uri] = previous
                }
            }
        }
        bump()
    }

    /** Most recently used keys (newest last), at most [limit]. */
    fun recentKeys(limit: Int): List<String> = synchronized(lock) { values.keys.toList().takeLast(limit) }

    fun clear() {
        synchronized(lock) {
            values.clear()
            lastMutation.clear()
        }
        bump()
    }

    private fun bump() = _version.update { it + 1 }

    private companion object {
        const val MAX_TRACKED_MUTATIONS = 1_000
    }
}
