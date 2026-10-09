package com.taehagen.spotifygood.playback

import android.os.SystemClock
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.min

sealed interface SleepTimerState {
    data object Off : SleepTimerState
    /** [endsAtElapsedMs] on the SystemClock.elapsedRealtime() clock. */
    data class Running(val endsAtElapsedMs: Long, val totalMs: Long) : SleepTimerState
    data object EndOfTrack : SleepTimerState
}

/**
 * Wakes the device for the sleep timer (installed by [PlaybackCoordinator]): coroutine delays run
 * on the monotonic clock, which stops while the CPU is suspended — and nothing keeps the CPU up
 * while a remote Connect device plays.
 */
interface SleepWakeups {
    /** Arms the wake-up alarm for a timer ending at [endsAtElapsedMs] (replaces the previous one). */
    fun schedule(endsAtElapsedMs: Long)

    /** Disarms the alarm. */
    fun cancel()

    /** Keeps the CPU awake for at most [ms] (replaces a previous hold). */
    fun holdAwake(ms: Long)

    /** Releases the hold. */
    fun release()
}

/**
 * When to wake the device for a sleep timer, and for how long (pure, see [SleepWakeups]).
 *
 * Without an exact-alarm grant (Android 12+) the wake-up is an inexact allow-while-idle alarm.
 * AlarmManager gives it the heuristic window [trigger, trigger + 0.75 × (trigger − now)] (at most
 * an hour; none under 10 s) and, on Android 12+, delivers it at the window's end unless something
 * else wakes the device first — also in Doze, where windowed alarms wait for a maintenance window.
 * So the trigger is placed where that window ends exactly at the timer's end ([stageTrigger]); an
 * earlier delivery re-arms the next stage the same way until less than 10 s remain.
 */
internal object SleepSchedule {
    /** Inexact alarms closer than this get no window (AlarmManager's MIN_FUZZABLE_INTERVAL). */
    const val MIN_FUZZ_MS = 10_000L

    /** AlarmManager caps a heuristic window at an hour. */
    const val MAX_WINDOW_MS = 60 * 60_000L

    /**
     * Longest time the CPU is held awake (from the last stage or, for remote playback, from arming
     * a timer that ends soon). Partial wake locks are honoured outside Doze only.
     */
    const val LEAD_MS = 10 * 60_000L

    /** Extra awake time after the end, for the pause request to reach the device. */
    const val PAUSE_SLACK_MS = 30_000L

    /**
     * Trigger of an inexact alarm whose heuristic window ends at [endsAt]: trigger + 0.75 ×
     * (trigger − now) = endsAt, i.e. now + (endsAt − now) / 1.75 (or an hour before the end when the
     * window would be longer than its cap); exact below [MIN_FUZZ_MS].
     */
    fun stageTrigger(endsAt: Long, now: Long): Long {
        val remaining = endsAt - now
        if (remaining <= MIN_FUZZ_MS) return max(now, endsAt)
        val trigger = now + remaining * 4 / 7
        return if (endsAt - trigger > MAX_WINDOW_MS) endsAt - MAX_WINDOW_MS else trigger
    }

    /** End of the heuristic window of an inexact alarm at [trigger] armed at [now] (AlarmManager's rule). */
    fun windowEnd(trigger: Long, now: Long): Long {
        val futurity = trigger - now
        if (futurity < MIN_FUZZ_MS) return trigger
        return trigger + min((0.75 * futurity).toLong(), MAX_WINDOW_MS)
    }

    /** A wake-up at [now] needs another stage: more than [MIN_FUZZ_MS] are left. */
    fun needsAnotherStage(endsAt: Long, now: Long): Boolean = endsAt - now > MIN_FUZZ_MS

    /** Awake time for an early stage: enough for the poked wait to re-check the clock and re-sleep. */
    const val POKE_AWAKE_MS = 2_000L

    /**
     * Hold when a wake-up alarm is delivered at [now]: until the end plus slack ([awakeMs]) when a
     * remote device plays ([remotePlaying], it must be paused on time) and the end is within
     * [LEAD_MS], or at the final stage (≤ [MIN_FUZZ_MS] left: the end and the pause); otherwise
     * [POKE_AWAKE_MS] — a hold that ends before the end would be wasted, the next stage is armed.
     */
    fun awakeHoldMs(endsAt: Long, now: Long, remotePlaying: Boolean): Long = when {
        remotePlaying && endsAt - now <= LEAD_MS -> awakeMs(endsAt, now)
        !needsAnotherStage(endsAt, now) -> awakeMs(endsAt, now)
        else -> POKE_AWAKE_MS
    }

