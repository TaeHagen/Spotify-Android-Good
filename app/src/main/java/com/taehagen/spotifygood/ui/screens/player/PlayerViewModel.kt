package com.taehagen.spotifygood.ui.screens.player

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.SleepTimerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val POSITION_TICK_MS = 500L
internal const val LYRICS_TICK_MS = 200L
internal const val CLOCK_TICK_MS = 1_000L
private const val STOP_TIMEOUT_MS = 5_000L
private const val PENDING_EDIT_TIMEOUT_MS = 4_000L

/** Lyrics of the current track. */
@Immutable
internal sealed interface LyricsState {
    data object Loading : LyricsState
    /** No track, a podcast episode, or Spotify has no lyrics. */
    data object Unavailable : LyricsState
    data object Error : LyricsState
    data class Loaded(val trackUri: String, val lyrics: Lyrics) : LyricsState
}

internal enum class PlayerMessage { LIKE_FAILED }

/**
 * Sends Connect volume changes at most every [intervalMs] while always delivering the latest value
 * (throttle-latest; the engine debounces again for remote devices).
 */
internal class VolumeThrottle(scope: CoroutineScope, private val intervalMs: Long = 200, private val send: (Int) -> Unit) {
    private val requests = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            requests.collect { volume ->
                send(volume)
                delay(intervalMs)
            }
        }
    }

    fun offer(volume: Int) {
        requests.tryEmit(volume.coerceIn(0, MAX_CONNECT_VOLUME))
    }
}

/**
 * Shared state and commands of the player surfaces (mini player, now playing, queue, lyrics, sleep
 * timer). Position updates are exposed as cold tickers that only run while a visible UI collects them.
 */
internal class PlayerViewModel(graph: AppGraph) : ViewModel() {
    private val playback = graph.playback
    private val player = graph.player
    private val library = graph.library
    private val lyricsRepository = graph.lyrics
    private val sleepTimer = graph.sleepTimer
    private val outputs = graph.outputs
    private val devices = graph.devices

    val snapshot: StateFlow<PlaybackSnapshot> = playback.snapshot

    /** Position for seek bars / progress lines (collect with lifecycle). */
    val position: Flow<Long> = playback.positionTicker(POSITION_TICK_MS)

    /** Finer position for synced lyrics. */
    val lyricsPosition: Flow<Long> = playback.positionTicker(LYRICS_TICK_MS)

    /**
     * A once-per-second tick for the sleep timer countdown, only while collected (the sheet is
     * visible) and a timed sleep timer runs. Independent of playback: the timer keeps counting
     * down while paused. "End of track" shows no countdown, so it does not tick.
     */
    val clock: Flow<Long> = sleepTimer.state.transformLatest { state ->
        if (state is SleepTimerState.Running) {
            while (true) {
                emit(SystemClock.elapsedRealtime())
                delay(CLOCK_TICK_MS)
            }
        }
    }

    fun positionNow(): Long = playback.positionMs()

    private val currentUri: Flow<String?> = snapshot.map { it.track?.uri }.distinctUntilChanged()

    val isLiked: StateFlow<Boolean> = currentUri
        .flatMapLatest { uri -> if (uri == null) flowOf(false) else library.isSaved(uri).catch { emit(false) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val indicator: StateFlow<DeviceIndicator> = combine(snapshot, outputs.current) { s, output -> deviceIndicator(s, output) }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            deviceIndicator(snapshot.value, outputs.current.value),
        )

    /** The remote device playing takes volume changes (false while this phone plays). */
    val remoteVolumeSupported: StateFlow<Boolean> = combine(snapshot, devices.devices, ::remoteVolumeSupported)
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            remoteVolumeSupported(snapshot.value, devices.devices.value),
        )

    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    // ----------------------------------------------------------------------------------------- lyrics

    private val lyricsRetry = MutableStateFlow(0)

