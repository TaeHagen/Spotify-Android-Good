package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.data.db.ResponseCacheDao
import com.taehagen.spotifygood.data.db.ResponseCacheEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private class FakeResponseCacheDao : ResponseCacheDao {
    val rows = ConcurrentHashMap<String, ResponseCacheEntity>()
    override suspend fun get(key: String) = rows[key]
    override suspend fun put(entity: ResponseCacheEntity) {
        rows[entity.key] = entity
    }
    override suspend fun prune(olderThan: Long) {
        rows.values.removeIf { it.fetchedAt < olderThan }
    }
    override suspend fun clear() = rows.clear()
    override suspend fun delete(key: String) {
        rows.remove(key)
    }
    override suspend fun markStale(prefix: String) {
        rows.replaceAll { k, v -> if (k.startsWith(prefix)) v.copy(fetchedAt = -kotlin.math.abs(v.fetchedAt)) else v }
    }
    override suspend fun markStaleKey(key: String) {
        rows.computeIfPresent(key) { _, v -> v.copy(fetchedAt = -kotlin.math.abs(v.fetchedAt)) }
    }
    override suspend fun pruneFetchedBefore(olderThan: Long) {
        rows.values.removeIf { kotlin.math.abs(it.fetchedAt) < olderThan }
    }
}

class ResponseCacheTest {
    private val dao = FakeResponseCacheDao()
    private val cache = ResponseCache(dao, Json)

    @Test
    fun missFetchesAndStores() = runBlocking {
        val emissions = cache.resource("k", String.serializer(), 60_000) { "fresh" }.toList()
        assertEquals(listOf(Resource.Loading<String>(null), Resource.Success("fresh")), emissions)
        assertEquals("\"fresh\"", dao.rows["k"]?.json)
    }

    @Test
    fun freshHitSkipsTheNetwork() = runBlocking {
        cache.put("k", String.serializer(), "cached")
        val emissions = cache.resource("k", String.serializer(), 60_000) { error("must not fetch") }.toList()
        assertEquals(listOf(Resource.Success("cached", fromCache = true)), emissions)
    }

    @Test
    fun failureKeepsTheCachedValue() = runBlocking {
        cache.put("k", String.serializer(), "cached")
        cache.invalidate("k")
        val emissions = cache.resource("k", String.serializer(), 60_000) { throw IOException("offline") }.toList()
        assertEquals(Resource.Loading("cached"), emissions[0])
        val error = emissions[1] as Resource.Error
        assertEquals("cached", error.cached)
        assertTrue(error.error is IOException)
        // The stale data survives for offline use.
        assertEquals("\"cached\"", dao.rows["k"]?.json)
    }

    @Test
    fun partialResultsAreShownButNotCachedAsFreshAndAreRetried() = runBlocking {
        val quick = ResponseCache(dao, Json, partialRetryDelayMs = 1)
        var fetches = 0
        val emissions = quick.resourceOf("p", String.serializer(), 60_000) {
            fetches++
            if (fetches < 3) CacheFill("partial$fetches", partial = true) else CacheFill("complete")
        }.toList()
        assertEquals(
            listOf(
                Resource.Loading<String>(null),
                Resource.Success("partial1"),
                Resource.Success("partial2"),
                Resource.Success("complete"),
            ),
            emissions,
        )
        // Only the complete value is stored as fresh.
        assertEquals("\"complete\"", dao.rows["p"]?.json)
        assertTrue(dao.rows["p"]!!.fetchedAt > 0)
    }

    @Test
    fun partialResultFillsAnEmptyRowAsStaleAndNeverReplacesACachedOne() = runBlocking {
        val quick = ResponseCache(dao, Json, partialRetryDelayMs = 1)
        // No row yet: stored for offline display, but stale (refetched on the next read).
        val first = quick.resourceOf("empty", String.serializer(), 60_000) { CacheFill("partial", partial = true) }.toList()
        assertEquals(Resource.Success("partial"), first.last())
        assertEquals(2 + ResponseCache.PARTIAL_RETRIES, first.size)
        assertEquals("\"partial\"", dao.rows["empty"]?.json)
        assertTrue(dao.rows["empty"]!!.fetchedAt < 0)
        // An earlier complete row stays (stale) for offline use.
        quick.put("full", String.serializer(), "complete")
        quick.invalidate("full")
        var retryFails = false
        val emissions = quick.resourceOf("full", String.serializer(), 60_000) {
            if (retryFails) throw IOException("offline")
            retryFails = true
            CacheFill("partial", partial = true)
        }.toList()
        assertEquals(listOf(Resource.Loading("complete"), Resource.Success("partial")), emissions)
        assertEquals("\"complete\"", dao.rows["full"]?.json)
        assertTrue(dao.rows["full"]!!.fetchedAt < 0)
    }

    @Test
    fun liveReloadsWhenItsKeyIsInvalidated() = runBlocking {
        var fetches = 0
        val out = Channel<Resource<String>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.Default) {
            cache.live("catalog.playlist:p:100", String.serializer(), 60_000) { "v${++fetches}" }.collect { out.send(it) }
        }
        withTimeout(5_000) {
            assertEquals(Resource.Loading<String>(null), out.receive())
            assertEquals(Resource.Success("v1"), out.receive())
            cache.invalidate("unrelated") // ignored
            cache.invalidatePrefix("catalog.playlist:p:")
            assertEquals(Resource.Loading("v1"), out.receive())
            assertEquals(Resource.Success("v2"), out.receive())
        }
        job.cancelAndJoin()
        assertEquals(2, fetches)
    }
}
