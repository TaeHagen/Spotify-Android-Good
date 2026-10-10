package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadActivity
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.download.DownloadPause
import com.taehagen.spotifygood.download.DownloadRules
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

/**
 * The queue stopped early ([DownloadActivity.lastError]: storage, account, network …) while items
 * still wait: the header offers to resume it.
 */
fun canResumeDownloads(activity: DownloadActivity, pendingCount: Int): Boolean =
    !activity.running && pendingCount > 0 && activity.lastError != null

/**
 * The header's note while the queue waits for Spotify's audio-key limit: why, the minutes left, and
 * whether the queue waits in a running job ([continuous]) or resumes in a burst.
 */
@Immutable
data class DownloadPauseNotice(val reason: DownloadPause.Reason, val minutes: Int, val continuous: Boolean = true)

/**
 * What the header says while [activity] waits for Spotify's audio-key limit with [pendingCount] items
 * queued, at [now]: the minutes left rounded up (0: the pause is over, the queue continues any
 * moment). Null when the queue doesn't wait for it, and once a pause that ended the run is more
 * than [PAUSE_NOTICE_GRACE_MS] over without a run coming back (it waits for Wi-Fi, say): the header
 * must not promise "shortly" for good.
 */
fun downloadPauseNotice(activity: DownloadActivity, pendingCount: Int, now: Long): DownloadPauseNotice? {
    val pause = activity.pause ?: return null
    if (pendingCount <= 0) return null
    if (!activity.running && now > pause.until + PAUSE_NOTICE_GRACE_MS) return null
    return DownloadPauseNotice(pause.reason, DownloadRules.minutesUntil(pause.until, now), pause.continuous)
}

/** How long after its end a pause stays in the header while no run came back. */
const val PAUSE_NOTICE_GRACE_MS = 60_000L

private val PENDING_STATES = setOf(DownloadState.QUEUED, DownloadState.PREPARING, DownloadState.DOWNLOADING)

/** Explicit per its stored metadata (which is always marked playable). */
val DownloadEntry.isExplicit: Boolean get() = track?.explicit == true || episode?.explicit == true

/** [content] as shown with Hide explicit content [filter]: explicit entries dimmed. */
fun DownloadsContent.withExplicitFilter(filter: Boolean): DownloadsContent {
    if (!filter) return this
    fun DownloadEntry.filtered() = if (isExplicit) copy(track = track?.withExplicitFilter(true), episode = episode?.withExplicitFilter(true)) else this
    return copy(songs = songs.map { it.filtered() }, episodes = episodes.map { it.filtered() }, active = active?.filtered())
}

/** [transform] applied to every episode listed (e.g. this phone's podcast progress). */
fun DownloadsContent.withEpisodes(transform: (Episode) -> Episode): DownloadsContent {
    fun DownloadEntry.mapped() = episode?.let { copy(episode = transform(it)) } ?: this
    return copy(episodes = episodes.map { it.mapped() }, active = active?.mapped())
}

/** What tapping a song or episode of the Downloads screen does ([planEntryPlay]). */
sealed interface EntryPlay {
    /** Play [uris] (the section) from [index]. */
    data class Tracks(val uris: List<String>, val index: Int) : EntryPlay

    /** Explicit while Hide explicit content is on: the player would refuse it and skip on. */
    data object Unavailable : EntryPlay

    /** Not downloaded while the session can't stream: another download would play instead. */
    data object NotDownloaded : EntryPlay
}

/**
 * Plays [entry] within its [section]. Unless the session is [online] only completed downloads play
 * in the section (a track list loaded while not online goes to the offline queue, which starts the
 * next download after an item that isn't one); one that isn't downloaded is sent alone while the
 * session is [connecting] (the load waits for it), else it doesn't start ([planListPlay]'s rule).
 * Explicit entries are left out while [filterExplicit].
 */
fun planEntryPlay(
    entry: DownloadEntry,
    section: List<DownloadEntry>,
    online: Boolean,
    filterExplicit: Boolean,
    connecting: Boolean = false,
): EntryPlay {
    val skipped = { e: DownloadEntry -> filterExplicit && e.isExplicit }
    if (skipped(entry)) return EntryPlay.Unavailable
    if (!online && entry.state != DownloadState.COMPLETED) {
        return if (connecting) EntryPlay.Tracks(listOf(entry.uri), 0) else EntryPlay.NotDownloaded
    }
    val uris = section.filter { !skipped(it) && (online || it.state == DownloadState.COMPLETED) }.map { it.uri }
    val index = uris.indexOf(entry.uri)
    return if (index >= 0) EntryPlay.Tracks(uris, index) else EntryPlay.Tracks(listOf(entry.uri), 0)
}

/** URIs to play for a downloaded collection: everything online, only completed items offline. */
fun DownloadedCollection.playableUris(completed: Set<String>, offline: Boolean): List<String> =
    if (offline) itemUris.filter { it in completed } else itemUris
