package com.taehagen.spotifygood.ui.screens.show

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.data.dataOrNull
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Show
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.album.CollectionDownloadUi
import com.taehagen.spotifygood.ui.screens.album.DetailViewModel
import com.taehagen.spotifygood.ui.screens.album.DownloadedPage
import com.taehagen.spotifygood.ui.screens.album.appendDownloadedEpisodes
import com.taehagen.spotifygood.ui.screens.album.downloadedPageFlow
import com.taehagen.spotifygood.ui.screens.album.FailureReason
import com.taehagen.spotifygood.ui.screens.album.LoadState
import com.taehagen.spotifygood.ui.screens.album.PlaybackInfo
import com.taehagen.spotifygood.ui.screens.album.RichText
import com.taehagen.spotifygood.ui.screens.album.collectionUi
import com.taehagen.spotifygood.ui.screens.album.dataOrNull
import com.taehagen.spotifygood.ui.screens.album.failureReason
import com.taehagen.spotifygood.ui.screens.album.oldestFirstPage
import com.taehagen.spotifygood.ui.screens.album.parseHtml
import com.taehagen.spotifygood.ui.screens.album.savedFlow
import com.taehagen.spotifygood.ui.screens.album.statesFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where to resume an episode (0 when fully played or never started). */
internal fun Episode.resumePosition(): Long {
    if (fullyPlayed == true) return 0
    val position = (resumePositionMs ?: 0).coerceAtLeast(0)
    return if (durationMs > 0) position.coerceAtMost(durationMs) else position
}

internal enum class EpisodeSort { NEWEST, OLDEST }

@Immutable
internal data class ShowHeader(
    val show: Show,
    val description: RichText,
    /** Built from the download (offline without a cached page). */
    val downloadedCopy: Boolean = false,
)

@Immutable
internal data class EpisodePage(
    val sort: EpisodeSort = EpisodeSort.NEWEST,
    val episodes: List<Episode> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
    val endReached: Boolean = false,
    /** A page loaded after the show itself came back partial (placeholder episodes). */
    val partial: Boolean = false,
    /** Episodes past the cached ones were taken from the download (offline). */
    val fromDownloads: Boolean = false,
)

@Immutable
internal data class ShowUiState(
    val load: LoadState<ShowHeader> = LoadState.Loading,
    val list: EpisodePage = EpisodePage(),
    val playback: PlaybackInfo = PlaybackInfo(),
    /** Null until known. */
    val following: Boolean? = null,
    val download: CollectionDownloadUi = CollectionDownloadUi(),
    val rowDownloads: Map<String, DownloadState> = emptyMap(),
    val offline: Boolean = false,
    /** Some listed episodes are placeholders (metadata failed right now): offer a retry. */
    val partial: Boolean = false,
    /** Showing the download (offline): the header or part of the list comes from it. */
    val downloadedCopy: Boolean = false,
)

internal class ShowViewModel(graph: AppGraph, private val uri: String) : DetailViewModel(graph, uri) {

    private val header = MutableStateFlow<LoadState<ShowHeader>>(LoadState.Loading)
    private val list = MutableStateFlow(EpisodePage())
    private var firstPage: List<Episode> = emptyList()
    private var total = 0
    private var loadJob: Job? = null

    private val episodeUris: Flow<Set<String>> = list
        .map { page -> page.episodes.mapTo(HashSet()) { it.uri } }
        .distinctUntilChanged()

    val state: StateFlow<ShowUiState> = combine(
        combine(header, list, ::Pair),
        playbackInfo,
        graph.savedFlow(uri),
        graph.downloads.collectionUi(uri),
        combine(graph.downloads.statesFor(episodeUris), offline, ::Pair),
    ) { (load, page), playback, following, download, (rows, offline) ->
        // The show's own first page is listed only in newest-first order.
        val partial = page.partial || (page.sort == EpisodeSort.NEWEST && load.dataOrNull()?.show?.partial == true)
        val downloadedCopy = load.dataOrNull()?.downloadedCopy == true || page.fromDownloads
        ShowUiState(load, page, playback, following, download, rows, offline, partial, downloadedCopy)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShowUiState())

    init {
        viewModelScope.launch {
            retryTrigger.collectLatest {
                graph.catalog.show(uri)
                    .catch { emit(Resource.Error(it)) }
                    .collect { onShow(it) }
            }
        }
    }

