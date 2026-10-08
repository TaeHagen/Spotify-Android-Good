package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.data.settings.LibraryView
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.SavedAlbum
import com.taehagen.spotifygood.model.SavedArtist
import com.taehagen.spotifygood.model.SavedShow
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.ui.screens.album.engineReach
import com.taehagen.spotifygood.ui.screens.album.engineReachFlow
import com.taehagen.spotifygood.ui.screens.album.resourceOnceConnected
import com.taehagen.spotifygood.ui.screens.album.retryWhenOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class LibraryUiState(
    val user: User? = null,
    val filter: LibraryFilter? = null,
    val sort: LibrarySort = LibrarySort.RECENT,
    val view: LibraryView = LibraryView.LIST,
    val offline: Boolean = false,
    val searchActive: Boolean = false,
    /** Debounced in-library search text. */
    val query: String = "",
    /** Open folder chain, outermost first. */
    val folders: List<LibraryItem> = emptyList(),
    val showEpisodes: Boolean = false,
    val pinned: List<PinnedEntry> = emptyList(),
    val items: List<LibraryItem> = emptyList(),
    val downloaded: Set<String> = emptySet(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: BrowseError? = null,
    val episodes: PagedState<Episode> = PagedState(),
    /** Some loaded Your Episodes pages hold placeholders: offer a retry. */
    val episodesPartial: Boolean = false,
    val nowPlaying: NowPlaying = NowPlaying(),
)

private data class LibrarySources(
    val playlists: Resource<Rootlist>,
    val albums: Resource<List<SavedAlbum>>,
    val artists: Resource<List<SavedArtist>>,
    val shows: Resource<List<SavedShow>>,
)

private data class LibraryData(
    val playlists: List<LibraryItem> = emptyList(),
    val albums: List<LibraryItem> = emptyList(),
    val artists: List<LibraryItem> = emptyList(),
    val shows: List<LibraryItem> = emptyList(),
    val isInitialLoading: Boolean = true,
    val error: Throwable? = null,
    /** A list is still loading or refreshing. */
    val loading: Boolean = true,
)

private data class Presentation(
    val query: LibraryQuery,
    val view: LibraryView,
    val searchActive: Boolean,
    val showEpisodes: Boolean,
)

private data class ListingResult(
    val listing: LibraryListing,
    val data: LibraryData,
    val presentation: Presentation,
    val downloaded: Set<String>,
    val collections: List<DownloadedCollection>,
)

/**
 * Whether Liked Songs is downloaded. From the downloaded collections themselves: the user (its
 * URI) is known only after an online session, so a cold start offline would hide it. Logout wipes
 * downloads, so a Liked Songs download is always this account's.
 */
internal fun likedSongsDownloaded(collections: List<DownloadedCollection>, username: String?): Boolean =
    collections.any { it.type == CollectionType.LIKED_SONGS } ||
        (username != null && collections.any { it.uri == likedSongsUri(username) })

private data class Extras(
    val likedCount: Int?,
    val downloadCount: Int,
    val user: User?,
    val refreshing: Boolean,
    val nowPlaying: NowPlaying,
)

@OptIn(FlowPreview::class)
class LibraryViewModel(private val graph: AppGraph) : ViewModel() {
    /** In-library search field (Compose state; filtered after a short debounce). */
    var searchText by mutableStateOf("")
        private set

    private val filter = MutableStateFlow<LibraryFilter?>(null)
    private val folderPath = MutableStateFlow<List<String>>(emptyList())
    private val searchActive = MutableStateFlow(false)
    private val showEpisodes = MutableStateFlow(false)
    private val reload = MutableStateFlow(0)
    private val refreshing = MutableStateFlow(false)
    private val recentRank = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val likedCount = MutableStateFlow<Int?>(null)
    private val offline = graph.offlineFlow()

    private val episodePartialPages = PartialPages()
    private val episodesLoader = PagedLoader(viewModelScope, EPISODE_PAGE, Episode::uri) { offset, limit ->
        val page = graph.library.episodes(offset, limit)
        episodePartialPages.record(offset, page.partial)
        PageResult(page.items.map { it.episode }, page.total)
    }

    /** The last [data], for the reload once the session is ONLINE (see init). */
    private val lastData = MutableStateFlow<LibraryData?>(null)

    // The library flows are live (they re-emit after edits); [reload] only re-subscribes after a
    // pull-to-refresh, a retry, or the session coming ONLINE after a list failed (see init). Opened
    // while the session connects, the lists wait for it (bounded) instead of failing NOT_CONNECTED.
    private val data: Flow<LibraryData> = reload
        .flatMapLatest {
            combine(
                graph.resourceOnceConnected { graph.library.playlists() },
                graph.resourceOnceConnected { graph.library.albums() },
                graph.resourceOnceConnected { graph.library.artists() },
                graph.resourceOnceConnected { graph.library.shows() },
                ::LibrarySources,
            )
        }
        .map { it.toData() }
        .flowOn(Dispatchers.Default)
        .catch { emit(LibraryData(isInitialLoading = false, error = it, loading = false)) }
        .onStart { emit(LibraryData()) }
        .onEach { lastData.value = it }

    /** Every downloaded collection: downloads need not be saved in the library. */
    private val downloadedCollections: Flow<List<DownloadedCollection>> = graph.downloadedCollectionsFlow()
        .catch { emit(emptyList()) }
        .onStart { emit(emptyList()) }
        .distinctUntilChanged()

    private val presentation: Flow<Presentation> = combine(
        combine(filter, folderPath, snapshotFlow { searchText }.debouncedInput(SEARCH_DEBOUNCE_MS).onStart { emit("") }, ::Triple),
        graph.settings.settings,
        offline,
        searchActive,
        showEpisodes,
    ) { (filter, path, text), settings, offline, searchActive, showEpisodes ->
        Presentation(
            query = LibraryQuery(filter, path, if (searchActive) text else "", settings.librarySort, offline),
            view = settings.libraryView,
            searchActive = searchActive,
            showEpisodes = showEpisodes && !offline,
        )
    }.distinctUntilChanged()

    /** Listing plus the inputs it was built from, so the UI never mixes old items with new chips. */
    private val listing: Flow<ListingResult> = combine(data, presentation, downloadedCollections, recentRank) { data, presentation, collections, ranks ->
        val downloaded = collections.mapTo(HashSet()) { it.uri }
        ListingResult(
            listing = buildLibraryListing(
                playlists = data.playlists,
                albums = data.albums,
                artists = data.artists,
                shows = data.shows,
                query = presentation.query,
                downloaded = downloaded,
                downloadedItems = collections.mapNotNull { it.toLibraryItem() },
                recentRank = ranks,
            ),
            data = data,
            presentation = presentation,
            downloaded = downloaded,
            collections = collections,
        )
    }.flowOn(Dispatchers.Default)

    private val extras: Flow<Extras> = combine(
        likedCount,
        graph.downloads.downloadedUris.map { it.size }.distinctUntilChanged(),
        graph.engine.user,
        refreshing,
        graph.nowPlayingFlow(),
        ::Extras,
    )

    /** Saved episodes with this phone's podcast progress where it is newer than Spotify's (docs §6.5). */
    private val episodes: Flow<PagedState<Episode>> = combine(episodesLoader.state, graph.episodeProgress.version) { page, _ ->
        page.copy(items = page.items.map(graph.episodeProgress::overlay))
    }

    val state: StateFlow<LibraryUiState> = combine(
        listing,
        extras,
        episodes,
        episodePartialPages.partial,
    ) { (listing, data, presentation, downloaded, collections), extras, episodes, episodesPartial ->
        val nothing = data.playlists.isEmpty() && data.albums.isEmpty() && data.artists.isEmpty() && data.shows.isEmpty()
        LibraryUiState(
            user = extras.user,
            filter = presentation.query.filter,
            sort = presentation.query.sort,
            view = presentation.view,
            offline = presentation.query.offline,
            searchActive = presentation.searchActive,
            query = presentation.query.text,
            folders = listing.folders,
            showEpisodes = presentation.showEpisodes,
            pinned = if (presentation.showEpisodes) {
                emptyList()
            } else {
                pinnedEntries(
                    query = presentation.query,
                    likedCount = extras.likedCount,
                    likedDownloaded = likedSongsDownloaded(collections, extras.user?.username),
                    downloadCount = extras.downloadCount,
                )
            },
            items = listing.items,
            downloaded = downloaded,
            isInitialLoading = data.isInitialLoading && !presentation.query.offline,
            isRefreshing = extras.refreshing,
            error = if (nothing && !presentation.query.offline) data.error?.toBrowseError() else null,
            episodes = episodes,
            episodesPartial = episodesPartial,
            nowPlaying = extras.nowPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Liked Songs count and recents ranking: once the session is ONLINE (a request made while
        // it connects fails NOT_CONNECTED), and again after reconnecting if they are still missing.
        graph.engineReachFlow()
            .filter { it == EngineReach.ONLINE }
            .onEach { if (likedCount.value == null || recentRank.value.isEmpty()) loadExtras() }
            .launchIn(viewModelScope)
        // The lists: again once the session is ONLINE when one failed or only a stale copy came
        // back (its refresh failed, e.g. NOT_CONNECTED while the session connected).
        viewModelScope.launch {
            retryWhenOnline(
                graph.engineReachFlow(),
                settled = { lastData.first { it != null && !it.loading } },
                needsRetry = { it.error != null },
                retry = { reload.update { it + 1 } },
            )
        }
        // Liked Songs and saved episodes are paged and not cached: refetch after library edits.
        graph.library.changes.debounce(CHANGE_DEBOUNCE_MS)
            .onEach {
                attempt { graph.library.likedTracks(0, 1) }.onSuccess { likedCount.value = it.total }
                if (episodesLoader.state.value.items.isNotEmpty() || showEpisodes.value) episodesLoader.reload()
            }
            .catch { }
            .launchIn(viewModelScope)
    }

    private suspend fun loadExtras() {
        if (graph.engineReach() != EngineReach.ONLINE) return
        attempt { graph.library.likedTracks(0, 1) }.onSuccess { likedCount.value = it.total }
        attempt { graph.catalog.recentlyPlayed(RECENTS_LIMIT) }.onSuccess { recentRank.value = recentRanks(it) }
    }

    fun selectFilter(value: LibraryFilter) {
        filter.update { if (it == value) null else value }
        folderPath.value = emptyList()
        showEpisodes.value = false
    }

    fun clearFilter() {
        filter.value = null
        folderPath.value = emptyList()
        showEpisodes.value = false
    }

    fun openFolder(folder: LibraryItem) {
        folderPath.update { it + folder.id }
    }

    /** Back from the innermost folder / episodes; returns false when nothing was open. */
    fun navigateUp(): Boolean = when {
        showEpisodes.value -> {
            showEpisodes.value = false
            true
        }
        folderPath.value.isNotEmpty() -> {
            folderPath.update { it.dropLast(1) }
            true
        }
        searchActive.value -> {
            setSearchActive(false)
            true
        }
        else -> false
    }

    fun openEpisodes() {
        showEpisodes.value = true
        val current = episodesLoader.state.value
        if (current.items.isEmpty() || current.error != null) episodesLoader.loadMore()
    }

    fun loadMoreEpisodes() = episodesLoader.loadMore()

    /** Your Episodes came back with placeholders: load it again from the start. */
    fun retryEpisodes() = episodesLoader.reload()

    fun setSearchActive(active: Boolean) {
        searchActive.value = active
        if (!active) searchText = ""
    }

    fun onSearchTextChange(text: String) {
        searchText = text
    }

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { attempt { graph.settings.update { it.copy(librarySort = sort) } } }
    }

    fun toggleView() {
        viewModelScope.launch {
            attempt {
                graph.settings.update {
                    it.copy(libraryView = if (it.libraryView == LibraryView.LIST) LibraryView.GRID else LibraryView.LIST)
                }
            }
        }
    }

    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                // The refresh drops the cached lists first: only when the server can answer.
                if (graph.engineReach() == EngineReach.ONLINE) attempt { graph.library.refresh() }
                reload.update { it + 1 }
                loadExtras()
                if (showEpisodes.value) episodesLoader.reload()
            } finally {
                refreshing.value = false
            }
        }
    }

    fun retry() {
        reload.update { it + 1 }
        viewModelScope.launch { loadExtras() }
    }

    /** Download indicator of a collection row. */
    fun downloadState(uri: String): Flow<DownloadState?> =
        graph.downloads.collectionStatus(uri).map { it.toIndicatorState() }.catch { emit(null) }.distinctUntilChanged()

    fun playEpisode(episode: Episode) {
        val episodes = episodesLoader.state.value.items
        val index = episodes.indexOfFirst { it.uri == episode.uri }
        if (index >= 0) graph.player.playTracks(episodes.map { it.uri }, index) else graph.player.playTracks(listOf(episode.uri))
    }

    private fun LibrarySources.toData(): LibraryData {
        val resources = listOf(playlists, albums, artists, shows)
        val noneCached = resources.all { it.dataOrNull == null }
        return LibraryData(
            playlists = playlists.dataOrNull?.toLibraryItems().orEmpty(),
            albums = albums.dataOrNull?.albumItems().orEmpty(),
            artists = artists.dataOrNull?.artistItems().orEmpty(),
            shows = shows.dataOrNull?.showItems().orEmpty(),
            isInitialLoading = noneCached && resources.any { it is Resource.Loading },
            error = resources.firstNotNullOfOrNull { (it as? Resource.Error)?.error },
            loading = resources.any { it is Resource.Loading },
        )
    }

    private companion object {
        const val EPISODE_PAGE = 50
        const val RECENTS_LIMIT = 50
        const val SEARCH_DEBOUNCE_MS = 150L
        const val CHANGE_DEBOUNCE_MS = 500L
    }
}
