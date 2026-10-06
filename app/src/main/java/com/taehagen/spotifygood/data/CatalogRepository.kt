package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Artist
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/**
 * Read-only catalog pages (native `catalog.*`). Flows are cached (stale-while-revalidate): album and
 * artist 24 h, playlist first page 10 min, show 1 h. They reload by themselves when their cache entry
 * is invalidated (playlist edits, follow/unfollow) and therefore never complete.
 */
class CatalogRepository(private val rpc: NativeRpc, private val cache: ResponseCache) {
    fun album(uri: String): Flow<Resource<Album>> =
        cache.live(CacheKeys.album(uri), Album.serializer(), CacheKeys.TTL_ALBUM) {
            rpc.callOffMain<Album>("catalog.album", rpcArgs { put("uri", uri) })
        }

    fun artist(uri: String): Flow<Resource<Artist>> =
        cache.live(CacheKeys.artist(uri), Artist.serializer(), CacheKeys.TTL_ARTIST) {
            rpc.callOffMain<Artist>("catalog.artist", rpcArgs { put("uri", uri) })
        }

    /** First page of a playlist (items 0..[pageSize]). */
    fun playlist(uri: String, pageSize: Int = 100): Flow<Resource<Playlist>> =
        cache.live(CacheKeys.playlist(uri, pageSize), Playlist.serializer(), CacheKeys.TTL_PLAYLIST) {
            playlistPage(uri, 0, pageSize)
        }

    /** Further playlist pages (not cached). */
    suspend fun playlistPage(uri: String, offset: Int, limit: Int = 100): Playlist =
        rpc.callOffMain("catalog.playlist", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })

    /** All item URIs of a playlist (paged internally), for play-all/shuffle/download. */
    suspend fun playlistItemUris(uri: String): List<String> =
        playlistItems(uri, ::playlistPage).mapNotNull { it.uri }.filter(SpotifyUris::isPlayableItem)

    fun show(uri: String): Flow<Resource<Show>> =
        cache.live(CacheKeys.show(uri), Show.serializer(), CacheKeys.TTL_SHOW) { showPage(uri, 0) }

    suspend fun showPage(uri: String, offset: Int, limit: Int = 50): Show =
        rpc.callOffMain("catalog.show", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })

    suspend fun tracks(uris: List<String>): List<Track> {
        if (uris.isEmpty()) return emptyList()
        return uris.chunked(METADATA_BATCH).flatMap { chunk ->
            rpc.callOffMain<TracksResult>("catalog.tracks", rpcArgs { putStrings("uris", chunk) }).tracks
        }
    }

    suspend fun episodes(uris: List<String>): List<Episode> {
        if (uris.isEmpty()) return emptyList()
        return uris.chunked(METADATA_BATCH).flatMap { chunk ->
            rpc.callOffMain<EpisodesResult>("catalog.episodes", rpcArgs { putStrings("uris", chunk) }).episodes
        }
    }

    suspend fun episode(uri: String): Episode? = episodes(listOf(uri)).firstOrNull()

    suspend fun radioContext(seedUri: String): String =
        rpc.callOffMain<RadioResult>("catalog.radio", rpcArgs { put("uri", seedUri) }).contextUri

    suspend fun recentlyPlayed(limit: Int = 50): List<MediaRef> =
        rpc.callOffMain<MediaRefs>("catalog.recentlyPlayed", rpcArgs { put("limit", limit) }).items

    suspend fun user(username: String? = null): User =
        rpc.callOffMain("catalog.user", rpcArgs { if (username != null) put("username", username) })

    internal companion object {
        /** `catalog.tracks` accepts at most 200 URIs per call (docs §6.3). */
        const val METADATA_BATCH = 200

        /** Pages a playlist with [fetchPage] until `total` (bounded). */
        suspend fun playlistItems(
            uri: String,
            fetchPage: suspend (uri: String, offset: Int, limit: Int) -> Playlist,
            pageSize: Int = 100,
        ): List<PlaylistItem> {
            val items = ArrayList<PlaylistItem>()
            var offset = 0
            while (items.size < MAX_PAGED_ITEMS) {
                val page = fetchPage(uri, offset, pageSize)
                if (page.items.isEmpty()) break
                items += page.items
                offset += page.items.size
                if (offset >= page.total) break
            }
            return items
        }
    }
}

@Serializable
internal data class TracksResult(val tracks: List<Track> = emptyList())

@Serializable
internal data class EpisodesResult(val episodes: List<Episode> = emptyList())

@Serializable
internal data class RadioResult(val contextUri: String)

@Serializable
internal data class MediaRefs(val items: List<MediaRef> = emptyList())
