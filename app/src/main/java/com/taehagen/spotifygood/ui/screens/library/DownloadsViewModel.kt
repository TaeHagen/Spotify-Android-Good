package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.DownloadActivity
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class DownloadsUiState(
    val isLoading: Boolean = true,
    val usedBytes: Long = 0,
    val content: DownloadsContent = DownloadsContent(),
    val offline: Boolean = false,
    val nowPlaying: NowPlaying = NowPlaying(),
    /** Live downloader state (header progress, why a run stopped). */
    val activity: DownloadActivity = DownloadActivity(),
)

class DownloadsViewModel(private val graph: AppGraph) : ViewModel() {
    private val messages = Channel<LibraryMessage>(Channel.BUFFERED)
    val events: Flow<LibraryMessage> = messages.receiveAsFlow()

    /** Decoded metadata per URI (metadata JSON never changes for a download). */
    private val metadataCache = ConcurrentHashMap<String, DownloadMetadata>()

    /** The item the downloader works on right now (null while it is not running). */
    private val activeUri: Flow<String?> = graph.downloads.activity
        .map { activity -> activity.currentUri.takeIf { activity.running } }
        .distinctUntilChanged()

    private val content: Flow<DownloadsContent?> = combine(
        graph.downloads.items,
        graph.downloadedCollectionsFlow(),
        activeUri,
    ) { items, collections, active ->
        buildDownloadsContent(items, collections, ::metadataOf, active)
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { emit(DownloadsContent()) }
        .onStart<DownloadsContent?> { emit(null) }

    val state: StateFlow<DownloadsUiState> = combine(
        content,
        graph.downloads.usedBytes.onStart { emit(0L) }.catch { emit(0L) },
        graph.offlineFlow(),
        graph.nowPlayingFlow(),
        graph.downloads.activity,
    ) { content, used, offline, nowPlaying, activity ->
        DownloadsUiState(
            isLoading = content == null,
            usedBytes = used,
            content = content ?: DownloadsContent(),
            offline = offline,
            nowPlaying = nowPlaying,
            activity = activity,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    private fun metadataOf(item: DownloadItem): DownloadMetadata? =
        metadataCache[item.uri] ?: decodeDownloadMetadata(graph.json, item.uri, item.metadataJson)?.also { metadataCache[item.uri] = it }

    fun collectionStatus(uri: String): Flow<CollectionDownloadStatus> =
        graph.downloads.collectionStatus(uri).catch { emit(CollectionDownloadStatus.None) }.distinctUntilChanged()

    fun playCollection(collection: DownloadedCollection) {
        val current = state.value
        if (!current.offline) {
            graph.player.playContext(collection.uri)
            return
        }
        val uris = collection.playableUris(current.content.completed, offline = true)
        if (uris.isEmpty()) messages.trySend(LibraryMessage.NOTHING_TO_PLAY) else graph.player.playTracks(uris, 0)
    }

    /** Plays [entry] within its section (songs or episodes); offline only completed items. */
    fun playEntry(entry: DownloadEntry) {
        val current = state.value
        val section = if (entry.isEpisode) current.content.episodes else current.content.songs
        val playable = section.filter { !current.offline || it.state == DownloadState.COMPLETED }.map { it.uri }
        val index = playable.indexOf(entry.uri)
        when {
            index >= 0 -> graph.player.playTracks(playable, index)
            current.offline -> messages.trySend(LibraryMessage.NOTHING_TO_PLAY)
            else -> graph.player.playTracks(listOf(entry.uri))
        }
    }

    fun removeCollection(uri: String) = mutate { graph.downloads.removeCollection(uri) }

    fun removeItem(uri: String) = mutate { graph.downloads.removeItems(listOf(uri)) }

    fun removeAll() = mutate { graph.downloads.removeAll() }

    fun retryFailed() = mutate(notify = false) { graph.downloads.retryFailed() }

    fun retryItem(uri: String) = mutate(notify = false) { graph.downloads.downloadItems(listOf(uri)) }

    /** Runs in the app scope: leaving the screen must not interrupt a removal half-way. */
    private fun mutate(notify: Boolean = true, block: suspend () -> Unit) {
        graph.appScope.launch {
            attempt { block() }
                .onSuccess { if (notify) messages.trySend(LibraryMessage.DOWNLOAD_REMOVED) }
                .onFailure { messages.trySend(LibraryMessage.DOWNLOAD_FAILED) }
        }
    }
}
