package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.playback.BrowsePaging.Request
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowsePagingTest {
    @Test
    fun aPagingBrowserGetsEveryPage() {
        // A 1 234-row list in pages of 100: page 12 is the last one, nothing past it.
        val last = BrowsePaging.request(0, 12, 100)!!
        assertEquals(Request(1_200, 100, continued = false), last)
        assertNull(BrowsePaging.moreAt(last, 1_234))
        assertEquals(Request(13_000, 100, continued = false), BrowsePaging.request(0, 130, 100))
        assertEquals(Request(0, 10, continued = false), BrowsePaging.request(0, -1, 10))
    }

    @Test
    fun aBrowserThatDoesNotPageReachesTheRestThroughMoreRows() {
        // Android Auto asks once for everything: one window, then a "More" row.
        val first = BrowsePaging.request(0, 0, Int.MAX_VALUE)!!
        assertEquals(Request(0, BrowsePaging.WINDOW, continued = true), first)
        assertEquals(BrowsePaging.WINDOW, BrowsePaging.moreAt(first, 1_234))
        // The "More" row opens the next window, and so on to the end.
        val id = BrowsePaging.moreId(LibraryTree.LIKED, 200)
        assertEquals(BrowsePaging.More(LibraryTree.LIKED, 200), BrowsePaging.parseMore(id))
        assertEquals(LibraryTree.LIKED, BrowsePaging.listIdOf(id))
        val next = BrowsePaging.request(200, 0, Int.MAX_VALUE)!!
        assertEquals(Request(200, BrowsePaging.WINDOW, continued = true), next)
        val end = BrowsePaging.request(1_200, 0, Int.MAX_VALUE)!!
        assertNull("the last window has no More row", BrowsePaging.moreAt(end, 1_234))
        assertNull("a short list has none", BrowsePaging.moreAt(first, 40))
        // An unpaged request asks for page 0 only.
        assertNull(BrowsePaging.request(0, 1, Int.MAX_VALUE))
        // A page larger than a window is cut, and its rest is a More row too.
        val big = BrowsePaging.request(0, 1, 1_000)!!
        assertEquals(Request(1_000, BrowsePaging.WINDOW, continued = true), big)
        assertEquals(1_200, BrowsePaging.moreAt(big, 1_234))
        // A paged More list pages from its offset.
        assertEquals(Request(250, 50, continued = false), BrowsePaging.request(200, 1, 50))
    }

    @Test
    fun onlyMoreRowsParseAsMoreRows() {
        assertNull(BrowsePaging.parseMore("spotify:playlist:p"))
        assertNull(BrowsePaging.parseMore("ctx|spotify:playlist:p|spotify:track:1"))
        assertNull(BrowsePaging.parseMore("more|x|spotify:playlist:p"))
        assertNull(BrowsePaging.parseMore("more|0|spotify:playlist:p"))
        assertEquals("spotify:playlist:p", BrowsePaging.listIdOf("spotify:playlist:p"))
        // The list id is kept whole.
        assertEquals(BrowsePaging.More("a|b", 5), BrowsePaging.parseMore("more|5|a|b"))
    }

    @Test
    fun aPlaylistPagesPastItsCachedFirstPage() = runBlocking {
        // 400 rows, the cached first page holds 100: rows 150-349 come from 100-row fetches.
        val all = (0 until 400).toList()
        val calls = mutableListOf<Pair<Int, Int>>()
        val rows = BrowsePaging.window(all.take(100), 400, 50, 300, pageLimit = 100) { offset, limit ->
            calls += offset to limit
            all.subList(offset, offset + limit)
        }
        assertEquals((50 until 350).toList(), rows)
        assertEquals(listOf(100 to 100, 200 to 100, 300 to 50), calls)
        // Past the end: nothing, no call.
        calls.clear()
        assertEquals(emptyList<Int>(), BrowsePaging.window(all.take(100), 400, 400, 200, 100) { o, l -> calls += o to l; emptyList() })
        assertEquals(emptyList<Pair<Int, Int>>(), calls)
    }

    @Test
    fun likedSongsPageByTheirTotal() = runBlocking {
        val all = (0 until 800).toList()
        val fetch: suspend (Int, Int) -> BrowsePaging.Fetched<Int> = { offset, limit ->
            BrowsePaging.Fetched(all.subList(offset.coerceAtMost(800), (offset + limit).coerceAtMost(800)), 800)
        }
        val window = BrowsePaging.fetchWindow(600, 200, pageLimit = 500, fetch = fetch)
        assertEquals((600 until 800).toList(), window.items)
        assertEquals(800, window.total)
        // A window across the end stops at it; one past it is empty but knows the size.
        assertEquals((700 until 800).toList(), BrowsePaging.fetchWindow(700, 200, 500, fetch).items)
        assertEquals(BrowsePaging.Fetched(emptyList<Int>(), 800), BrowsePaging.fetchWindow(900, 200, 500, fetch))
        // More than one call's limit.
        assertEquals((0 until 700).toList(), BrowsePaging.fetchWindow(0, 700, 500, fetch).items)
    }
}
