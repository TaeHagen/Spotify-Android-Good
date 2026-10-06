package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Track

// Pure grouping of downloads for the Downloads screen (JVM-testable).

/** One individually downloaded (or queued) song or episode. */
@Immutable
data class DownloadEntry(
    val uri: String,
    val state: DownloadState,
    val bytes: Long,
    val totalBytes: Long,
    val error: String?,
    val track: Track?,
    val episode: Episode?,
) {
    val isEpisode: Boolean get() = episode != null || uri.startsWith("spotify:episode:")
    /** 0..1 while the size is known. */
    val progress: Float? get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

@Immutable
data class DownloadsContent(
    /** Playlists and Liked Songs (Liked Songs first). */
    val playlists: List<DownloadedCollection> = emptyList(),
    val albums: List<DownloadedCollection> = emptyList(),
    val podcasts: List<DownloadedCollection> = emptyList(),
    /** Items not part of any downloaded collection, newest first. */
    val songs: List<DownloadEntry> = emptyList(),
    val episodes: List<DownloadEntry> = emptyList(),
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val pendingCount: Int = 0,
    /** The item currently transferring (for the header progress). */
    val active: DownloadEntry? = null,
    /** URIs of completed downloads (offline playback). */
    val completed: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = playlists.isEmpty() && albums.isEmpty() && podcasts.isEmpty() && songs.isEmpty() && episodes.isEmpty()
}

/**
 * Groups [items] (in download order) and [collections] (newest first) into screen sections.
 * [metadata] decodes an item's Track/Episode (memoised by the caller). [activeUri] is the item the
 * downloader works on ([com.taehagen.spotifygood.download.DownloadActivity.currentUri]); without
 * it the first DOWNLOADING row counts as active.
 */
fun buildDownloadsContent(
    items: List<DownloadItem>,
    collections: List<DownloadedCollection>,
    metadata: (DownloadItem) -> DownloadMetadata?,
    activeUri: String? = null,
): DownloadsContent {
    val inCollections = HashSet<String>()
    collections.forEach { inCollections.addAll(it.itemUris) }
    val songs = ArrayList<DownloadEntry>()
    val episodes = ArrayList<DownloadEntry>()
    var completed = 0
    var failed = 0
    var pending = 0
    var active: DownloadEntry? = null
    val completedUris = HashSet<String>()
    for (item in items.asReversed()) {
        when (item.state) {
            DownloadState.COMPLETED -> {
                completed++
                completedUris += item.uri
            }
            DownloadState.FAILED -> failed++
            DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING -> pending++
            DownloadState.CANCELLED -> Unit
        }
        val isActive = active == null && if (activeUri != null) {
            item.uri == activeUri && item.state in PENDING_STATES
        } else {
            item.state == DownloadState.DOWNLOADING
        }
        val needsEntry = item.uri !in inCollections || isActive
        if (!needsEntry) continue
        val meta = metadata(item)
        val entry = DownloadEntry(
            uri = item.uri,
            state = item.state,
            bytes = item.bytes,
            totalBytes = item.totalBytes,
            error = item.error,
            track = (meta as? DownloadMetadata.OfTrack)?.track,
            episode = (meta as? DownloadMetadata.OfEpisode)?.episode,
        )
        if (isActive) active = entry
        if (item.uri in inCollections || item.state == DownloadState.CANCELLED) continue
        if (entry.isEpisode) episodes += entry else songs += entry
    }
    return DownloadsContent(
        playlists = collections
            .filter { it.type == CollectionType.PLAYLIST || it.type == CollectionType.LIKED_SONGS }
            .sortedBy { if (it.type == CollectionType.LIKED_SONGS) 0 else 1 },
        albums = collections.filter { it.type == CollectionType.ALBUM },
        podcasts = collections.filter { it.type == CollectionType.SHOW },
        songs = songs,
        episodes = episodes,
        completedCount = completed,
        failedCount = failed,
        pendingCount = pending,
        active = active,
        completed = completedUris,
    )
}

private val PENDING_STATES = setOf(DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING)

/** URIs to play for a downloaded collection: everything online, only completed items offline. */
fun DownloadedCollection.playableUris(completed: Set<String>, offline: Boolean): List<String> =
    if (offline) itemUris.filter { it in completed } else itemUris
