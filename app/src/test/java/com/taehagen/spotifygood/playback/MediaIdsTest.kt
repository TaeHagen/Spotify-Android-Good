package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {
    private val playlist = "spotify:playlist:abc"
    private val t1 = "spotify:track:1"
    private val t2 = "spotify:track:2"

    @Test
    fun parsesAllForms() {
        assertEquals(MediaIds.Parsed.InContext(playlist, t1), MediaIds.parse(MediaIds.inContext(playlist, t1)))
        assertEquals(MediaIds.Parsed.Downloaded(t1), MediaIds.parse(MediaIds.downloaded(t1)))
        assertEquals(MediaIds.Parsed.Plain(t1), MediaIds.parse(t1))
        assertEquals(MediaIds.Parsed.Invalid, MediaIds.parse("tab:home"))
        assertEquals(MediaIds.Parsed.Invalid, MediaIds.parse("ctx||$t1"))
        assertEquals(MediaIds.Parsed.Invalid, MediaIds.parse(""))
    }

    @Test
    fun collectionContextUrisSurviveEncoding() {
        val liked = "spotify:user:someone:collection"
        assertEquals(MediaIds.Parsed.InContext(liked, t2), MediaIds.parse(MediaIds.inContext(liked, t2)))
    }

    @Test
    fun itemUris() {
        assertEquals(t1, MediaIds.itemUriOf(MediaIds.inContext(playlist, t1)))
        assertEquals(t1, MediaIds.itemUriOf(MediaIds.downloaded(t1)))
        assertEquals("spotify:episode:e", MediaIds.itemUriOf("spotify:episode:e"))
        assertNull(MediaIds.itemUriOf(playlist))
    }

    @Test
    fun planPlaysTrackInsideItsContext() {
        val plan = MediaIds.plan(listOf(MediaIds.inContext(playlist, t2)), 0) { error("unused") }
        assertEquals(LoadPlan(contextUri = playlist, startUri = t2), plan)
    }

    @Test
    fun planPlaysWholeContext() {
        assertEquals(LoadPlan(contextUri = playlist), MediaIds.plan(listOf(playlist), 0) { emptyList() })
    }

    @Test
    fun planPlaysTrackListFromStartIndex() {
        val plan = MediaIds.plan(listOf(t1, "tab:home", t2), 2) { emptyList() }
        assertEquals(LoadPlan(trackUris = listOf(t1, t2), startIndex = 1), plan)
    }

    @Test
    fun planPlaysDownloadsAsQueue() {
        val all = listOf(t1, t2, "spotify:track:3")
        assertEquals(LoadPlan(trackUris = all, startIndex = 1), MediaIds.plan(listOf(MediaIds.downloaded(t2)), 0) { all })
        // A download missing from the list is still played first.
        val missing = "spotify:track:9"
        assertEquals(
            LoadPlan(trackUris = listOf(missing) + all, startIndex = 0),
            MediaIds.plan(listOf(MediaIds.downloaded(missing)), 0) { all },
        )
    }

    @Test
    fun planRejectsNothing() {
        assertNull(MediaIds.plan(emptyList(), 0) { emptyList() })
        assertNull(MediaIds.plan(listOf("garbage"), 0) { emptyList() })
        // INDEX_UNSET start index falls back to the first item.
        assertEquals(LoadPlan(contextUri = playlist), MediaIds.plan(listOf(playlist), -1) { emptyList() })
    }
}
