package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.transformLatest

/**
 * What [PlaybackService] writes to the [ResumeStore], in order (one collector, so a save never
 * lands after the clear that follows it):
 * * the local session (this phone plays or has it loaded) on every relevant change — track,
 *   status, position, modes — and every `intervalMs` while it plays;
 * * once playback leaves the phone (a transfer, another device taking over, the engine's reset),
 *   one last save of the session that was playing at that moment, and then nothing: the stored
 *   session stays frozen (remote or empty snapshots never update it, and the periodic save of the
 *   last local snapshot stops instead of extrapolating it further);
 * * a clear on logout (after having been logged in), and no saves while logged out.
 */
internal object ResumeSaver {
    sealed interface Action {
        data class Save(val state: ResumeState) : Action
        data object Clear : Action
    }

    private data class Input(
        /** The local session to save; null when playback is not on this phone, or logged out. */
        val local: PlaybackSnapshot?,
        val loggedIn: Boolean,
        /** Logged out after having been logged in: forget the stored session. */
        val loggedOut: Boolean,
        /** The local session that played until this input (playback just left the phone). */
        val handedOver: PlaybackSnapshot?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun actions(
        snapshots: Flow<PlaybackSnapshot>,
        loggedIn: Flow<Boolean>,
        intervalMs: Long,
        now: () -> Long = System::currentTimeMillis,
    ): Flow<Action> = combine(snapshots, loggedIn.distinctUntilChanged()) { s, li -> s to li }
        .scan(Input(local = null, loggedIn = false, loggedOut = false, handedOver = null)) { prev, (s, li) ->
            val local = s.takeIf { li && it.source == PlaybackSource.LOCAL && it.track != null }
            Input(
                local = local,
                loggedIn = li,
                loggedOut = !li && (prev.loggedIn || prev.loggedOut),
                handedOver = prev.local?.takeIf { local == null && li && it.isPlaying },
            )
        }
        .drop(1)
        .distinctUntilChanged { a, b ->
            a.loggedOut == b.loggedOut && a.handedOver == b.handedOver && sameSession(a.local, b.local)
        }
        .transformLatest { input ->
            fun stateOf(s: PlaybackSnapshot) = ResumeState.from(s, s.positionAt(now()))
            val local = input.local
            when {
                input.loggedOut -> emit(Action.Clear)
                local != null -> {
                    stateOf(local)?.let { emit(Action.Save(it)) }
                    if (local.isPlaying) {
                        while (true) {
                            delay(intervalMs)
                            stateOf(local)?.let { emit(Action.Save(it)) }
                        }
                    }
                }
                input.handedOver != null -> stateOf(input.handedOver)?.let { emit(Action.Save(it)) }
            }
        }
        .distinctUntilChanged()

    /** The parts of a local snapshot the stored session depends on (a change saves at once). */
    private fun sameSession(a: PlaybackSnapshot?, b: PlaybackSnapshot?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.track?.uri == b.track?.uri && a.track?.provider == b.track?.provider &&
            a.context?.uri == b.context?.uri && a.status == b.status && a.positionMs == b.positionMs &&
            a.shuffle == b.shuffle && a.smartShuffle == b.smartShuffle && a.repeat == b.repeat
    }
}
