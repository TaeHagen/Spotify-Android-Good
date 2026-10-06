package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.data.db.RecentSearchDao
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow

enum class SearchType(val wire: String) {
    TRACK("track"), ARTIST("artist"), ALBUM("album"), PLAYLIST("playlist"), SHOW("show"), EPISODE("episode")
}

sealed interface RecentSearch {
    val key: String
    data class Query(val query: String, override val key: String = "q:$query") : RecentSearch
    data class Item(val ref: MediaRef, override val key: String = ref.uri) : RecentSearch
}

class SearchRepository(rpc: NativeRpc, dao: RecentSearchDao) {
    suspend fun search(query: String, types: Set<SearchType> = SearchType.entries.toSet(), offset: Int = 0, limit: Int = 20): SearchResults = TODO()
    val recent: Flow<List<RecentSearch>> get() = TODO()
    suspend fun addRecent(item: RecentSearch): Unit = TODO()
    suspend fun removeRecent(item: RecentSearch): Unit = TODO()
    suspend fun clearRecent(): Unit = TODO()
}
