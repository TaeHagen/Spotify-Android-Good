package com.taehagen.spotifygood.data

import android.util.Log
import com.taehagen.spotifygood.data.db.ResponseCacheDao
import com.taehagen.spotifygood.data.db.ResponseCacheEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistent cache of browse responses (Room `response_cache`), docs/ARCHITECTURE.md §9.8.
 *
 * Rows hold the last successful response of a call keyed by method + arguments ([CacheKeys]).
 * Invalidation never drops data: it only marks rows stale ([invalidate]/[invalidatePrefix]) so screens
 * still open instantly and work offline, but revalidate on their next collection.
 *
 * Degraded responses (`partial`, docs §6.3: some item metadata failed to load) are shown but never
 * stored as fresh: they only fill an empty row, stored stale, and the flow refetches them a few
 * times while collected ([resourceOf]).
 */
class ResponseCache(
    private val dao: ResponseCacheDao,
    private val json: Json,
    private val partialRetryDelayMs: Long = PARTIAL_RETRY_DELAY_MS,
) {
    private val pruned = AtomicBoolean(false)
    private val clearListeners = CopyOnWriteArrayList<() -> Unit>()

    /** An invalidated key (or key prefix); [live] flows of matching keys reload. */
    private class Invalidation(val key: String, val prefix: Boolean) {
        fun matches(candidate: String) = if (prefix) candidate.startsWith(key) else candidate == key
    }

    private val invalidations = MutableSharedFlow<Invalidation>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Last stored value and its fetch time (epoch ms; `<= 0` = invalidated), or null when absent/corrupt. */
    suspend fun <T> get(key: String, serializer: KSerializer<T>): Pair<T, Long>? = withContext(Dispatchers.Default) {
        val entity = try {
            dao.get(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // E.g. a row larger than the CursorWindow; treat as a miss.
            Log.w(TAG, "Cache read failed for $key", e)
            null
        } ?: return@withContext null
        try {
            json.decodeFromString(serializer, entity.json) to entity.fetchedAt
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Schema drift between app versions: drop the unreadable row.
            Log.w(TAG, "Dropping undecodable cache entry $key", e)
            runCatching { dao.delete(key) }
            null
        }
    }

    /** Stores [value]; a [stale] row is kept for offline display but revalidated on its next read. */
    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T, stale: Boolean = false): Unit = withContext(Dispatchers.Default) {
        val encoded = json.encodeToString(serializer, value)
        if (encoded.length > MAX_ENTRY_CHARS) {
            // Rows above ~2 MB cannot be read back through a CursorWindow; skip instead of failing later.
            Log.w(TAG, "Not caching $key (${encoded.length} chars)")
            dao.delete(key)
            return@withContext
        }
        val now = System.currentTimeMillis()
        // Stale rows store the negated fetch time (see ResponseCacheDao.markStale).
        dao.put(ResponseCacheEntity(key, encoded, if (stale) -now else now))
        if (pruned.compareAndSet(false, true)) {
            // Once per process: drop rows nobody has refreshed for a month.
            dao.pruneFetchedBefore(now - PRUNE_AGE_MS)
        }
    }

    /**
     * Emits Loading(cached) → Success(fresh) / Error(e, cached). Skips the network if the cached
     * value is younger than [maxAgeMs]; [fetch] runs only when collected.
     *
     * A fresh cache hit emits a single `Success(cached, fromCache = true)`. `maxAgeMs <= 0` forces a
     * network fetch. Failures to write the cache never fail the flow.
     */
    fun <T> resource(key: String, serializer: KSerializer<T>, maxAgeMs: Long, fetch: suspend () -> T): Flow<Resource<T>> =
        resourceOf(key, serializer, maxAgeMs) { CacheFill(fetch()) }

    /**
     * [resource] for responses that can come back degraded ([CacheFill.partial]). A partial value is
     * emitted as `Success` but not stored as fresh: it only fills a missing row (stored stale, for
     * offline display) and an existing row is kept for offline use. While collected, the call is
     * then retried up to [PARTIAL_RETRIES] times (after [partialRetryDelayMs], growing); each retry
     * emits its result, a failed retry keeps the partial value on screen.
     */
    fun <T> resourceOf(key: String, serializer: KSerializer<T>, maxAgeMs: Long, fetch: suspend () -> CacheFill<T>): Flow<Resource<T>> = flow {
        val cached = get(key, serializer)
        if (cached != null && isFresh(cached.second, maxAgeMs, System.currentTimeMillis())) {
            emit(Resource.Success(cached.first, fromCache = true))
            return@flow
        }
        emit(Resource.Loading(cached?.first))
        var fresh = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(Resource.Error(e, cached?.first))
            return@flow
        }
        // A row from an earlier complete response is worth more offline than a partial one.
        val keepRow = cached != null
        store(key, serializer, fresh, keepRow)
        emit(Resource.Success(fresh.value))
        var attempt = 0
        while (fresh.partial && attempt < PARTIAL_RETRIES) {
            attempt++
            delay(partialRetryDelayMs * attempt)
            fresh = try {
                fetch()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // keep showing the partial value
            }
            store(key, serializer, fresh, keepRow)
            emit(Resource.Success(fresh.value))
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun <T> store(key: String, serializer: KSerializer<T>, fill: CacheFill<T>, keepRow: Boolean) {
        try {
            when {
                !fill.partial -> put(key, serializer, fill.value)
                !keepRow -> put(key, serializer, fill.value, stale = true)
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed for $key", e)
        }
    }

    /**
     * [resource] that stays subscribed: whenever [key] is invalidated while collected, it reloads
     * (Loading(cached) → Success / Error). Never completes; listens only while collected.
     */
    fun <T> live(key: String, serializer: KSerializer<T>, maxAgeMs: Long, fetch: suspend () -> T): Flow<Resource<T>> =
        liveOf(key, serializer, maxAgeMs) { CacheFill(fetch()) }

    /** [live] for responses that can come back degraded (see [resourceOf]). */
    fun <T> liveOf(key: String, serializer: KSerializer<T>, maxAgeMs: Long, fetch: suspend () -> CacheFill<T>): Flow<Resource<T>> =
        invalidations
            .onSubscription { emit(Invalidation(key, prefix = false)) } // initial load, no missed invalidation
            .filter { it.matches(key) }
            .flatMapLatest { resourceOf(key, serializer, maxAgeMs, fetch) }

    /** Marks [key] stale (kept for offline display, refetched on next collection) and reloads [live] flows. */
    suspend fun invalidate(key: String) {
        try {
            dao.markStaleKey(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invalidate $key failed", e)
        }
        invalidations.tryEmit(Invalidation(key, prefix = false))
    }

    /** Marks every key starting with [prefix] stale and reloads matching [live] flows. */
    suspend fun invalidatePrefix(prefix: String) {
        try {
            dao.markStale(prefix)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "invalidate $prefix* failed", e)
        }
        invalidations.tryEmit(Invalidation(prefix, prefix = true))
    }

    suspend fun clear() {
        dao.clear()
        clearListeners.forEach { runCatching(it) }
    }

    /** [listener] runs after [clear] (logout); used to drop in-memory per-account state. */
    fun addClearListener(listener: () -> Unit) {
        clearListeners += listener
    }

    companion object {
        private const val TAG = "ResponseCache"
        private const val MAX_ENTRY_CHARS = 1_000_000
        private const val PRUNE_AGE_MS = 30L * 24 * 60 * 60 * 1000
        /** Refetches of a partial response while it is on screen (after 15 s, then 30 s). */
        const val PARTIAL_RETRIES = 2
        const val PARTIAL_RETRY_DELAY_MS = 15_000L

        /** True when a row fetched at [fetchedAt] (`<= 0` = invalidated) is younger than [maxAgeMs] at [now]. */
        internal fun isFresh(fetchedAt: Long, maxAgeMs: Long, now: Long): Boolean =
            maxAgeMs > 0 && fetchedAt > 0 && now - fetchedAt in 0 until maxAgeMs
    }
}

/** A fetched response; [partial] when the engine marked it degraded (docs §6.3), see [ResponseCache.resourceOf]. */
class CacheFill<out T>(val value: T, val partial: Boolean = false)

/** Cache keys (method + arguments) and TTLs shared by the repositories. */
internal object CacheKeys {
    const val HOME = "catalog.home"
    const val LIBRARY_PREFIX = "library."
    const val LIBRARY_PLAYLISTS = "library.playlists"
    const val LIBRARY_ALBUMS = "library.albums"
    const val LIBRARY_ARTISTS = "library.artists"
    const val LIBRARY_SHOWS = "library.shows"

    fun album(uri: String) = "catalog.album:$uri"
    fun artist(uri: String) = "catalog.artist:$uri"
    fun show(uri: String) = "catalog.show:$uri"
    /** Prefix of every cached page size of a playlist. */
    fun playlistPrefix(uri: String) = "catalog.playlist:$uri:"
    fun playlist(uri: String, pageSize: Int) = "${playlistPrefix(uri)}$pageSize"

    const val MINUTE = 60_000L
    const val TTL_HOME = 15 * MINUTE
    const val TTL_LIBRARY = 5 * MINUTE
    const val TTL_ALBUM = 24 * 60 * MINUTE
    const val TTL_ARTIST = 24 * 60 * MINUTE
    const val TTL_PLAYLIST = 10 * MINUTE
    const val TTL_SHOW = 60 * MINUTE
}
