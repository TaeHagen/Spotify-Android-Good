package com.taehagen.spotifygood.playback

import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadedCollection
import com.taehagen.spotifygood.playback.OfflineTree.Row
import com.taehagen.spotifygood.playback.OfflineTree.Section
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTreeTest {
    private fun t(n: Int) = "spotify:track:$n"
    private fun e(n: Int) = "spotify:episode:$n"

    private fun collection(uri: String, type: CollectionType, vararg items: String) =
        DownloadedCollection(CollectionRef(uri, type, uri.substringAfterLast(':'), null), items.toList(), addedAt = 0)

    private val liked = collection("spotify:user:me:collection", CollectionType.LIKED_SONGS, t(1), t(2))
    private val playlist = collection("spotify:playlist:p", CollectionType.PLAYLIST, t(3), t(1))
    private val album = collection("spotify:album:a", CollectionType.ALBUM, t(4))
    private val show = collection("spotify:show:s", CollectionType.SHOW, e(1), e(2))
    private val pending = collection("spotify:playlist:q", CollectionType.PLAYLIST, t(9))

    @Test
    fun theDownloadsTabIsGroupedAsTheDownloadsScreen() {
        // Collections newest first (as stored), downloads newest first.
        val collections = listOf(playlist, show, pending, album, liked)
        val downloaded = linkedSetOf(t(7), e(3), t(1), t(2), t(3), t(4), e(1), t(8))
        val rows = OfflineTree.tab(collections, downloaded)
        assertEquals(
            listOf(
                Row.Collection(liked, Section.PLAYLISTS),
                Row.Collection(playlist, Section.PLAYLISTS),
                Row.Collection(album, Section.ALBUMS),
                Row.Collection(show, Section.PODCASTS),
                // Nothing of the pending playlist plays yet: not listed.
                Row.Item(t(7), Section.SONGS),
                Row.Item(t(8), Section.SONGS),
                Row.Item(e(3), Section.EPISODES),
            ),
            rows,
        )
    }

    @Test
    fun singlesAreTheDownloadsOfNoCollectionByKind() {
        val singles = OfflineTree.singles(linkedSetOf(t(7), e(3), t(1), e(1), t(8), t(9)), listOf(liked, show, pending))
        assertEquals(listOf(t(7), t(8)), singles.songs)
        assertEquals(listOf(e(3)), singles.episodes)
        // A dl| row plays its own section.
        assertEquals(listOf(t(7), t(8)), singles.sectionOf(t(8)))
        assertEquals(listOf(e(3)), singles.sectionOf(e(3)))
    }

    @Test
    fun aDownloadedCollectionIsFoundByWhatBrowsesIt() {
        val collections = listOf(playlist, album, show, liked)
        assertEquals(playlist, OfflineTree.collectionOf("spotify:playlist:p", collections))
        assertEquals(show, OfflineTree.collectionOf("spotify:show:s", collections))
        // Liked Songs by its kind: the Library folder, its context uri (any user form), the old id.
        assertEquals(liked, OfflineTree.collectionOf(LibraryTree.LIKED, collections))
        assertEquals(liked, OfflineTree.collectionOf("spotify:user:other:collection", collections))
        assertEquals(liked, OfflineTree.collectionOf("spotify:collection", collections))
        assertNull(OfflineTree.collectionOf("spotify:playlist:other", collections))
        assertNull(OfflineTree.collectionOf(LibraryTree.LIKED, listOf(playlist)))
        assertTrue(OfflineTree.mayBeCollection("spotify:album:x"))
        assertTrue(OfflineTree.mayBeCollection(LibraryTree.LIKED))
        assertFalse(OfflineTree.mayBeCollection("spotify:artist:x"))
        assertFalse(OfflineTree.mayBeCollection(LibraryTree.PLAYLISTS))
        // What plays offline, in collection order.
        assertEquals(listOf(t(3)), OfflineTree.playableItems(playlist, setOf(t(3), t(4))))
    }
}
