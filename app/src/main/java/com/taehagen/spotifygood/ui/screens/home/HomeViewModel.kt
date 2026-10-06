package com.taehagen.spotifygood.ui.screens.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.HomeFeed
import com.taehagen.spotifygood.model.HomeSection
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.ui.screens.library.BrowseError
import com.taehagen.spotifygood.ui.screens.library.DownloadedCollection
import com.taehagen.spotifygood.ui.screens.library.attempt
import com.taehagen.spotifygood.ui.screens.library.downloadedCollectionsFlow
import com.taehagen.spotifygood.ui.screens.library.offlineFlow
import com.taehagen.spotifygood.ui.screens.library.toBrowseError
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import java.time.LocalTime

@Immutable
data class HomeUiState(
    val greeting: Greeting = Greeting.MORNING,
    val user: User? = null,
    val filter: HomeFilter = HomeFilter.ALL,
    val offline: Boolean = false,
    val quickAccess: List<QuickAccessItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
    /** First load without anything cached: show skeletons. */
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /** Nothing to show because loading failed. */
    val error: BrowseError? = null,
    /** Cached content is shown but the latest refresh failed. */
    val refreshFailed: Boolean = false,
    /** Offline and nothing downloaded. */
    val nothingDownloaded: Boolean = false,
)

private data class Reload(val generation: Int = 0, val force: Boolean = false)

/** Feed-related inputs combined before the presentation inputs. */
private data class HomeContent(
    val offline: Boolean,
    val feed: Resource<HomeFeed>?,
    val recent: List<MediaRef>,
    val downloaded: List<DownloadedCollection>,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val filter = MutableStateFlow(HomeFilter.ALL)
    private val reload = MutableStateFlow(Reload())
    private val userRefreshing = MutableStateFlow(false)
    private val offline = graph.offlineFlow()

    @Volatile private var recentCache: List<MediaRef> = emptyList()
    @Volatile private var lastFeedFailed = false

    private val feed: Flow<Resource<HomeFeed>?> = combine(reload, offline, ::Pair)
        .flatMapLatest { (request, isOffline) ->
            if (isOffline) flowOf(null) else graph.home.home(force = request.force)
        }
        .onEach { resource ->
            lastFeedFailed = resource is Resource.Error
            if (resource !is Resource.Loading) userRefreshing.value = false
        }
        .onStart { emit(null) }

    private val recent: Flow<List<MediaRef>> = combine(reload, offline, ::Pair)
        .transformLatest { (_, isOffline) ->
            emit(recentCache)
            if (!isOffline) {
                attempt { graph.catalog.recentlyPlayed(RECENT_LIMIT) }.onSuccess {
                    recentCache = it
                    emit(it)
                }
            }
        }
        .distinctUntilChanged()

    private val downloaded: Flow<List<DownloadedCollection>> = offline.flatMapLatest { isOffline ->
        if (isOffline) graph.downloadedCollectionsFlow() else flowOf(emptyList())
    }

    private val greeting: Flow<Greeting> = flow {
        while (true) {
            val now = LocalTime.now()
            emit(greetingForHour(now.hour))
            // Wake up once at the next full hour (only while the screen is visible).
            delay(((60 - now.minute) * 60L - now.second) * 1000L)
        }
    }.distinctUntilChanged()

    private val content: Flow<HomeContent> = combine(offline, feed, recent, downloaded, ::HomeContent)

    val state: StateFlow<HomeUiState> = combine(
        content,
        filter,
        greeting,
        graph.engine.user,
        userRefreshing,
    ) { content, filter, greeting, user, refreshing ->
        buildState(content, filter, greeting, user, refreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        // The session may come online after the first attempt failed: retry quietly.
        graph.engine.isOnline.drop(1).filter { it }
            .onEach { if (lastFeedFailed || recentCache.isEmpty()) reload.update { it.copy(generation = it.generation + 1) } }
            .launchIn(viewModelScope)
    }

    fun selectFilter(value: HomeFilter) {
        filter.value = if (filter.value == value) HomeFilter.ALL else value
    }

    /** Pull to refresh: bypasses the feed's cache age. */
    fun refresh() {
        userRefreshing.value = true
        reload.update { Reload(it.generation + 1, force = true) }
    }

    fun retry() {
        reload.update { Reload(it.generation + 1, force = false) }
    }

    private fun buildState(
        content: HomeContent,
        filter: HomeFilter,
        greeting: Greeting,
        user: User?,
        refreshing: Boolean,
    ): HomeUiState {
        if (content.offline) {
            val likedDownloaded = content.downloaded.any { it.type == CollectionType.LIKED_SONGS }
            val refs = content.downloaded.filter { it.type != CollectionType.LIKED_SONGS }.map { it.toMediaRef() }
            val sections = filterSections(
                listOf(HomeSection(DOWNLOADED_SECTION_ID, "", content.downloaded.map { it.toMediaRef() })),
                filter,
            )
            return HomeUiState(
                greeting = greeting,
                user = user,
                filter = filter,
                offline = true,
                quickAccess = buildQuickAccess(refs, emptyList(), filter, includeLikedSongs = likedDownloaded),
                sections = sections,
                isInitialLoading = false,
                isRefreshing = false,
                nothingDownloaded = content.downloaded.isEmpty(),
            )
        }
        val resource = content.feed
        val feedSections = resource?.dataOrNull?.sections.orEmpty()
        val sections = assembleSections(feedSections, content.recent, filter)
        val hasData = feedSections.isNotEmpty() || content.recent.isNotEmpty()
        val waiting = resource == null || resource is Resource.Loading
        val error = (resource as? Resource.Error)?.error
        return HomeUiState(
            greeting = greeting,
            user = user,
            filter = filter,
            offline = false,
            quickAccess = buildQuickAccess(
                recent = content.recent,
                fallback = feedSections.flatMap { it.items },
                filter = filter,
                includeLikedSongs = true,
            ),
            sections = sections,
            isInitialLoading = waiting && !hasData,
            isRefreshing = refreshing,
            error = if (error != null && !hasData) error.toBrowseError() else null,
            refreshFailed = error != null && hasData,
        )
    }

    private companion object {
        const val RECENT_LIMIT = 20
    }
}
