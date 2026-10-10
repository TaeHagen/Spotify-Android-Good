package com.taehagen.spotifygood.ui.screens.playlist

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.CollectionDownloadUi
import com.taehagen.spotifygood.ui.screens.album.Connectivity
import com.taehagen.spotifygood.ui.screens.album.DetailViewModel
import com.taehagen.spotifygood.ui.screens.album.DownloadedPage
import com.taehagen.spotifygood.ui.screens.album.FailureReason
import com.taehagen.spotifygood.ui.screens.album.downloadedPageFlow
import com.taehagen.spotifygood.ui.screens.album.remainingPlaylistItems
import com.taehagen.spotifygood.ui.screens.album.LoadState
import com.taehagen.spotifygood.ui.screens.album.PlaybackInfo
import com.taehagen.spotifygood.ui.screens.album.RichText
import com.taehagen.spotifygood.ui.screens.album.assignRowKeys
import com.taehagen.spotifygood.ui.screens.album.cachedPageMatchesDownload
import com.taehagen.spotifygood.ui.screens.album.collectionUi
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.engineReach
import com.taehagen.spotifygood.ui.screens.album.engineReachFlow
import com.taehagen.spotifygood.ui.screens.album.failureReason
import com.taehagen.spotifygood.ui.screens.album.insertBeforeIndex
import com.taehagen.spotifygood.ui.screens.album.matchesTokens
import com.taehagen.spotifygood.ui.screens.album.moved
import com.taehagen.spotifygood.ui.screens.album.parseHtml
import com.taehagen.spotifygood.ui.screens.album.savedFlow
import com.taehagen.spotifygood.ui.screens.album.searchText
import com.taehagen.spotifygood.ui.screens.album.searchTokens
import com.taehagen.spotifygood.ui.screens.album.statesFor
import com.taehagen.spotifygood.ui.screens.library.explicitFilterChanges
import com.taehagen.spotifygood.ui.screens.library.ListPlayback
import com.taehagen.spotifygood.ui.screens.library.listPlaybackFlow
import com.taehagen.spotifygood.ui.screens.library.planSortedPlay
import com.taehagen.spotifygood.ui.screens.library.SortedStart
import com.taehagen.spotifygood.ui.screens.library.SortedPlays
import com.taehagen.spotifygood.ui.screens.library.PageWindows
import com.taehagen.spotifygood.ui.screens.library.RowWindows
import com.taehagen.spotifygood.ui.screens.library.WindowPage
import com.taehagen.spotifygood.ui.screens.library.SortedPlayStarter
import com.taehagen.spotifygood.ui.screens.library.SORTED_PLAY_WAIT_MS
import com.taehagen.spotifygood.ui.screens.library.sortedPlayRequest
import com.taehagen.spotifygood.ui.screens.library.sortOrder
import com.taehagen.spotifygood.ui.screens.library.sortKey
import com.taehagen.spotifygood.ui.screens.library.isListPlaying
import com.taehagen.spotifygood.ui.screens.library.attempt
import com.taehagen.spotifygood.ui.screens.library.TrackSort
import com.taehagen.spotifygood.ui.screens.library.ListSortStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** A loaded playlist item with a stable list key. */
@Immutable
internal class PlaylistRow(val key: String, val item: PlaylistItem) {
    /** Normalised text for the in-playlist filter (computed on first use, off the main thread). */
    val searchText: String by lazy(LazyThreadSafetyMode.PUBLICATION) { item.searchText() }

    val durationMs: Long get() = item.track?.durationMs ?: item.episode?.durationMs ?: 0

    override fun equals(other: Any?): Boolean = other is PlaylistRow && other.key == key && other.item == item
    override fun hashCode(): Int = 31 * key.hashCode() + item.hashCode()
}

@Immutable
internal data class PlaylistData(
    /** Playlist metadata (its `items` are not used; see [rows]). */
    val meta: Playlist,
    val description: RichText,
    val rows: List<PlaylistRow>,
    val total: Int,
    val revision: String?,
    /**
     * Some loaded rows are placeholders (their metadata failed right now, docs §6.5). Such rows are
     * never kept in place of a fresh fetch, and the page offers a retry.
     */
    val partial: Boolean = false,
    /**
     * Some or all rows come from the download (offline: no cached page for them). Their order is
     * the download's, not the server's, so such data is read-only: no edits, no positional actions.
     */
    val downloadedCopy: Boolean = false,
) {
    val allLoaded: Boolean get() = rows.size >= total
}

/**
 * A sorted play of this playlist waits for its remaining rows first ([SortedPlayStarter]): the
 * session is ONLINE and the server's rows aren't all loaded (also after a failed page: the play
 * fetches them once more).
 */
internal fun playlistPlayWaits(reach: EngineReach, playlist: PlaylistData?): Boolean =
    reach == EngineReach.ONLINE && playlist != null && !playlist.allLoaded && !playlist.downloadedCopy

/**
 * The URIs a sorted play plays: [rows] (the loaded rows, in [sort] order) that can play; not local
 * files, placeholders (metadata failed) or unplayable items.
 */
internal fun sortedPlayableUris(rows: List<PlaylistRow>, sort: TrackSort): List<String> =
    sortOrder(rows.map { it.item.sortKey() }, sort, default = TrackSort.CUSTOM).mapNotNull { index ->
        val item = rows[index].item
        val playable = item.track?.let { it.playable && !it.isPlaceholder } ?: item.episode?.let { it.playable && !it.isPlaceholder } ?: false
        item.uri?.takeIf { playable && !it.startsWith("spotify:local:") }
    }

/** A row to display with its absolute position in the playlist. */
@Immutable
internal data class VisibleRow(val index: Int, val row: PlaylistRow)

@Immutable
internal data class PlaylistListUi(
    val rows: List<VisibleRow> = emptyList(),
    val filterActive: Boolean = false,
    /** Rows are shown in another order than the playlist's own ([VisibleRow.index] keeps theirs). */
    val sortActive: Boolean = false,
    /** Total duration, known once every item is loaded. */
    val totalDurationMs: Long? = null,
)

