package com.taehagen.spotifygood.ui.screens.album

import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.Resource
import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.DownloadManager
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.components.BackgroundMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ViewModel-side plumbing shared by the detail pages.

internal enum class FailureReason { OFFLINE, NOT_FOUND, GENERIC }

/** Screen content state derived from [Resource] (stale-while-revalidate). */
internal sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val reason: FailureReason) : LoadState<Nothing>
    /** [refreshing]: cached data shown while revalidating; [stale]: refresh failed, cached data shown. */
    data class Ready<T>(val data: T, val refreshing: Boolean = false, val stale: Boolean = false) : LoadState<T>
}

internal val LoadState<*>.isRefreshing: Boolean get() = this is LoadState.Ready<*> && refreshing

internal fun <T> LoadState<T>.dataOrNull(): T? = (this as? LoadState.Ready<T>)?.data

internal fun failureReason(error: Throwable): FailureReason = when {
    error is NativeException && error.isNetwork -> FailureReason.OFFLINE
    error is NativeException && error.code == NativeErrorCode.NOT_FOUND -> FailureReason.NOT_FOUND
    error is java.io.IOException -> FailureReason.OFFLINE
    else -> FailureReason.GENERIC
}

internal inline fun <T, R> Resource<T>.toLoadState(transform: (T) -> R): LoadState<R> = when (this) {
    is Resource.Loading -> cached?.let { LoadState.Ready(transform(it), refreshing = true) } ?: LoadState.Loading
    is Resource.Success -> LoadState.Ready(transform(data))
    is Resource.Error -> cached?.let { LoadState.Ready(transform(it), stale = true) } ?: LoadState.Failed(failureReason(error))
}

/** What is playing, projected for detail pages (row highlight, play/pause buttons). */
@Immutable
internal data class PlaybackInfo(
    val trackUri: String? = null,
    val contextUri: String? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val smartShuffle: Boolean = false,
) {
    fun isContext(uri: String): Boolean = isSameContext(contextUri, uri)
    fun isPlayingContext(uri: String): Boolean = isPlaying && isContext(uri)
    fun isCurrent(itemUri: String): Boolean = trackUri == itemUri
    fun isPlayingItem(itemUri: String): Boolean = isPlaying && trackUri == itemUri
}

internal fun PlaybackSnapshot.toPlaybackInfo(): PlaybackInfo = PlaybackInfo(
    trackUri = track?.uri,
    contextUri = context?.uri,
    // A track that is loading after a play command shows the pause icon, like playing.
    isPlaying = track != null && (status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING),
    shuffle = shuffle,
    smartShuffle = smartShuffle,
)

/** Header download toggle state of a collection (album/playlist/show). */
@Immutable
internal data class CollectionDownloadUi(
    val downloaded: Boolean = false,
    val status: CollectionDownloadStatus = CollectionDownloadStatus.None,
)

internal fun DownloadManager.collectionUi(uri: String): Flow<CollectionDownloadUi> =
    combine(isCollectionDownloaded(uri), collectionStatus(uri)) { downloaded, status ->
        CollectionDownloadUi(downloaded, status)
    }.onStart { emit(CollectionDownloadUi()) }.distinctUntilChanged().catch { emit(CollectionDownloadUi()) }

/**
 * Per-row download states for the [uris] shown on a page (one flow for the whole list instead of
 * one per row). Cancelled downloads count as not downloaded.
 */
internal fun DownloadManager.statesFor(uris: Flow<Set<String>>): Flow<Map<String, DownloadState>> =
    combine(downloadedUris, items, uris) { done, items, wanted ->
        if (wanted.isEmpty()) {
            emptyMap()
        } else {
            buildMap {
                for (item in items) {
                    if (item.uri in wanted && item.state != DownloadState.CANCELLED) put(item.uri, item.state)
                }
                for (uri in wanted) if (uri in done) put(uri, DownloadState.COMPLETED)
            }
        }
    }.onStart { emit(emptyMap()) }.distinctUntilChanged().catch { emit(emptyMap()) }.flowOn(Dispatchers.Default)

/** True while offline mode is on or there is no network. */
internal fun AppGraph.offlineFlow(): Flow<Boolean> =
    combine(settings.settings.map { it.offlineMode }, engine.isNetworkAvailable) { offlineMode, network ->
        offlineMode || !network
    }.distinctUntilChanged().catch { emit(false) }

