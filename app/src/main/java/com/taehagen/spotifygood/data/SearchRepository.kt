package com.taehagen.spotifygood.data

import android.util.Log
import com.taehagen.spotifygood.data.db.RecentSearchDao
import com.taehagen.spotifygood.data.db.RecentSearchEntity
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

enum class SearchType(val wire: String) {
    TRACK("track"), ARTIST("artist"), ALBUM("album"), PLAYLIST("playlist"), SHOW("show"), EPISODE("episode")
}

sealed interface RecentSearch {
    val key: String
    data class Query(val query: String, override val key: String = "q:$query") : RecentSearch
    data class Item(val ref: MediaRef, override val key: String = ref.uri) : RecentSearch
}

/** Search (native `catalog.search`) and the persisted recent-searches list (≤ 50 entries). */
class SearchRepository(private val rpc: NativeRpc, private val dao: RecentSearchDao) {
    private val json get() = rpc.json

    suspend fun search(query: String, types: Set<SearchType> = SearchType.entries.toSet(), offset: Int = 0, limit: Int = 20): SearchResults {
        val q = query.trim()
        if (q.isEmpty() || types.isEmpty()) return SearchResults()
        return rpc.callOffMain(
            "catalog.search",
            rpcArgs {
                put("query", q)
                // Stable order keeps the native response cache effective.
                put("types", JsonArray(SearchType.entries.filter { it in types }.map { JsonPrimitive(it.wire) }))
                put("offset", offset)
                put("limit", limit)
            },
        )
    }

    val recent: Flow<List<RecentSearch>> = dao.observe(RECENT_SHOWN)
        .map { rows -> rows.mapNotNull(::toRecent) }
        .flowOn(Dispatchers.Default)

    suspend fun addRecent(item: RecentSearch) {
        val entity = when (item) {
            is RecentSearch.Query -> {
                if (item.query.isBlank()) return
                RecentSearchEntity(key = item.key, query = item.query, timestamp = System.currentTimeMillis())
            }
            is RecentSearch.Item -> RecentSearchEntity(
                key = item.key,
                mediaRefJson = json.encodeToString(MediaRef.serializer(), item.ref),
                timestamp = System.currentTimeMillis(),
            )
        }
        // The unique key index + REPLACE moves an existing entry to the top.
        dao.insert(entity)
        dao.trim(RECENT_KEPT)
    }

    suspend fun removeRecent(item: RecentSearch): Unit = dao.deleteKey(item.key)

    suspend fun clearRecent(): Unit = dao.clear()

    private fun toRecent(row: RecentSearchEntity): RecentSearch? {
        row.query?.let { return RecentSearch.Query(it, row.key) }
        val refJson = row.mediaRefJson ?: return null
        return try {
            RecentSearch.Item(json.decodeFromString(MediaRef.serializer(), refJson), row.key)
        } catch (e: Exception) {
            Log.w(TAG, "Skipping unreadable recent search ${row.key}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SearchRepository"
        const val RECENT_SHOWN = 30
        const val RECENT_KEPT = 50
    }
}
