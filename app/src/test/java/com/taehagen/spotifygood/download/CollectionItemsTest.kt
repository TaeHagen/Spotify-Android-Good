package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionItemsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aPlaceholderGetsNoMetadataAndIsNotUnavailable() {
        // Its metadata failed (partial page) or the server has none: uri only, playable:false, no name.
        val item = CollectionResolver.trackItem(Track(uri = "spotify:track:1", name = "", playable = false), json)
        assertEquals("spotify:track:1", item.uri)
        assertNull("filled in later from catalog.tracks / the record", item.metadataJson)
        assertEquals(false, item.unavailable)
        val episode = CollectionResolver.episodeItem(Episode(uri = "spotify:episode:1", name = "", playable = false), json)
        assertNull(episode.metadataJson)
        assertEquals(false, episode.unavailable)
    }

    @Test
    fun aResolvedButUnplayableItemIsUnavailable() {
        val item = CollectionResolver.trackItem(Track(uri = "spotify:track:2", name = "Greyed out", playable = false), json)
        assertTrue(item.unavailable)
        assertNotNull(item.metadataJson)
        val episode = CollectionResolver.episodeItem(Episode(uri = "spotify:episode:2", name = "Region locked", playable = false), json)
        assertTrue(episode.unavailable)
    }

    @Test
    fun aPlayableItemKeepsItsMetadata() {
        val item = CollectionResolver.trackItem(Track(uri = "spotify:track:3", name = "Song"), json)
        assertEquals(false, item.unavailable)
        assertEquals("Song", item.metadataJson?.let { json.decodeFromString(Track.serializer(), it).name })
    }

    @Test
    fun onlyAWholeListingWithoutFailuresIsComplete() {
        assertTrue(DownloadRules.listingComplete(listed = 12, partial = false))
        assertEquals(false, DownloadRules.listingComplete(listed = 12, partial = true))
        assertEquals(false, DownloadRules.listingComplete(listed = 0, partial = false))
        // Sources that list every slot (playlists, Liked Songs URIs): a short listing is incomplete.
        assertTrue(DownloadRules.listingComplete(listed = 300, partial = false, total = 300))
        assertEquals(false, DownloadRules.listingComplete(listed = 200, partial = false, total = 300))
        assertEquals(false, DownloadRules.listingComplete(listed = 300, partial = true, total = 300))
    }
}
