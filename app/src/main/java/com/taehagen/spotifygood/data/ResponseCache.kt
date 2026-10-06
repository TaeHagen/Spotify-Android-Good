package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.data.db.ResponseCacheDao
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Persistent cache of browse responses (Room `response_cache`). */
class ResponseCache(dao: ResponseCacheDao, json: Json) {
    suspend fun <T> get(key: String, serializer: KSerializer<T>): Pair<T, Long>? = TODO()
    suspend fun <T> put(key: String, serializer: KSerializer<T>, value: T): Unit = TODO()

    /**
     * Emits Loading(cached) → Success(fresh) / Error(e, cached). Skips the network if the cached
     * value is younger than [maxAgeMs]; [fetch] runs only when collected.
     */
    fun <T> resource(key: String, serializer: KSerializer<T>, maxAgeMs: Long, fetch: suspend () -> T): Flow<Resource<T>> = TODO()

    suspend fun clear(): Unit = TODO()
}
