package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.RepeatMode

/** The three states of the shuffle button (docs/ARCHITECTURE.md §7). */
enum class ShuffleMode { OFF, SHUFFLE, SMART }

/** Tri-state shuffle of this snapshot (smart shuffle implies shuffle). */
val PlaybackSnapshot.shuffleMode: ShuffleMode
    get() = when {
        smartShuffle -> ShuffleMode.SMART
        shuffle -> ShuffleMode.SHUFFLE
        else -> ShuffleMode.OFF
    }

/**
 * Smart shuffle is implemented by the local Spirc only (remote devices report `UNAVAILABLE`) and
 * only makes sense for playlist-like contexts (docs §7).
 */
val PlaybackSnapshot.isSmartShuffleAvailable: Boolean
    get() = source == PlaybackSource.LOCAL &&
        (context?.type == ContextType.PLAYLIST || context?.type == ContextType.COLLECTION)

/** Pure state machines of the shuffle / repeat buttons (shared by the UI, notification and Auto). */
internal object PlaybackModes {
    /** off → shuffle → smart shuffle → off; smart shuffle is skipped when unavailable. */
    fun nextShuffle(current: ShuffleMode, smartAvailable: Boolean): ShuffleMode = when (current) {
        ShuffleMode.OFF -> ShuffleMode.SHUFFLE
        ShuffleMode.SHUFFLE -> if (smartAvailable) ShuffleMode.SMART else ShuffleMode.OFF
        ShuffleMode.SMART -> ShuffleMode.OFF
    }

    /** off → context → track → off. */
    fun nextRepeat(current: RepeatMode): RepeatMode = when (current) {
        RepeatMode.OFF -> RepeatMode.CONTEXT
        RepeatMode.CONTEXT -> RepeatMode.TRACK
        RepeatMode.TRACK -> RepeatMode.OFF
    }

    /** Wire value of `player.setRepeat {mode}`. */
    fun wire(mode: RepeatMode): String = when (mode) {
        RepeatMode.OFF -> "off"
        RepeatMode.CONTEXT -> "context"
        RepeatMode.TRACK -> "track"
    }
}
