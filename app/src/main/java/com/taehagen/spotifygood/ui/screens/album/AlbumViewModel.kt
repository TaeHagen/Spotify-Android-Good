package com.taehagen.spotifygood.ui.screens.album

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.Album
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.isPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Album data prepared for display (computed off the main thread). */
@Immutable
internal data class AlbumContent(
    val album: Album,
    val discs: List<DiscGroup>,
    val multiDisc: Boolean,
    val totalDurationMs: Long,
    val year: Int?,
    /** Built from the download (offline without a cached page). */
    val downloadedCopy: Boolean = false,
)

@Immutable
internal data class AlbumUiState(
    val load: LoadState<AlbumContent> = LoadState.Loading,
    val playback: PlaybackInfo = PlaybackInfo(),
    /** Null until known. */
    val saved: Boolean? = null,
    val download: CollectionDownloadUi = CollectionDownloadUi(),
    val rowDownloads: Map<String, DownloadState> = emptyMap(),
    val offline: Boolean = false,
    /** Other albums of the primary artist. */
    val moreBy: List<AlbumRef> = emptyList(),
)

internal class AlbumViewModel(graph: AppGraph, private val uri: String) : DetailViewModel(graph, uri) {

    /** Refetching after the session came back while the download was shown: keep it until then. */
    @Volatile private var refetchingCopy = false

    private val content: StateFlow<LoadState<AlbumContent>> = retryTrigger
        .flatMapLatest { graph.catalog.album(uri).catch { emit(Resource.Error(it)) } }
        .map { resource -> resource.toLoadState { toContent(it) } }
        // No page and no cached copy (offline, cache cleared or pruned): a downloaded album still
        // opens, from the download database.
        .flatMapLatest { load ->
            val keepCopy = load is LoadState.Loading && refetchingCopy
            if (load !is LoadState.Loading) refetchingCopy = false
            if (load !is LoadState.Failed && !keepCopy) {
                flowOf(load)
            } else {
                graph.downloadedPageFlow(uri).map { copy ->
                    copy?.let {
                        LoadState.Ready(toContent(it.toAlbum(), downloadedCopy = true), refreshing = keepCopy, stale = !keepCopy)
                    } ?: load
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    init {
        // Session back ONLINE while showing the download: fetch the album (it replaces the copy).
        refetchWhenOnline(
            showingDownload = { content.value.dataOrNull()?.downloadedCopy == true },
            refetch = {
                refetchingCopy = true
                retry()
            },
        )
    }

    private val primaryArtist: Flow<ArtistRef?> = content
        .map { it.dataOrNull()?.album?.artists?.firstOrNull() }
        .distinctUntilChanged()

    private val moreBy: Flow<List<AlbumRef>> = primaryArtist.flatMapLatest { artist ->
        if (artist == null) {
            flowOf(emptyList())
        } else {
            graph.catalog.artist(artist.uri)
                .map { resource ->
                    val data = resource.dataOrNull ?: return@map emptyList()
                    data.albums.ifEmpty { data.singles }.filter { it.uri != uri }.distinctBy { it.uri }.take(MORE_BY_LIMIT)
                }
                .catch { emit(emptyList()) }
        }
    }.distinctUntilChanged()

    private val trackUris: Flow<Set<String>> = content
        .map { load -> load.dataOrNull()?.album?.tracks?.mapTo(HashSet()) { it.uri } ?: emptySet() }
        .distinctUntilChanged()

    val state: StateFlow<AlbumUiState> = combine(
        content,
        playbackInfo,
        graph.savedFlow(uri),
        graph.downloads.collectionUi(uri),
        combine(graph.downloads.statesFor(trackUris), offline, moreBy, ::Triple),
    ) { load, playback, saved, download, (rows, offline, moreBy) ->
        AlbumUiState(load, playback, saved, download, rows, offline, moreBy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumUiState())

    /** Plays the album starting at [track]. */
    fun playTrack(track: Track) {
        if (track.isPlaceholder || !track.playable) return
        graph.player.play(PlayRequest(contextUri = uri, startUri = track.uri))
    }

    fun download() {
        val album = state.value.load.dataOrNull()?.album ?: return
        downloadCollection(CollectionRef(uri, CollectionType.ALBUM, album.name, album.images.best(300)))
    }

    private fun toContent(album: Album, downloadedCopy: Boolean = false): AlbumContent {
        val discs = groupByDisc(album.tracks)
        return AlbumContent(
            album = album,
            discs = discs,
            multiDisc = discs.size > 1,
            totalDurationMs = album.tracks.sumOf { it.durationMs },
            year = releaseYear(album.releaseDate),
            downloadedCopy = downloadedCopy,
        )
    }

    private companion object {
        const val MORE_BY_LIMIT = 12
    }
}
