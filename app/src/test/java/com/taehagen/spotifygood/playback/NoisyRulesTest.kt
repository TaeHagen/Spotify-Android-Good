package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoisyRulesTest {
    private val playing = PlaybackSnapshot(
        source = PlaybackSource.LOCAL,
        status = PlaybackStatus.PLAYING,
        track = PlaybackTrack(uri = "spotify:track:t"),
    )

    @Test
    fun theReceiverStaysWhilePlaybackIsMeantToGoOn() {
        assertTrue(NoisyRules.wanted(sinkActive = true, snapshot = playing, waitingForGain = false))
        // The stall watchdog stopped the sink; the snapshot still says playing (or loading).
        assertTrue(NoisyRules.wanted(sinkActive = false, snapshot = playing, waitingForGain = false))
        assertTrue(NoisyRules.wanted(false, playing.copy(status = PlaybackStatus.LOADING), false))
        // Paused for a call or a spoken prompt, a focus resume pending.
        assertTrue(NoisyRules.wanted(false, playing.copy(status = PlaybackStatus.PAUSED), waitingForGain = true))
    }

    @Test
    fun itGoesWhenNothingWillPlayHere() {
        // Paused by the user (no resume pending), stopped, nothing loaded.
        assertFalse(NoisyRules.wanted(false, playing.copy(status = PlaybackStatus.PAUSED), false))
        assertFalse(NoisyRules.wanted(false, PlaybackSnapshot.EMPTY, false))
        // Another device plays: its output is not ours to watch.
        val remote = playing.copy(source = PlaybackSource.REMOTE)
        assertFalse(NoisyRules.wanted(sinkActive = false, snapshot = remote, waitingForGain = true))
        assertFalse(NoisyRules.wanted(sinkActive = true, snapshot = remote, waitingForGain = false))
    }
}
