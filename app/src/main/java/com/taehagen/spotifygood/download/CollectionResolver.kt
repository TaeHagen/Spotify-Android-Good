package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.CatalogRepository
import com.taehagen.spotifygood.data.EpisodesResult
import com.taehagen.spotifygood.data.MAX_PAGED_ITEMS
import com.taehagen.spotifygood.data.SpotifyUris
import com.taehagen.spotifygood.data.TracksResult
import com.taehagen.spotifygood.data.callOffMain
import com.taehagen.spotifygood.data.putStrings
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put

/**
 * Resolves downloadable collections to their item URIs with the catalog RPCs (docs §6.3), without
 * going through the UI-facing repositories (no caching: downloads always want fresh membership).
 */
internal class CollectionResolver(private val rpc: NativeRpc, private val json: Json) {
    /**
     * [metadataJson] is null when the source lists URIs only (Liked Songs) and for placeholders
     * (`playable:false` without a name: metadata failed or missing), so new rows get it later.
     * [unavailable]: the catalog resolved the item and reports it as not playable here (region,
     * explicit filter, relinking included), so downloading it would fail. Placeholders are never
     * unavailable: their lookup failed, `download.track` may well succeed.
     */
    data class Item(val uri: String, val metadataJson: String?, val unavailable: Boolean = false)

    /**
     * [complete]: the source listed every item and nothing failed ([DownloadRules.listingComplete]: not
     * `partial`, no empty or short page). Only a complete resolution may drop items from a downloaded
     * collection: for an incomplete one, the items it does not list (or lists as placeholders) are
     * unknown, not gone. An empty resolution is never complete (a failed lookup and an emptied
     * collection look the same).
     */
    data class Resolved(
        val items: List<Item>,
        val name: String?,
        val imageUrl: String?,
        val revision: String?,
        val complete: Boolean,
    )

    /**
     * Current items of [uri] (distinct, collection order, local files dropped). For playlists, returns
     * null when the playlist [knownRevision] is unchanged (only the first page is fetched then).
     */
    suspend fun resolve(type: CollectionType, uri: String, knownRevision: String? = null): Resolved? = when (type) {
        CollectionType.ALBUM -> {
            val album = rpc.callOffMain<Album>("catalog.album", rpcArgs { put("uri", uri) })
            // Tracks whose metadata failed are placeholders and the album is `partial`; tracks the
            // server has no data for (taken down) are dropped, which is a real change (docs §6.3).
            val complete = DownloadRules.listingComplete(album.tracks.size, album.partial)
            Resolved(album.tracks.map { trackItem(it, json) }.distinctItems(), album.name, album.images.best(), null, complete)
        }
        CollectionType.PLAYLIST -> resolvePlaylist(uri, knownRevision)
        CollectionType.LIKED_SONGS -> resolveLikedSongs()
        CollectionType.SHOW -> {
            // Newest episodes only: a whole back catalogue would be tens of GB. Syncing keeps this a
            // rolling window (new episodes are added, ones that fall out are removed).
            val show = rpc.callOffMain<Show>("catalog.show", rpcArgs { put("uri", uri); put("offset", 0); put("limit", MAX_SHOW_EPISODES) })
            val complete = DownloadRules.listingComplete(show.episodes.size, show.partial)
            Resolved(show.episodes.take(MAX_SHOW_EPISODES).map { episodeItem(it, json) }.distinctItems(), show.name, show.images.best(), null, complete)
        }
    }