    /** How long to keep the CPU awake when the alarm fires at [now]: until the end plus slack. */
    fun awakeMs(endsAt: Long, now: Long): Long = (endsAt - now).coerceIn(0L, LEAD_MS) + PAUSE_SLACK_MS

    /**
     * End (elapsed clock) of the current track for "end of track", [marginMs] of wall time before
     * its last sample: the media time left takes `left / speed` at [speed] (a podcast speed; one
     * that is not positive and finite counts as 1x).
     */
    /**
     * Whether the playing item ([uri], [uid] at [positionMs]) still is the one "end of track" waits
     * for, last seen as [lastUri] / [lastUid] at [lastPositionMs] (extrapolated to now). Its uri
     * names it: the engine re-makes the uid of the same item while it plays on (a hand-off to the
     * offline queue, `o<i>`; a Spirc restore's queue and suggestion uids), so a changed uid only
     * means another item when the position also starts over (it went back by more than
     * [toleranceMs]): repeat-one, or the same track reached again in the queue. The same uid going
     * back is a seek.
     */
    fun sameItem(
        lastUri: String,
        lastUid: String,
        lastPositionMs: Long,
        uri: String,
        uid: String,
        positionMs: Long,
        toleranceMs: Long = RESTART_TOLERANCE_MS,
    ): Boolean = when {
        uri != lastUri -> false
        uid == lastUid -> true
        else -> positionMs >= lastPositionMs - toleranceMs
    }

    /** How far back a re-made uid's position may go and still be the same item ([sameItem]). */
    const val RESTART_TOLERANCE_MS = 5_000L

    fun trackEndsAt(now: Long, durationMs: Long, positionMs: Long, speed: Double = 1.0, marginMs: Long = SleepTimer.END_MARGIN_MS): Long {
        val rate = speed.takeIf { it > 0 && it.isFinite() } ?: 1.0
        val left = ((durationMs - positionMs).coerceAtLeast(0) / rate).toLong()
        return now + (left - marginMs).coerceAtLeast(0)
    }
}

