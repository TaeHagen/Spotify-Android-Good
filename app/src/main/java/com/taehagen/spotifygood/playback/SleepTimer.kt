package com.taehagen.spotifygood.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    /** [endsAtElapsedMs] on the SystemClock.elapsedRealtime() clock. */
    data class Running(val endsAtElapsedMs: Long, val totalMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/** Pauses playback after a delay or at the end of the current track (fades out the last 10 s). */
class SleepTimer(scope: CoroutineScope, player: PlayerController, playback: PlaybackRepository) {
    val state: StateFlow<SleepTimerState> get() = TODO()
    fun start(minutes: Int): Unit = TODO()
    fun endOfTrack(): Unit = TODO()
    fun cancel(): Unit = TODO()
}