@Immutable
internal data class PagingUi(val loading: Boolean = false, val failed: Boolean = false, val loadingAll: Boolean = false)

@Immutable
internal data class PlaylistUiState(
    val load: LoadState<PlaylistData> = LoadState.Loading,
    val list: PlaylistListUi = PlaylistListUi(),
    val paging: PagingUi = PagingUi(),
    val editMode: Boolean = false,
    val playback: PlaybackInfo = PlaybackInfo(),
    /** Null until known. */
    val following: Boolean? = null,
    val download: CollectionDownloadUi = CollectionDownloadUi(),
    val rowDownloads: Map<String, DownloadState> = emptyMap(),
    val offline: Boolean = false,
    /** The session is ONLINE: rows that aren't downloaded can start ([canStartNow]). */
    val online: Boolean = true,
    val filterExplicit: Boolean = false,
    /** The chosen order (not applied in edit mode, which shows the playlist's own). */
    val sort: TrackSort = TrackSort.CUSTOM,
    /**
     * What plays is this playlist (playing or paused): its context in any order, or the sorted track
     * list started for it, also before the page was reopened ([isListPlaying]).
     */
    val listIsCurrent: Boolean = false,
    /** A sorted play waits for the remaining rows ([SortedPlayStarter]): the Play button shows it. */
    val playPending: Boolean = false,
    /** Rows past the loaded ones, loaded where the list is looked at ([PlaylistViewModel.onRowsVisible]). */
    val windows: RowWindows<PlaylistRow> = RowWindows(WINDOW_PAGE_SIZE),
)

/** Rows per window page of a playlist (its page size). */
internal const val WINDOW_PAGE_SIZE = 100

/**
 * Rows the playlist page shows as placeholders after the [shown] ones, to the end of the playlist:
 * they load by window as they come on screen (a fast scroll seeks the whole playlist). Only in its
 * own order with the server's rows, and not [offline]: kept while the session (re)connects, so the
 * list keeps its length and place (they load once it is ONLINE); none sorted or filtered (every row
 * is loaded for it), in edit mode (the loaded rows are edited) or for the download.
 */
internal fun playlistPlaceholders(playlist: PlaylistData, shown: Int, list: PlaylistListUi, editMode: Boolean, offline: Boolean): Int =
    if (editMode || list.sortActive || list.filterActive || playlist.downloadedCopy || offline) 0 else (playlist.total - shown).coerceAtLeast(0)

@Immutable
internal data class AddSongsUi(
    val query: String = "",
    val loading: Boolean = false,
    val failed: Boolean = false,
    val results: List<Track> = emptyList(),
    /** Tracks already in the (loaded part of the) playlist or added from the sheet. */
    val addedUris: Set<String> = emptySet(),
)

internal sealed interface PlaylistEvent {
    data object Deleted : PlaylistEvent
}

/**
 * Per-playlist edit locks, process-wide: queued edits outlive their page (app scope), and a page
 * reopened meanwhile must queue its edits behind them instead of interleaving.
 */
private object PlaylistEditLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forUri(uri: String): Mutex = locks.computeIfAbsent(uri) { Mutex() }
}

@OptIn(FlowPreview::class)
internal class PlaylistViewModel(graph: AppGraph, private val uri: String) : DetailViewModel(graph, uri) {

    private val data = MutableStateFlow<LoadState<PlaylistData>>(LoadState.Loading)
    private val paging = MutableStateFlow(PagingUi())
    private val filter = MutableStateFlow("")
    private val sortStore = ListSortStore(graph.app)
    private val sort = MutableStateFlow(TrackSort.CUSTOM)
    private val editMode = MutableStateFlow(false)
    private val addQuery = MutableStateFlow("")
    private val addedFromSheet = MutableStateFlow<Set<String>>(emptySet())
    private val eventChannel = Channel<PlaylistEvent>(Channel.BUFFERED)

    /** Navigation events (playlist deleted → close the page). */
    val events: Flow<PlaylistEvent> = eventChannel.receiveAsFlow()

    // Mutations run one at a time, each with the latest known revision. Local (optimistic) state
    // wins while mutations are pending; a failure bumps [generation] so queued mutations computed
    // on the stale optimistic list are dropped, and the server state is reloaded.
    // They run in the app scope (on the main thread, like the rest of this class): the user saw
    // them applied, so leaving the page must not cancel the queued ones. The lock is per playlist,
    // shared with a reopened page of the same playlist.
    private val mutationMutex = PlaylistEditLocks.forUri(uri)
    private var pendingMutations = 0
    private var generation = 0
    private var dragging = false
    /** The page is gone; queued mutations still run, but nothing needs refreshing. */
    @Volatile private var cleared = false
    private var pageJob: Job? = null
    private var loadAllJob: Job? = null
    /** Play and row taps: a sorted one waits for every row ([playlistPlayWaits]). */
    private val listPlays = SortedPlayStarter(viewModelScope, graph.player.userCommands)
    /** Rows past the loaded ones, by page, where the list is looked at ([onRowsVisible]); retries wait for the session. */
    private val windows = PageWindows(
        viewModelScope,
        WINDOW_PAGE_SIZE,
        ready = { graph.engineReachFlow().first { it == EngineReach.ONLINE } },
    ) { offset, limit -> windowRows(offset, limit) }
    /** The rows on screen last reported ([onRowsVisible]): asked again after the windows were dropped. */
    private var visibleRows: Pair<Int, Int>? = null
    /**
     * Changes made elsewhere (another device, the web player): pushed, or found by the revision
     * check when the page starts and when the session comes online; the rows refresh in place.
     */
    private val freshness = PlaylistFreshness(
        viewModelScope,
        shown = ::refreshableRevision,
        online = { graph.engineReach() == EngineReach.ONLINE },
        fetchRevision = { graph.catalog.playlistRevision(uri) },
        refresh = ::refreshFromServer,
    )

