package com.taehagen.spotifygood.nativebridge

import com.taehagen.spotifygood.playback.PodcastSpeeds
import kotlin.math.ceil

/**
 * Buffer sizing of the PCM track ([AudioSinkBridge]; pure, JVM-testable). The fill level is
 * ~250 ms of wall-clock audio at any speed: librespot's position is the decoded one, so what the
 * track holds is dropped by a pause and still plays after a seek. AudioTrack refuses a speed it
 * cannot time-stretch in its capacity (about the speed times the 1x minimum), so a track for a
 * podcast speed above 1x is built with room for the fastest one; any other is built at the 1x fill,
 * which an output re-route (that rebuilds the server track at the capacity) cannot enlarge.
 */
internal object SinkBuffer {
    /** Capacity of a track for [speed], in multiples of the 1x fill. */
    fun capacityScale(speed: Float): Int = if (speed > 1f) ceil(PodcastSpeeds.MAX).toInt() else 1

    /** A track built with [scale] has no room for [speed]: it is built again, larger. */
    fun needsLargerTrack(scale: Int, speed: Float): Boolean = capacityScale(speed) > scale

    /** The fill level for [speed]: the 1x fill ([baseFrames]) times the speed (never less than 1x). */
    fun fillFrames(baseFrames: Int, speed: Float): Int = ceil(baseFrames * maxOf(1f, speed)).toInt()

    /**
     * The track's effective buffer ([current] frames) grew past the fill last set ([expected]): a
     * restore (a re-route, an audioserver restart) rebuilt it at its capacity. Put the fill back.
     */
    fun grew(current: Int, expected: Int): Boolean = expected > 0 && current > expected
}
