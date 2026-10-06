package com.taehagen.spotifygood.ui.screens.search

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.RecentSearch
import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.library.BrowseError
import com.taehagen.spotifygood.ui.screens.library.NowPlaying
import com.taehagen.spotifygood.ui.screens.library.PageResult
import com.taehagen.spotifygood.ui.screens.library.PagedLoader
import com.taehagen.spotifygood.ui.screens.library.PagedState
import com.taehagen.spotifygood.ui.screens.library.attempt
import com.taehagen.spotifygood.ui.screens.library.debouncedInput
import com.taehagen.spotifygood.ui.screens.library.nowPlayingFlow
import com.taehagen.spotifygood.ui.screens.library.offlineFlow
import com.taehagen.spotifygood.ui.screens.library.playTrackInAlbum
import com.taehagen.spotifygood.ui.screens.library.toBrowseError
import com.taehagen.spotifygood.ui.screens.library.toMediaRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class SearchUiState(
    /** The (debounced) query the results belong to. */
    val query: String = "",
    val filter: SearchFilter = SearchFilter.TOP,
    val offline: Boolean = false,
    val recent: List<RecentSearch> = emptyList(),
    val top: TopResultsState = TopResultsState.Idle,
    /** Paged results of the selected type filter (null for Top or without a query). */
    val typed: PagedState<SearchItem>? = null,
    val nowPlaying: NowPlaying = NowPlaying(),
    val myUsername: String? = null,
)

private data class SearchInput(val query: String, val filter: SearchFilter, val retry: Int, val offline: Boolean)

class SearchViewModel(private val graph: AppGraph) : ViewModel() {
    /** Text field content: Compose state, so typing never races the UI (debounced below). */
    var queryText by mutableStateOf("")
        private set

    private val filter = MutableStateFlow(SearchFilter.TOP)
    private val retry = MutableStateFlow(0)
    /** Queries that skip the debounce (IME action, recent search or browse tile). */
    private val immediate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val cache = SearchCache<SearchResults>()

    @Volatile private var typedLoader: PagedLoader<SearchItem>? = null
    @Volatile private var lastReady: TopResultsState.Ready? = null

    private val query: Flow<String> = merge(
        snapshotFlow { queryText }.debouncedInput(DEBOUNCE_MS),
        immediate.map { it.trim() },
    ).distinctUntilChanged()

    private val input: Flow<SearchInput> =
        combine(query, filter, retry, graph.offlineFlow(), ::SearchInput).distinctUntilChanged()

    private val top: Flow<TopResultsState> = input.flatMapLatest { input ->
        when {
            input.query.isEmpty() || input.filter != SearchFilter.TOP -> flowOf(TopResultsState.Idle)
            input.offline -> flowOf(TopResultsState.Failed(input.query, BrowseError.OFFLINE))
            else -> topResults(input.query)
        }
    }.onEach { state ->
        lastReady = when (state) {
            is TopResultsState.Ready -> state.copy(isRefreshing = false)
            is TopResultsState.Loading -> lastReady
            else -> null
        }
    }

    private val typed: Flow<PagedState<SearchItem>?> = input.flatMapLatest { input ->
        val type = input.filter.type
        if (input.query.isEmpty() || type == null || input.offline) {
            typedLoader = null
            flowOf(null)
        } else {
            typedResults(input.query, type)
        }
    }

    private val recent: Flow<List<RecentSearch>> = graph.search.recent
        .catch { emit(emptyList()) }
        .onStart { emit(emptyList()) }
        .distinctUntilChanged()