/**
 * Saved/followed state of [uri]; null until known (`isSaved` only emits once the state is known,
 * so this starts with null to never hold back the screen state).
 */
internal fun AppGraph.savedFlow(uri: String): Flow<Boolean?> =
    runCatching<Flow<Boolean?>> { library.isSaved(uri) }.getOrElse { flowOf(null) }
        .onStart { emit(null) }
        .distinctUntilChanged()
        .catch { emit(null) }

/**
 * Base ViewModel of a detail page whose playback context is [contextUri].
 *
 * Writes (library, downloads, playlist edits) run in the app scope, not in [viewModelScope]: this
 * ViewModel is cleared right after its page is popped, and cancelling a write half-way would e.g.
 * queue nothing of a collection download or leave the files of a removed one behind. Their results
 * go through [BackgroundMessages], so they are shown even after the page was left.
 */
internal abstract class DetailViewModel(
    protected val graph: AppGraph,
    protected val contextUri: String,
) : ViewModel() {
    /** Incremented by [retry] to re-collect the page's data. */
    protected val retryTrigger = MutableStateFlow(0)

    protected val playbackInfo: Flow<PlaybackInfo> =
        graph.playback.snapshot.map { it.toPlaybackInfo() }.distinctUntilChanged()

    protected val offline: Flow<Boolean> = graph.offlineFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun retry() {
        retryTrigger.update { it + 1 }
    }

    /** A snackbar message, shown by the main scaffold (also once the page is gone). */
    protected fun message(@StringRes res: Int, vararg args: Any) {
        BackgroundMessages.post(graph.app.getString(res, *args))
    }

    /**
     * Runs the write [block] in the app scope (see the class comment). On success shows
     * [successRes] (if any), on failure [failureRes].
     */
    protected fun launchWrite(
        @StringRes successRes: Int?,
        @StringRes failureRes: Int,
        block: suspend () -> Unit,
    ): Job = graph.appScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Write failed", e)
            message(failureRes)
            return@launch
        }
        if (successRes != null) message(successRes)
    }

    protected fun currentPlayback(): PlaybackInfo = graph.playback.snapshot.value.toPlaybackInfo()

    /** Play/pause button: toggles when this page is the current context, otherwise starts it. */
    open fun playContext() {
        if (currentPlayback().isContext(contextUri)) {
            graph.player.togglePlayPause()
        } else {
            graph.player.play(PlayRequest(contextUri = contextUri))
        }
    }

    /** Shuffle button: toggles shuffle for the current context, otherwise starts it shuffled. */
    fun shuffleContext() {
        val playback = currentPlayback()
        if (playback.isContext(contextUri)) {
            graph.player.setShuffle(!playback.shuffle)
        } else {
            graph.player.play(PlayRequest(contextUri = contextUri, shuffle = true))
        }
    }

    /** Smart shuffle button: toggles it for the current context, otherwise starts the context with it. */
    fun smartShuffleContext() {
        val playback = currentPlayback()
        if (playback.isContext(contextUri)) {
            graph.player.setSmartShuffle(!playback.smartShuffle)
        } else {
            graph.player.play(PlayRequest(contextUri = contextUri, smartShuffle = true))
        }
    }

    fun startRadio() {
        graph.player.startRadio(contextUri)
    }

    /** Toggles the saved/followed state of [uri]; [saved] is the state the user saw (null = unknown yet). */
    fun toggleSaved(
        saved: Boolean?,
        uri: String = contextUri,
        @StringRes addedMessage: Int = R.string.detail_added_to_library,
        @StringRes removedMessage: Int = R.string.detail_removed_from_library,
    ) {
        if (saved == null) return
        launchWrite(if (saved) removedMessage else addedMessage, R.string.detail_library_failed) {
            graph.library.toggleSaved(uri)
        }
    }

    fun downloadCollection(ref: CollectionRef) {
        launchWrite(successRes = null, failureRes = R.string.detail_download_failed) {
            graph.downloads.downloadCollection(ref)
        }
    }

    fun removeCollectionDownload(uri: String = contextUri) {
        launchWrite(R.string.detail_download_removed, R.string.detail_download_failed) {
            graph.downloads.removeCollection(uri)
        }
    }

    private companion object {
        const val TAG = "DetailViewModel"
    }
}