    /**
     * Liked Songs as URIs only (`urisOnly`): membership needs no metadata, and tracks whose metadata
     * fails cannot drop out. New rows get their metadata from [metadataBestEffort].
     */
    private suspend fun resolveLikedSongs(): Resolved {
        val uris = ArrayList<String>()
        var total = 0
        var shortPage = false
        while (uris.size < MAX_PAGED_ITEMS) {
            val page = rpc.callOffMain<UriPage>(
                "library.tracks",
                rpcArgs {
                    put("offset", uris.size)
                    put("limit", URI_PAGE_SIZE)
                    put("urisOnly", true)
                },
            )
            total = page.total
            if (page.uris.isEmpty()) {
                shortPage = uris.size < total
                break
            }
            uris += page.uris
            if (uris.size >= page.total) break
        }
        val complete = DownloadRules.listingComplete(uris.size, partial = shortPage, total = total)
        return Resolved(uris.map { Item(it, null) }.distinctItems(), null, null, null, complete)
    }

    /**
     * Display metadata for [uris] like [metadata], batch by batch within [timeoutMs]: what arrived in
     * time is kept, failed batches are skipped.
     */
    suspend fun metadataBestEffort(uris: List<String>, timeoutMs: Long): Map<String, String> {
        val out = HashMap<String, String>()
        val deadline = System.currentTimeMillis() + timeoutMs
        for (chunk in uris.chunked(CatalogRepository.METADATA_BATCH)) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            try {
                withTimeoutOrNull(left) { out += metadata(chunk) } ?: break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue // best effort: the downloaded record brings the metadata anyway
            }
        }
        return out
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
        var partial = first.partial
        var offset = first.items.size
        while (offset < first.total && items.size < MAX_PAGED_ITEMS) {
            val page = fetchPlaylistPage(uri, offset)
            if (page.items.isEmpty()) break
            items += page.items
            partial = partial || page.partial
            offset += page.items.size
        }
        // Items never drop out of a playlist page (unresolved ones keep their slot), so a complete
        // listing has `total` entries.
        val complete = DownloadRules.listingComplete(items.size, partial, first.total)
        val resolved = items.mapNotNull { item -> item.track?.let { trackItem(it, json) } ?: item.episode?.let { episodeItem(it, json) } }
        return Resolved(resolved.distinctItems(), first.name, first.images.best(), first.revision, complete)
    }

    private suspend fun fetchPlaylistPage(uri: String, offset: Int): Playlist =
        rpc.callOffMain("catalog.playlist", rpcArgs { put("uri", uri); put("offset", offset); put("limit", PAGE_SIZE) })

    private suspend fun fetchTracks(uris: List<String>): List<Track> =
        rpc.callOffMain<TracksResult>("catalog.tracks", rpcArgs { putStrings("uris", uris) }).tracks

    private suspend fun fetchEpisodes(uris: List<String>): List<Episode> =
        rpc.callOffMain<EpisodesResult>("catalog.episodes", rpcArgs { putStrings("uris", uris) }).episodes


    private fun List<Item>.distinctItems(): List<Item> = filter { SpotifyUris.isPlayableItem(it.uri) }.distinctBy { it.uri }

    /** `library.tracks` with `urisOnly` (docs §6.3). */
    @Serializable
    private data class UriPage(val total: Int = 0, val uris: List<String> = emptyList())

    companion object {
        /**
         * A member from catalog metadata. A placeholder (no name: metadata failed or missing) carries
         * no metadata, so a new row gets it later, and is never [Item.unavailable].
         */
        internal fun trackItem(track: Track, json: Json): Item {
            val resolved = track.name.isNotEmpty()
            return Item(
                uri = track.uri,
                metadataJson = if (resolved) json.encodeToString(Track.serializer(), track) else null,
                unavailable = resolved && !track.playable,
            )
        }

        /** Like [trackItem]. */
        internal fun episodeItem(episode: Episode, json: Json): Item {
            val resolved = episode.name.isNotEmpty()
            return Item(
                uri = episode.uri,
                metadataJson = if (resolved) json.encodeToString(Episode.serializer(), episode) else null,
                unavailable = resolved && !episode.playable,
            )
        }

        private const val PAGE_SIZE = 100
        private const val URI_PAGE_SIZE = 500

        /** Size of the rolling window of a downloaded show. */
        const val MAX_SHOW_EPISODES = 20
    }
}
