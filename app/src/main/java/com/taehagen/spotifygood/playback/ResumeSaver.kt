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
 * lands after the clear that follows it). The stored session is the account's last session as
 * this phone saw it, like Spotify resumes it:
 * * the session playing or loaded here, or on the remote device this phone mirrors, on every
 *   relevant change — track, status, position, modes — and every `intervalMs` while it plays.
 *   After a transfer to a speaker it follows the speaker, so when that device goes away (switched
 *   off: nothing is active any more) Play here continues what it played, not what played here
 *   before the transfer;
 * * once nothing is active any more (the device left, the engine's reset), one last save of the
 *   session that was playing at that moment, extrapolated to then, and then nothing: the stored
 *   session stays frozen (the periodic save of the last snapshot stops instead of extrapolating
 *   it further);
 * * a clear on logout (after having been logged in), and no saves while logged out.
 */
internal object ResumeSaver {
    sealed interface Action {
        data class Save(val state: ResumeState) : Action
        data object Clear : Action
    }

    private data class Input(
        /** The session to save (local or mirrored); null when nothing is active, or logged out. */
        val session: PlaybackSnapshot?,
        val loggedIn: Boolean,
        /** Logged out after having been logged in: forget the stored session. */
        val loggedOut: Boolean,
        /** The session that played until this input (nothing is active any more). */
        val handedOver: PlaybackSnapshot?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun actions(
        snapshots: Flow<PlaybackSnapshot>,
        loggedIn: Flow<Boolean>,
        intervalMs: Long,
        now: () -> Long = System::currentTimeMillis,
    ): Flow<Action> = combine(snapshots, loggedIn.distinctUntilChanged()) { s, li -> s to li }
        .scan(Input(session = null, loggedIn = false, loggedOut = false, handedOver = null)) { prev, (s, li) ->
            val session = s.takeIf { li && it.source != PlaybackSource.NONE && it.track != null }
            Input(
                session = session,
                loggedIn = li,
                loggedOut = !li && (prev.loggedIn || prev.loggedOut),
                handedOver = prev.session?.takeIf { session == null && li && it.isPlaying },
            )
        }
        .drop(1)
        .distinctUntilChanged { a, b ->
            a.loggedOut == b.loggedOut && a.handedOver == b.handedOver && sameSession(a.session, b.session)
        }
        .transformLatest { input ->
            fun stateOf(s: PlaybackSnapshot) = now().let { t -> ResumeState.from(s, s.positionAt(t), t) }
            val session = input.session
            when {
                input.loggedOut -> emit(Action.Clear)
                session != null -> {
                    stateOf(session)?.let { emit(Action.Save(it)) }
                    if (session.isPlaying) {
                        while (true) {
                            delay(intervalMs)
                            stateOf(session)?.let { emit(Action.Save(it)) }
                        }
                    }
                }
                input.handedOver != null -> stateOf(input.handedOver)?.let { emit(Action.Save(it)) }
            }
        }
        .distinctUntilChanged()

    /** The parts of a snapshot the stored session depends on (a change saves at once). */
    private fun sameSession(a: PlaybackSnapshot?, b: PlaybackSnapshot?): Boolean {
        if (a == null || b == null) return a == null && b == null
        return a.track?.uri == b.track?.uri && a.track?.provider == b.track?.provider &&
            a.context?.uri == b.context?.uri && a.status == b.status && a.positionMs == b.positionMs &&
            a.shuffle == b.shuffle && a.smartShuffle == b.smartShuffle && a.repeat == b.repeat
    }
}
