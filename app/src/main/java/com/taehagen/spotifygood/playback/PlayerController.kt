package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow

/** What to play (maps to `player.load`, docs/ARCHITECTURE.md §6.2). */
data class PlayRequest(
    val contextUri: String? = null,
    val trackUris: List<String>? = null,
    val startUri: String? = null,
    val startIndex: Int? = null,
    val startUid: String? = null,
    val positionMs: Long = 0,
    val shuffle: Boolean? = null,
    val smartShuffle: Boolean? = null,
    val repeat: RepeatMode? = null,
    val play: Boolean = true,
)

/**
 * Playback commands. All commands are routed natively to the active device (this phone or a
 * remote Connect device). Methods never throw: failures are reported on [errors] (user-visible
 * message) and logged. Commands are serialised in call order.
 */
class PlayerController(
    scope: CoroutineScope,
    rpc: NativeRpc,
    playback: PlaybackRepository,
) {
    /** Human-readable errors for snackbars. */
    val errors: SharedFlow<String> get() = TODO()

    fun play(request: PlayRequest): Unit = TODO()
    fun playContext(contextUri: String, startUri: String? = null, shuffle: Boolean? = null): Unit = TODO()
    fun playTracks(trackUris: List<String>, startIndex: Int = 0): Unit = TODO()
    fun resume(): Unit = TODO()
    fun pause(): Unit = TODO()
    fun togglePlayPause(): Unit = TODO()
    fun next(): Unit = TODO()
    fun previous(): Unit = TODO()
    fun seekTo(positionMs: Long): Unit = TODO()
    fun setShuffle(enabled: Boolean): Unit = TODO()
    fun setSmartShuffle(enabled: Boolean): Unit = TODO()
    /** off → shuffle → smart shuffle → off (smart shuffle skipped when unavailable). */
    fun cycleShuffle(): Unit = TODO()
    fun setRepeat(mode: RepeatMode): Unit = TODO()
    /** off → context → track → off. */
    fun cycleRepeat(): Unit = TODO()
    /** Connect volume of the active device, 0..65535. */
    fun setVolume(volume: Int): Unit = TODO()
    fun addToQueue(uri: String): Unit = TODO()
    fun addToQueue(uris: List<String>): Unit = TODO()
    fun removeFromQueue(uid: String): Unit = TODO()
    fun moveInQueue(uid: String, toIndex: Int): Unit = TODO()
    fun clearQueue(): Unit = TODO()
    fun skipTo(uid: String): Unit = TODO()
    /** Starts a radio station seeded by [uri] (track/artist/album/playlist). */
    fun startRadio(uri: String): Unit = TODO()
}
