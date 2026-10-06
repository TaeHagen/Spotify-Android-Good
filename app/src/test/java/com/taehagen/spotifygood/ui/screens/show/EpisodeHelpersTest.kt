package com.taehagen.spotifygood.ui.screens.show

import com.taehagen.spotifygood.model.Episode
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeHelpersTest {
    private fun episode(resume: Long?, duration: Long = 60_000, fullyPlayed: Boolean? = null) =
        Episode(uri = "spotify:episode:1", name = "E", durationMs = duration, resumePositionMs = resume, fullyPlayed = fullyPlayed)

    @Test
    fun resumesWherePlaybackStopped() {
        assertEquals(30_000, episode(resume = 30_000).resumePosition())
    }

    @Test
    fun startsOverWhenFullyPlayedOrNeverStarted() {
        assertEquals(0, episode(resume = 30_000, fullyPlayed = true).resumePosition())
        assertEquals(0, episode(resume = null).resumePosition())
        assertEquals(0, episode(resume = -5).resumePosition())
    }

    @Test
    fun clampsToTheDurationWhenKnown() {
        assertEquals(60_000, episode(resume = 90_000).resumePosition())
        assertEquals(90_000, episode(resume = 90_000, duration = 0).resumePosition())
    }
}
