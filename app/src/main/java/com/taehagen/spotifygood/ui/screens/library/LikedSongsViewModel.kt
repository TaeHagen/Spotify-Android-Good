package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.SavedTrack
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.isPlaceholder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class LikedSongsUiState(
    /** `spotify:user:<me>:collection`, null until the user is known. */
    val contextUri: String? = null,
    /** Visible tracks (filtered). */
    val tracks: List<Track> = emptyList(),
    val total: Int? = null,
    val isInitialLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: BrowseError? = null,
    /** Debounced filter text. */
    val filter: String = "",
    val offline: Boolean = false,
    val download: CollectionDownloadStatus = CollectionDownloadStatus.None,
    val isDownloaded: Boolean = false,
    val downloadStates: Map<String, DownloadState> = emptyMap(),
    val nowPlaying: NowPlaying = NowPlaying(),
    /** Some loaded pages hold placeholders (metadata failed right now): offer a retry. */
    val partial: Boolean = false,
)

enum class LibraryMessage { DOWNLOAD_FAILED, DOWNLOAD_STARTED, DOWNLOAD_REMOVED, NOTHING_TO_PLAY }

/** Tracks shown in the list plus paging info. */
private data class LikedSource(
    val tracks: List<Track>,
    val total: Int?,
    val isLoading: Boolean,
    val canLoadMore: Boolean,
    val error: Throwable?,
    val offline: Boolean,
)

private data class LikedDownload(
    val status: CollectionDownloadStatus,
    val downloaded: Boolean,
    val states: Map<String, DownloadState>,
)

@OptIn(FlowPreview::class)
class LikedSongsViewModel(private val graph: AppGraph) : ViewModel() {
    var filterText by mutableStateOf("")
        private set

    private val partialPages = PartialPages()
    private val pager = PagedLoader<SavedTrack>(viewModelScope, PAGE_SIZE, { it.track.uri }) { offset, limit ->
        val page = graph.library.likedTracks(offset, limit)
        partialPages.record(offset, page.partial)
        PageResult(page.items, page.total)
    }
    private val offline = graph.offlineFlow()
    private val refreshing = MutableStateFlow(false)
    private val messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val events: Flow<LibraryMessage> = messages.receiveAsFlow()

    private val contextUri: Flow<String?> = graph.engine.user.map { it?.username?.let(::likedSongsUri) }.distinctUntilChanged()

    private val filterQuery: Flow<String> = snapshotFlow { filterText }.debouncedInput(FILTER_DEBOUNCE_MS).onStart { emit("") }

    private val decoded = ConcurrentHashMap<String, Track>()

