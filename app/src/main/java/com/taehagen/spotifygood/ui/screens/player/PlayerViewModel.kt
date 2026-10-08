package com.taehagen.spotifygood.ui.screens.player

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taehagen.spotifygood.AppGraph
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Lyrics
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.playback.SleepTimerState
import com.taehagen.spotifygood.ui.screens.album.offlineFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val POSITION_TICK_MS = 500L
internal const val LYRICS_TICK_MS = 200L
internal const val CLOCK_TICK_MS = 1_000L
private const val STOP_TIMEOUT_MS = 5_000L
private const val PENDING_EDIT_TIMEOUT_MS = 4_000L
/** Longest the placeholder shows LOADING after play (PlayerController's load timeout). */
private const val RESUME_FEEDBACK_MS = 30_000L

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
    private val speedControl = graph.podcastSpeed
    private val outputs = graph.outputs
    private val devices = graph.devices
    private val resumeStore = graph.resumeStore
    private val downloads = graph.downloads

    /**
     * The last local session (ResumeStore) as a paused placeholder, while the engine has nothing
     * loaded: after a cold start, or once the engine stopped after idling. Re-read whenever the
     * snapshot loses its track; null once a real track arrives or after logout (the store is
     * cleared then too). The engine's snapshot itself is never touched (service, sleep timer…).
     */
    private val resumePlaceholder: StateFlow<PlaybackSnapshot?> = combine(
        playback.snapshot.map(::wantsResumePlaceholder).distinctUntilChanged(),
        graph.engine.isLoggedIn,
    ) { wanted, loggedIn -> wanted && loggedIn }
        .distinctUntilChanged()
        .transformLatest { wanted -> emit(if (wanted) resumeStore.read()?.toPlaceholderSnapshot() else null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Play was tapped on the placeholder; LOADING until the session shows up (or fails). */
    private val resuming = MutableStateFlow(false)
    private var resumeJob: Job? = null

    /** What the player surfaces show: the engine's snapshot, or the last-session placeholder. */
    val snapshot: StateFlow<PlaybackSnapshot> = combine(playback.snapshot, resumePlaceholder, resuming, ::displaySnapshot)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), playback.snapshot.value)

    /** True while the mini player has something to show (a loaded item or the placeholder). */
    val hasContent: StateFlow<Boolean> = snapshot
        .map { it.track != null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), playback.snapshot.value.track != null)

    /** The placeholder while it is shown (the engine has nothing loaded). */
    private fun shownPlaceholder(): PlaybackSnapshot? =
        resumePlaceholder.value?.takeIf { wantsResumePlaceholder(playback.snapshot.value) }

    private val shownPlaceholderFlow: Flow<PlaybackSnapshot?> =
        combine(playback.snapshot, resumePlaceholder) { real, placeholder -> placeholder?.takeIf { wantsResumePlaceholder(real) } }
            .distinctUntilChanged()

    private fun ticker(periodMs: Long): Flow<Long> = shownPlaceholderFlow.flatMapLatest { placeholder ->
        if (placeholder != null) flowOf(placeholder.positionMs) else playback.positionTicker(periodMs)
    }

    /** Position for seek bars / progress lines (collect with lifecycle). */
    val position: Flow<Long> = ticker(POSITION_TICK_MS)

    /** Finer position for synced lyrics. */
    val lyricsPosition: Flow<Long> = ticker(LYRICS_TICK_MS)

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

    fun positionNow(): Long = shownPlaceholder()?.positionMs ?: playback.positionMs()

    private val currentUri: Flow<String?> = snapshot.map { it.track?.uri }.distinctUntilChanged()

    /** Liked state of the current item, with the item it belongs to (see [LikeState.shownFor]). */
    private val likeState: StateFlow<LikeState> = currentUri
        .flatMapLatest { uri ->
            if (uri == null) {
                flowOf(LikeState.NONE)
            } else {
                library.isSaved(uri).map { LikeState(uri, it) }.catch { emit(LikeState(uri, null)) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LikeState.NONE)

    /** Whether the current item is liked; null while unknown (lookup pending or failed): the heart is disabled. */
    val isLiked: StateFlow<Boolean?> = likeState.map { it.liked }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * (uri, downloaded cover path) of the current item; the path is null until it is downloaded.
     * Watches only the current item's download state and reads its cover once it is complete (a
     * cover never changes), instead of mapping every download row on each progress update.
     */
    private val downloadedCover: Flow<Pair<String, String?>?> = currentUri
        .flatMapLatest { uri ->
            if (uri == null) {
                flowOf(null)
            } else {
                downloads.state(uri)
                    .map { it == DownloadState.COMPLETED }
                    .distinctUntilChanged()
                    .mapLatest { done -> uri to if (done) coverPath(uri) else null }
            }
        }
        .catch { emit(null) }

    private suspend fun coverPath(uri: String): String? =
        downloads.items.first().firstOrNull { it.uri == uri }?.imagePath?.takeIf { it.isNotBlank() }

    /** Artwork of the current item: its downloaded cover offline, else the CDN image ([playerArtwork]). */
    val artwork: StateFlow<String?> = combine(
        snapshot.map { s -> s.track?.let { it.uri to it.imageUrl } }.distinctUntilChanged(),
        downloadedCover,
        graph.offlineFlow(),
    ) { current, cover, offline ->
        current?.let { (uri, imageUrl) -> playerArtwork(imageUrl, cover?.takeIf { it.first == uri }?.second, offline) }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), snapshot.value.track?.imageUrl)

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

    /** The podcast playback speed (episodes played on this phone, one speed for all). */
    val podcastSpeed: StateFlow<Float> = speedControl.speed

    fun setPodcastSpeed(speed: Float) = speedControl.set(speed)

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

    fun togglePlayPause() {
        if (shownPlaceholder() != null) resumeLastSession() else player.togglePlayPause()
    }

    /**
     * Play on the last-session placeholder. With no active device PlayerController loads exactly the
     * saved session (the placeholder's content); shows LOADING until a track arrives or it failed.
     */
    private fun resumeLastSession() {
        if (resumeJob?.isActive == true) return
        resumeJob = viewModelScope.launch {
            resuming.value = true
            try {
                withTimeoutOrNull(RESUME_FEEDBACK_MS) {
                    merge(
                        playback.snapshot.filter { !wantsResumePlaceholder(it) }.map { },
                        player.errors.map { },
                    ).first()
                }
            } finally {
                resuming.value = false
            }
        }
        // After the watcher subscribed (immediate dispatcher), so a fast failure is not missed.
        player.resume()
    }
    fun next() = player.next()
    fun previous() = player.previous()
    fun seekTo(positionMs: Long) {
        pendingSeek = null
        if (shownPlaceholder() != null) return // nothing loaded yet (seeking is disabled there)
        player.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Last skip step, so quick repeated taps add up before the snapshot catches up (main thread). */
    private var pendingSeek: PendingSeek? = null

    /** Podcast skip back / forward by [deltaMs] (e.g. ±[SEEK_STEP_MS]). */
    fun seekBy(deltaMs: Long) {
        val s = playback.snapshot.value
        val uri = s.track?.uri ?: return
        if (!s.restrictions.canSeek) return
        val now = SystemClock.elapsedRealtime()
        val base = seekStepBase(uri, playback.positionMs(), pendingSeek, now, s.isPlaying)
        val target = seekStepTarget(base, deltaMs, s.effectiveDurationMs())
        pendingSeek = PendingSeek(uri, target, now)
        player.seekTo(target)
    }
    fun cycleShuffle() = player.cycleShuffle()
    fun cycleRepeat() = player.cycleRepeat()
    fun setVolume(volume: Int) = volumeThrottle.offer(volume)
    fun startRadio(uri: String) = player.startRadio(uri)

    /** Heart tap: writes the opposite of what the heart showed for the current item; nothing while unknown. */
    fun toggleLike() {
        val uri = snapshot.value.track?.uri ?: return
        val shown = likeState.value.shownFor(uri) ?: return
        viewModelScope.launch {
            try {
                library.toggleSaved(uri, displayed = shown)
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
