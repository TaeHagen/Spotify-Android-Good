package com.taehagen.spotifygood.playback

import androidx.media3.common.MediaItem
import com.taehagen.spotifygood.playback.VoiceMatch.Candidate
import com.taehagen.spotifygood.playback.VoiceMatch.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRequestTest {
    @Test
    fun theActivitysRequestCarriesWhatTheAssistantSplitOut() {
        // MEDIA_PLAY_FROM_SEARCH as LinkActivity forwards it (MediaSearchRequest's fields).
        val request = VoiceRequest.of(
            query = "play my road trip playlist",
            focus = VoiceMatch.PLAYLIST_FOCUS,
            artist = null,
            album = " ",
            title = null,
            playlist = "Road Trip",
        )
        assertEquals(mapOf(Kind.PLAYLIST to "Road Trip"), request.names)
        assertEquals(setOf(Kind.PLAYLIST), request.kinds)
        assertFalse(request.isBlank)
        // The raw query goes to the catalog; without one, the name given for the focus.
        assertEquals("play my road trip playlist", request.text)
        assertEquals("Road Trip", request.copy(query = "").text)
        // "Play something".
        assertTrue(VoiceRequest.of(null, null, null, null, null, null).isBlank)
        assertFalse(VoiceRequest.of("", null, "Taylor Swift", null, null, null).isBlank)
    }

    @Test
    fun anOfflineRequestForADownloadedPlaylistNameFindsIt() {
        // The candidates the resolver builds offline: the downloaded collections only.
        val downloaded = listOf(
            Candidate("Road Trip 2019", Kind.PLAYLIST, "spotify:playlist:old"),
            Candidate("Road Trip", Kind.PLAYLIST, "spotify:playlist:road"),
            Candidate("Road Trip", Kind.ALBUM, "spotify:album:road"),
        )
        val request = VoiceRequest.of("road trip on spotifygood", VoiceMatch.PLAYLIST_FOCUS, null, null, null, "Road Trip")
        val match = VoiceMatch.best(request.query, downloaded, request.kinds, request.names)
        assertEquals("spotify:playlist:road", match?.candidate?.value)
        assertEquals(VoiceMatch.Strength.EXACT, match?.strength)
        // Its item plays the playlist as its context (the activity's play, as the session's).
        val plan = MediaIds.plan(listOf("spotify:playlist:road"), 0) { emptyList() }
        assertEquals(PlayRequest(contextUri = "spotify:playlist:road", play = true), plan?.toPlayRequest())
    }

    @Test
    fun aRequestThatFindsNothingSaysWhy() {
        assertEquals(VoiceOutcome.NoMatch(PlaybackErrorKind.NOT_AVAILABLE_OFFLINE), VoiceOutcome.of(emptyList(), offline = true))
        assertEquals(VoiceOutcome.NoMatch(PlaybackErrorKind.NOT_FOUND), VoiceOutcome.of(emptyList(), offline = false))
        val item = MediaItem.Builder().setMediaId("spotify:album:a").build()
        assertEquals(VoiceOutcome.Play(listOf(item)), VoiceOutcome.of(listOf(item), offline = true))
        // Songs found offline play as a list.
        val songs = listOf("spotify:track:1", "spotify:track:2")
        assertEquals(
            PlayRequest(trackUris = songs, startIndex = 0, play = true),
            MediaIds.plan(songs, 0) { emptyList() }?.toPlayRequest(),
        )
    }
}
