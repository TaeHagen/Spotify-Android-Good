package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * When a media-session load ([SpotifyPlayer.handleSetMediaItems]) has settled. Media3 keeps its
 * placeholder state (BUFFERING with the requested items, and with it the media notification and
 * the foreground) until the operation's future completes, and only then reads the player's real
 * state. A cold load passes through snapshots without a track: the engine's first empty snapshot,
 * then the Spirc's activation before its context is resolved. Settling on "any new snapshot"
 * would publish IDLE with an empty playlist there, and Media3 would remove the notification and
 * leave the foreground mid-resume (a second foreground start from the background may then be
 * refused). So a load settles only on a snapshot that shows a track (or remote playback), or once
 * the start failed ([PlayerController.failure]).
 */
internal object LoadSettle {
    fun isSettled(before: PlaybackSnapshot, now: PlaybackSnapshot, failed: Boolean): Boolean =
        failed || (now != before && (now.source == PlaybackSource.REMOTE || now.track?.let(QueueWindow::isVisible) == true))

    /** Suspends (≤ [timeoutMs]) until [isSettled]; false at the timeout. */
    suspend fun await(
        before: PlaybackSnapshot,
        snapshots: Flow<PlaybackSnapshot>,
        failure: Flow<PlaybackFailure?>,
        timeoutMs: Long,
    ): Boolean = withTimeoutOrNull(timeoutMs) {
        combine(snapshots, failure) { s, f -> isSettled(before, s, f != null) }.first { it }
    } ?: false
}
