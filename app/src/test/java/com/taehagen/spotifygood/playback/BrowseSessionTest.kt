package com.taehagen.spotifygood.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which browse requests start the engine (a browse-only bind must not, docs §9.4). */
class BrowseSessionTest {
    @Test
    fun theRootRecentAndDownloadsNeverStartTheEngine() {
        // SysUI's resumption card (root + recent at boot), Bluetooth player discovery (root).
        for (id in listOf(LibraryTree.ROOT, LibraryTree.ROOT_RECENT, LibraryTree.ROOT_OFFLINE, LibraryTree.LIBRARY, LibraryTree.DOWNLOADS)) {
            assertFalse(id, LibraryTree.needsSession(id))
            assertFalse(id, LibraryTree.itemNeedsSession(id))
        }
        for (id in listOf(LibraryTree.HOME, LibraryTree.BROWSE, LibraryTree.PLAYLISTS, LibraryTree.ALBUMS, LibraryTree.ARTISTS, LibraryTree.PODCASTS)) {
            assertFalse(id, LibraryTree.itemNeedsSession(id))
        }
    }

    @Test
    fun catalogContentDoes() {
        for (id in listOf(LibraryTree.HOME, LibraryTree.BROWSE, LibraryTree.LIKED, LibraryTree.PLAYLISTS, "spotify:playlist:p")) {
            assertTrue(id, LibraryTree.needsSession(id))
        }
        for (id in listOf("spotify:album:a", MediaIds.inContext("spotify:album:a", "spotify:track:t"), "spotify:track:t")) {
            assertTrue(id, LibraryTree.itemNeedsSession(id))
        }
    }
}