    /** Lyrics of the current track, reloaded when the track changes (loads only while collected). */
    val lyrics: StateFlow<LyricsState> = combine(
        snapshot.map { s -> s.track?.takeUnless { it.isEpisode }?.uri }.distinctUntilChanged(),
        lyricsRetry,
    ) { uri, _ -> uri }
        .transformLatest { uri ->
            if (uri == null) {
                emit(LyricsState.Unavailable)
            } else {
                emit(LyricsState.Loading)
                emit(loadLyrics(uri))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LyricsState.Loading)

    /** Lyrics for the now playing preview card; Unavailable when the setting is off. */
    val lyricsPreview: StateFlow<LyricsState> = graph.settings.settings
        .map { it.showLyricsOnNowPlaying }
        .distinctUntilChanged()
        .flatMapLatest { show -> if (show) lyrics else flowOf(LyricsState.Unavailable) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LyricsState.Unavailable)

    private suspend fun loadLyrics(uri: String): LyricsState = try {
        val result = lyricsRepository.lyrics(uri)
        if (result == null || result.lines.isEmpty()) LyricsState.Unavailable else LyricsState.Loaded(uri, result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: NativeException) {
        if (e.code == NativeErrorCode.NOT_FOUND) LyricsState.Unavailable else LyricsState.Error
    } catch (e: Exception) {
        LyricsState.Error
    }

    fun retryLyrics() = lyricsRetry.update { it + 1 }

    // ------------------------------------------------------------------------------------------ queue

    private val removedKeys = MutableStateFlow<Set<String>>(emptySet())
    private val pendingOrder = MutableStateFlow<List<String>?>(null)

    private val engineSections: Flow<QueueSections> = snapshot
        .map { partitionQueue(it.nextTracks, it.isPlayingAutoplay) }
        .distinctUntilChanged()
        .onEach { sections -> reconcilePendingEdits(sections) }
        .flowOn(Dispatchers.Default)

    /** Queue sections with optimistic local edits applied. */
    val queue: StateFlow<QueueSections> = combine(engineSections, removedKeys, pendingOrder) { sections, removed, order ->
        sections.withPendingEdits(removed, order)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            snapshot.value.let { partitionQueue(it.nextTracks, it.isPlayingAutoplay) },
        )

    private fun reconcilePendingEdits(sections: QueueSections) {
        if (removedKeys.value.isNotEmpty()) {
            val present = HashSet<String>()
            sections.queued.forEach { present += it.key }
            sections.upNext.forEach { present += it.key }
            sections.autoplay.forEach { present += it.key }
            removedKeys.update { removed -> removed.filterTo(HashSet()) { it in present } }
        }
        val order = pendingOrder.value
        if (order != null && sections.matchesOrder(order)) pendingOrder.compareAndSet(order, null)
    }

    private val messageChannel = Channel<PlayerMessage>(Channel.BUFFERED)
    val messages: Flow<PlayerMessage> = messageChannel.receiveAsFlow()

    private val volumeThrottle = VolumeThrottle(viewModelScope) { player.setVolume(it) }

    // --------------------------------------------------------------------------------------- commands

    fun togglePlayPause() = player.togglePlayPause()
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))
    fun cycleShuffle() = player.cycleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun setVolume(volume: Int) = volumeThrottle.offer(volume)
    fun startRadio(uri: String) = player.startRadio(uri)

    fun toggleLike() {
        val uri = snapshot.value.track?.uri ?: return
        viewModelScope.launch {
            try {
                library.toggleSaved(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messageChannel.trySend(PlayerMessage.LIKE_FAILED)
            }
        }
    }

    fun skipTo(entry: QueueEntry) {
        if (entry.hasUid) player.skipTo(entry.track.uid)
    }

    fun remove(entry: QueueEntry) {
        if (!entry.hasUid) return
        removedKeys.update { it + entry.key }
        player.removeFromQueue(entry.track.uid)
        expireRemoval(setOf(entry.key))
    }

    /** Commits a drag reorder of the queued section ([newOrder] = keys after the move). */
    fun move(entry: QueueEntry, toIndex: Int, newOrder: List<String>) {
        if (!entry.hasUid) return
        pendingOrder.value = newOrder
        player.moveInQueue(entry.track.uid, toIndex)
        viewModelScope.launch {
            delay(PENDING_EDIT_TIMEOUT_MS)
            pendingOrder.compareAndSet(newOrder, null)
        }
    }

    /** Moves a queued row one step (accessibility actions). */
    fun moveBy(entry: QueueEntry, delta: Int) {
        val keys = queue.value.queued.map { it.key }
        val from = keys.indexOf(entry.key)
        if (from < 0) return
        val newOrder = keys.moved(from, from + delta)
        val target = queueMoveTarget(keys, newOrder, entry.key) ?: return
        move(entry, target, newOrder)
    }

    fun clearQueue() {
        val keys = queue.value.queued.mapTo(HashSet()) { it.key }
        if (keys.isEmpty()) return
        removedKeys.update { it + keys }
        player.clearQueue()
        expireRemoval(keys)
    }

    private fun expireRemoval(keys: Set<String>) {
        viewModelScope.launch {
            delay(PENDING_EDIT_TIMEOUT_MS)
            removedKeys.update { it - keys }
        }
    }

    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes)
    fun sleepAtEndOfTrack() = sleepTimer.endOfTrack()
    fun cancelSleepTimer() = sleepTimer.cancel()
}