    private val listUi: Flow<PlaylistListUi> = combine(
        data,
        filter.debounce { if (it.isBlank()) 0L else FILTER_DEBOUNCE_MS },
        sort,
        editMode,
    ) { load, query, sort, editing -> ListInput(load.dataOrNull(), query, if (editing) TrackSort.CUSTOM else sort) }
        .mapLatest { (playlist, query, sort) -> buildListUi(playlist, query, sort) }

    private val itemUris: Flow<Set<String>> = combine(data, windows.windows) { load, windows ->
        val uris = load.dataOrNull()?.rows?.mapNotNullTo(HashSet()) { it.item.uri } ?: HashSet()
        windows.pages.values.forEach { page -> page.mapNotNullTo(uris) { it.item.uri } }
        uris
    }.distinctUntilChanged()

    private val core: Flow<CoreState> = combine(
        data,
        listUi,
        paging,
        editMode,
        combine(sort, SortedPlays.last, graph.listPlaybackFlow(), ::Triple),
    ) { load, list, paging, editing, (sort, last, listPlayback) ->
        CoreState(load, list, paging, editing, sort, last, listPlayback)
    }

    val state: StateFlow<PlaylistUiState> = combine(
        core,
        playbackInfo,
        graph.savedFlow(uri),
        graph.downloads.collectionUi(uri),
        combine(graph.downloads.statesFor(itemUris), connectivity, listPlays.waiting, windows.windows, ::RowExtras),
    ) { core, playback, following, download, extras ->
        val connectivity = extras.connectivity
        PlaylistUiState(
            core.load, core.list, core.paging, core.editMode, playback, following, download, extras.downloads,
            connectivity.offline, connectivity.online, connectivity.filterExplicit,
            sort = core.sort,
            listIsCurrent = isListPlaying(ListSortStore.playlist(uri), uri, core.listPlayback, core.lastSorted),
            playPending = extras.playPending,
            windows = extras.windows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaylistUiState())

    val addSongs: StateFlow<AddSongsUi> = combine(
        addQuery.debounce { if (it.isBlank()) 0L else SEARCH_DEBOUNCE_MS }.flatMapLatest(::searchTracks),
        addedFromSheet,
        itemUris,
    ) { search, added, existing -> search.copy(addedUris = added + existing) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AddSongsUi())

    init {
        viewModelScope.launch {
            retryTrigger.collectLatest {
                loadOnceConnected { graph.catalog.playlist(uri, PAGE_SIZE) }
                    .catch { emit(Resource.Error(it)) }
                    .collect { onFirstPage(it) }
            }
        }
        // Session ONLINE after the page failed, showed a stale copy or the download: fetch the
        // server's rows (they replace it).
        reloadWhenOnline(data, showingDownload = { data.value.dataOrNull()?.downloadedCopy == true })
        // The sort chosen for this playlist last time (every row is fetched for it, with progress).
        viewModelScope.launch {
            val stored = sortStore.get(ListSortStore.playlist(uri))?.takeIf { it in TrackSort.PLAYLIST } ?: return@launch
            if (sort.value == TrackSort.CUSTOM && stored != TrackSort.CUSTOM) {
                sort.value = stored
                ensureAllLoaded()
            }
        }
        // Windows belong to one version of the playlist: another revision or total, or the
        // download, drops them (the rows on screen load again); rows loaded since cover theirs.
        data.map { load -> load.dataOrNull()?.let { WindowsOf(it.revision, it.total, it.downloadedCopy) } }
            .distinctUntilChanged()
            .onEach { resetWindows() }
            .launchIn(viewModelScope)
        data.map { it.dataOrNull()?.rows?.size ?: 0 }
            .distinctUntilChanged()
            .onEach { windows.dropBelow(it) }
            .launchIn(viewModelScope)
        // ONLINE again (the placeholders stayed while it reconnected): the rows on screen load.
        graph.engineReachFlow()
            .filter { it == EngineReach.ONLINE }
            .onEach { visibleRows?.let { (first, last) -> onRowsVisible(first, last) } }
            .catch { }
            .launchIn(viewModelScope)
        // Changed elsewhere: pushed by Spotify (only while the dealer is up), and checked again each
        // time the session comes back (pushes are lost while it is away).
        graph.libraryPushes.playlistChanges(uri)
            .onEach { freshness.onPushed(it) }
            .catch { }
            .launchIn(viewModelScope)
        graph.engineReachFlow()
            .drop(1)
            .filter { it == EngineReach.ONLINE }
            .onEach { freshness.onOnline() }
            .catch { }
            .launchIn(viewModelScope)
        // Loaded rows carry the playable flags of the old explicit filter; the revision doesn't
        // change, so the live page alone wouldn't replace them.
        graph.explicitFilterChanges()
            .onEach {
                resetWindows()
                mutationMutex.withLock {
                    val current = data.value.dataOrNull()
                    if (current != null && !current.downloadedCopy && canApplyServerRows() && graph.engineReach() == EngineReach.ONLINE) {
                        refreshLoaded(force = false)
                    }
                }
            }
            .catch { }
            .launchIn(viewModelScope)
    }

    // -----------------------------------------------------------------------------------------
    // Loading
    // -----------------------------------------------------------------------------------------

    private suspend fun onFirstPage(resource: Resource<Playlist>) {
        val page = resource.dataOrNull
        // A downloaded playlist follows changes made elsewhere: a fresh server revision other than
        // the downloaded one syncs the download.
        if (resource is Resource.Success && !resource.fromCache) {
            page?.revision?.let { revision -> viewModelScope.launch { graph.downloads.requestSync(uri, revision) } }
        }
        if (page == null) {
            val shown = data.value
            if (shown is LoadState.Ready && shown.data.downloadedCopy) {
                // Refetching while the download is shown: it stays, refreshing until the fetch ends.
                data.value = shown.copy(refreshing = resource is Resource.Loading)
            }
            if (data.value !is LoadState.Ready) {
                // No page and no cached copy while the session can't reach the server (cache cleared
                // or pruned), or the playlist is gone: a downloaded playlist still opens, read-only,
                // from the download database.
                val useCopy = resource is Resource.Error &&
                    (downloadFallbackAllowed(resource.error) || failureReason(resource.error) == FailureReason.NOT_FOUND)
                val copy = if (useCopy) downloadedCopy() else null
                data.value = when {
                    copy != null -> LoadState.Ready(downloadedData(copy), stale = true)
                    resource is Resource.Error -> LoadState.Failed(failureReason(resource.error))
                    else -> LoadState.Loading
                }
            }
            return
        }
        // The catalog flow is live (re-emits after edits). Local edits win until the mutation queue
        // drains; the queue refreshes the loaded range itself afterwards.
        if (pendingMutations > 0 || dragging) return
        // A stale cached page while the server can't be reached: the daily sync may have changed
        // the downloaded playlist since it was cached. Then the download is what can play: list it.
        if (resource is Resource.Error && !editMode.value && downloadFallbackAllowed(resource.error)) {
            val copy = downloadedCopy()
            if (copy != null && !cachedPageMatchesDownload(page.items.map { it.uri }, page.total, copy.itemUris)) {
                data.value = LoadState.Ready(downloadedData(copy), stale = true)
                return
            }
        }
        val description = withContext(Dispatchers.Default) { parseHtml(page.description.orEmpty()) }
        val current = data.value.dataOrNull()
        val sameRevision = current != null && page.revision != null && current.revision == page.revision
        // Loaded rows are kept for the same revision, unless they hold placeholders or come from the
        // download: then a refetch (this one, or the data layer's retries) replaces them.
        val replaceable = current != null && (current.partial || current.downloadedCopy)
        // Same revision but other rows or playable flags (e.g. Hide explicit content changed).
        val rowsDiffer = current != null && !current.startsWith(page.items)
        val keepRows = current != null && sameRevision && !replaceable && !rowsDiffer
        // A new revision (or replaceable / changed rows) while more than the first page is loaded:
        // keep showing the loaded rows (no scroll jump) and reload the whole loaded range.
        val reloadRange = current != null && (!sameRevision || replaceable || rowsDiffer) && current.rows.size > page.items.size
        val playlist = when {
            keepRows -> PlaylistData(page.copy(items = emptyList()), description, current.rows, maxOf(page.total, current.rows.size), page.revision)
            // Rows still from the download stay read-only until the reload replaces them.
            reloadRange -> current.copy(
                meta = page.copy(items = emptyList(), canEdit = page.canEdit && !current.downloadedCopy),
                description = description,
            )
            else -> PlaylistData(page.copy(items = emptyList()), description, buildRows(page.items), page.total, page.revision, page.partial)
        }
        data.value = LoadState.Ready(
            playlist,
            refreshing = resource is Resource.Loading,
            stale = resource is Resource.Error,
        )
        // The refresh of the loaded range below reports its own revision.
        if (!reloadRange && resource !is Resource.Loading) {
            freshness.onPage(playlist.revision, fromServer = resource is Resource.Success && !resource.fromCache)
        }
        if (reloadRange) {
            viewModelScope.launch {
                mutationMutex.withLock {
                    val latest = data.value.dataOrNull()
                    val replace = latest?.revision != page.revision || latest?.partial == true || latest?.downloadedCopy == true || rowsDiffer
                    if (canApplyServerRows() && replace) refreshLoaded(force = false)
                }
            }
        }
        if (needsAllRows()) ensureAllLoaded()
    }

    /** A filter or a sort needs every row (fetched page by page, with progress). */
    private fun needsAllRows(): Boolean = filter.value.isNotBlank() || sort.value != TrackSort.CUSTOM

    /** Owner: shows the playlist on the profile, or not. The page reloads with the new state. */
    fun setPublic(public: Boolean) {
        launchWrite(if (public) R.string.shell_msg_playlist_public else R.string.shell_msg_playlist_private, R.string.detail_playlist_privacy_failed) {
            graph.playlists.setPublic(uri, public)
        }
    }

    /** Owner: makes the playlist collaborative or not (collaborative also makes it private). */
    fun setCollaborative(collaborative: Boolean) {
        launchWrite(
            if (collaborative) R.string.shell_msg_playlist_collaborative else R.string.shell_msg_playlist_not_collaborative,
            R.string.detail_playlist_privacy_failed,
        ) {
            graph.playlists.setCollaborative(uri, collaborative)
        }
    }

    /**
     * Rows [first]..[last] (positions in the shown list) are on screen. In the playlist's own order
     * the next page continues the loaded rows when they come near their end; rows farther down
     * (placeholders reached by scrolling or a fast scroll) load by window ([PageWindows]), only
     * their pages. A sort or a filter loads every row itself. Without windows the window loads
     * and retries stop ([PageWindows.hide]).
     */
    fun onRowsVisible(first: Int, last: Int) {
        visibleRows = first to last
        val playlist = data.value.dataOrNull()
        if (playlist == null || needsAllRows() || playlist.allLoaded) {
            windows.hide()
            return
        }
        val loaded = playlist.rows.size
        val nextPage = !paging.value.failed && last >= loaded - LOAD_AHEAD && first < loaded + PAGE_SIZE
        if (nextPage) loadMore()
        // Edit mode lists (and edits) the loaded rows only.
        if (!editMode.value && graph.engineReach() == EngineReach.ONLINE && !playlist.downloadedCopy) {
            val continued = nextPage || pageJob?.isActive == true
            windows.show(first, last, from = if (continued) loaded + PAGE_SIZE else loaded, total = playlist.total)
        } else {
            windows.hide()
        }
    }

    /** The list left the screen (or the app went to the background): window loads and retries stop. */
    fun onRowsHidden() {
        visibleRows = null
        windows.hide()
    }

    /** The page is on screen (ON_START): its revision is checked, a change pushed meanwhile shows. */
    fun onScreenStarted() = freshness.onStart()

    /** The page left the screen or the app went to the background (ON_STOP): nothing refreshes. */
    fun onScreenStopped() = freshness.onStop()

    private fun resetWindows() {
        windows.clear()
        visibleRows?.let { (first, last) -> viewModelScope.launch { onRowsVisible(first, last) } }
    }

    /**
     * A window's rows, partial when some are placeholders (loaded again), or null when they can't
     * be shown (another revision: the playlist reloads).
     */
    private suspend fun windowRows(offset: Int, limit: Int): WindowPage<PlaylistRow>? {
        val page = graph.catalog.playlistPage(uri, offset, limit)
        val latest = data.value.dataOrNull() ?: return null
        if (latest.downloadedCopy) return null
        if (page.revision != null && latest.revision != null && page.revision != latest.revision) {
            // Changed elsewhere: reload the loaded rows; their new revision drops the windows.
            mutationMutex.withLock { if (canApplyServerRows()) refreshLoaded(force = false) }
            return null
        }
        return WindowPage(page.items.mapIndexed { i, item -> PlaylistRow("w:${offset + i}", item) }, page.partial)
    }

    /** Loads the next page when the list is scrolled near its end. */
    fun loadMore() {
        val playlist = data.value.dataOrNull() ?: return
        if (pageJob?.isActive == true || loadAllJob?.isActive == true || playlist.allLoaded) return
        if (pendingMutations > 0 || dragging) return
        pageJob = viewModelScope.launch { loadNextPage() }
    }

    /** Appends one page; returns true when more pages may follow. */
    private suspend fun loadNextPage(): Boolean {
        val before = data.value.dataOrNull() ?: return false
        if (before.allLoaded) return false
        paging.update { it.copy(loading = true, failed = false) }
        try {
            val page = graph.catalog.playlistPage(uri, before.rows.size, PAGE_SIZE)
            val latest = data.value.dataOrNull() ?: return false
            if (latest.rows.size != before.rows.size || pendingMutations > 0 || dragging) return false
            if (page.revision != null && latest.revision != null && page.revision != latest.revision) {
                // Changed elsewhere since the first page: reload what we have, then continue.
                mutationMutex.withLock { if (pendingMutations == 0 && !dragging) refreshLoaded(force = false) }
                return data.value.dataOrNull()?.allLoaded == false
            }
            val used = latest.rows.mapTo(HashSet()) { it.key }
            val rows = latest.rows + buildRows(page.items, used)
            val total = if (page.items.isEmpty()) rows.size else maxOf(page.total, rows.size)
            data.value = LoadState.Ready(latest.copy(rows = rows, total = total, partial = latest.partial || page.partial))
            return page.items.isNotEmpty() && rows.size < total
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The session can't reach the server past the cached first page: the rest of a downloaded
            // playlist is on disk. Not while the session is ONLINE: a transient error then keeps the
            // server list (Retry footer).
            if (downloadFallbackAllowed(e) && appendDownloadedRows()) return false
            paging.update { it.copy(failed = true) }
            return false
        } finally {
            paging.update { it.copy(loading = false) }
        }
    }

    /** The downloaded copy of this playlist, or null when it is not downloaded. */
    private suspend fun downloadedCopy(): DownloadedPage? = try {
        graph.downloadedPageFlow(uri).first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** The playlist as downloaded (read-only: no edits without the server). */
    private fun downloadedData(copy: DownloadedPage): PlaylistData {
        val meta = copy.toPlaylist()
        return PlaylistData(meta.copy(items = emptyList()), RichText.EMPTY, buildRows(meta.items), meta.total, null, copy.partial, downloadedCopy = true)
    }

    /**
     * Appends every downloaded item not shown yet (matched by uri: the cached server page may hold
     * local files, duplicates or another revision) and makes the page read-only; false when the
     * playlist isn't downloaded or edits are in progress.
     */
    private suspend fun appendDownloadedRows(): Boolean {
        val copy = downloadedCopy() ?: return false
        val latest = data.value.dataOrNull() ?: return false
        if (pendingMutations > 0 || dragging || editMode.value) return false
        val used = latest.rows.mapTo(HashSet()) { it.key }
        val shown = latest.rows.mapNotNullTo(HashSet()) { it.item.uri }
        val rows = latest.rows + buildRows(copy.remainingPlaylistItems(shown), used)
        data.value = LoadState.Ready(
            latest.copy(
                meta = latest.meta.copy(canEdit = false),
                rows = rows,
                total = rows.size,
                partial = latest.partial || copy.partial,
                downloadedCopy = true,
            ),
            stale = true,
        )
        return true
    }

    /** Loads every remaining page (used by the in-playlist filter and a sort). */
    private fun ensureAllLoaded() {
        if (loadAllJob?.isActive == true) return
        loadAllJob = viewModelScope.launch {
            paging.update { it.copy(loadingAll = true) }
            try {
                pageJob?.join()
                var pages = 0
                while (pages++ < MAX_PAGES && needsAllRows()) {
                    val playlist = data.value.dataOrNull() ?: break
                    if (playlist.allLoaded || !loadNextPage()) break
                }
            } finally {
                paging.update { it.copy(loadingAll = false) }
            }
        }
    }

    fun retryPage() {
        paging.update { it.copy(failed = false) }
        if (needsAllRows()) ensureAllLoaded() else loadMore()
    }

    private fun buildRows(items: List<PlaylistItem>, used: MutableSet<String> = HashSet()): List<PlaylistRow> =
        assignRowKeys(items, used).zip(items, ::PlaylistRow)

    private suspend fun buildListUi(playlist: PlaylistData?, query: String, sort: TrackSort): PlaylistListUi {
        if (playlist == null) return PlaylistListUi()
        val tokens = searchTokens(query)
        val duration = if (playlist.allLoaded) playlist.rows.sumOf { it.durationMs } else null
        val sortActive = sort != TrackSort.CUSTOM
        if (tokens.isEmpty() && !sortActive) {
            return PlaylistListUi(playlist.rows.mapIndexed(::VisibleRow), filterActive = false, totalDurationMs = duration)
        }
        // Sorted, then filtered, off the main thread; rows keep their playlist index (edits).
        val rows = withContext(Dispatchers.Default) {
            sortedRows(playlist, sort).filter { tokens.isEmpty() || matchesTokens(it.row.searchText, tokens) }
        }
        return PlaylistListUi(rows, filterActive = tokens.isNotEmpty(), sortActive = sortActive, totalDurationMs = duration)
    }

    /** Every loaded row in [sort] order, with its playlist index ([sortedPlayableUris] plays the same order). */
    private fun sortedRows(playlist: PlaylistData, sort: TrackSort): List<VisibleRow> =
        sortOrder(playlist.rows.map { it.item.sortKey() }, sort, default = TrackSort.CUSTOM)
            .map { VisibleRow(it, playlist.rows[it]) }

    /** Shows (and plays) the rows in [value] order; remembered for this playlist. */
    fun setSort(value: TrackSort) {
        if (value !in TrackSort.PLAYLIST || value == sort.value) return
        sort.value = value
        graph.appScope.launch { attempt { sortStore.set(ListSortStore.playlist(uri), value, default = TrackSort.CUSTOM) } }
        if (value != TrackSort.CUSTOM) ensureAllLoaded()
    }

    /** The playlist is shown in another order than its own (not in edit mode). */
    private val sorted: Boolean get() = sort.value != TrackSort.CUSTOM && !editMode.value

    /**
     * Plays the rows in the shown order from [startUri] (else the first) as a track list: the
     * playlist context plays its own order ([sortedPlayRequest]). The playlist and the order are
     * read now (after [playlistPlayWaits]' wait: every row), sorted off the main thread.
     */
    private suspend fun playSorted(startUri: String?) {
        val playlist = data.value.dataOrNull() ?: return
        val order = sort.value
        val uris = withContext(Dispatchers.Default) { sortedPlayableUris(playlist.rows, order) }
        // Not ONLINE, a track list goes to the offline queue: planned like any plain list (the
        // reach as of now, after the wait and the sort).
        when (val plan = planSortedPlay(uris, startUri, graph.engineReach(), graph.downloads.downloadedUris.value)) {
            is SortedStart.Load -> {
                SortedPlays.record(graph, ListSortStore.playlist(uri), plan.request)
                graph.player.play(plan.request)
            }
            SortedStart.NotDownloaded -> message(R.string.playback_error_not_available_offline)
            SortedStart.Nothing -> Unit
        }
    }

    /**
     * Play ([row] null: from the top) or a row tap. Sorted, the shown order plays as a track list
     * once every row is in ([playlistPlayWaits]: they are being fetched for the sort meanwhile,
     * "Loading songs… n of total"), at most [SORTED_PLAY_WAIT_MS]; a newer tap replaces a waiting
     * one ([SortedPlayStarter]). What plays is decided when it starts: the sort, edit mode or the
     * session may have changed during the wait.
     */
    private fun play(row: VisibleRow?) {
        val waits = sorted && playlistPlayWaits(graph.engineReach(), data.value.dataOrNull())
        listPlays.play(awaitRows = if (waits) ::awaitAllRows else null) { startPlay(row) }
    }

    /** Returns once every row is loaded, or loading stopped (a failed page, the session not ONLINE). */
    private suspend fun awaitAllRows() {
        // Also after a failed page: the play fetches the rest once more.
        ensureAllLoaded()
        val loading = loadAllJob ?: return
        merge(
            flow { loading.join(); emit(Unit) },
            graph.engineReachFlow().filter { it != EngineReach.ONLINE }.map { },
        ).first()
    }

    private suspend fun startPlay(row: VisibleRow?) {
        when {
            sorted -> playSorted(row?.row?.item?.uri)
            row == null -> super.playContext()
            else -> {
                val item = row.row.item
                graph.player.play(PlayRequest(contextUri = uri, startUri = item.uri, startIndex = row.index, startUid = item.uid))
            }
        }
    }

    /** Play button: toggles this playlist's playback (either form, [isListPlaying]); else starts it. */
    override fun playContext() {
        // This playlist plays: its context in any order (Shuffle, started before the sort, another
        // device) or a sorted list started for it, also after the page was reopened: toggle it.
        // Otherwise start it, sorted or as its context.
        if (state.value.listIsCurrent || currentPlayback().isContext(uri)) {
            listPlays.cancel()
            graph.player.togglePlayPause()
        } else {
            play(row = null)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Playback, filter, library
    // -----------------------------------------------------------------------------------------

    fun playItem(row: VisibleRow) {
        val item = row.row.item
        // Placeholders (metadata failed) are not played; the row does not offer it either.
        if (item.track?.isPlaceholder == true || item.episode?.isPlaceholder == true) return
        play(row)
    }

    fun setFilter(query: String) {
        filter.value = query
        if (query.isNotBlank()) ensureAllLoaded()
    }

    fun setEditMode(enabled: Boolean) {
        val playlist = data.value.dataOrNull()
        // Rows from the download are not in the server's order: never edit them.
        if (enabled && (playlist?.meta?.canEdit != true || playlist.downloadedCopy)) return
        if (enabled) filter.value = ""
        editMode.value = enabled
    }

    fun toggleFollow() {
        toggleSaved(state.value.following)
    }

    fun download() {
        val playlist = data.value.dataOrNull()?.meta ?: return
        downloadCollection(CollectionRef(uri, CollectionType.PLAYLIST, playlist.name, playlist.images.best(300)))
    }

    // -----------------------------------------------------------------------------------------
    // Editing
    // -----------------------------------------------------------------------------------------

    fun removeItem(key: String) = mutate { playlist ->
        val index = playlist.rows.indexOfFirst { it.key == key }
        val itemUri = playlist.rows.getOrNull(index)?.item?.uri ?: return@mutate null
        Mutation(
            optimistic = playlist.copy(
                rows = playlist.rows.filterIndexed { i, _ -> i != index },
                total = (playlist.total - 1).coerceAtLeast(0),
            ),
        ) { revision -> graph.playlists.removeItems(uri, listOf(itemUri to index), revision) }
    }

    /** Moves the item at [from] to final position [to] (both absolute indices). */
    fun moveItem(from: Int, to: Int) = mutate { playlist ->
        if (from == to || from !in playlist.rows.indices || to !in playlist.rows.indices) return@mutate null
        val itemUri = playlist.rows[from].item.uri
        Mutation(optimistic = playlist.copy(rows = playlist.rows.moved(from, to))) { revision ->
            graph.playlists.moveItem(uri, from, insertBeforeIndex(from, to), revision, itemUri)
        }
    }

    fun beginDrag() {
        dragging = true
    }

    /** Live reordering while dragging (local only). */
    fun previewMove(from: Int, to: Int) {
        val playlist = data.value.dataOrNull() ?: return
        if (from == to || from !in playlist.rows.indices || to !in playlist.rows.indices) return
        data.value = LoadState.Ready(playlist.copy(rows = playlist.rows.moved(from, to)))
    }

    /** Commits a drag that started at [from] and ended at [to] (already applied locally). */
    fun endDrag(from: Int, to: Int) {
        dragging = false
        if (from == to || from < 0 || to < 0) return
        mutate { playlist ->
            // The rows were already reordered while dragging: the moved item is at [to] now.
            val itemUri = playlist.rows.getOrNull(to)?.item?.uri
            Mutation(optimistic = playlist) { revision ->
                graph.playlists.moveItem(uri, from, insertBeforeIndex(from, to), revision, itemUri)
            }
        }
    }

    fun addTracks(tracks: List<Track>) = mutate { playlist ->
        if (tracks.isEmpty()) return@mutate null
        addedFromSheet.update { it + tracks.map(Track::uri) }
        val rows = if (playlist.allLoaded) {
            playlist.rows + buildRows(tracks.map { PlaylistItem(track = it) }, playlist.rows.mapTo(HashSet()) { it.key })
        } else {
            playlist.rows
        }
        Mutation(optimistic = playlist.copy(rows = rows, total = playlist.total + tracks.size)) {
            graph.playlists.addItems(uri, tracks.map(Track::uri))
        }
    }

    fun updateDetails(name: String, description: String) = mutate { playlist ->
        val newName = name.trim()
        val newDescription = description.trim()
        if (newName.isEmpty() || !playlist.meta.isOwnedByMe) return@mutate null
        val nameChanged = newName != playlist.meta.name
        val descriptionChanged = newDescription != playlist.description.text
        if (!nameChanged && !descriptionChanged) return@mutate null
        Mutation(
            optimistic = playlist.copy(
                meta = playlist.meta.copy(
                    name = newName,
                    description = if (descriptionChanged) newDescription else playlist.meta.description,
                ),
                description = if (descriptionChanged) RichText(newDescription) else playlist.description,
            ),
        ) {
            graph.playlists.updateDetails(
                uri,
                name = newName.takeIf { nameChanged },
                description = newDescription.takeIf { descriptionChanged },
            )
            null // details are not revisioned
        }
    }

    fun delete() {
        launchWrite(R.string.detail_playlist_deleted, R.string.detail_playlist_delete_failed) {
            graph.playlists.delete(uri)
            // Leaves the page if it is still showing.
            eventChannel.trySend(PlaylistEvent.Deleted)
        }
    }

    /**
     * Some rows came back as placeholders: re-fetch the loaded range (not while edits are pending)
     * and the windows on screen.
     */
    fun retryPartial() {
        resetWindows()
        viewModelScope.launch {
            mutationMutex.withLock { if (canApplyServerRows()) refreshLoaded(force = false) }
        }
    }

    fun setAddQuery(query: String) {
        addQuery.value = query
    }

    /** [apply] runs against the latest known revision and returns the new one (null if unknown). */
    private class Mutation(val optimistic: PlaylistData, val apply: suspend (revision: String?) -> String?)

    private class CoreState(
        val load: LoadState<PlaylistData>,
        val list: PlaylistListUi,
        val paging: PagingUi,
        val editMode: Boolean,
        val sort: TrackSort,
        val lastSorted: SortedPlays.Entry?,
        val listPlayback: ListPlayback,
    )

    private data class ListInput(val playlist: PlaylistData?, val query: String, val sort: TrackSort)

    private class RowExtras(
        val downloads: Map<String, DownloadState>,
        val connectivity: Connectivity,
        val playPending: Boolean,
        val windows: RowWindows<PlaylistRow>,
    )

    /** What a playlist's windows belong to (see init). */
    private data class WindowsOf(val revision: String?, val total: Int, val downloadedCopy: Boolean)

    private fun mutate(change: (PlaylistData) -> Mutation?) {
        val playlist = data.value.dataOrNull() ?: return
        // Positions of rows from the download do not match the server's.
        if (!playlist.meta.canEdit || playlist.downloadedCopy) return
        val mutation = change(playlist) ?: return
        data.value = LoadState.Ready(mutation.optimistic)
        pendingMutations++
        val queuedGeneration = generation
        graph.appScope.launch(Dispatchers.Main.immediate) {
            mutationMutex.withLock {
                if (queuedGeneration != generation) {
                    pendingMutations--
                    return@withLock
                }
                var failure: Exception? = null
                var revision: String? = null
                try {
                    revision = mutation.apply(data.value.dataOrNull()?.revision)
                    // Kept for the next queued mutation (no extra fetch, no stale overwrite).
                    if (revision != null) data.value.dataOrNull()?.let { data.value = LoadState.Ready(it.copy(revision = revision)) }
                    // Its push is this edit's: nothing to refresh for it.
                    if (revision != null) freshness.onPage(revision, fromServer = true)
                } catch (e: CancellationException) {
                    pendingMutations--
                    throw e
                } catch (e: Exception) {
                    failure = e
                    generation++
                }
                pendingMutations--
                if (failure != null) message(mutationFailureMessage(failure))
                // With pending edits a refresh would only fetch the revision we already have; the
                // rows are refreshed once the queue drains (or right away after a failure).
                val refresh = failure != null || revision == null || pendingMutations == 0
                if (refresh && !cleared) refreshLoaded(force = failure != null)
            }
        }
    }

    override fun onCleared() {
        cleared = true
        super.onCleared()
    }

    /**
     * Re-fetches the loaded range (always, to learn the new revision). Rows are replaced only when
     * [force]d (after a failure) or when no local edit is pending; otherwise only the revision is
     * taken over so the next queued mutation applies to the latest version.
     */
    private suspend fun refreshLoaded(force: Boolean): Boolean {
        val before = data.value.dataOrNull() ?: return false
        try {
            val applyRows = force || canApplyServerRows()
            // Only the revision is needed while local edits are pending.
            val range = fetchLoadedRange(if (applyRows) before.rows.size else 0, if (applyRows) PAGE_SIZE else 1) { offset, limit ->
                graph.catalog.playlistPage(uri, offset, limit)
            }
            val first = range.first
            val items = range.items
            val partial = range.partial
            val latest = data.value.dataOrNull() ?: return false
            if (applyRows && (force || canApplyServerRows())) {
                val description = withContext(Dispatchers.Default) { parseHtml(first.description.orEmpty()) }
                // Row keys come from the items' uids: the rows that stay keep theirs, so the list
                // keeps its place (the loaded range is fetched whole, it doesn't shrink).
                data.value = LoadState.Ready(
                    PlaylistData(first.copy(items = emptyList()), description, buildRows(items), first.total, first.revision, partial),
                )
                // Mutations queued against the replaced optimistic list are no longer valid.
                if (force) generation++
            } else {
                data.value = LoadState.Ready(latest.copy(revision = first.revision))
            }
            freshness.onPage(first.revision, fromServer = true)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the local state; the next mutation reports a conflict if the revision is stale.
            return false
        }
    }

    /**
     * The revision on screen when the page can be refreshed in place from the server ([freshness]):
     * the server's rows, settled (not loading or refreshing, not a stale copy, not the download),
     * no edit pending or being dragged. Otherwise null.
     */
    private fun refreshableRevision(): String? {
        val load = data.value as? LoadState.Ready ?: return null
        if (load.refreshing || load.stale || load.data.downloadedCopy || !canApplyServerRows()) return null
        return load.data.revision
    }

    /**
     * Changed elsewhere: the loaded range is fetched again and replaces the rows in place (no
     * loading state, the scroll position stays); the windows of the old revision go with it (see
     * init), and the cached page is marked stale so a reopened page revalidates. False when the
     * fetch failed. Queued edits refresh the rows themselves once they are done.
     */
    private suspend fun refreshFromServer(): Boolean = mutationMutex.withLock {
        if (cleared || !canApplyServerRows()) return@withLock true
        val current = data.value.dataOrNull() ?: return@withLock false
        if (current.downloadedCopy) return@withLock true
        graph.catalog.markPlaylistStale(uri)
        refreshLoaded(force = false)
    }

    private fun canApplyServerRows(): Boolean = pendingMutations == 0 && !dragging

    private fun mutationFailureMessage(error: Exception): Int = when {
        error is NativeException && error.isNetwork -> R.string.detail_playlist_update_offline
        error is NativeException && (
            error.code == CONFLICT_CODE ||
                error.code == NativeErrorCode.INVALID_ARGUMENT ||
                error.message?.contains("revision", ignoreCase = true) == true
            ) -> R.string.detail_playlist_conflict
        else -> R.string.detail_playlist_update_failed
    }

    private fun searchTracks(query: String): Flow<AddSongsUi> = flow {
        if (query.isBlank()) {
            emit(AddSongsUi(query = query))
            return@flow
        }
        emit(AddSongsUi(query = query, loading = true))
        val result = try {
            val tracks = graph.search.search(query, setOf(SearchType.TRACK), limit = SEARCH_LIMIT).tracks.distinctBy { it.uri }
            AddSongsUi(query = query, results = tracks)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AddSongsUi(query = query, failed = true)
        }
        emit(result)
    }

    private companion object {
        const val PAGE_SIZE = WINDOW_PAGE_SIZE
        /** The next page loads when the rows on screen come this close to the loaded end. */
        const val LOAD_AHEAD = 15
        const val MAX_PAGES = 200
        const val SEARCH_LIMIT = 30
        const val FILTER_DEBOUNCE_MS = 150L
        const val SEARCH_DEBOUNCE_MS = 300L
        /** Not in the documented code list yet; accepted if the engine adds it. */
        const val CONFLICT_CODE = "CONFLICT"
    }
}

/** A playlist's first rows fetched again ([fetchLoadedRange]): its first page and the items. */
internal class LoadedRange(val first: Playlist, val items: List<PlaylistItem>, val partial: Boolean)

/**
 * The first [loaded] rows of a playlist as the server has them now (at least its first page of
 * [pageSize]), in pages of [pageSize]: as many as the page shows, so a refresh doesn't shrink the
 * list under the user (a shorter playlist ends sooner). [loaded] 0 with [pageSize] 1 is the
 * revision alone.
 */
internal suspend fun fetchLoadedRange(loaded: Int, pageSize: Int, fetch: suspend (offset: Int, limit: Int) -> Playlist): LoadedRange {
    val first = fetch(0, pageSize)
    val items = first.items.toMutableList()
    var partial = first.partial
    val wanted = minOf(maxOf(pageSize, loaded), first.total)
    while (items.size < wanted) {
        val next = fetch(items.size, pageSize)
        if (next.items.isEmpty()) break
        items += next.items
        partial = partial || next.partial
    }
    return LoadedRange(first, items, partial)
}

/** The loaded rows begin with [items] (same items, same playable flags). */
private fun PlaylistData.startsWith(items: List<PlaylistItem>): Boolean {
    if (rows.size < items.size) return false
    return items.indices.all { i ->
        val row = rows[i].item
        val item = items[i]
        row.uri == item.uri && row.playableFlag == item.playableFlag
    }
}

private val PlaylistItem.playableFlag: Boolean? get() = track?.playable ?: episode?.playable
