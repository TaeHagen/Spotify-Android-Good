package com.taehagen.spotifygood.ui.screens.search

import com.taehagen.spotifygood.data.SearchType
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.PlaylistRef
import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import com.taehagen.spotifygood.ui.screens.library.debouncedInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {
    private val album = AlbumRef("spotify:album:a", "Album")
    private val track = Track("spotify:track:t", "Song", artists = listOf(ArtistRef("spotify:artist:x", "X")), album = album)

    @Test
    fun typingIsDebouncedAndClearingIsImmediate() = runTest {
        val input = MutableSharedFlow<String>()
        val out = mutableListOf<String>()
        val job = launch { input.debouncedInput(300).toList(out) }
        runCurrent()

        input.emit("p")
        advanceTimeBy(100)
        input.emit("po")
        advanceTimeBy(100)
        input.emit("pop ")
        advanceTimeBy(299)
        assertEquals(emptyList<String>(), out)
        advanceTimeBy(2)
        assertEquals(listOf("pop"), out)

        // Same text after trimming: no new search.
        input.emit(" pop")
        advanceTimeBy(400)
        assertEquals(listOf("pop"), out)

        // Clearing applies without waiting.
        input.emit("")
        runCurrent()
        assertEquals(listOf("pop", ""), out)
        input.emit("   ")
        advanceTimeBy(400)
        assertEquals(listOf("pop", ""), out)
        job.cancel()
    }

    @Test
    fun staleQueryIsDroppedWhenTypingContinues() = runTest {
        val input = MutableSharedFlow<String>()
        val out = mutableListOf<String>()
        val job = launch { input.debouncedInput(300).toList(out) }
        runCurrent()
        input.emit("rock")
        advanceTimeBy(250)
        input.emit("rocket")
        advanceTimeBy(301)
        assertEquals(listOf("rocket"), out)
        job.cancel()
    }

    @Test
    fun filtersMapToTypes() {
        assertNull(SearchFilter.TOP.type)
        assertEquals(SearchType.TRACK, SearchFilter.SONGS.type)
        assertEquals(SearchType.SHOW, SearchFilter.PODCASTS.type)
        assertEquals(SearchType.EPISODE, searchTypeOf("episode"))
        assertNull(searchTypeOf("nope"))
    }

    @Test
    fun itemsOfTypeAndKeys() {
        val results = SearchResults(tracks = listOf(track), albums = listOf(album))
        val songs = results.itemsOf(SearchType.TRACK)
        assertEquals(listOf("track:spotify:track:t"), songs.map { it.key })
        assertEquals(MediaType.TRACK, songs.single().ref.type)
        assertEquals("X", songs.single().ref.subtitle)
        assertEquals(listOf("album:spotify:album:a"), results.itemsOf(SearchType.ALBUM).map { it.key })
        assertTrue(results.itemsOf(SearchType.SHOW).isEmpty())
    }

    @Test
    fun topResultFallsBackToArtistThenTrack() {
        val server = MediaRef(MediaType.PLAYLIST, "spotify:playlist:p", "P")
        assertEquals(server, SearchResults(topResult = server, tracks = listOf(track)).topResultOrBest())
        val artist = ArtistRef("spotify:artist:a", "A")
        assertEquals("spotify:artist:a", SearchResults(tracks = listOf(track), artists = listOf(artist)).topResultOrBest()?.uri)
        assertEquals("spotify:track:t", SearchResults(tracks = listOf(track)).topResultOrBest()?.uri)
        assertNull(SearchResults().topResultOrBest())
    }

    @Test
    fun topSectionsAreDistinctAndIndexed() {
        val results = SearchResults(tracks = List(8) { track.copy(uri = "spotify:track:$it") } + track.copy(uri = "spotify:track:0")).distinct()
        assertEquals(8, results.tracks.size)
        val sections = results.toTopSections(songCount = 5)
        assertEquals(5, sections.songs.size)
        assertTrue(sections.byUri["spotify:track:7"] is SearchItem.Song)
    }

    @Test
    fun playlistActionTargetKnowsOwnership() {
        val playlist = PlaylistRef("spotify:playlist:p", "Mine", owner = PlaylistOwner("me"))
        val item = SearchItem.PlaylistItem(playlist)
        assertEquals(true, (item.actionTarget("me") as MediaActionTarget.PlaylistTarget).isOwned)
        assertEquals(false, (item.actionTarget("other") as MediaActionTarget.PlaylistTarget).isOwned)
        assertEquals(false, (item.actionTarget(null) as MediaActionTarget.PlaylistTarget).isOwned)
        val songTarget = SearchItem.Song(track).actionTarget("me") as MediaActionTarget.TrackTarget
        assertEquals("spotify:album:a", songTarget.contextUri)
    }

    @Test
    fun cacheEvictsLeastRecentlyUsed() {
        val cache = SearchCache<Int>(capacity = 2)
        cache["a"] = 1
        cache["b"] = 2
        assertEquals(1, cache["a"])
        cache["c"] = 3
        assertNull(cache["b"])
        assertEquals(1, cache["a"])
        assertEquals(3, cache["c"])
    }
}
