package com.taehagen.spotifygood.playback

import android.content.Context
import android.media.AudioDeviceInfo
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

enum class OutputKind { SPEAKER, WIRED, BLUETOOTH, USB, HDMI, CAR, HEARING_AID, OTHER }

/** A local audio output of this phone. */
data class AudioOutput(
    val id: Int,
    val name: String,
    val kind: OutputKind,
    /** Where audio is actually routed right now. */
    val isCurrent: Boolean,
    /** The user's explicit choice (null choice = system default). */
    val isPreferred: Boolean,
    val info: AudioDeviceInfo?,
)

/**
 * Local output routing (docs/ARCHITECTURE.md §9.5): lists outputs, tracks the routed device,
 * lets the user pick one (AudioTrack preferred device), opens the system output switcher and
 * reports the current output to Spotify Connect. Listeners are registered only between
 * [start] and [stop] (the engine calls these while it runs).
 */
class OutputRouteManager(
    context: Context,
    scope: CoroutineScope,
    audioSink: AudioSinkBridge,
    rpc: NativeRpc,
) {
    val outputs: StateFlow<List<AudioOutput>> get() = TODO()
    val current: StateFlow<AudioOutput?> get() = TODO()

    fun start(): Unit = TODO()
    fun stop(): Unit = TODO()

    /** null = follow the system default route. */
    fun select(output: AudioOutput?): Unit = TODO()

    /** Opens the system media output switcher (Cast, BT pairing, etc.); returns false if unsupported. */
    fun showSystemOutputSwitcher(context: Context): Boolean = TODO()
}
