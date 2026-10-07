package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.album.canStartNow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.runTest
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

    // ---- planTrackStart ----------------------------------------------------------------------

    private val album = AlbumRef(uri = "spotify:album:a", name = "Album")
    private val inAlbum = track.copy(album = album)

    private class Fakes(var online: Boolean = true, var looked: Track? = null) {
        var waits = 0
        var lookups = 0
        var superseded = false
    }

    private suspend fun plan(known: Track?, reach: EngineReach, fakes: Fakes, downloaded: Boolean = false) = planTrackStart(
        trackUri = track.uri,
        known = known,
        downloaded = downloaded,
        reach = reach,
        awaitOnline = { fakes.waits++; fakes.online },
        lookup = { fakes.lookups++; fakes.looked },
        superseded = { fakes.superseded },
    )

    @Test
    fun aLinkOpenedWhileConnectingIsLookedUpOnceOnline() = runTest {
        // The early lookup would fail NOT_CONNECTED: it happens after the session is ONLINE.
        val unplayable = Fakes(online = true, looked = explicitFiltered)
        assertEquals(TrackStartPlan.Blocked(TrackStartBlock.UNAVAILABLE), plan(null, EngineReach.CONNECTING, unplayable))
        assertEquals(1, unplayable.waits)
        assertEquals(1, unplayable.lookups)

        val playable = Fakes(online = true, looked = inAlbum)
        assertEquals(TrackStartPlan.Play(track.uri, album.uri), plan(null, EngineReach.CONNECTING, playable))
    }

    @Test
    fun aSessionThatNeverComesOnlineMeansNotDownloaded() = runTest {
        val fakes = Fakes(online = false, looked = inAlbum)
        assertEquals(TrackStartPlan.Blocked(TrackStartBlock.NOT_DOWNLOADED), plan(null, EngineReach.CONNECTING, fakes))
        assertEquals("no lookup without a session", 0, fakes.lookups)
        assertEquals(TrackStartPlan.Blocked(TrackStartBlock.NOT_DOWNLOADED), plan(inAlbum, EngineReach.OFFLINE, Fakes()))
    }

    @Test
    fun knownMetadataAndDownloadsNeedNoWait() = runTest {
        val fakes = Fakes()
        assertEquals(TrackStartPlan.Blocked(TrackStartBlock.UNAVAILABLE), plan(explicitFiltered, EngineReach.CONNECTING, fakes))
        assertEquals(TrackStartPlan.Play(track.uri, album.uri), plan(inAlbum, EngineReach.ONLINE, fakes))
        assertEquals(TrackStartPlan.Play(track.uri, album.uri), plan(inAlbum, EngineReach.CONNECTING, fakes, downloaded = true))
        assertEquals(0, fakes.waits)
        assertEquals(0, fakes.lookups)
        // Online, unknown metadata: looked up right away; a failed lookup still plays it alone.
        assertEquals(TrackStartPlan.Play(track.uri, null), plan(null, EngineReach.ONLINE, Fakes(looked = null)))
    }

    @Test
    fun aPlayStartedWhileWaitingWins() = runTest {
        val fakes = Fakes(online = true, looked = inAlbum).apply { superseded = true }
        assertEquals(TrackStartPlan.Superseded, plan(null, EngineReach.CONNECTING, fakes))
    }

    private fun t(uri: String) = PlaybackTrack(uri = uri, name = uri)

    private val local = PlaybackSnapshot(
        source = PlaybackSource.LOCAL,
        status = PlaybackStatus.PLAYING,
        context = PlaybackContext(uri = "spotify:album:x"),
        track = t("a"),
        nextTracks = listOf(t("b")),
    )

    @Test
    fun anotherLocalStartDuringTheWaitSupersedes() {
        assertTrue(startSuperseded(local, local.copy(track = t("z"))))
        assertTrue(startSuperseded(local, local.copy(context = PlaybackContext(uri = "spotify:playlist:y"), track = t("y"))))
        assertTrue("the song ended, the next one plays", !startSuperseded(local, local.copy(track = t("b"))))
        assertTrue(!startSuperseded(local, local))
        assertTrue("same track, handed back to Spirc", !startSuperseded(local, local.copy(context = PlaybackContext(uri = "spotify:internal:x"))))
    }

    @Test
    fun theSessionComingOnlineDoesNotSupersede() {
        // The first cluster after reconnecting: another device's playback replaces the empty snapshot.
        val remote = PlaybackSnapshot(
            source = PlaybackSource.REMOTE,
            status = PlaybackStatus.PLAYING,
            context = PlaybackContext(uri = "spotify:playlist:laptop"),
            track = t("l"),
        )
        assertTrue(!startSuperseded(PlaybackSnapshot(), remote))
        assertTrue(!startSuperseded(local, remote))
        // The reconnect placeholder restored: same track, playing again.
        val placeholder = local.copy(status = PlaybackStatus.PAUSED)
        assertTrue(!startSuperseded(placeholder, placeholder.copy(status = PlaybackStatus.PLAYING)))
        // Nothing to compare with (empty snapshot): a local load can't be told from a restore.
        assertTrue(!startSuperseded(PlaybackSnapshot(), local))
    }

    @Test
    fun aPreviousSingleTrackStartLandingDoesNotSupersedeTheNewerTap() {
        val landed = local.copy(context = PlaybackContext(uri = "spotify:album:tileA"), track = t("spotify:track:A"))
        assertTrue(!startSuperseded(local, landed, ownTargets = setOf("spotify:album:tileA", "spotify:track:A")))
        assertTrue(startSuperseded(local, landed, ownTargets = emptySet()))
    }
}
