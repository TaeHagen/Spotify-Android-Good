package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.nativebridge.NativeRpc

/** Playlist mutations (native `playlist.*`). Throws NativeException on failure. */
class PlaylistEditor(rpc: NativeRpc, library: LibraryRepository) {
    /** Creates a playlist (added to the library) and returns its URI. */
    suspend fun create(name: String, description: String? = null, public: Boolean = false, initialUris: List<String> = emptyList()): String = TODO()
    suspend fun addItems(playlistUri: String, uris: List<String>): Unit = TODO()
    /** [items]: (uri, index) pairs from the latest fetched [revision]. */
    suspend fun removeItems(playlistUri: String, items: List<Pair<String, Int>>, revision: String?): Unit = TODO()
    suspend fun moveItem(playlistUri: String, fromIndex: Int, toIndex: Int, revision: String?): Unit = TODO()
    suspend fun updateDetails(playlistUri: String, name: String? = null, description: String? = null): Unit = TODO()
    suspend fun delete(playlistUri: String): Unit = TODO()
    suspend fun follow(playlistUri: String): Unit = TODO()
    suspend fun unfollow(playlistUri: String): Unit = TODO()
}
