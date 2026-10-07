package com.taehagen.spotifygood.ui.screens.album

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.library.DownloadMetadata
import com.taehagen.spotifygood.ui.screens.library.DownloadedCollection
import com.taehagen.spotifygood.ui.screens.library.decodeDownloadMetadata
import com.taehagen.spotifygood.ui.screens.library.downloadedCollectionsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

// Downloaded collections as detail pages, built from the download database alone: their pages
// open offline even when the catalog response cache has no (or only a first-page) copy.

/**
 * A downloaded album / playlist / show read from the download database: name, image and the items
 * in collection order with the metadata stored at download time ([DownloadedPage.metadata] null:
 * none stored, shown as a placeholder).
 */
@Immutable
internal data class DownloadedPage(
    val uri: String,
    val name: String,
    val imageUrl: String?,
    val itemUris: List<String>,
    val metadata: Map<String, DownloadMetadata>,
) {
    /** Some items have no stored metadata (placeholders). */
    val partial: Boolean get() = itemUris.any { it !in metadata }

    val images: List<Image> get() = imageUrl?.takeIf { it.isNotBlank() }?.let { listOf(Image(it)) }.orEmpty()

    /** Tracks in collection order; an item without metadata is a placeholder (docs §6.5). */
    fun tracks(): List<Track> = itemUris.map { uri ->
        (metadata[uri] as? DownloadMetadata.OfTrack)?.track ?: Track(uri = uri, name = "", playable = false)
    }

    /** Episodes in collection order (newest first, as the show listed them when downloaded). */
    fun episodes(): List<Episode> = itemUris.map { uri ->
        (metadata[uri] as? DownloadMetadata.OfEpisode)?.episode ?: Episode(uri = uri, name = "", playable = false)
    }

    /** Playlist items in collection order (tracks or episodes). */
    fun playlistItems(): List<PlaylistItem> = itemUris.map { uri ->
        when (val meta = metadata[uri]) {
            is DownloadMetadata.OfTrack -> PlaylistItem(track = meta.track)
            is DownloadMetadata.OfEpisode -> PlaylistItem(episode = meta.episode)
            null -> if (uri.startsWith("spotify:episode:")) {
                PlaylistItem(episode = Episode(uri = uri, name = "", playable = false))
            } else {
                PlaylistItem(track = Track(uri = uri, name = "", playable = false))
            }
        }
    }

    /** The album page: artists, date and type from the tracks' own album reference. */
    fun toAlbum(): Album {
        val tracks = tracks()
        val ref = tracks.firstNotNullOfOrNull { it.album?.takeIf { album -> album.uri == uri } }
        val artists = ref?.artists?.takeIf { it.isNotEmpty() } ?: tracks.firstOrNull { it.artists.isNotEmpty() }?.artists.orEmpty()
        return Album(
            uri = uri,
            name = name,
            images = images.ifEmpty { ref?.images.orEmpty() },
            artists = artists,
            releaseDate = ref?.releaseDate,
            albumType = ref?.albumType,
            totalTracks = tracks.size,
            tracks = tracks,
            partial = partial,
        )
    }

    /** The playlist page (read-only: no edits without the server). */
    fun toPlaylist(): Playlist =
        Playlist(uri = uri, name = name, images = images, total = itemUris.size, items = playlistItems(), partial = partial)

    /** The show page header (its episodes come from [episodes]). */
    fun toShow(): Show {
        val episodes = episodes()
        val ref: ShowRef? = episodes.firstNotNullOfOrNull { it.show?.takeIf { show -> show.uri == uri } }
        return Show(
            uri = uri,
            name = name,
            publisher = ref?.publisher,
            images = images.ifEmpty { ref?.images.orEmpty() },
            total = episodes.size,
            partial = partial,
        )
    }
}

/** Playlist rows past the [loaded] ones (from a cached first page), from the downloaded copy. */
internal fun DownloadedPage.remainingPlaylistItems(loaded: Int): List<PlaylistItem> = playlistItems().drop(loaded.coerceAtLeast(0))

/**
 * [existing] episodes plus the downloaded ones not listed yet, in [newestFirst] order (or reversed
 * for an oldest-first list): an offline show lists everything it has on disk.
 */
internal fun appendDownloadedEpisodes(existing: List<Episode>, downloaded: List<Episode>, newestFirst: Boolean): List<Episode> {
    val listed = existing.mapTo(HashSet()) { it.uri }
    val ordered = if (newestFirst) downloaded else downloaded.asReversed()
    return existing + ordered.filter { listed.add(it.uri) }
}

/** Builds the [DownloadedPage] of [collection]; [decode] reads an item's stored metadata. */
internal fun downloadedPage(
    collection: DownloadedCollection,
    items: Map<String, DownloadItem>,
    decode: (DownloadItem) -> DownloadMetadata?,
): DownloadedPage {
    val metadata = HashMap<String, DownloadMetadata>()
    for (uri in collection.itemUris) {
        val item = items[uri] ?: continue
        decode(item)?.let { metadata[uri] = it }
    }
    return DownloadedPage(collection.uri, collection.name, collection.imageUrl, collection.itemUris, metadata)
}

/**
 * The downloaded copy of collection [uri], or null when it is not downloaded. Reads the download
 * database only (works offline); collect it only as a fallback, it follows every download change.
 */
internal fun AppGraph.downloadedPageFlow(uri: String): Flow<DownloadedPage?> {
    val decoded = ConcurrentHashMap<String, DownloadMetadata>()
    return combine(
        downloadedCollectionsFlow().map { list -> list.firstOrNull { it.uri == uri } }.distinctUntilChanged(),
        downloads.items,
    ) { collection, items ->
        if (collection == null) {
            null
        } else {
            val byUri = items.associateBy { it.uri }
            downloadedPage(collection, byUri) { item ->
                // Metadata stored with a download never changes: decode each once.
                decoded[item.uri] ?: decodeDownloadMetadata(json, item.uri, item.metadataJson)?.also { decoded[item.uri] = it }
            }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default).catch { emit(null) }
}
