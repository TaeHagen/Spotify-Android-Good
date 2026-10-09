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
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.put

/**
 * Read-only catalog pages (native `catalog.*`). Flows are cached (stale-while-revalidate): album and
 * artist 24 h, playlist first page 10 min, show 1 h. They reload by themselves when their cache entry
 * is invalidated (playlist edits, follow/unfollow) and therefore never complete.
 *
 * A page the engine marks `partial` (some item metadata failed, docs §6.3) is shown but not cached as
 * fresh, and is refetched while on screen ([ResponseCache.resourceOf]).
 */
/**
 * [progress]: learns Spotify's podcast played state from every fresh answer that carries it (show
 * pages, episodes), once, where the answer arrives (docs §6.5).
 */
class CatalogRepository(
    private val rpc: NativeRpc,
    private val cache: ResponseCache,
    private val progress: EpisodeProgressStore? = null,
) {
    fun album(uri: String): Flow<Resource<Album>> =
        cache.liveOf(CacheKeys.album(uri), Album.serializer(), CacheKeys.TTL_ALBUM) {
            rpc.callOffMain<Album>("catalog.album", rpcArgs { put("uri", uri) }).let { CacheFill(it, it.partial) }
        }

    fun artist(uri: String): Flow<Resource<Artist>> =
        cache.liveOf(CacheKeys.artist(uri), Artist.serializer(), CacheKeys.TTL_ARTIST) {
            rpc.callOffMain<Artist>("catalog.artist", rpcArgs { put("uri", uri) }).let { CacheFill(it, it.partial) }
        }

    /** First page of a playlist (items 0..[pageSize]). */
    fun playlist(uri: String, pageSize: Int = 100): Flow<Resource<Playlist>> =
        cache.liveOf(CacheKeys.playlist(uri, pageSize), Playlist.serializer(), CacheKeys.TTL_PLAYLIST) {
            playlistPage(uri, 0, pageSize).let { CacheFill(it, it.partial) }
        }

    /** Further playlist pages (not cached). */
    suspend fun playlistPage(uri: String, offset: Int, limit: Int = 100): Playlist =
        rpc.callOffMain("catalog.playlist", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })

    /** All item URIs of a playlist (paged internally), for play-all/shuffle/download. */
    suspend fun playlistItemUris(uri: String): List<String> =
        playlistItems(uri, ::playlistPage).mapNotNull { it.uri }.filter(SpotifyUris::isPlayableItem)

    /**
     * A window of a playlist's item URIs (and uids) without their metadata
     * (`catalog.playlistUris`): what an add needs, a small fraction of a [playlistPage].
     */
    suspend fun playlistUrisPage(uri: String, offset: Int, limit: Int = PLAYLIST_MAX_ITEMS): PlaylistUris =
        rpc.callOffMain("catalog.playlistUris", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })

    /**
     * A playlist's items to add to another playlist: at most [PLAYLIST_MAX_ITEMS] (no playlist
     * holds more), URIs only, in a bounded number of requests ([pagePlaylist]).
     */
    suspend fun playlistItemUrisToAdd(uri: String): List<String> =
        pagePlaylist(uri, ::playlistUrisPage).items.filter(SpotifyUris::isPlayableItem)

    /**
     * The show's first page. Spotify's played state (resume points) only comes with a fresh answer:
     * cached copies (fresh within the TTL, or shown while revalidating / offline) carry none, so
     * an old state can never stand for the current one (docs §6.5, [EpisodeProgressStore.observe]).
     */
    fun show(uri: String): Flow<Resource<Show>> =
        cache.liveOf(CacheKeys.show(uri), Show.serializer(), CacheKeys.TTL_SHOW) { showPage(uri, 0).let { CacheFill(it, it.partial) } }
            .map { it.withoutCachedPlayedState() }

    suspend fun showPage(uri: String, offset: Int, limit: Int = 50): Show {
        val requestedAt = System.currentTimeMillis()
        val page = rpc.callOffMain<Show>("catalog.show", rpcArgs { put("uri", uri); put("offset", offset); put("limit", limit) })
        progress?.observe(page.episodes, requestedAt)
        return page
    }

    suspend fun tracks(uris: List<String>): List<Track> {
        if (uris.isEmpty()) return emptyList()
        return uris.chunked(METADATA_BATCH).flatMap { chunk ->
            rpc.callOffMain<TracksResult>("catalog.tracks", rpcArgs { putStrings("uris", chunk) }).tracks
        }
    }

    suspend fun episodes(uris: List<String>): List<Episode> {
        if (uris.isEmpty()) return emptyList()
        return uris.chunked(METADATA_BATCH).flatMap { chunk ->
            val requestedAt = System.currentTimeMillis()
            rpc.callOffMain<EpisodesResult>("catalog.episodes", rpcArgs { putStrings("uris", chunk) }).episodes
                .also { progress?.observe(it, requestedAt) }
        }
    }

    suspend fun episode(uri: String): Episode? = episodes(listOf(uri)).firstOrNull()

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

/** [Resource] of a show page with Spotify's played state only where it is a fresh answer. */
internal fun Resource<Show>.withoutCachedPlayedState(): Resource<Show> = when (this) {
    is Resource.Success -> if (fromCache) copy(data = data.withoutPlayedState()) else this
    is Resource.Loading -> copy(cached = cached?.withoutPlayedState())
    is Resource.Error -> copy(cached = cached?.withoutPlayedState())
}

@Serializable
internal data class EpisodesResult(val episodes: List<Episode> = emptyList())

@Serializable
internal data class MediaRefs(val items: List<MediaRef> = emptyList())
