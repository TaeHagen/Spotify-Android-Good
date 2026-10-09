package com.taehagen.spotifygood.data

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
    fun aPlaylistThatCouldNotBeListedAsksAddAnyway() {
        val plan = planPlaylistAdd(listOf(t(1), t(2)), contents = null)
        assertFalse(plan.checked)
        assertTrue("never a silent duplicate", plan.asks)
        assertNull(plan.room)
        assertFalse(plan.full)
        val prompt = PlaylistAddPrompt("spotify:playlist:road", "Road trip", plan)
        assertTrue(prompt.unchecked)
        assertFalse("nobody knows which are new", prompt.offersNewOnes)
        // "Add anyway": everything, the server keeping its own limit.
        assertEquals(PlaylistAddOutcome.Send(listOf(t(1), t(2)), left = 0), playlistAddOutcome(plan, PlaylistAddChoice.ALL))
        // A checked plan is no such question.
        assertFalse(PlaylistAddPrompt("p", "P", planPlaylistAdd(listOf(t(1)), contents(1, t(1)))).unchecked)
    }

    // ---- paging ------------------------------------------------------------------------------

    /**
     * `catalog.playlistUris` of a playlist of [size] items, windows of [maxPage] (or what is asked,
     * if fewer) at a time; counts requests.
     */
    private class FakePlaylist(val size: Int, val maxPage: Int = Int.MAX_VALUE, val emptyFrom: Int = Int.MAX_VALUE) {
        var requests = 0
        val limits = mutableListOf<Int>()

        fun page(uri: String, offset: Int, limit: Int): PlaylistUris {
            requests++
            limits += limit
            val end = if (offset >= emptyFrom) offset else minOf(size, offset + minOf(limit, maxPage))
            return PlaylistUris(total = size, offset = offset, uris = (offset until end).map { "spotify:track:$it" })
        }
    }

    @Test
    fun aWholePlaylistsUrisComeInOneRequest() = runTest {
        // URIs only: the largest playlist is one small request when the server answers it whole.
        val full = FakePlaylist(PLAYLIST_MAX_ITEMS)
        val paged = pagePlaylist("spotify:playlist:p", full::page)
        assertEquals(1, full.requests)
        assertEquals(PLAYLIST_MAX_ITEMS, paged.total)
        assertEquals((0 until PLAYLIST_MAX_ITEMS).map { t(it) }, paged.items)
    }

    @Test
    fun aPlaylistIsPagedThroughTheServersWindows() = runTest {
        val fake = FakePlaylist(1_234, maxPage = 500)
        val paged = pagePlaylist("spotify:playlist:p", fake::page)
        assertEquals(3, fake.requests)
        assertEquals(1_234, paged.total)
        assertEquals((0 until 1_234).map { t(it) }, paged.items)
        // Each request asks for what is still wanted.
        assertEquals(listOf(PLAYLIST_MAX_ITEMS, PLAYLIST_MAX_ITEMS - 500, PLAYLIST_MAX_ITEMS - 1_000), fake.limits)
    }

    @Test
    fun aHugePlaylistCostsABoundedNumberOfRequests() = runTest {
        val huge = FakePlaylist(25_000)
        val paged = pagePlaylist("spotify:playlist:p", huge::page)
        assertEquals(PLAYLIST_MAX_ITEMS, paged.items.size)
        assertEquals(25_000, paged.total)
        assertEquals(1, huge.requests)
        // A server answering windows of 100: 10,000 items in 100 small requests, and no more.
        val short = FakePlaylist(25_000, maxPage = 100)
        val shortPaged = pagePlaylist("spotify:playlist:p", short::page)
        assertEquals(PLAYLIST_MAX_ITEMS, shortPaged.items.size)
        assertEquals(PLAYLIST_MAX_ITEMS / 100, short.requests)
        assertTrue(short.requests <= ADD_MAX_PAGES)
        // Even shorter windows stop at the bound.
        val tiny = FakePlaylist(25_000, maxPage = 10)
        assertEquals(ADD_MAX_PAGES * 10, pagePlaylist("spotify:playlist:p", tiny::page).items.size)
        assertEquals(ADD_MAX_PAGES, tiny.requests)
        // The last request asks for no more than is wanted.
        val capped = FakePlaylist(1_000, maxPage = 500)
        assertEquals(700, pagePlaylist("spotify:playlist:p", capped::page, maxItems = 700).items.size)
        assertEquals(listOf(700, 200), capped.limits)
    }

    @Test
    fun anEmptyPageBeforeTheEndStopsThePaging() = runTest {
        val broken = FakePlaylist(2_000, maxPage = 500, emptyFrom = 500)
        val paged = pagePlaylist("spotify:playlist:p", broken::page)
        assertEquals(2, broken.requests)
        assertEquals(500, paged.items.size)
        assertEquals(2_000, paged.total)
    }

    @Test
    fun theUriOnlyAnswerDecodes() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString(
            PlaylistUris.serializer(),
            """{"total":3,"revision":"ab","offset":1,"uris":["spotify:track:a","spotify:local:x:y:z:1"],"uids":["dead",null]}""",
        )
        assertEquals(3, page.total)
        assertEquals(listOf("spotify:track:a", "spotify:local:x:y:z:1"), page.uris)
        assertEquals(listOf("dead", null), page.uids)
    }
}
