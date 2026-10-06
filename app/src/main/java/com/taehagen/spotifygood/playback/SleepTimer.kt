package com.taehagen.spotifygood.playback

import android.os.SystemClock
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    /** [endsAtElapsedMs] on the SystemClock.elapsedRealtime() clock. */
    data class Running(val endsAtElapsedMs: Long, val totalMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/**
 * Pauses playback after a delay or at the end of the current track (fades out the last 10 s).
 *
 * The timer sleeps (no polling) until the fade starts, re-checking the elapsed-realtime clock after
 * each sleep so device deep sleep cannot make it late. The fade only touches the local AudioTrack
 * gain ([fader]); remote devices are simply paused. Manual pauses do not cancel the timer.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    private val player: PlayerController,
    private val playback: PlaybackRepository,
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    /** Sets the local fade gain (0..1); installed by the playback coordinator. */
    @Volatile var fader: ((Float) -> Unit)? = null

    private val lock = Any()
    private var job: Job? = null
    private var generation = 0

    fun start(minutes: Int) {
        val totalMs = minutes.coerceAtLeast(1) * 60_000L
        val endsAt = SystemClock.elapsedRealtime() + totalMs
        launch(SleepTimerState.Running(endsAt, totalMs)) {
            fadeUntil(endsAt)
            pauseAndRestore()
        }
    }

    fun endOfTrack() {
        launch(SleepTimerState.EndOfTrack) {
            val first = playback.snapshot.value.track ?: return@launch
            val key = first.uid.ifEmpty { first.uri }
            playback.snapshot.transformLatest { s ->
                val track = s.track
                if (track == null || track.uid.ifEmpty { track.uri } != key || s.status == PlaybackStatus.STOPPED) {
                    // The track ended (or was skipped) before we could pause right at its end.
                    emit(Unit)
                    return@transformLatest
                }
                if (!s.isPlaying) {
                    // Paused (manually) mid-fade: restore the gain and wait for playback to resume.
                    setFade(1f)
                    return@transformLatest
                }
                val durationMs = s.durationMs.takeIf { it > 0 } ?: track.durationMs ?: return@transformLatest
                val endsAt = SystemClock.elapsedRealtime() + (durationMs - s.positionAt() - END_MARGIN_MS)
                fadeUntil(endsAt)
                emit(Unit)
            }.first()
            pauseAndRestore()
        }
    }

    fun cancel() {
        synchronized(lock) {
            generation++
            job?.cancel()
            job = null
            _state.value = SleepTimerState.Off
        }
        setFade(1f)
    }

    private fun launch(state: SleepTimerState, block: suspend () -> Unit) {
        synchronized(lock) {
            job?.cancel()
            val gen = ++generation
            _state.value = state
            job = scope.launch {
                try {
                    block()
                } finally {
                    setFade(1f)
                    synchronized(lock) {
                        if (generation == gen) {
                            _state.value = SleepTimerState.Off
                            job = null
                        }
                    }
                }
            }
        }
    }

    /** Sleeps until [FADE_MS] before [endsAt], then fades the local output to silence. */
    private suspend fun fadeUntil(endsAt: Long) {
        while (true) {
            val remaining = endsAt - SystemClock.elapsedRealtime()
            if (remaining <= FADE_MS) break
            delay(remaining - FADE_MS)
        }
        val s = playback.snapshot.value
        val fade = fader.takeIf { s.source == PlaybackSource.LOCAL && s.isPlaying }
        while (true) {
            val remaining = endsAt - SystemClock.elapsedRealtime()
            if (remaining <= 0) break
            fade?.invoke(fadeGain(remaining, FADE_MS))
            delay(if (fade != null) min(FADE_STEP_MS, remaining) else remaining)
        }
        fade?.invoke(0f)
    }

    private suspend fun pauseAndRestore() {
        player.pause()
        // Keep the output silent until the pause has taken effect, then restore the gain.
        withTimeoutOrNull(RESTORE_TIMEOUT_MS) { playback.snapshot.first { !it.isPlaying } }
        setFade(1f)
    }

    private fun setFade(gain: Float) {
        fader?.invoke(gain)
    }

    internal companion object {
        const val FADE_MS = 10_000L
        const val FADE_STEP_MS = 200L
        const val END_MARGIN_MS = 400L
        const val RESTORE_TIMEOUT_MS = 3_000L

        /** Perceptually smooth fade: quadratic in the remaining fraction. */
        fun fadeGain(remainingMs: Long, fadeMs: Long): Float {
            if (fadeMs <= 0) return 0f
            val f = (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
            return f * f
        }
    }
}
