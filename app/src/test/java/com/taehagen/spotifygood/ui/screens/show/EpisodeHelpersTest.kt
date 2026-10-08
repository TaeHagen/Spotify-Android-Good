package com.taehagen.spotifygood.ui.screens.show

import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun theEpisodePagesPlayWhileOfflineNeedsItsDownload() {
        val uri = "spotify:episode:1"
        val show = "spotify:show:s"
        // Offline a show load starts its downloads: one that isn't downloaded would start another.
        assertNull(episodePlayRequest(uri, show, EngineReach.OFFLINE, null))
        assertNull(episodePlayRequest(uri, show, EngineReach.OFFLINE, DownloadState.DOWNLOADING))
        assertNull(episodePlayRequest(uri, null, EngineReach.OFFLINE, null))
        val inShow = PlayRequest(contextUri = show, startUri = uri)
        assertEquals(inShow, episodePlayRequest(uri, show, EngineReach.OFFLINE, DownloadState.COMPLETED))
        // Connecting: the offline plan sends the start item alone; online: as is.
        assertEquals(inShow, episodePlayRequest(uri, show, EngineReach.CONNECTING, null))
        assertEquals(inShow, episodePlayRequest(uri, show, EngineReach.ONLINE, null))
        assertEquals(PlayRequest(trackUris = listOf(uri)), episodePlayRequest(uri, null, EngineReach.ONLINE, null))
        // No position named: the player resumes it (looking Spotify's point up when needed).
        assertEquals(0L, episodePlayRequest(uri, show, EngineReach.ONLINE, null)!!.positionMs)
    }
}