    /** Downloaded Liked Songs in collection order (offline mode). */
    private val offlineTracks: Flow<List<Track>> = combine(graph.downloadedCollectionsFlow(), graph.downloads.items) { collections, items ->
        val liked = collections.firstOrNull { it.type == CollectionType.LIKED_SONGS } ?: return@combine emptyList()
        val completed = items.filter { it.state == DownloadState.COMPLETED }.associateBy { it.uri }
        liked.itemUris.distinct().mapNotNull { uri ->
            val item = completed[uri] ?: return@mapNotNull null
            decoded[uri] ?: (decodeDownloadMetadata(graph.json, uri, item.metadataJson) as? DownloadMetadata.OfTrack)
                ?.track?.also { decoded[uri] = it }
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private val source: Flow<LikedSource> = offline.flatMapLatest { isOffline ->
        if (isOffline) {
            offlineTracks.map { LikedSource(it, it.size, isLoading = false, canLoadMore = false, error = null, offline = true) }
        } else {
            pager.state.map { page ->
                LikedSource(page.items.map { it.track }, page.total, page.isLoading, page.canLoadMore, page.error, offline = false)
            }
        }
    }

    private val download: Flow<LikedDownload> = contextUri.flatMapLatest { uri ->
        if (uri == null) {
            graph.downloadStatesFlow().map { LikedDownload(CollectionDownloadStatus.None, false, it) }
        } else {
            combine(
                graph.downloads.collectionStatus(uri),
                graph.downloads.isCollectionDownloaded(uri),
                graph.downloadStatesFlow(),
                ::LikedDownload,
            )
        }
    }.catch { emit(LikedDownload(CollectionDownloadStatus.None, false, emptyMap())) }
        .onStart { emit(LikedDownload(CollectionDownloadStatus.None, false, emptyMap())) }

    val state: StateFlow<LikedSongsUiState> = combine(
        source,
        filterQuery,
        contextUri,
        combine(download, refreshing, partialPages.partial, ::Triple),
        graph.nowPlayingFlow(),
    ) { source, filter, contextUri, (download, refreshing, partial), nowPlaying ->
        val visible = if (filter.isEmpty()) source.tracks else source.tracks.filter { it.matches(filter) }
        LikedSongsUiState(
            contextUri = contextUri,
            tracks = visible,
            total = source.total,
            isInitialLoading = source.tracks.isEmpty() && source.isLoading,
            isLoadingMore = source.tracks.isNotEmpty() && source.isLoading,
            canLoadMore = source.canLoadMore,
            isRefreshing = refreshing,
            error = source.error?.toBrowseError(),
            filter = filter,
            offline = source.offline,
            download = download.status,
            isDownloaded = download.downloaded,
            downloadStates = download.states,
            nowPlaying = nowPlaying,
            partial = partial && !source.offline,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LikedSongsUiState())

    init {
        // First page once online; again after reconnecting with nothing loaded.
        offline.onEach { isOffline -> if (!isOffline && pager.state.value.items.isEmpty()) pager.loadMore() }
            .launchIn(viewModelScope)
        // While filtering, fetch the remaining pages so the filter covers every liked song.
        combine(filterQuery, pager.state, offline) { filter, page, isOffline -> filter.isNotEmpty() && !isOffline && page.canLoadMore }
            .onEach { if (it) pager.loadMore() }
            .launchIn(viewModelScope)
        // Liked Songs pages are not cached: start over after library edits (likes/unlikes).
        graph.library.changes.debounce(CHANGE_DEBOUNCE_MS)
            .onEach { if (!offline.first()) pager.reload() }
            .catch { }
            .launchIn(viewModelScope)
        // Refresh indicator ends with the reload.
        pager.state.onEach { if (!it.isLoading) refreshing.value = false }.launchIn(viewModelScope)
    }

    fun onFilterChange(text: String) {
        filterText = text
    }

    fun loadMore() = pager.loadMore()

    fun refresh() {
        viewModelScope.launch {
            if (offline.first()) return@launch
            refreshing.value = true
            pager.reload()
        }
    }

    fun retry() = pager.loadMore()

    /** URIs to play as a track list; placeholders (metadata failed) are left out. */
    private fun playableUris(): List<String> = state.value.let { s ->
        val tracks = if (s.offline) s.tracks else pager.state.value.items.map { it.track }
        tracks.filter { it.playable && !it.isPlaceholder }.map { it.uri }
    }

    /** Play button: toggles when Liked Songs is already playing. */
    fun playOrToggle() {
        val current = state.value
        if (current.nowPlaying.contextUri != null && current.nowPlaying.contextUri == current.contextUri) {
            graph.player.togglePlayPause()
        } else {
            play(shuffle = false)
        }
    }

    fun shuffle() = play(shuffle = true)

    private fun play(shuffle: Boolean) {
        val current = state.value
        val context = current.contextUri
        if (!current.offline && context != null) {
            graph.player.play(PlayRequest(contextUri = context, shuffle = shuffle))
            return
        }
        val uris = playableUris()
        if (uris.isEmpty()) {
            messages.trySend(LibraryMessage.NOTHING_TO_PLAY)
            return
        }
        graph.player.play(PlayRequest(trackUris = uris, startIndex = if (shuffle) null else 0, shuffle = shuffle))
    }

    fun playTrack(track: Track) {
        if (track.isPlaceholder || !track.playable) return
        val current = state.value
        val context = current.contextUri
        if (!current.offline && context != null) {
            graph.player.playContext(context, startUri = track.uri)
        } else {
            val uris = playableUris()
            val index = uris.indexOf(track.uri)
            if (index >= 0) graph.player.playTracks(uris, index) else graph.player.playTracks(listOf(track.uri))
        }
    }

    /** Download toggle (removal is confirmed by the UI first). */
    fun setDownloaded(download: Boolean, name: String) {
        val uri = state.value.contextUri ?: return
        // App scope: leaving the screen must not interrupt enqueueing or removal half-way.
        graph.appScope.launch {
            attempt {
                if (download) {
                    graph.downloads.downloadCollection(CollectionRef(uri, CollectionType.LIKED_SONGS, name, null))
                } else {
                    graph.downloads.removeCollection(uri)
                }
            }.onSuccess {
                messages.trySend(if (download) LibraryMessage.DOWNLOAD_STARTED else LibraryMessage.DOWNLOAD_REMOVED)
            }.onFailure {
                messages.trySend(LibraryMessage.DOWNLOAD_FAILED)
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val FILTER_DEBOUNCE_MS = 200L
        const val CHANGE_DEBOUNCE_MS = 500L
    }
}

/** Filter match on title, artists or album (case-insensitive). */
fun Track.matches(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        artists.any { it.name.contains(query, ignoreCase = true) } ||
        album?.name?.contains(query, ignoreCase = true) == true
