package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for what is playing (local or remote), built from native `playback`
 * snapshots merged with `queueMetadata` (names/artwork for queue entries).
 */
class PlaybackRepository(scope: CoroutineScope, events: NativeEvents) {
    /** Latest snapshot with metadata merged in. */
    val snapshot: StateFlow<PlaybackSnapshot> get() = TODO()

    /** Convenience projections. */
    val currentTrack: StateFlow<PlaybackTrack?> get() = TODO()
    val isPlaying: StateFlow<Boolean> get() = TODO()

    /** Interpolated position of the active playback, cheap and thread-safe. */
    fun positionMs(): Long = TODO()

    /**
     * Position ticker for UI: emits the interpolated position every [periodMs] while collected
     * (collect with collectAsStateWithLifecycle so it stops when invisible); emits once when paused.
     */
    fun positionTicker(periodMs: Long = 500): Flow<Long> = TODO()
}
