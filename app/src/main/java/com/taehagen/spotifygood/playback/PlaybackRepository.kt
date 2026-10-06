package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.QueueMetadataEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Single source of truth for what is playing (local or remote), built from native `playback`
 * snapshots merged with `queueMetadata` (names/artwork for queue entries).
 */
class PlaybackRepository(scope: CoroutineScope, events: NativeEvents) {
    private val metadata = PlaybackMetadataCache()

    /** Wall clock used for position extrapolation (snapshots carry epoch timestamps); replaceable in tests. */
    internal var wallClock: () -> Long = System::currentTimeMillis
    private val _snapshot = MutableStateFlow(PlaybackSnapshot.EMPTY)

    /** Latest snapshot with metadata merged in. */
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    /** Convenience projections. */
    val currentTrack: StateFlow<PlaybackTrack?> =
        snapshot.map { it.track }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, null)
    val isPlaying: StateFlow<Boolean> =
        snapshot.map { it.isPlaying }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, false)

    private sealed interface Update {
        data class Snapshot(val value: PlaybackSnapshot) : Update
        data class Metadata(val value: QueueMetadataEvent) : Update
    }

    init {
        // One collector owns the cache, so snapshot and metadata updates are applied in order.
        scope.launch {
            var raw = events.playback.value
            merge(
                events.playback.map { Update.Snapshot(it) },
                events.queueMetadata.map { Update.Metadata(it) },
            ).collect { update ->
                when (update) {
                    is Update.Snapshot -> {
                        raw = update.value
                        metadata.remember(raw)
                    }
                    is Update.Metadata -> if (!metadata.addAll(update.value)) return@collect
                }
                _snapshot.value = metadata.merge(raw)
            }
        }
    }

    /** Interpolated position of the active playback, cheap and thread-safe. */
    fun positionMs(): Long = _snapshot.value.positionAt(wallClock())

    /**
     * Position ticker for UI: emits the interpolated position every [periodMs] while collected
     * (collect with collectAsStateWithLifecycle so it stops when invisible); emits once when paused.
     */
    fun positionTicker(periodMs: Long = 500): Flow<Long> {
        val period = periodMs.coerceAtLeast(MIN_TICK_MS)
        return snapshot.transformLatest { s ->
            emit(s.positionAt(wallClock()))
            if (s.isPlaying && s.track != null) {
                // Cancelled by transformLatest as soon as the next snapshot (pause, seek, ...) arrives.
                while (true) {
                    delay(period)
                    emit(s.positionAt(wallClock()))
                }
            }
        }.distinctUntilChanged()
    }

    private companion object {
        const val MIN_TICK_MS = 16L
    }
}
