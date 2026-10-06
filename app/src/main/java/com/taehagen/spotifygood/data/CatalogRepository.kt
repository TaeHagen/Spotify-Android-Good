package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Artist
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow

/** Read-only catalog pages (native `catalog.*`). Flows are cached (stale-while-revalidate). */
class CatalogRepository(rpc: NativeRpc, cache: ResponseCache) {
    fun album(uri: String): Flow<Resource<Album>> = TODO()
    fun artist(uri: String): Flow<Resource<Artist>> = TODO()
    /** First page of a playlist (items 0..[pageSize]). */
    fun playlist(uri: String, pageSize: Int = 100): Flow<Resource<Playlist>> = TODO()
    /** Further playlist pages (not cached). */
    suspend fun playlistPage(uri: String, offset: Int, limit: Int = 100): Playlist = TODO()
    /** All item URIs of a playlist (paged internally), for play-all/shuffle/download. */
    suspend fun playlistItemUris(uri: String): List<String> = TODO()
    fun show(uri: String): Flow<Resource<Show>> = TODO()
    suspend fun showPage(uri: String, offset: Int, limit: Int = 50): Show = TODO()
    suspend fun tracks(uris: List<String>): List<Track> = TODO()
    suspend fun episodes(uris: List<String>): List<Episode> = TODO()
    suspend fun episode(uri: String): Episode? = TODO()
    suspend fun radioContext(seedUri: String): String = TODO()
    suspend fun recentlyPlayed(limit: Int = 50): List<MediaRef> = TODO()
    suspend fun user(username: String? = null): User = TODO()
}