    val state: StateFlow<SearchUiState> = combine(
        input,
        combine(top, typed, ::Pair),
        recent,
        graph.nowPlayingFlow(),
        graph.engine.user,
    ) { input, (top, typed), recent, nowPlaying, user ->
        SearchUiState(
            query = input.query,
            filter = input.filter,
            offline = input.offline,
            recent = recent,
            top = top,
            typed = typed,
            nowPlaying = nowPlaying,
            myUsername = user?.username,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    private fun topResults(query: String): Flow<TopResultsState> = flow {
        val key = query.lowercase()
        cache[key]?.let { cached ->
            emit(readyOrEmpty(query, cached))
            return@flow
        }
        emit(lastReady?.copy(isRefreshing = true) ?: TopResultsState.Loading)
        attempt { graph.search.search(query).distinct() }
            .onSuccess { results ->
                cache[key] = results
                emit(readyOrEmpty(query, results))
            }
            .onFailure { emit(TopResultsState.Failed(query, it.toBrowseError())) }
    }

    private fun readyOrEmpty(query: String, results: SearchResults): TopResultsState =
        if (results.isEmpty && results.topResult == null) {
            TopResultsState.Empty(query)
        } else {
            TopResultsState.Ready(query, results.toTopSections())
        }

    private fun typedResults(query: String, type: SearchType): Flow<PagedState<SearchItem>> = channelFlow {
        // The loader lives in this producer scope: a newer query/filter cancels its requests.
        val loader = PagedLoader(this, TYPED_PAGE_SIZE, SearchItem::key) { offset, limit ->
            PageResult(graph.search.search(query, setOf(type), offset, limit).itemsOf(type))
        }
        typedLoader = loader
        loader.loadMore()
        loader.state.collect { send(it) }
    }

    fun onQueryChange(text: String) {
        queryText = text
        if (text.isBlank()) filter.value = SearchFilter.TOP
    }

    fun clearQuery() = onQueryChange("")

    /** IME search action: search now and remember the query. */
    fun submit() {
        val query = queryText.trim()
        if (query.isEmpty()) return
        immediate.tryEmit(query)
        saveRecent(RecentSearch.Query(query))
    }

    /** Runs [text] right away (recent query, browse tile). */
    fun runQuery(text: String, filter: SearchFilter = SearchFilter.TOP) {
        queryText = text
        this.filter.value = filter
        immediate.tryEmit(text)
    }

    fun selectFilter(value: SearchFilter) {
        filter.value = if (filter.value == value && value != SearchFilter.TOP) SearchFilter.TOP else value
    }

    fun retry() = retry.update { it + 1 }

    fun loadMore() {
        typedLoader?.loadMore()
    }

    /** A result was opened: remember it. */
    fun onOpened(ref: MediaRef) = saveRecent(RecentSearch.Item(ref))

    fun playTrack(track: Track) {
        onOpened(track.toMediaRef())
        graph.playTrackInAlbum(track)
    }

    /** Plays a top-result reference (play button on the top result card). */
    fun playTop(ref: MediaRef, sections: TopSections) {
        onOpened(ref)
        when (ref.type) {
            MediaType.TRACK -> (sections.byUri[ref.uri] as? SearchItem.Song)?.let { graph.playTrackInAlbum(it.track) }
                ?: graph.player.playTracks(listOf(ref.uri))
            MediaType.EPISODE -> graph.player.playTracks(listOf(ref.uri))
            else -> graph.player.playContext(ref.uri)
        }
    }

    fun openRecent(item: RecentSearch) {
        when (item) {
            is RecentSearch.Query -> runQuery(item.query)
            is RecentSearch.Item -> Unit
        }
        saveRecent(item)
    }

    fun removeRecent(item: RecentSearch) {
        viewModelScope.launch { attempt { graph.search.removeRecent(item) } }
    }

    fun clearRecent() {
        viewModelScope.launch { attempt { graph.search.clearRecent() } }
    }

    private fun saveRecent(item: RecentSearch) {
        viewModelScope.launch { attempt { graph.search.addRecent(item) } }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
        const val TYPED_PAGE_SIZE = 30
    }
}

@Immutable
data class SearchResultsUiState(
    val type: SearchType = SearchType.TRACK,
    val paged: PagedState<SearchItem> = PagedState(isLoading = true),
    val offline: Boolean = false,
    val nowPlaying: NowPlaying = NowPlaying(),
    val myUsername: String? = null,
)

/** All results of one type for a query, paged on scroll. */
class SearchResultsViewModel(private val graph: AppGraph, private val query: String, typeWire: String) : ViewModel() {
    private val type: SearchType = searchTypeOf(typeWire) ?: SearchType.TRACK

    private val loader = PagedLoader(viewModelScope, PAGE_SIZE, SearchItem::key) { offset, limit ->
        PageResult(graph.search.search(query, setOf(type), offset, limit).itemsOf(type))
    }

    private val offline = graph.offlineFlow()

    val state: StateFlow<SearchResultsUiState> = combine(
        loader.state,
        offline,
        graph.nowPlayingFlow(),
        graph.engine.user,
    ) { paged, offline, nowPlaying, user ->
        SearchResultsUiState(type, paged, offline, nowPlaying, user?.username)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResultsUiState(type))

    init {
        // First page as soon as we are online (and again after coming back online with nothing loaded).
        viewModelScope.launch {
            offline.collect { isOffline ->
                if (!isOffline && loader.state.value.items.isEmpty()) loader.loadMore()
            }
        }
    }

    fun loadMore() = loader.loadMore()

    fun retry() = loader.loadMore()

    fun onOpened(ref: MediaRef) {
        viewModelScope.launch { attempt { graph.search.addRecent(RecentSearch.Item(ref)) } }
    }

    fun playTrack(track: Track) {
        onOpened(track.toMediaRef())
        graph.playTrackInAlbum(track)
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
