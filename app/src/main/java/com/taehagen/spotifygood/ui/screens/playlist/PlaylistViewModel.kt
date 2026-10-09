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
import com.taehagen.spotifygood.ui.screens.library.sortedListUris
import com.taehagen.spotifygood.ui.screens.library.planSortedPlay
import com.taehagen.spotifygood.ui.screens.library.SortedStart
import com.taehagen.spotifygood.ui.screens.library.SortedPlays
import com.taehagen.spotifygood.ui.screens.library.sortedPlayRequest
import com.taehagen.spotifygood.ui.screens.library.sortOrder
import com.taehagen.spotifygood.ui.screens.library.sortKey
import com.taehagen.spotifygood.ui.screens.library.isSortedPlayback
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
    /** What plays is this sorted list (playing or paused), also when started before the page reopened. */
    val sortedListIsCurrent: Boolean = false,
)

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

    private val listUi: Flow<PlaylistListUi> = combine(
        data,
        filter.debounce { if (it.isBlank()) 0L else FILTER_DEBOUNCE_MS },
        sort,
        editMode,
    ) { load, query, sort, editing -> ListInput(load.dataOrNull(), query, if (editing) TrackSort.CUSTOM else sort) }
        .mapLatest { (playlist, query, sort) -> buildListUi(playlist, query, sort) }

    private val itemUris: Flow<Set<String>> = data
        .map { load -> load.dataOrNull()?.rows?.mapNotNullTo(HashSet()) { it.item.uri } ?: emptySet() }
        .distinctUntilChanged()

    private val core: Flow<CoreState> = combine(data, listUi, paging, editMode, combine(sort, SortedPlays.last, ::Pair)) { load, list, paging, editing, (sort, last) ->
        CoreState(load, list, paging, editing, sort, last)
    }

    val state: StateFlow<PlaylistUiState> = combine(
        core,
        playbackInfo,
        graph.savedFlow(uri),
        graph.downloads.collectionUi(uri),
        combine(graph.downloads.statesFor(itemUris), connectivity, ::Pair),
    ) { core, playback, following, download, (rows, connectivity) ->
        PlaylistUiState(
            core.load, core.list, core.paging, core.editMode, playback, following, download, rows,
            connectivity.offline, connectivity.online, connectivity.filterExplicit,
            sort = core.sort,
            sortedListIsCurrent = core.sort != TrackSort.CUSTOM && !core.editMode && isSortedPlayback(
                playback.trackUri,
                playback.contextUri,
                sortedListUris(ListSortStore.playlist(uri), core.lastSorted) { core.list.rows.mapNotNull { it.row.item.uri } },
            ),
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
        // Loaded rows carry the playable flags of the old explicit filter; the revision doesn't
        // change, so the live page alone wouldn't replace them.
        graph.explicitFilterChanges()
            .onEach {
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

    /** Every loaded row in [sort] order, with its playlist index. */
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
     * Plays the loaded rows in the shown order from [startUri] (else the first) as a track list: the
     * playlist context plays its own order ([sortedPlayRequest]). Sorted off the main thread.
     */
    private fun playSorted(startUri: String?) {
        val playlist = data.value.dataOrNull() ?: return
        val order = sort.value
        viewModelScope.launch {
            val uris = withContext(Dispatchers.Default) {
                sortedRows(playlist, order).mapNotNull { visible ->
                    val item = visible.row.item
                    val playable = item.track?.let { it.playable && !it.isPlaceholder } ?: item.episode?.let { it.playable && !it.isPlaceholder } ?: false
                    item.uri?.takeIf { playable && !it.startsWith("spotify:local:") }
                }
            }
            // Not ONLINE, a track list goes to the offline queue: planned like any plain list (the
            // reach as of now, after the sort).
            when (val plan = planSortedPlay(uris, startUri, graph.engineReach(), graph.downloads.downloadedUris.value)) {
                is SortedStart.Load -> {
                    SortedPlays.record(ListSortStore.playlist(uri), plan.request)
                    graph.player.play(plan.request)
                }
                SortedStart.NotDownloaded -> message(R.string.playback_error_not_available_offline)
                SortedStart.Nothing -> Unit
            }
        }
    }

    /** Play button: toggles the sorted list started here; sorted, plays it from the top. */
    override fun playContext() {
        if (!sorted) {
            super.playContext()
            return
        }
        // Also after the page was reopened (the last sorted play is kept outside it).
        if (state.value.sortedListIsCurrent) {
            graph.player.togglePlayPause()
        } else {
            playSorted(startUri = null)
        }
    }

    // -----------------------------------------------------------------------------------------
    // Playback, filter, library
    // -----------------------------------------------------------------------------------------

    fun playItem(row: VisibleRow) {
        val item = row.row.item
        // Placeholders (metadata failed) are not played; the row does not offer it either.
        if (item.track?.isPlaceholder == true || item.episode?.isPlaceholder == true) return
        if (sorted) {
            playSorted(item.uri)
            return
        }
        graph.player.play(
            PlayRequest(contextUri = uri, startUri = item.uri, startIndex = row.index, startUid = item.uid),
        )
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

    /** Some rows came back as placeholders: re-fetch the loaded range (not while edits are pending). */
    fun retryPartial() {
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
    )

    private data class ListInput(val playlist: PlaylistData?, val query: String, val sort: TrackSort)

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
    private suspend fun refreshLoaded(force: Boolean) {
        val before = data.value.dataOrNull() ?: return
        try {
            val applyRows = force || canApplyServerRows()
            // Only the revision is needed while local edits are pending.
            val first = graph.catalog.playlistPage(uri, 0, if (applyRows) PAGE_SIZE else 1)
            val items = first.items.toMutableList()
            var partial = first.partial
            val wanted = minOf(maxOf(PAGE_SIZE, before.rows.size), first.total)
            if (applyRows) {
                while (items.size < wanted) {
                    val next = graph.catalog.playlistPage(uri, items.size, PAGE_SIZE)
                    if (next.items.isEmpty()) break
                    items += next.items
                    partial = partial || next.partial
                }
            }
            val latest = data.value.dataOrNull() ?: return
            if (applyRows && (force || canApplyServerRows())) {
                val description = withContext(Dispatchers.Default) { parseHtml(first.description.orEmpty()) }
                data.value = LoadState.Ready(
                    PlaylistData(first.copy(items = emptyList()), description, buildRows(items), first.total, first.revision, partial),
                )
                // Mutations queued against the replaced optimistic list are no longer valid.
                if (force) generation++
            } else {
                data.value = LoadState.Ready(latest.copy(revision = first.revision))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the local state; the next mutation reports a conflict if the revision is stale.
        }
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
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 200
        const val SEARCH_LIMIT = 30
        const val FILTER_DEBOUNCE_MS = 150L
        const val SEARCH_DEBOUNCE_MS = 300L
        /** Not in the documented code list yet; accepted if the engine adds it. */
        const val CONFLICT_CODE = "CONFLICT"
    }
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
