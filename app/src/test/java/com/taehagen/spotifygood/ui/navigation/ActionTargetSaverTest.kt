package com.taehagen.spotifygood.ui.navigation

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActionTargetSaverTest {
    private val album = AlbumRef(
        uri = "spotify:album:a",
        name = "Album",
        images = listOf(Image("https://i.scdn.co/image/a")),
        artists = listOf(ArtistRef("spotify:artist:x", "Artist")),
    )

    private fun roundTrip(target: MediaActionTarget): MediaActionTarget? {
        val saved = encodeActionTarget(target)
        assertNotNull("saved", saved)
        return decodeActionTarget(saved!!)
    }

    @Test
    fun everyTargetKindComesBackAfterAConfigurationChange() {
        val targets = listOf(
            MediaActionTarget.TrackTarget(
                Track(uri = "spotify:track:t", name = "Song", album = album, explicit = true),
                contextUri = "spotify:playlist:p",
                playlistUri = "spotify:playlist:p",
                playlistIndex = 3,
                playlistRevision = "rev",
                queueUid = "q1",
            ),
            MediaActionTarget.AlbumTarget(album),
            MediaActionTarget.ArtistTarget(ArtistRef("spotify:artist:x", "Artist")),
            MediaActionTarget.PlaylistTarget(PlaylistRef(uri = "spotify:playlist:p", name = "Mix"), isOwned = true),
            MediaActionTarget.ShowTarget(ShowRef(uri = "spotify:show:s", name = "Show", publisher = "P")),
        )
        for (target in targets) assertEquals(target, roundTrip(target))
    }

    @Test
    fun anEpisodeIsSavedWithoutItsDescription() {
        val episode = Episode(uri = "spotify:episode:e", name = "Ep", description = "x".repeat(100_000))
        val restored = roundTrip(MediaActionTarget.EpisodeTarget(episode)) as MediaActionTarget.EpisodeTarget
        assertEquals(episode.copy(description = ""), restored.episode)
    }

    @Test
    fun anUnreadableOrOversizedTargetClosesTheSheet() {
        assertNull(decodeActionTarget("not json"))
        assertNull(decodeActionTarget("""{"targetKind":"gone"}"""))
        val huge = MediaActionTarget.TrackTarget(Track(uri = "spotify:track:t", name = "x".repeat(100_000)))
        assertNull(encodeActionTarget(huge))
    }
}
