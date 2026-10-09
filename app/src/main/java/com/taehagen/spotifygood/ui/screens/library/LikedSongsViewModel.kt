package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.LibraryEdit
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.SavedTrack
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.SessionMessenger
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.engineReach
import com.taehagen.spotifygood.ui.screens.album.engineReachFlow
import com.taehagen.spotifygood.ui.screens.album.isNetworkClassError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
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
    /** The engine is offline (offline mode, no network, session offline): the banner. */
    val offline: Boolean = false,
    /** The list is the download (offline, or while the session connects). */
    val fromDownload: Boolean = false,
    val download: CollectionDownloadStatus = CollectionDownloadStatus.None,
    val isDownloaded: Boolean = false,
    val downloadStates: Map<String, DownloadState> = emptyMap(),
    val nowPlaying: NowPlaying = NowPlaying(),
    /** Some loaded pages hold placeholders (metadata failed right now): offer a retry. */
    val partial: Boolean = false,
    /** The shown order; [TrackSort.RECENTLY_ADDED] is the server's (newest first). */
    val sort: TrackSort = TrackSort.RECENTLY_ADDED,
    /** Songs loaded (before the filter): progress while every page is fetched to sort or filter. */
    val loadedCount: Int = 0,
    /** Every page is being fetched (a sort or a filter needs them all). */
    val loadingAll: Boolean = false,
    /** What plays is this sorted list (playing or paused), also when started before the page reopened. */
    val sortedListIsCurrent: Boolean = false,
    /** The session is ONLINE: songs that aren't downloaded can start ([canStartNow]). */
    val online: Boolean = true,
)

/**
 * Library page messages. Write results (download / removal) go through [SessionMessenger], so they
 * are shown after the page was left too; only NOTHING_TO_PLAY is sent to the page itself.
 */
enum class LibraryMessage { DOWNLOAD_FAILED, DOWNLOAD_STARTED, DOWNLOAD_REMOVED, NOTHING_TO_PLAY }

internal fun LibraryMessage.messageRes(): Int = when (this) {
    LibraryMessage.DOWNLOAD_FAILED -> R.string.browse_download_failed
    LibraryMessage.DOWNLOAD_STARTED -> R.string.browse_download_started
    LibraryMessage.DOWNLOAD_REMOVED -> R.string.browse_download_removed
    LibraryMessage.NOTHING_TO_PLAY -> R.string.browse_nothing_to_play
}

/** Tracks shown in the list plus paging info. */
private data class LikedSource(
    val tracks: List<Track>,
    val total: Int?,
    val isLoading: Boolean,
    val canLoadMore: Boolean,
    val error: Throwable?,
    /** Listing the download: plays then use its track list, not the online context. */
    val fromDownload: Boolean,
)

private data class LikedMeta(
    val download: LikedDownload,
    val refreshing: Boolean,
    val partial: Boolean,
    val offline: Boolean,
    val sort: TrackSort,
    val lastSorted: SortedPlays.Entry?,
    val online: Boolean,
)

/** Likes and unlikes made in the app, applied to a fully loaded Liked Songs instead of paging it again. */
internal data class LikedPatch(val removed: Set<String> = emptySet(), val added: List<Track> = emptyList()) {
    fun unliked(uris: Collection<String>): LikedPatch {
        val gone = uris.toHashSet()
        return copy(removed = removed + gone, added = added.filterNot { it.uri in gone })
    }

    /** [tracks] were just liked: newest first, ahead of the earlier ones. */
    fun liked(tracks: List<Track>): LikedPatch {
        val uris = tracks.mapTo(HashSet()) { it.uri }
        return copy(removed = removed - uris, added = tracks + added.filterNot { it.uri in uris })
    }
}

/**
 * The loaded Liked Songs (newest first) with [patch] applied: new likes first (a song liked again
 * moves to the top), unliked songs gone, [total] adjusted to match.
 */
