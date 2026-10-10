package com.taehagen.spotifygood.widget

import com.taehagen.spotifygood.model.ActiveDeviceRef
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.ContextType
import com.taehagen.spotifygood.model.DeviceType
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackRestrictions
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.best
import com.taehagen.spotifygood.playback.ResumeState
import com.taehagen.spotifygood.playback.ShuffleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelsTest {
    private val track = PlaybackTrack(
        uri = "spotify:track:t",
        name = "Song",
        artists = listOf(ArtistRef(uri = "spotify:artist:a", name = "Artist A"), ArtistRef(uri = "spotify:artist:b", name = "Artist B")),
        album = AlbumRef(
            uri = "spotify:album:al",
            name = "Album",
            images = listOf(Image("https://i.scdn.co/image/small", 64, 64), Image("https://i.scdn.co/image/cover", 300, 300), Image("https://i.scdn.co/image/large", 640, 640)),
        ),
    )

    private fun playing(
        source: PlaybackSource = PlaybackSource.LOCAL,
        status: PlaybackStatus = PlaybackStatus.PLAYING,
        item: PlaybackTrack = track,
    ) = PlaybackSnapshot(
        source = source,
        status = status,
        positionMs = 42_000,
        context = PlaybackContext("spotify:playlist:p", type = ContextType.PLAYLIST),
        track = item,
        activeDevice = if (source == PlaybackSource.REMOTE) {
            ActiveDeviceRef("kitchen", "Kitchen", DeviceType.SPEAKER)
        } else {
            ActiveDeviceRef("phone", "This phone", DeviceType.SMARTPHONE)
        },
    )

    private val resume = ResumeState(
        contextUri = "spotify:album:al",
        trackUri = "spotify:track:r",
        positionMs = 61_000,
        title = "Stored song",
        artist = "Stored artist",
        album = "Stored album",
        artworkUrl = "https://i.scdn.co/image/stored",
        durationMs = 200_000,
        isEpisode = false,
        shuffle = true,
    )

    private fun item(model: WidgetModel): WidgetModel.Item = model as WidgetModel.Item

    @Test
    fun loggedOutShowsSignInWhateverPlays() {
        assertEquals(WidgetModel.SignedOut, WidgetModels.of(false, playing(), liked = true, resume = resume))
        assertEquals(WidgetModel.SignedOut, WidgetModels.of(false, PlaybackSnapshot.EMPTY, liked = null, resume = null))
        assertFalse(WidgetModels.needsResume(false, PlaybackSnapshot.EMPTY))
    }

    @Test
    fun localPlaybackShowsTheTrackWithoutADevice() {
        val model = item(WidgetModels.of(true, playing(), liked = false, resume = resume))
        assertEquals("spotify:track:t", model.uri)
        assertEquals("Song", model.title)
        assertEquals("Artist A, Artist B", model.subtitle)
        // Every size, so that a large widget can use the large cover.
        assertEquals(track.album!!.images, model.art)
        assertEquals("https://i.scdn.co/image/cover", model.art.best(256))
        assertEquals("https://i.scdn.co/image/large", model.art.best(512))
        assertTrue(model.live)
        assertTrue(model.playing)
        assertNull(model.device)
        assertEquals(false, model.liked)
        assertEquals(ShuffleMode.OFF, model.shuffle)
        assertTrue(model.canSkipBack)
        assertTrue(model.canSkipForward)
        assertFalse(WidgetModels.needsResume(true, playing()))
    }

    @Test
    fun remotePlaybackNamesTheConnectDevice() {
        val model = item(WidgetModels.of(true, playing(source = PlaybackSource.REMOTE), liked = true, resume = null))
        assertEquals("Kitchen", model.device)
        assertTrue(model.live)
        assertEquals(true, model.liked)
    }

    @Test
    fun pausedShowsPlayAndLoadingToPlayShowsPause() {
        assertFalse(item(WidgetModels.of(true, playing(status = PlaybackStatus.PAUSED), null, null)).playing)
        assertTrue(item(WidgetModels.of(true, playing(status = PlaybackStatus.LOADING), null, null)).playing)
    }

    @Test
    fun theLikeStateIsPassedOnAndUnknownHidesIt() {
        assertEquals(true, item(WidgetModels.of(true, playing(), liked = true, resume = null)).liked)
        assertEquals(false, item(WidgetModels.of(true, playing(), liked = false, resume = null)).liked)
        assertNull(item(WidgetModels.of(true, playing(), liked = null, resume = null)).liked)
    }

    @Test
    fun theModelHoldsNoPosition() {
        // Only position changes: the same model, so the widget is not pushed.
        val a = WidgetModels.of(true, playing(), liked = true, resume = null)
        val b = WidgetModels.of(true, playing().copy(positionMs = 99_000, positionTimestampMs = 123), liked = true, resume = null)
        assertEquals(a, b)
    }

    @Test
    fun nothingLoadedShowsTheStoredSessionWithPlayOnly() {
        assertTrue(WidgetModels.needsResume(true, PlaybackSnapshot.EMPTY))
        val model = item(WidgetModels.of(true, PlaybackSnapshot.EMPTY, liked = true, resume = resume))
        assertEquals("spotify:track:r", model.uri)
        assertEquals("Stored song", model.title)
        assertEquals("Stored artist", model.subtitle)
        assertEquals(listOf(Image("https://i.scdn.co/image/stored")), model.art)
        assertFalse(model.live)
        assertFalse(model.playing)
        assertNull(model.device)
        // Nothing but Play: no like, no shuffle, no skips (there is no session to act on).
        assertNull(model.liked)
        assertNull(model.shuffle)
        assertFalse(model.canSkipBack)
        assertFalse(model.canSkipForward)
    }

    @Test
    fun aFrozenSnapshotWithoutAnActiveDeviceIsNotLive() {
        // The device left: the snapshot keeps its track but nothing is active any more.
        val frozen = playing(status = PlaybackStatus.PAUSED).copy(source = PlaybackSource.NONE, activeDevice = null)
        assertTrue(WidgetModels.needsResume(true, frozen))
        assertFalse(item(WidgetModels.of(true, frozen, liked = true, resume = resume)).live)
    }

    @Test
    fun nothingLoadedAndNothingStoredIsIdle() {
        assertEquals(WidgetModel.Idle, WidgetModels.of(true, PlaybackSnapshot.EMPTY, liked = null, resume = null))
    }

    @Test
    fun episodesSkip15sAndHaveNoShuffle() {
        val episode = PlaybackTrack(
            uri = "spotify:episode:e",
            name = "Episode",
            isEpisode = true,
            show = ShowRef(uri = "spotify:show:s", name = "The Show"),
        )
        val snapshot = playing(item = episode).copy(restrictions = PlaybackRestrictions(canSkipPrev = false, canSkipNext = false, canSeek = true))
        val model = item(WidgetModels.of(true, snapshot, liked = false, resume = null))
        assertTrue(model.isEpisode)
        assertEquals("The Show", model.subtitle)
        assertNull(model.shuffle)
        assertFalse(model.smartShuffleAvailable)
        assertTrue(model.canSkipBack)
        assertTrue(model.canSkipForward)
        val unseekable = item(WidgetModels.of(true, snapshot.copy(restrictions = PlaybackRestrictions(canSeek = false)), null, null))
        assertFalse(unseekable.canSkipBack)
        assertFalse(unseekable.canSkipForward)
    }

    @Test
    fun restrictionsDisableTheSkipsAndHideShuffle() {
        val restricted = playing().copy(restrictions = PlaybackRestrictions(canSkipPrev = false, canSkipNext = true, canToggleShuffle = false))
        val model = item(WidgetModels.of(true, restricted, null, null))
        assertFalse(model.canSkipBack)
        assertTrue(model.canSkipForward)
        assertNull(model.shuffle)
    }

    @Test
    fun theShuffleModeAndSmartShuffleAvailability() {
        val shuffled = item(WidgetModels.of(true, playing().copy(shuffle = true), null, null))
        assertEquals(ShuffleMode.SHUFFLE, shuffled.shuffle)
        assertTrue(shuffled.smartShuffleAvailable)
        assertEquals(ShuffleMode.SMART, item(WidgetModels.of(true, playing().copy(shuffle = true, smartShuffle = true), null, null)).shuffle)
        // Remote devices have no smart shuffle.
        assertFalse(item(WidgetModels.of(true, playing(source = PlaybackSource.REMOTE).copy(shuffle = true), null, null)).smartShuffleAvailable)
    }
}
