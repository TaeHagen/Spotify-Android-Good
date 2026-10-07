package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.album.canStartNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStartTest {
    private val track = Track(uri = "spotify:track:t", name = "Song")
    private val explicitFiltered = track.copy(playable = false)
    private val placeholder = Track(uri = "spotify:track:p", name = "", playable = false)

    @Test
    fun unplayableTracksAreNotStarted() {
        assertEquals(TrackStartBlock.UNAVAILABLE, trackStartBlock(explicitFiltered, online = true, downloaded = false))
        assertEquals(TrackStartBlock.UNAVAILABLE, trackStartBlock(explicitFiltered, online = false, downloaded = true))
    }

    @Test
    fun aPlaceholderOrUnknownTrackIsStillTried() {
        // Metadata failed: unknown, not unplayable (playback decides, as before).
        assertNull(trackStartBlock(placeholder, online = true, downloaded = false))
        assertNull(trackStartBlock(null, online = true, downloaded = false))
    }

    @Test
    fun withoutAnOnlineSessionOnlyDownloadsStart() {
        assertEquals(TrackStartBlock.NOT_DOWNLOADED, trackStartBlock(track, online = false, downloaded = false))
        assertEquals(TrackStartBlock.NOT_DOWNLOADED, trackStartBlock(null, online = false, downloaded = false))
        assertNull(trackStartBlock(track, online = false, downloaded = true))
        assertNull(trackStartBlock(track, online = true, downloaded = false))
    }

    @Test
    fun rowsStartWhenOnlineOrDownloaded() {
        assertTrue(canStartNow(playable = true, online = true, downloadState = null))
        assertTrue(canStartNow(playable = true, online = false, downloadState = DownloadState.COMPLETED))
        assertFalse("connecting: another download would play", canStartNow(playable = true, online = false, downloadState = null))
        assertFalse(canStartNow(playable = true, online = false, downloadState = DownloadState.QUEUED))
        assertFalse(canStartNow(playable = false, online = true, downloadState = DownloadState.COMPLETED))
    }
}
