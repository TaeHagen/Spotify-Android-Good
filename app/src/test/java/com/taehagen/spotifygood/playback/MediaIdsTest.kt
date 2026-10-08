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
    fun likedSongsFolderIsPlayableWithItsContextUri() {
        val liked = "spotify:user:someone:collection"
        val id = LibraryTree.likedMediaId(liked)
        assertEquals(LoadPlan(contextUri = liked), MediaIds.plan(listOf(id), 0) { error("unused") })
        // While the user is unknown the folder keeps the browse-only id (not playable).
        assertEquals(LibraryTree.LIKED, LibraryTree.likedMediaId(null))
        assertNull(MediaIds.plan(listOf(LibraryTree.LIKED), 0) { error("unused") })
    }

    @Test
    fun resolvableContextsMirrorTheEngine() {
        for (uri in listOf(playlist, "spotify:album:a", "spotify:artist:a", "spotify:show:s", "spotify:user:u:collection", "spotify:station:track:1")) {
            assertEquals(uri, true, MediaIds.isResolvableContext(uri))
        }
        for (uri in listOf(null, "", "spotify:web-api", "spotify:web-api:x", t1, "spotify:episode:e", "spotify:local:a:b:c:1", "https://x")) {
            assertEquals(uri, false, MediaIds.isResolvableContext(uri))
        }
    }

    @Test
    fun cachedIdsWithATrackListContextPlayTheTrack() {
        val plan = MediaIds.plan(listOf(MediaIds.inContext("spotify:web-api", t1)), 0) { error("unused") }
        assertEquals(LoadPlan(trackUris = listOf(t1), startIndex = 0), plan)
    }

    @Test
    fun uriOnlyPlaceholdersAreNotBrowseRows() {
        assertEquals(true, LibraryTree.isPlaceholder(""))
        assertEquals(true, LibraryTree.isPlaceholder("  "))
        assertEquals(true, LibraryTree.isPlaceholder(null))
        assertEquals(false, LibraryTree.isPlaceholder("Song"))
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
        // The queue is the one of the start item (its section of the Downloads tab).
        val episode = "spotify:episode:e"
        val asked = mutableListOf<String>()
        val plan = MediaIds.plan(listOf(MediaIds.downloaded(episode)), 0) { start ->
            asked += start
            listOf("spotify:episode:d", episode)
        }
        assertEquals(listOf(episode), asked)
        assertEquals(LoadPlan(trackUris = listOf("spotify:episode:d", episode), startIndex = 1), plan)
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
