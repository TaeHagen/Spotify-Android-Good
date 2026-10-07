package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.Page
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingTest {
    @Test
    fun pagesUntilTotal() = runTest {
        val all = (0 until 250).toList()
        val requested = mutableListOf<Pair<Int, Int>>()
        val items = pageAll(100) { offset, limit ->
            requested += offset to limit
            Page(total = all.size, items = all.drop(offset).take(limit))
        }
        assertEquals(all, items)
        assertEquals(listOf(0 to 100, 100 to 100, 200 to 100), requested)
    }

    @Test
    fun anEmptyPageBeforeTotalFailsInsteadOfTruncating() = runTest {
        var calls = 0
        val failure = runCatching {
            pageAll(10) { offset, _ ->
                calls++
                Page(total = 1_000, items = if (offset == 0) (0 until 10).toList() else emptyList())
            }
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals(2, calls)
        // An empty page at the (new) end is fine: the list shrank between pages.
        val items = pageAll(10) { offset, _ -> if (offset == 0) Page(total = 15, items = (0 until 10).toList()) else Page(total = 8) }
        assertEquals((0 until 10).toList(), items)
    }

    @Test
    fun advancesByWholeWindowsAndReportsPartialPages() = runTest {
        val requested = mutableListOf<Int>()
        val paged = pageAllChecked(100) { offset, limit ->
            requested += offset
            val window = (offset until minOf(offset + limit, 250)).toList()
            // An older engine dropped one item of the first window.
            Page(total = 250, items = if (offset == 0) window - 57 else window, partial = offset == 100)
        }
        assertEquals(listOf(0, 100, 200), requested)
        assertEquals(249, paged.items.size)
        assertEquals("no overlap, no duplicates", paged.items.distinct(), paged.items)
        assertTrue(paged.partial)
        assertFalse(pageAllChecked(10) { _, _ -> Page(total = 3, items = listOf(1, 2, 3)) }.partial)
    }

    @Test
    fun respectsMaxItems() = runTest {
        val items = pageAll(10, maxItems = 25) { offset, limit -> Page(total = 1_000, items = (offset until offset + limit).toList()) }
        assertEquals((0 until 25).toList(), items)
    }

    @Test
    fun playlistItemsFollowsOffsets() = runTest {
        val tracks = (0 until 130).map { PlaylistItem(track = Track(uri = "spotify:track:$it", name = "t$it")) }
        val pages = mutableListOf<Int>()
        val items = CatalogRepository.playlistItems("spotify:playlist:x", { uri, offset, limit ->
            pages += offset
            Playlist(uri = uri, name = "p", offset = offset, total = tracks.size, items = tracks.drop(offset).take(limit))
        })
        assertEquals(130, items.size)
        assertEquals(listOf(0, 100), pages)
    }
}

class CacheFreshnessTest {
    @Test
    fun freshWithinMaxAge() {
        assertTrue(ResponseCache.isFresh(fetchedAt = 1_000, maxAgeMs = 500, now = 1_400))
        assertFalse(ResponseCache.isFresh(fetchedAt = 1_000, maxAgeMs = 500, now = 1_500))
    }

    @Test
    fun invalidatedOrForcedIsNeverFresh() {
        assertFalse(ResponseCache.isFresh(fetchedAt = -1_000, maxAgeMs = 500, now = 1_100))
        assertFalse(ResponseCache.isFresh(fetchedAt = 0, maxAgeMs = 500, now = 100))
        assertFalse(ResponseCache.isFresh(fetchedAt = 1_000, maxAgeMs = 0, now = 1_000))
    }

    @Test
    fun clockGoingBackwardsRevalidates() {
        assertFalse(ResponseCache.isFresh(fetchedAt = 2_000, maxAgeMs = 500, now = 1_000))
    }
}

class SpotifyUrisTest {
    @Test
    fun parsesTypes() {
        assertEquals("track", SpotifyUris.typeOf("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("playlist", SpotifyUris.typeOf("spotify:user:someone:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertEquals("collection", SpotifyUris.typeOf("spotify:user:someone:collection"))
        assertNull(SpotifyUris.typeOf("https://open.spotify.com/track/x"))
        assertTrue(SpotifyUris.isPlaylist("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertTrue(SpotifyUris.isLibraryItem("spotify:show:abc"))
        assertFalse(SpotifyUris.isLibraryItem("spotify:user:someone:collection"))
        assertTrue(SpotifyUris.isPlayableItem("spotify:episode:abc"))
        assertFalse(SpotifyUris.isPlayableItem("spotify:local:artist:album:title:180"))
    }
}

class LruMapTest {
    @Test
    fun evictsEldest() {
        val lru = LruMap<String, Int>(2)
        lru["a"] = 1
        lru["b"] = 2
        lru["a"]
        lru["c"] = 3
        assertEquals(1, lru["a"])
        assertNull(lru["b"])
        assertEquals(2, lru.size)
    }
}
