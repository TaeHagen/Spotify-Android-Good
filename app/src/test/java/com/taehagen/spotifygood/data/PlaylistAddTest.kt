package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.components.PlaylistAddOutcome
import com.taehagen.spotifygood.ui.components.PlaylistAddPrompt
import com.taehagen.spotifygood.ui.components.playlistAddOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAddTest {
    private fun t(n: Int) = "spotify:track:$n"

    private fun contents(total: Int, vararg uris: String) = PlaylistContents(total, uris.toSet())

    // ---- "Already added" ---------------------------------------------------------------------

    @Test
    fun nothingInThePlaylistYetAddsWithoutAsking() {
        val plan = planPlaylistAdd(listOf(t(1), t(2)), contents(2, t(8), t(9)))
        assertFalse(plan.asks)
        assertEquals(listOf(t(1), t(2)), plan.itemsFor(PlaylistAddChoice.ALL))
        assertEquals(PLAYLIST_MAX_ITEMS - 2, plan.room)
    }

    @Test
    fun aSongAlreadyThereAsksAddAnywayOrDontAdd() {
        val plan = planPlaylistAdd(listOf(t(1)), contents(3, t(1), t(8), t(9)))
        assertTrue(plan.asks)
        assertEquals(1, plan.duplicates)
        val prompt = PlaylistAddPrompt("spotify:playlist:road", "Road trip", plan)
        assertFalse("nothing new to offer", prompt.offersNewOnes)
        assertEquals(PlaylistAddOutcome.Send(listOf(t(1)), left = 0), playlistAddOutcome(plan, PlaylistAddChoice.ALL))
        assertEquals("no new ones: nothing is sent", PlaylistAddOutcome.Nothing, playlistAddOutcome(plan, PlaylistAddChoice.NEW_ONES))
    }

    @Test
    fun anAlbumWithSomeSongsThereOffersTheNewOnes() {
        val album = listOf(t(1), t(2), t(3), t(4))
        val plan = planPlaylistAdd(album, contents(10, t(2), t(4)))
        assertTrue(plan.asks)
        assertEquals(2, plan.duplicates)
        assertTrue(PlaylistAddPrompt("spotify:playlist:road", "Road trip", plan).offersNewOnes)
        assertEquals(listOf(t(1), t(3)), plan.itemsFor(PlaylistAddChoice.NEW_ONES))
        assertEquals(album, plan.itemsFor(PlaylistAddChoice.ALL))
        // A source listing a song twice adds it once as a new one.
        assertEquals(listOf(t(1)), planPlaylistAdd(listOf(t(1), t(2), t(1)), contents(1, t(2))).fresh)
        // All of an album there already: "Add anyway" or nothing.
        assertFalse(PlaylistAddPrompt("p", "P", planPlaylistAdd(listOf(t(2), t(4)), contents(10, t(2), t(4)))).offersNewOnes)
    }

    @Test
    fun anAddStopsAtThePlaylistsItemLimit() {
        val plan = planPlaylistAdd(listOf(t(1), t(2), t(3), t(4), t(5)), contents(PLAYLIST_MAX_ITEMS - 2))
        assertEquals(2, plan.room)
        assertEquals(PlaylistAddOutcome.Send(listOf(t(1), t(2)), left = 3), playlistAddOutcome(plan, PlaylistAddChoice.ALL))
        val full = planPlaylistAdd(listOf(t(1)), contents(PLAYLIST_MAX_ITEMS))
        assertTrue(full.full)
        assertEquals(PlaylistAddOutcome.Full, playlistAddOutcome(full, PlaylistAddChoice.ALL))
        // The new ones are cut the same way.
        val some = planPlaylistAdd(listOf(t(1), t(2), t(3)), contents(PLAYLIST_MAX_ITEMS - 1, t(1)))
        assertEquals(PlaylistAddOutcome.Send(listOf(t(2)), left = 1), playlistAddOutcome(some, PlaylistAddChoice.NEW_ONES))
    }

    @Test
    fun aPlaylistThatCouldNotBeListedGetsEverythingAsBefore() {
        val plan = planPlaylistAdd(listOf(t(1), t(2)), contents = null)
        assertFalse(plan.asks)
        assertNull(plan.room)
        assertFalse(plan.full)
        assertEquals(PlaylistAddOutcome.Send(listOf(t(1), t(2)), left = 0), playlistAddOutcome(plan, PlaylistAddChoice.ALL))
    }

    // ---- paging ------------------------------------------------------------------------------

    /** A playlist of [size] items served [maxPage] (or what is asked, if fewer) at a time; counts requests. */
    private class FakePlaylist(val size: Int, val maxPage: Int = Int.MAX_VALUE, val emptyFrom: Int = Int.MAX_VALUE) {
        var requests = 0
        val limits = mutableListOf<Int>()

        fun page(uri: String, offset: Int, limit: Int): Playlist {
            requests++
            limits += limit
            val end = if (offset >= emptyFrom) offset else minOf(size, offset + minOf(limit, maxPage))
            val items = (offset until end).map { PlaylistItem(track = Track(uri = "spotify:track:$it", name = "T$it")) }
            return Playlist(uri = uri, name = "P", offset = offset, total = size, items = items)
        }
    }

    @Test
    fun aPlaylistIsPagedThroughInLargePages() = runTest {
        val fake = FakePlaylist(1_234)
        val paged = pagePlaylist("spotify:playlist:p", fake::page)
        assertEquals(3, fake.requests)
        assertEquals(1_234, paged.total)
        assertEquals((0 until 1_234).map { t(it) }, paged.items)
    }

    @Test
    fun aHugePlaylistCostsABoundedNumberOfRequests() = runTest {
        val huge = FakePlaylist(25_000)
        val paged = pagePlaylist("spotify:playlist:p", huge::page)
        assertEquals(PLAYLIST_MAX_ITEMS, paged.items.size)
        assertEquals(25_000, paged.total)
        assertEquals(PLAYLIST_MAX_ITEMS / ADD_PAGE_SIZE, huge.requests)
        assertTrue(huge.limits.all { it <= ADD_PAGE_SIZE })
        // A server answering short pages: still bounded.
        val short = FakePlaylist(25_000, maxPage = 100)
        val shortPaged = pagePlaylist("spotify:playlist:p", short::page)
        assertEquals(ADD_MAX_PAGES, short.requests)
        assertEquals(ADD_MAX_PAGES * 100, shortPaged.items.size)
        // The last page asks for no more than is wanted.
        val capped = FakePlaylist(1_000)
        assertEquals(700, pagePlaylist("spotify:playlist:p", capped::page, maxItems = 700).items.size)
        assertEquals(listOf(500, 200), capped.limits)
    }

    @Test
    fun anEmptyPageBeforeTheEndStopsThePaging() = runTest {
        val broken = FakePlaylist(2_000, emptyFrom = 500)
        val paged = pagePlaylist("spotify:playlist:p", broken::page)
        assertEquals(2, broken.requests)
        assertEquals(500, paged.items.size)
        assertEquals(2_000, paged.total)
    }
}