internal fun applyLikedPatch(loaded: List<Track>, total: Int?, patch: LikedPatch): Pair<List<Track>, Int?> {
    if (patch.removed.isEmpty() && patch.added.isEmpty()) return loaded to total
    val loadedUris = loaded.mapTo(HashSet()) { it.uri }
    val addedUris = patch.added.mapTo(HashSet()) { it.uri }
    val kept = loaded.filter { it.uri !in patch.removed && it.uri !in addedUris }
    val newCount = patch.added.count { it.uri !in loadedUris }
    val removedCount = loaded.count { it.uri in patch.removed }
    return (patch.added + kept) to total?.let { (it + newCount - removedCount).coerceAtLeast(0) }
}

/** What Liked Songs lists. */
internal enum class LikedView {
    /** The downloaded Liked Songs. */
    DOWNLOADS,

    /** The server's pages (with their loading / error states). */
    SERVER,

    /** Nothing yet: the session is connecting and nothing is downloaded (a spinner). */
    WAITING,
}

/**
 * What Liked Songs lists, by the engine's reach (as playback routes): offline the download;
 * ONLINE the server's pages. While the session connects: loaded pages stay; otherwise the download
 * when there is one ([hasDownload]), or a spinner while the connect attempt is still worth waiting
 * for ([awaitingSession]); after a connection failure the download (or the error).
 */
internal fun likedSongsView(
    reach: EngineReach,
    loaded: Boolean,
    error: Throwable?,
    hasDownload: Boolean,
    awaitingSession: Boolean,
): LikedView = when (reach) {
    EngineReach.OFFLINE -> LikedView.DOWNLOADS
    EngineReach.ONLINE -> LikedView.SERVER
    EngineReach.CONNECTING -> when {
        error != null && isNetworkClassError(error) -> if (hasDownload) LikedView.DOWNLOADS else LikedView.SERVER
        loaded -> LikedView.SERVER
        hasDownload -> LikedView.DOWNLOADS
        awaitingSession -> LikedView.WAITING
        else -> LikedView.SERVER
    }
}

/**
 * Whether the server's pages should be (re)requested: once the session is ONLINE, when nothing is
 * loaded or the last request failed (e.g. it ran while the session was still reconnecting). While
 * it connects, one attempt once waiting stopped being worth it ([awaitingSession] false: captive
 * portal, retry backoff), so the page shows an answer instead of a spinner.
 */
internal fun likedSongsNeedsFetch(reach: EngineReach, page: PagedState<*>, awaitingSession: Boolean = false): Boolean = when (reach) {
    EngineReach.ONLINE -> !page.isLoading && (page.items.isEmpty() || page.error != null)
    EngineReach.CONNECTING -> !awaitingSession && !page.isLoading && page.items.isEmpty() && page.error == null
    EngineReach.OFFLINE -> false
}

