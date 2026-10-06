package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.data.db.DownloadCollectionEntity
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

// Pure (JVM-testable) helpers shared by the browse screens: home, search, library, profile.

// ---------------------------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------------------------

/** User-facing error categories of browse screens. */
enum class BrowseError { OFFLINE, RATE_LIMITED, NOT_FOUND, GENERIC }

fun Throwable.toBrowseError(): BrowseError = when (this) {
    is NativeException -> when (code) {
        NativeErrorCode.NETWORK, NativeErrorCode.NOT_CONNECTED -> BrowseError.OFFLINE
        NativeErrorCode.RATE_LIMITED -> BrowseError.RATE_LIMITED
        NativeErrorCode.NOT_FOUND -> BrowseError.NOT_FOUND
        else -> BrowseError.GENERIC
    }
    is java.io.IOException -> BrowseError.OFFLINE
    else -> BrowseError.GENERIC
}

/** [runCatching] that never swallows coroutine cancellation. */
suspend inline fun <T> attempt(crossinline block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

// ---------------------------------------------------------------------------------------------
// User input
// ---------------------------------------------------------------------------------------------

/**
 * Trims and debounces typed text: non-empty values wait [timeoutMs] of silence, clearing the
 * field applies immediately; repeated values are dropped.
 */
@OptIn(FlowPreview::class)
fun Flow<String>.debouncedInput(timeoutMs: Long): Flow<String> =
    map { it.trim() }
        .debounce { if (it.isEmpty()) 0L else timeoutMs }
        .distinctUntilChanged()

// ---------------------------------------------------------------------------------------------
// Media references
// ---------------------------------------------------------------------------------------------

fun likedSongsUri(username: String): String = "spotify:user:$username:collection"

fun isLikedSongsUri(uri: String): Boolean = uri.startsWith("spotify:user:") && uri.endsWith(":collection")

fun Track.toMediaRef(): MediaRef =
    MediaRef(MediaType.TRACK, uri, name, artists.joinToString { it.name }.ifEmpty { null }, album?.images.orEmpty())

fun AlbumRef.toMediaRef(): MediaRef =
    MediaRef(MediaType.ALBUM, uri, name, artists.joinToString { it.name }.ifEmpty { null }, images)

fun ArtistRef.toMediaRef(): MediaRef = MediaRef(MediaType.ARTIST, uri, name, null, images)

fun PlaylistRef.toMediaRef(): MediaRef =
    MediaRef(MediaType.PLAYLIST, uri, name, owner?.displayName ?: owner?.username, images)

fun ShowRef.toMediaRef(): MediaRef = MediaRef(MediaType.SHOW, uri, name, publisher, images)

fun Episode.toMediaRef(): MediaRef =
    MediaRef(MediaType.EPISODE, uri, name, show?.name, images.ifEmpty { show?.images.orEmpty() })

/** Action sheet target for a generic reference (null for collections, which have no sheet). */
fun MediaRef.toActionTarget(myUsername: String? = null, ownerUsername: String? = null): MediaActionTarget? =
    when (type) {
        MediaType.TRACK -> MediaActionTarget.TrackTarget(Track(uri = uri, name = name))
        MediaType.EPISODE -> MediaActionTarget.EpisodeTarget(Episode(uri = uri, name = name, images = images))
        MediaType.ALBUM -> MediaActionTarget.AlbumTarget(AlbumRef(uri = uri, name = name, images = images))
        MediaType.ARTIST -> MediaActionTarget.ArtistTarget(ArtistRef(uri = uri, name = name, images = images))
        MediaType.PLAYLIST -> MediaActionTarget.PlaylistTarget(
            PlaylistRef(
                uri = uri,
                name = name,
                images = images,
                owner = ownerUsername?.let { PlaylistOwner(it, subtitle) },
            ),
            isOwned = myUsername != null && ownerUsername == myUsername,
        )
        MediaType.SHOW -> MediaActionTarget.ShowTarget(ShowRef(uri = uri, name = name, publisher = subtitle, images = images))
        MediaType.COLLECTION -> null
    }

// ---------------------------------------------------------------------------------------------
// Playback highlight
// ---------------------------------------------------------------------------------------------

/** The subset of the playback snapshot list screens need (changes rarely). */
@Immutable
data class NowPlaying(val trackUri: String? = null, val contextUri: String? = null, val isPlaying: Boolean = false) {
    fun isCurrent(uri: String): Boolean = trackUri == uri
    fun isPlayingContext(uri: String?): Boolean = uri != null && contextUri == uri && isPlaying
}

fun PlaybackSnapshot.toNowPlaying(): NowPlaying =
    if (!isActive) NowPlaying() else NowPlaying(track?.uri, context?.uri, isPlaying)

// ---------------------------------------------------------------------------------------------
// Downloads
// ---------------------------------------------------------------------------------------------

/** A downloaded collection row (playlist, album, Liked Songs or podcast). */
@Immutable
data class DownloadedCollection(
    val uri: String,
    val type: CollectionType,
    val name: String,
    val imageUrl: String?,
    val itemUris: List<String>,
    val addedAt: Long,
) {
    val mediaType: MediaType
        get() = when (type) {
            CollectionType.PLAYLIST -> MediaType.PLAYLIST
            CollectionType.ALBUM -> MediaType.ALBUM
            CollectionType.LIKED_SONGS -> MediaType.COLLECTION
            CollectionType.SHOW -> MediaType.SHOW
        }

    fun toMediaRef(): MediaRef = MediaRef(mediaType, uri, name, null, imageUrl?.let { listOf(Image(it)) }.orEmpty())
}

fun collectionTypeOf(wire: String): CollectionType =
    CollectionType.entries.firstOrNull { it.wire == wire } ?: CollectionType.PLAYLIST

fun DownloadCollectionEntity.toDownloadedCollection(json: Json): DownloadedCollection = DownloadedCollection(
    uri = uri,
    type = collectionTypeOf(type),
    name = name,
    imageUrl = imageUrl,
    itemUris = runCatching { json.decodeFromString<List<String>>(itemUrisJson) }.getOrDefault(emptyList()),
    addedAt = addedAt,
)

/** Maps a collection status to the per-row indicator state (null = not downloaded). */
fun CollectionDownloadStatus.toIndicatorState(): DownloadState? = when (this) {
    CollectionDownloadStatus.None -> null
    CollectionDownloadStatus.Complete -> DownloadState.COMPLETED
    is CollectionDownloadStatus.InProgress -> if (active) DownloadState.DOWNLOADING else DownloadState.QUEUED
}

/** Decoded display metadata of a download item (Track or Episode JSON). */
sealed interface DownloadMetadata {
    data class OfTrack(val track: Track) : DownloadMetadata
    data class OfEpisode(val episode: Episode) : DownloadMetadata
}

fun decodeDownloadMetadata(json: Json, uri: String, metadataJson: String?): DownloadMetadata? {
    if (metadataJson.isNullOrBlank()) return null
    return runCatching {
        if (uri.startsWith("spotify:episode:")) {
            DownloadMetadata.OfEpisode(json.decodeFromString<Episode>(metadataJson))
        } else {
            DownloadMetadata.OfTrack(json.decodeFromString<Track>(metadataJson))
        }
    }.getOrNull()
}

// ---------------------------------------------------------------------------------------------
// Paging
// ---------------------------------------------------------------------------------------------

/** One fetched page: [items] in server order, [total] when the server reports it. */
data class PageResult<T>(val items: List<T>, val total: Int? = null)

@Immutable
data class PagedState<T>(
    val items: List<T> = emptyList(),
    val total: Int? = null,
    val isLoading: Boolean = false,
    val endReached: Boolean = false,
    val error: Throwable? = null,
) {
    val isInitialLoading: Boolean get() = isLoading && items.isEmpty()
    val canLoadMore: Boolean get() = !endReached && !isLoading && error == null
}

/** Appends [page] to [existing], dropping items whose key is already present. */
fun <T> mergeUnique(existing: List<T>, page: List<T>, keyOf: (T) -> String): List<T> {
    if (page.isEmpty()) return existing
    val seen = HashSet<String>(existing.size + page.size)
    existing.forEach { seen += keyOf(it) }
    val added = page.filter { seen.add(keyOf(it)) }
    return if (added.isEmpty()) existing else existing + added
}

/**
 * Offset paging driven by the UI ("load more near the end"). One request in flight at a time;
 * [reload] keeps the current items visible until the first page arrives. All jobs run in [scope].
 */
class PagedLoader<T>(
    private val scope: CoroutineScope,
    private val pageSize: Int,
    private val keyOf: (T) -> String,
    private val fetch: suspend (offset: Int, limit: Int) -> PageResult<T>,
) {
    private val _state = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = _state.asStateFlow()

    private var job: Job? = null
    /** Server offset of the next page (raw items received so far, duplicates included). */
    private var nextOffset = 0

    /** Loads the next page unless the end was reached or a request is running. Clears errors. */
    fun loadMore() {
        if (job?.isActive == true || _state.value.endReached) return
        load(replace = false)
    }

    /** Starts over from offset 0. */
    fun reload() {
        job?.cancel()
        nextOffset = 0
        _state.update { it.copy(endReached = false, error = null) }
        load(replace = true)
    }

    private fun load(replace: Boolean) {
        _state.update { it.copy(isLoading = true, error = null) }
        val offset = nextOffset
        job = scope.launch {
            try {
                val page = fetch(offset, pageSize)
                nextOffset = offset + page.items.size
                _state.update { current ->
                    val base = if (replace) emptyList() else current.items
                    val total = page.total ?: if (replace) null else current.total
                    current.copy(
                        items = mergeUnique(base, page.items, keyOf),
                        total = total,
                        isLoading = false,
                        endReached = page.items.isEmpty() || page.items.size < pageSize ||
                            (total != null && nextOffset >= total),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(isLoading = false, error = e) }
            }
        }
    }
}
