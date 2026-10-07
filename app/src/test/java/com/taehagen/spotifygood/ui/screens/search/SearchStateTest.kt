package com.taehagen.spotifygood.ui.screens.search

import com.taehagen.spotifygood.model.SearchResults
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.library.PageResult
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchStateTest {
    private data class Key(val query: String, val type: String)

    @Test
    fun theTypedListSurvivesTheScreenGoingAwayAndIsNotRefetched() = runTest {
        val requests = mutableListOf<Pair<Key, Int>>()
        val pages = KeptPages<Key, String>(this, pageSize = 30, keyOf = { it }) { key, offset, limit ->
            requests += key to offset
            PageResult((offset until offset + limit).map { "${key.query}$it" }, total = 120)
        }
        val albums = Key("daft punk", "album")
        pages.select(albums)
        advanceUntilIdle()
        pages.loader!!.loadMore()
        advanceUntilIdle()
        pages.loader!!.loadMore()
        advanceUntilIdle()
        assertEquals(90, pages.state.value!!.items.size)

        // The screen comes back (its subscription restarts and selects the same list again).
        pages.select(albums)
        advanceUntilIdle()
        assertEquals("the loaded pages are kept", 90, pages.state.value!!.items.size)
        assertEquals("nothing is fetched again", listOf(0, 30, 60), requests.map { it.second })

        // Another query (or a Retry generation) starts over; no key drops the list.
        val other = Key("daft", "album")
        pages.select(other)
        advanceUntilIdle()
        assertEquals(other to 0, requests.last())
        assertEquals(30, pages.state.value!!.items.size)
        pages.select(null)
        assertNull(pages.state.value)
        assertNull(pages.loader)
    }

    @Test
    fun aDroppedListNoLongerUpdatesTheState() = runTest {
        val pages = KeptPages<Key, String>(this, pageSize = 2, keyOf = { it }) { key, offset, _ ->
            PageResult(listOf("${key.query}$offset"), total = 10)
        }
        pages.select(Key("a", "track"))
        pages.select(Key("b", "track")) // before the first list's page arrived
        advanceUntilIdle()
        assertEquals(listOf("b0"), pages.state.value!!.items)
        pages.select(null) // ends the list's coroutines
    }

    @Test
    fun partialOrEmptyTopResultsAreNotKept() {
        val song = Track(uri = "spotify:track:x", name = "x")
        assertTrue(SearchResults(tracks = listOf(song)).cacheable())
        assertFalse("a failed source: ask again", SearchResults(tracks = listOf(song), partial = true).cacheable())
        assertFalse("empty: ask again", SearchResults().cacheable())
    }
}