/** How long Liked Songs shows a spinner for a connecting session before trying anyway. */
private const val SESSION_WAIT_MS = 10_000L

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
    private val reach = graph.engineReachFlow()
    private val refreshing = MutableStateFlow(false)
    private val sortStore = ListSortStore(graph.app)
    private val sort = MutableStateFlow(TrackSort.RECENTLY_ADDED)
    /** Likes and unlikes since the list was fully loaded ([applyLikedPatch]). */
    private val likedPatch = MutableStateFlow(LikedPatch())
    /** The loaded songs in the shown order (before the filter): what a sorted play plays. */
    @Volatile private var sortedTracks: List<Track> = emptyList()
    private val messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val events: Flow<LibraryMessage> = messages.receiveAsFlow()
    private val messenger = SessionMessenger(graph.app)

    /**
     * The user is known only after an online session; until then (cold start offline) the
     * downloaded Liked Songs collection gives the URI (logout wipes downloads: it is this account's).
     */
    private val contextUri: Flow<String?> = combine(graph.engine.user, graph.downloadedCollectionsFlow()) { user, collections ->
        user?.username?.let(::likedSongsUri) ?: collections.firstOrNull { it.type == CollectionType.LIKED_SONGS }?.uri
    }.distinctUntilChanged()

    private val hasDownload: Flow<Boolean> = graph.downloadedCollectionsFlow()
        .map { collections -> collections.any { it.type == CollectionType.LIKED_SONGS } }
        .distinctUntilChanged()

    /**
     * True while the session connects and waiting for it is still worth it: not once it reported a
     * retry (backoff), nor after [SESSION_WAIT_MS] (e.g. a captive portal never lets it connect).
     */
    private val awaitingSession: Flow<Boolean> =
        combine(reach, graph.engine.state.map { it.nextRetryMs != null }.distinctUntilChanged(), ::Pair)
            .transformLatest { (reach, retrying) ->
                if (reach != EngineReach.CONNECTING || retrying) {
                    emit(false)
                } else {
                    emit(true)
                    delay(SESSION_WAIT_MS)
                    emit(false)
                }
            }
            .distinctUntilChanged()

    private val filterQuery: Flow<String> = snapshotFlow { filterText }.debouncedInput(FILTER_DEBOUNCE_MS).onStart { emit("") }

    private val decoded = ConcurrentHashMap<String, Track>()

    /**
     * Downloaded Liked Songs in collection order (shown while the server can't be reached). Their
     * stored metadata is always playable: the explicit filter is applied here ([decoded] keeps the
     * raw tracks).
     */
    private val offlineTracks: Flow<List<Track>> = combine(
        graph.downloadedCollectionsFlow(),
        graph.downloads.items,
        graph.explicitFilterFlow(),
    ) { collections, items, filterExplicit ->
        val liked = collections.firstOrNull { it.type == CollectionType.LIKED_SONGS } ?: return@combine emptyList()
        val completed = items.filter { it.state == DownloadState.COMPLETED }.associateBy { it.uri }
        liked.itemUris.distinct().mapNotNull { uri ->
            val item = completed[uri] ?: return@mapNotNull null
            val track = decoded[uri] ?: (decodeDownloadMetadata(graph.json, uri, item.metadataJson) as? DownloadMetadata.OfTrack)
                ?.track?.also { decoded[uri] = it }
            track?.withExplicitFilter(filterExplicit)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    private val view: Flow<LikedView> = combine(reach, pager.state, hasDownload, awaitingSession) { reach, page, hasDownload, awaiting ->
        likedSongsView(reach, loaded = page.items.isNotEmpty(), error = page.error, hasDownload = hasDownload, awaitingSession = awaiting)
    }.distinctUntilChanged()

    private val source: Flow<LikedSource> = view.flatMapLatest { view ->
        when (view) {
            LikedView.DOWNLOADS -> offlineTracks.map {
                LikedSource(it, it.size, isLoading = false, canLoadMore = false, error = null, fromDownload = true)
            }
            LikedView.WAITING -> flowOf(
                LikedSource(emptyList(), null, isLoading = true, canLoadMore = false, error = null, fromDownload = false),
            )
            LikedView.SERVER -> combine(pager.state, likedPatch) { page, patch ->
                val (tracks, total) = applyLikedPatch(page.items.map { it.track }, page.total, patch)
                LikedSource(tracks, total, page.isLoading, page.canLoadMore, page.error, fromDownload = false)
            }
        }
    }

    /** [source] in the chosen order, sorted off the main thread (lists of thousands). */
    private val sortedSource: Flow<LikedSource> = combine(source, sort, ::Pair)
        .mapLatest { (source, sort) -> source.copy(tracks = source.tracks.sortedFor(sort, default = TrackSort.RECENTLY_ADDED)) }
        .flowOn(Dispatchers.Default)
        .onEach { sortedTracks = it.tracks }

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
        sortedSource,
        filterQuery,
        contextUri,
        combine(download, refreshing, partialPages.partial, reach, combine(sort, SortedPlays.last, ::Pair)) { download, refreshing, partial, reach, (sort, last) ->
            LikedMeta(
                download, refreshing, partial,
                offline = reach == EngineReach.OFFLINE,
                sort = sort,
                lastSorted = last,
                online = reach == EngineReach.ONLINE,
            )
        },
        graph.nowPlayingFlow(),
    ) { source, filter, contextUri, meta, nowPlaying ->
        val (download, refreshing, partial, offline, sort, lastSorted, online) = meta
        val visible = if (filter.isEmpty()) source.tracks else source.tracks.filter { it.matches(filter) }
        val needsAll = filter.isNotEmpty() || sort != TrackSort.RECENTLY_ADDED
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
            offline = offline,
            fromDownload = source.fromDownload,
            download = download.status,
            isDownloaded = download.downloaded,
            downloadStates = download.states,
            nowPlaying = nowPlaying,
            partial = partial && !source.fromDownload,
            sort = sort,
            loadedCount = source.tracks.size,
            loadingAll = needsAll && !source.fromDownload && source.tracks.isNotEmpty() && (source.canLoadMore || source.isLoading),
            sortedListIsCurrent = sort != TrackSort.RECENTLY_ADDED && isSortedPlayback(
                nowPlaying.trackUri,
                nowPlaying.contextUri,
                sortedListUris(ListSortStore.LIKED_SONGS, lastSorted) { source.tracks.map { it.uri } },
            ),
            online = online,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LikedSongsUiState())

    init {
        // First page once the session is ONLINE (not when a network merely appears: requests fail
        // NOT_CONNECTED while it reconnects); again after reconnecting with nothing loaded or a
        // failed load.
        combine(reach, awaitingSession, ::Pair)
            .onEach { (reach, awaiting) -> if (likedSongsNeedsFetch(reach, pager.state.value, awaiting)) pager.loadMore() }
            .launchIn(viewModelScope)
        // Loaded pages carry the playable flags of the old explicit filter.
        graph.explicitFilterChanges()
            .onEach { if (graph.engineReach() == EngineReach.ONLINE) reloadPager() }
            .catch { }
            .launchIn(viewModelScope)
        // While filtering or sorted, fetch the remaining pages (one at a time, the pager's size) so
        // the filter covers, and the sort orders, every liked song.
        combine(filterQuery, pager.state, reach, sort) { filter, page, reach, sort ->
            (filter.isNotEmpty() || sort != TrackSort.RECENTLY_ADDED) && reach == EngineReach.ONLINE && page.canLoadMore
        }
            .onEach { if (it) pager.loadMore() }
            .launchIn(viewModelScope)
        // The sort chosen last time.
        viewModelScope.launch {
            sortStore.get(ListSortStore.LIKED_SONGS)?.takeIf { it in TrackSort.LIKED_SONGS }?.let { sort.value = it }
        }
        // Likes and unlikes made in the app: applied to the loaded list (a sorted or filtered list
        // has every page; paging them all again, with metadata, after each like would cost the
        // whole library). Other library edits (albums, follows, playlists) don't touch it;
        // pull-to-refresh (changes elsewhere) starts over.
        graph.library.edits
            .onEach { edit ->
                when (edit) {
                    is LibraryEdit.LikedTracks -> onLikedEdit(edit)
                    LibraryEdit.Refreshed -> if (graph.engineReach() == EngineReach.ONLINE) reloadPager()
                    is LibraryEdit.PlaylistEdited -> Unit
                }
            }
            .catch { }
            .launchIn(viewModelScope)
        // Refresh indicator ends with the reload.
        pager.state.onEach { if (!it.isLoading) refreshing.value = false }.launchIn(viewModelScope)
        // A downloaded Liked Songs follows likes made elsewhere (another device): a loaded server page
        // asks the download to sync (at most every few minutes; pull-to-refresh always does).
        combine(contextUri, pager.state.map { !it.isLoading && it.error == null && it.items.isNotEmpty() }.distinctUntilChanged(), ::Pair)
            .onEach { (uri, loaded) -> if (uri != null && loaded && graph.engineReach() == EngineReach.ONLINE) graph.downloads.requestSync(uri) }
            .catch { }
            .launchIn(viewModelScope)
    }

    fun onFilterChange(text: String) {
        filterText = text
    }

    /** Shows (and plays) the songs in [value] order; remembered for next time. */
    fun setSort(value: TrackSort) {
        if (value !in TrackSort.LIKED_SONGS || value == sort.value) return
        sort.value = value
        graph.appScope.launch { attempt { sortStore.set(ListSortStore.LIKED_SONGS, value, default = TrackSort.RECENTLY_ADDED) } }
    }

    private val sorted: Boolean get() = sort.value != TrackSort.RECENTLY_ADDED

    /**
     * Plays the loaded songs in the shown order from [startUri] (else the first) as a track list:
     * the Liked Songs context plays the server's order ([sortedPlayRequest]). False when there is
     * nothing to play.
     */
    private fun playSorted(startUri: String?): Boolean {
        val uris = sortedTracks.filter { it.playable && !it.isPlaceholder }.map { it.uri }
        // Not ONLINE, a track list goes to the offline queue: planned like any plain list.
        return when (val plan = planSortedPlay(uris, startUri, graph.engineReach(), graph.downloads.downloadedUris.value)) {
            is SortedStart.Load -> {
                SortedPlays.record(ListSortStore.LIKED_SONGS, plan.request)
                graph.player.play(plan.request)
                true
            }
            SortedStart.NotDownloaded -> {
                messenger.post(R.string.playback_error_not_available_offline)
                true
            }
            SortedStart.Nothing -> false
        }
    }

    /** Starts the list over (pull-to-refresh, the explicit filter): the patch goes with the old pages. */
    private fun reloadPager() {
        likedPatch.value = LikedPatch()
        pager.reload()
    }

    /**
     * Songs liked or unliked in the app. A fully loaded list (every page: a sort, a filter, or a
     * short library) is patched: unliked songs are dropped, liked ones looked up (just those) and put
     * first. Otherwise only the first pages are loaded and a reload costs one page.
     */
    private suspend fun onLikedEdit(edit: LibraryEdit.LikedTracks) {
        if (graph.engineReach() != EngineReach.ONLINE) return
        val page = pager.state.value
        if (!page.endReached || page.items.isEmpty() || page.error != null) {
            reloadPager()
            return
        }
        if (!edit.saved) {
            likedPatch.update { it.unliked(edit.uris) }
            return
        }
        val tracks = try {
            graph.catalog.tracks(edit.uris)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (tracks.isNullOrEmpty()) reloadPager() else likedPatch.update { it.liked(tracks) }
    }

    fun loadMore() = pager.loadMore()

    fun refresh() {
        viewModelScope.launch {
            if (graph.engineReach() != EngineReach.ONLINE) return@launch
            refreshing.value = true
            // Drops the engine's and the app's cached library lists first, so the reload reaches
            // the server; the pager reloads on the resulting library change.
            graph.library.refresh()
        }
    }

    fun retry() = pager.loadMore()

    /** URIs to play as a track list; placeholders (metadata failed) are left out. */
    private fun playableUris(): List<String> = state.value.let { s ->
        val tracks = if (s.fromDownload) s.tracks else pager.state.value.items.map { it.track }
        tracks.filter { it.playable && !it.isPlaceholder }.map { it.uri }
    }

    /** Play button: toggles when Liked Songs (or a sorted list of it started here) is already playing. */
    fun playOrToggle() {
        val current = state.value
        val isContext = current.nowPlaying.contextUri != null && current.nowPlaying.contextUri == current.contextUri
        if (isContext || current.sortedListIsCurrent) {
            graph.player.togglePlayPause()
        } else {
            play(shuffle = false)
        }
    }

    fun shuffle() = play(shuffle = true)

    private fun play(shuffle: Boolean) {
        val current = state.value
        val context = current.contextUri
        // Sorted: the shown order (shuffle has no order to keep: the context, below).
        if (!current.fromDownload && sorted && !shuffle) {
            if (!playSorted(startUri = null)) messages.trySend(LibraryMessage.NOTHING_TO_PLAY)
            return
        }
        if (!current.fromDownload && context != null) {
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
        if (!current.fromDownload && sorted) {
            playSorted(track.uri)
        } else if (!current.fromDownload && context != null) {
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
                    graph.downloads.downloadCollection(CollectionRef(uri, CollectionType.LIKED_SONGS, name, null)).notice
                } else {
                    graph.downloads.removeCollection(uri)
                    null
                }
            }.onSuccess { notice ->
                // Shown also when the page was left meanwhile (resolving Liked Songs takes a while).
                messenger.post(notice ?: (if (download) LibraryMessage.DOWNLOAD_STARTED else LibraryMessage.DOWNLOAD_REMOVED).messageRes())
            }.onFailure {
                messenger.post(LibraryMessage.DOWNLOAD_FAILED.messageRes())
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val FILTER_DEBOUNCE_MS = 200L
    }
}

/** Filter match on title, artists or album (case-insensitive). */
fun Track.matches(query: String): Boolean =
    name.contains(query, ignoreCase = true) ||
        artists.any { it.name.contains(query, ignoreCase = true) } ||
        album?.name?.contains(query, ignoreCase = true) == true
