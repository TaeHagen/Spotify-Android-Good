package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.SavedAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DownloadedLibraryTest {
    private val collator = defaultCollator(Locale.US)

    private fun downloaded(uri: String, type: CollectionType, name: String) =
        DownloadedCollection(uri, type, name, imageUrl = null, itemUris = emptyList(), addedAt = 10)

    private val unsavedAlbum = downloaded("spotify:album:dl", CollectionType.ALBUM, "Downloaded album")
    private val savedAlbumDownload = downloaded("spotify:album:saved", CollectionType.ALBUM, "Old name")
    private val playlist = downloaded("spotify:playlist:p", CollectionType.PLAYLIST, "Trip")
    private val show = downloaded("spotify:show:s", CollectionType.SHOW, "Podcast")
    private val liked = downloaded("spotify:user:me:collection", CollectionType.LIKED_SONGS, "Liked Songs")
    private val collections = listOf(unsavedAlbum, savedAlbumDownload, playlist, show, liked)
    private val downloadedUris = collections.map { it.uri }.toSet()
    private val downloadedItems = collections.mapNotNull { it.toLibraryItem() }

    private val savedAlbums = listOf(
        SavedAlbum(100, AlbumRef("spotify:album:saved", "Saved album", artists = listOf(ArtistRef("spotify:artist:a", "Alpha")))),
    ).albumItems()

    private fun listing(query: LibraryQuery, albums: List<LibraryItem> = savedAlbums) =
        buildLibraryListing(emptyList(), albums, emptyList(), emptyList(), query, downloadedUris, downloadedItems, collator = collator)
            .items

    @Test
    fun anUnsavedDownloadIsListedUnderDownloaded() {
        val items = listing(LibraryQuery(filter = LibraryFilter.DOWNLOADED, sort = LibrarySort.ALPHABETICAL))
        assertEquals(listOf("Downloaded album", "Podcast", "Saved album", "Trip"), items.map { it.name })
        // The saved row wins over its download row (creator, saved name).
        assertEquals("Alpha", items.first { it.id == "spotify:album:saved" }.creator)
    }

    @Test
    fun offlineFiltersPickTheirKindOfDownloads() {
        val albums = listing(LibraryQuery(filter = LibraryFilter.ALBUMS, offline = true, sort = LibrarySort.ALPHABETICAL))
        assertEquals(listOf("Downloaded album", "Saved album"), albums.map { it.name })
        val podcasts = listing(LibraryQuery(filter = LibraryFilter.PODCASTS, offline = true))
        assertEquals(listOf("Podcast"), podcasts.map { it.name })
        assertEquals(emptyList<LibraryItem>(), listing(LibraryQuery(filter = LibraryFilter.ARTISTS, offline = true)))
    }

    @Test
    fun aClearedLibraryCacheOfflineStillListsTheDownloads() {
        val items = listing(LibraryQuery(offline = true, sort = LibrarySort.ALPHABETICAL), albums = emptyList())
        assertEquals(listOf("Downloaded album", "Old name", "Podcast", "Trip"), items.map { it.name })
    }

    @Test
    fun likedSongsHasItsOwnPinnedRow() {
        assertNull(liked.toLibraryItem())
        // Online without the Downloaded chip, unsaved downloads are not mixed into the library.
        assertEquals(listOf("Saved album"), listing(LibraryQuery()).map { it.name })
    }

    @Test
    fun downloadedLikedSongsShowOfflineBeforeTheUserIsKnown() {
        // Cold start offline: the user (and so the Liked Songs URI) is only known once online.
        assertTrue(likedSongsDownloaded(collections, username = null))
        val pinned = pinnedEntries(
            LibraryQuery(offline = true),
            likedCount = null,
            likedDownloaded = likedSongsDownloaded(collections, username = null),
            downloadCount = 3,
        )
        assertEquals(listOf("pinned:liked", "pinned:downloads"), pinned.map { it.key })
        assertFalse(likedSongsDownloaded(collections - liked, username = null))
        assertTrue(likedSongsDownloaded(collections, username = "me"))
    }
}