    private suspend fun onShow(resource: Resource<Show>) {
        val show = resource.dataOrNull
        if (show == null) {
            if (header.value !is LoadState.Ready) {
                // No page and no cached copy (offline, cache cleared or pruned): a downloaded show
                // still opens, from the download database.
                if (resource is Resource.Error && showDownloadedCopy()) return
                header.value = if (resource is Resource.Error) LoadState.Failed(failureReason(resource.error)) else LoadState.Loading
            }
            return
        }
        val description = withContext(Dispatchers.Default) { parseHtml(show.description) }
        total = show.total
        firstPage = show.episodes.distinctBy { it.uri }
        header.value = LoadState.Ready(
            ShowHeader(show.copy(episodes = emptyList()), description),
            refreshing = resource is Resource.Loading,
            stale = resource is Resource.Error,
        )
        val current = list.value
        when {
            current.sort == EpisodeSort.NEWEST -> {
                val merged = mergeNewest(firstPage, current.episodes)
                list.value = current.copy(episodes = merged, endReached = firstPage.isEmpty() || merged.size >= total)
            }
            current.episodes.isEmpty() -> loadMore()
        }
    }

    fun loadMore() {
        val current = list.value
        if (loadJob?.isActive == true || current.endReached || header.value !is LoadState.Ready) return
        val sort = current.sort
        loadJob = viewModelScope.launch {
            list.update { it.copy(loading = true, failed = false) }
            try {
                var pagePartial = false
                val page: List<Episode> = when (sort) {
                    EpisodeSort.NEWEST -> graph.catalog.showPage(uri, current.episodes.size, PAGE_SIZE)
                        .also {
                            total = it.total
                            pagePartial = it.partial
                        }
                        .episodes
                    EpisodeSort.OLDEST -> {
                        val range = oldestFirstPage(total, current.episodes.size, PAGE_SIZE)
                        if (range == null) {
                            emptyList()
                        } else {
                            graph.catalog.showPage(uri, range.first, range.second)
                                .also { pagePartial = it.partial }
                                .episodes
                                .asReversed()
                        }
                    }
                }
                list.update { latest ->
                    if (latest.sort != sort) {
                        latest
                    } else {
                        val merged = (latest.episodes + page).distinctBy { it.uri }
                        latest.copy(
                            episodes = merged,
                            loading = false,
                            partial = latest.partial || pagePartial,
                            // No progress (empty or fully duplicate page) also ends paging.
                            endReached = merged.size == latest.episodes.size || merged.size >= total,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline past the cached episodes: list the downloaded ones instead of failing.
                val copy = if (offline.value || failureReason(e) == FailureReason.OFFLINE) downloadedCopy() else null
                list.update { latest ->
                    when {
                        latest.sort != sort -> latest
                        copy != null -> latest.copy(
                            episodes = appendDownloadedEpisodes(latest.episodes, copy.episodes(), newestFirst = sort == EpisodeSort.NEWEST),
                            loading = false,
                            endReached = true,
                            partial = latest.partial || copy.partial,
                            fromDownloads = true,
                        )
                        else -> latest.copy(loading = false, failed = true)
                    }
                }
            }
        }
    }

    /** The downloaded copy of this show, or null when it is not downloaded. */
    private suspend fun downloadedCopy(): DownloadedPage? = try {
        graph.downloadedPageFlow(uri).first()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /** Shows the downloaded copy as the page; false when the show isn't downloaded. */
    private suspend fun showDownloadedCopy(): Boolean {
        val copy = downloadedCopy() ?: return false
        val episodes = copy.episodes()
        total = episodes.size
        firstPage = episodes
        header.value = LoadState.Ready(ShowHeader(copy.toShow(), RichText.EMPTY, downloadedCopy = true), stale = true)
        list.update {
            EpisodePage(
                sort = it.sort,
                episodes = if (it.sort == EpisodeSort.NEWEST) episodes else episodes.asReversed(),
                endReached = true,
                partial = copy.partial,
            )
        }
        return true
    }

    /** Some episodes came back as placeholders: reload the show and its list from the start. */
    fun retryPartial() {
        loadJob?.cancel()
        list.update { EpisodePage(sort = it.sort) }
        retry()
    }

    fun setSort(sort: EpisodeSort) {
        if (list.value.sort == sort) return
        loadJob?.cancel()
        list.value = when (sort) {
            EpisodeSort.NEWEST -> EpisodePage(sort, firstPage, endReached = firstPage.isEmpty() || firstPage.size >= total)
            EpisodeSort.OLDEST -> EpisodePage(sort)
        }
        if (sort == EpisodeSort.OLDEST) loadMore()
    }

    fun playEpisode(episode: Episode) {
        if (episode.isPlaceholder || !episode.playable) return
        graph.player.play(PlayRequest(contextUri = uri, startUri = episode.uri, positionMs = episode.resumePosition()))
    }

    /** Toggles playback of this show, or starts its newest episode. */
    override fun playContext() {
        val newest = firstPage.firstOrNull { it.playable && !it.isPlaceholder }
        when {
            currentPlayback().isContext(uri) -> graph.player.togglePlayPause()
            newest != null -> playEpisode(newest)
            else -> graph.player.play(PlayRequest(contextUri = uri))
        }
    }

    fun toggleFollow() {
        toggleSaved(state.value.following)
    }

    fun download() {
        val show = header.value.dataOrNull()?.show ?: return
        downloadCollection(CollectionRef(uri, CollectionType.SHOW, show.name, show.images.best(300)))
    }

    private fun mergeNewest(fresh: List<Episode>, existing: List<Episode>): List<Episode> {
        if (existing.size <= fresh.size) return fresh
        val freshUris = fresh.mapTo(HashSet()) { it.uri }
        return fresh + existing.filter { it.uri !in freshUris }
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

// ---------------------------------------------------------------------------------------------
// Episode
// ---------------------------------------------------------------------------------------------

@Immutable
internal data class EpisodeContent(val episode: Episode, val description: RichText)

@Immutable
internal data class EpisodeUiState(
    val load: LoadState<EpisodeContent> = LoadState.Loading,
    val playback: PlaybackInfo = PlaybackInfo(),
    /** Null until known. */
    val saved: Boolean? = null,
    val download: DownloadState? = null,
    val offline: Boolean = false,
)

internal class EpisodeViewModel(graph: AppGraph, private val uri: String) : DetailViewModel(graph, uri) {

    private val content: StateFlow<LoadState<EpisodeContent>> = retryTrigger
        .flatMapLatest {
            flow {
                emit(LoadState.Loading)
                emit(load())
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    private val downloadState: Flow<DownloadState?> =
        runCatching { graph.downloads.state(uri) }.getOrElse { flowOf(null) }
            .map { state -> state.takeIf { it != DownloadState.CANCELLED } }
            .distinctUntilChanged()
            .catch { emit(null) }

    val state: StateFlow<EpisodeUiState> = combine(
        content,
        playbackInfo,
        graph.savedFlow(uri),
        downloadState,
        offline,
    ) { load, playback, saved, download, offline ->
        EpisodeUiState(load, playback, saved, download, offline)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeUiState())

    private suspend fun load(): LoadState<EpisodeContent> = try {
        val episode = graph.catalog.episode(uri)
        when {
            episode != null -> LoadState.Ready(toContent(episode))
            else -> downloadedCopy()?.let { LoadState.Ready(toContent(it), stale = true) }
                ?: LoadState.Failed(FailureReason.NOT_FOUND)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        downloadedCopy()?.let { LoadState.Ready(toContent(it), stale = true) } ?: LoadState.Failed(failureReason(e))
    }

    /** Metadata stored with a download, so downloaded episodes open offline. */
    private suspend fun downloadedCopy(): Episode? = try {
        graph.downloads.items.first()
            .firstOrNull { it.uri == uri }
            ?.metadataJson
            ?.let { graph.json.decodeFromString(Episode.serializer(), it) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun toContent(episode: Episode) = EpisodeContent(episode, parseHtml(episode.description))

    fun playPause() {
        val episode = state.value.load.dataOrNull()?.episode ?: return
        if (currentPlayback().isCurrent(uri)) {
            graph.player.togglePlayPause()
            return
        }
        val showUri = episode.show?.uri
        val request = if (showUri != null) {
            PlayRequest(contextUri = showUri, startUri = uri, positionMs = episode.resumePosition())
        } else {
            PlayRequest(trackUris = listOf(uri), positionMs = episode.resumePosition())
        }
        graph.player.play(request)
    }

    fun toggleSaved() {
        toggleSaved(
            saved = state.value.saved,
            uri = uri,
            addedMessage = R.string.detail_saved_episode,
            removedMessage = R.string.detail_removed_episode,
        )
    }

    fun download() {
        launchWrite(successRes = null, failureRes = R.string.detail_download_failed) {
            graph.downloads.downloadItems(listOf(uri))
        }
    }

    fun removeDownload() {
        launchWrite(R.string.detail_download_removed, R.string.detail_download_failed) {
            graph.downloads.removeItems(listOf(uri))
        }
    }
}