/**
 * Pauses playback after a delay or at the end of the current track (fades out the last 10 s).
 *
 * The timer sleeps (no polling) until the fade starts and re-checks the elapsed-realtime clock
 * whenever it wakes. A sleeping coroutine does not count time while the CPU is suspended, so a
 * wake-up alarm ([wakeups], `ELAPSED_REALTIME_WAKEUP`, delivered by the end: see [SleepSchedule])
 * pokes it ([onWakeupAlarm]) and keeps the CPU awake until the end; for local playback the
 * playback wake lock keeps the CPU up anyway. The fade
 * only touches the local AudioTrack gain ([fader]); remote devices are simply paused. Manual pauses
 * do not cancel the timer.
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

    /** Device wake-ups; installed by the playback coordinator. */
    @Volatile var wakeups: SleepWakeups? = null

    /** Elapsed-realtime clock (counts deep sleep); replaceable in tests. */
    internal var clock: () -> Long = { SystemClock.elapsedRealtime() }

    private val lock = Any()
    private var job: Job? = null
    private var generation = 0

    /** Wakes a sleeping wait so it re-reads [clock] (conflated: a poke is never lost). */
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    /** The end the alarm is armed for (null: none). */
    @Volatile private var wakeTarget: Long? = null

    fun start(minutes: Int) {
        val totalMs = minutes.coerceAtLeast(1) * 60_000L
        val endsAt = clock() + totalMs
        launch(SleepTimerState.Running(endsAt, totalMs)) {
            arm(endsAt)
            fadeUntil(endsAt)
            pauseAndRestore()
        }
    }

    fun endOfTrack() {
        launch(SleepTimerState.EndOfTrack) {
            val first = playback.snapshot.value
            val uri = first.track?.uri ?: return@launch
            // The item as last seen: its uri names it, the uid and position tell a restart of
            // the same uri from the same item with a re-made uid (SleepSchedule.sameItem).
            var last = first
            playback.snapshot.transformLatest { s ->
                val previous = last
                last = s
                val track = s.track
                if (track == null || track.uri != uri || s.status == PlaybackStatus.STOPPED) {
                    // The track ended (or was skipped) before we could pause right at its end.
                    emit(Unit)
                    return@transformLatest
                }
                if (!s.isPlaying) {
                    // Paused (manually) mid-fade: restore the gain, no wake-up until it resumes. A
                    // uid re-made meanwhile (a hand-off, a restore) does not end the wait.
                    setFade(1f)
                    disarm()
                    return@transformLatest
                }
                val now = System.currentTimeMillis()
                val previousTrack = previous.track
                if (previousTrack != null &&
                    !SleepSchedule.sameItem(previousTrack.uri, previousTrack.uid, previous.positionAt(now), track.uri, track.uid, s.positionAt(now))
                ) {
                    // The same uri again from the start (repeat-one, queued twice): the item ended.
                    emit(Unit)
                    return@transformLatest
                }
                val durationMs = s.durationMs.takeIf { it > 0 } ?: track.durationMs ?: return@transformLatest
                // Re-armed on every snapshot (seek, resume, remote position updates, a change of
                // the podcast speed): the media time left plays at the snapshot's speed.
                val endsAt = SleepSchedule.trackEndsAt(clock(), durationMs, s.positionAt(), s.playbackSpeed)
                arm(endsAt)
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
        disarm()
        setFade(1f)
    }

    /**
     * The wake-up alarm fired: arm the next stage while the end is still more than a few seconds
     * away (an inexact alarm may come early) and let the wait re-check the clock. The CPU is held
     * awake until the end only where that is needed and can reach it: a playing remote device within
     * the last [SleepSchedule.LEAD_MS], or the final stage; otherwise just long enough for the
     * re-check ([SleepSchedule.awakeHoldMs]).
     */
    fun onWakeupAlarm() {
        val target = wakeTarget ?: return
        val now = clock()
        val s = playback.snapshot.value
        wakeups?.holdAwake(SleepSchedule.awakeHoldMs(target, now, remotePlaying = s.source == PlaybackSource.REMOTE && s.isPlaying))
        if (SleepSchedule.needsAnotherStage(target, now)) wakeups?.schedule(target)
        pokes.trySend(Unit)
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
                    val current = synchronized(lock) {
                        (generation == gen).also { current ->
                            if (current) {
                                _state.value = SleepTimerState.Off
                                job = null
                            }
                        }
                    }
                    // A replacing timer armed its own alarm already.
                    if (current) disarm()
                }
            }
        }
    }

    private fun arm(endsAt: Long) {
        wakeTarget = endsAt
        val wakeups = wakeups ?: return
        wakeups.schedule(endsAt)
        // Remote playback holds no wake lock: when the end is near anyway (short timer, end of
        // track), keep the CPU up from now on instead of relying on the alarm alone.
        val now = clock()
        val s = playback.snapshot.value
        if (s.source == PlaybackSource.REMOTE && s.isPlaying && endsAt - now <= SleepSchedule.LEAD_MS) {
            wakeups.holdAwake(SleepSchedule.awakeMs(endsAt, now))
        }
    }

    private fun disarm() {
        wakeTarget = null
        wakeups?.cancel()
        wakeups?.release()
    }

    /** Sleeps (interruptibly, see [pokes]) for at most [ms]. */
    private suspend fun sleep(ms: Long) {
        withTimeoutOrNull(ms) { pokes.receive() }
    }

    /** Sleeps until [FADE_MS] before [endsAt], then fades the local output to silence. */
    private suspend fun fadeUntil(endsAt: Long) {
        while (true) {
            val remaining = endsAt - clock()
            if (remaining <= FADE_MS) break
            sleep(remaining - FADE_MS)
        }
        val s = playback.snapshot.value
        val fade = fader.takeIf { s.source == PlaybackSource.LOCAL && s.isPlaying }
        while (true) {
            val remaining = endsAt - clock()
            if (remaining <= 0) break
            fade?.invoke(fadeGain(remaining, FADE_MS))
            sleep(if (fade != null) min(FADE_STEP_MS, remaining) else remaining)
        }
        fade?.invoke(0f)
    }

    private suspend fun pauseAndRestore() {
        // A remote device's pause is a network request: keep the CPU up until it went out.
        if (playback.snapshot.value.source == PlaybackSource.REMOTE) wakeups?.holdAwake(PAUSE_AWAKE_MS)
        // Not the user's command, but on purpose: a call ending later must not resume it.
        withTimeoutOrNull(PAUSE_AWAKE_MS) { player.pauseAsync(user = false, deliberate = true).await() }
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
        /** Awake time around a remote pause request. */
        const val PAUSE_AWAKE_MS = 15_000L

        /** Perceptually smooth fade: quadratic in the remaining fraction. */
        fun fadeGain(remainingMs: Long, fadeMs: Long): Float {
            if (fadeMs <= 0) return 0f
            val f = (remainingMs.toFloat() / fadeMs).coerceIn(0f, 1f)
            return f * f
        }
    }
}
