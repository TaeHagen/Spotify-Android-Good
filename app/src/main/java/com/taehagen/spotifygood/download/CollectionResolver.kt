package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.CatalogRepository
import com.taehagen.spotifygood.data.EpisodesResult
import com.taehagen.spotifygood.data.MAX_PAGED_ITEMS
import com.taehagen.spotifygood.data.SpotifyUris
import com.taehagen.spotifygood.data.TracksResult
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.data.pageAll
import com.taehagen.spotifygood.data.putStrings
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Page
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.SavedTrack
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put

/**
 * Resolves downloadable collections to their item URIs with the catalog RPCs (docs §6.3), without
 * going through the UI-facing repositories (no caching: downloads always want fresh membership).
 */
internal class CollectionResolver(private val rpc: NativeRpc, private val json: Json) {
    data class Item(val uri: String, val metadataJson: String?)

    data class Resolved(val items: List<Item>, val name: String?, val imageUrl: String?, val revision: String?)

    /**
     * Current items of [uri] (distinct, collection order, local files dropped). For playlists, returns
     * null when the playlist [knownRevision] is unchanged (only the first page is fetched then).
     */
    suspend fun resolve(type: CollectionType, uri: String, knownRevision: String? = null): Resolved? = when (type) {
        CollectionType.ALBUM -> {
            val album = rpc.callOffMain<Album>("catalog.album", rpcArgs { put("uri", uri) })
            Resolved(album.tracks.map(::trackItem).distinctItems(), album.name, album.images.best(), null)
        }
        CollectionType.PLAYLIST -> resolvePlaylist(uri, knownRevision)
        CollectionType.LIKED_SONGS -> {
            val tracks = pageAll(PAGE_SIZE) { offset, limit ->
                rpc.callOffMain<Page<SavedTrack>>("library.tracks", rpcArgs { put("offset", offset); put("limit", limit) })
            }
            Resolved(tracks.map { trackItem(it.track) }.distinctItems(), null, null, null)
        }
        CollectionType.SHOW -> {
            // Newest episodes only: a whole back catalogue would be tens of GB. Syncing keeps this a
            // rolling window (new episodes are added, ones that fall out are removed).
            val show = rpc.callOffMain<Show>("catalog.show", rpcArgs { put("uri", uri); put("offset", 0); put("limit", MAX_SHOW_EPISODES) })
            Resolved(show.episodes.take(MAX_SHOW_EPISODES).map(::episodeItem).distinctItems(), show.name, show.images.best(), null)
        }
    }

    /** Display metadata (Track / Episode JSON) for single items; best effort, missing ones omitted. */
    suspend fun metadata(uris: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        val (tracks, episodes) = uris.partition { SpotifyUris.typeOf(it) == "track" }
        tracks.chunked(CatalogRepository.METADATA_BATCH).forEach { chunk ->
            fetchTracks(chunk).forEach { out[it.uri] = json.encodeToString(Track.serializer(), it) }
        }
        episodes.filter { SpotifyUris.typeOf(it) == "episode" }.chunked(CatalogRepository.METADATA_BATCH).forEach { chunk ->
            fetchEpisodes(chunk).forEach { out[it.uri] = json.encodeToString(Episode.serializer(), it) }
        }
        return out
    }

    /** Current `playable` flag of tracks / episodes; items the catalog did not return are omitted. */
    suspend fun playability(uris: List<String>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        val (tracks, episodes) = uris.partition { SpotifyUris.typeOf(it) == "track" }
        tracks.chunked(CatalogRepository.METADATA_BATCH).forEach { chunk -> fetchTracks(chunk).forEach { out[it.uri] = it.playable } }
        episodes.filter { SpotifyUris.typeOf(it) == "episode" }.chunked(CatalogRepository.METADATA_BATCH).forEach { chunk ->
            fetchEpisodes(chunk).forEach { out[it.uri] = it.playable }
        }
        return out
    }

    private suspend fun resolvePlaylist(uri: String, knownRevision: String?): Resolved? {
        val first = fetchPlaylistPage(uri, 0)
        // Unchanged playlist: skip fetching the remaining pages.
        if (knownRevision != null && first.revision != null && first.revision == knownRevision) return null
        val items = ArrayList(first.items)
        var offset = first.items.size
        while (offset < first.total && items.size < MAX_PAGED_ITEMS) {
            val page = fetchPlaylistPage(uri, offset)
            if (page.items.isEmpty()) break
            items += page.items
            offset += page.items.size
        }
        val resolved = items.mapNotNull { item -> item.track?.let(::trackItem) ?: item.episode?.let(::episodeItem) }
        return Resolved(resolved.distinctItems(), first.name, first.images.best(), first.revision)
    }

    private suspend fun fetchPlaylistPage(uri: String, offset: Int): Playlist =
        rpc.callOffMain("catalog.playlist", rpcArgs { put("uri", uri); put("offset", offset); put("limit", PAGE_SIZE) })

    private suspend fun fetchTracks(uris: List<String>): List<Track> =
        rpc.callOffMain<TracksResult>("catalog.tracks", rpcArgs { putStrings("uris", uris) }).tracks

    private suspend fun fetchEpisodes(uris: List<String>): List<Episode> =
        rpc.callOffMain<EpisodesResult>("catalog.episodes", rpcArgs { putStrings("uris", uris) }).episodes

    private fun trackItem(track: Track) = Item(track.uri, json.encodeToString(Track.serializer(), track))

    private fun episodeItem(episode: Episode) = Item(episode.uri, json.encodeToString(Episode.serializer(), episode))

    private fun List<Item>.distinctItems(): List<Item> = filter { SpotifyUris.isPlayableItem(it.uri) }.distinctBy { it.uri }

    companion object {
        private const val PAGE_SIZE = 100

        /** Size of the rolling window of a downloaded show. */
        const val MAX_SHOW_EPISODES = 20
    }
}
