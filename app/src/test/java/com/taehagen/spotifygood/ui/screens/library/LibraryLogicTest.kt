package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.data.settings.LibrarySort
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.MediaRef
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.PlaylistOwner
import com.taehagen.spotifygood.model.Rootlist
import com.taehagen.spotifygood.model.RootlistEntry
import com.taehagen.spotifygood.model.RootlistEntryType
import com.taehagen.spotifygood.model.SavedAlbum
import com.taehagen.spotifygood.model.SavedArtist
import com.taehagen.spotifygood.ui.navigation.MediaActionTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LibraryLogicTest {
    private val collator = defaultCollator(Locale.US)

    private fun playlist(id: String, name: String, owner: String = "me") =
        RootlistEntry(RootlistEntryType.PLAYLIST, "spotify:playlist:$id", name, owner = PlaylistOwner(owner, owner.uppercase()))

    private val rootlist = Rootlist(
        listOf(
            playlist("p1", "road trip"),
            RootlistEntry(
                RootlistEntryType.FOLDER,
                "spotify:user:me:folder:f1",
                "Moods",
                children = listOf(
                    playlist("p2", "Calm", owner = "someone"),
                    RootlistEntry(RootlistEntryType.FOLDER, null, "Deep", children = listOf(playlist("p3", "Ambient"))),
                ),
            ),
            playlist("p4", "Éclectique"),
        ),
    )
    private val playlists = rootlist.toLibraryItems()
    private val albums = listOf(
        SavedAlbum(100, AlbumRef("spotify:album:old", "Zebra", artists = listOf(ArtistRef("spotify:artist:b", "Beta")))),
        SavedAlbum(300, AlbumRef("spotify:album:new", "apple", artists = listOf(ArtistRef("spotify:artist:a", "Alpha")))),
    ).albumItems()
    private val artists = listOf(SavedArtist(200, ArtistRef("spotify:artist:c", "Charlie"))).artistItems()

    @Test
    fun rootlistKeepsFoldersWithChildren() {
        assertEquals(listOf(LibraryItemKind.PLAYLIST, LibraryItemKind.FOLDER, LibraryItemKind.PLAYLIST), playlists.map { it.kind })
        val folder = playlists[1]
        assertEquals("spotify:user:me:folder:f1", folder.id)
        assertEquals(2, folder.playlistCount())
        assertEquals("folder:1/1", folder.children[1].id)
        assertEquals(listOf("road trip", "Calm", "Ambient", "Éclectique"), playlists.flattenFolders().map { it.name })
    }

    @Test
    fun folderPathResolvesUntilUnknownId() {
        val chain = resolveFolderPath(playlists, listOf("spotify:user:me:folder:f1", "folder:1/1"))
        assertEquals(listOf("Moods", "Deep"), chain.map { it.name })
        assertEquals(listOf("Moods"), resolveFolderPath(playlists, listOf("spotify:user:me:folder:f1", "missing")).map { it.name })
        assertTrue(resolveFolderPath(playlists, listOf("missing")).isEmpty())
    }

    @Test
    fun alphabeticalIgnoresCaseAndAccents() {
        val sorted = (playlists.flattenFolders() + albums).sortedWith(libraryComparator(LibrarySort.ALPHABETICAL, collator = collator))
        assertEquals(listOf("Ambient", "apple", "Calm", "Éclectique", "road trip", "Zebra"), sorted.map { it.name })
    }

    @Test
    fun recentlyAddedPutsRootlistOrderFirstThenNewestSaves() {
        val sorted = (albums + artists + playlists).sortedWith(libraryComparator(LibrarySort.RECENTLY_ADDED, collator = collator))
        assertEquals(listOf("road trip", "Moods", "Éclectique", "apple", "Charlie", "Zebra"), sorted.map { it.name })
    }

    @Test
    fun recentsUsesPlaybackRankFirst() {
        val ranks = recentRanks(
            listOf(
                MediaRef(MediaType.ALBUM, "spotify:album:old", "Zebra"),
                MediaRef(MediaType.PLAYLIST, "spotify:playlist:p4", "Éclectique"),
                MediaRef(MediaType.ALBUM, "spotify:album:old", "Zebra"),
            ),
        )
        assertEquals(mapOf("spotify:album:old" to 0, "spotify:playlist:p4" to 1), ranks)
        val sorted = (albums + playlists).sortedWith(libraryComparator(LibrarySort.RECENT, ranks, collator))
        assertEquals(listOf("Zebra", "Éclectique", "road trip", "Moods", "apple"), sorted.map { it.name })
    }

    @Test
    fun creatorSortPutsUnknownCreatorsLast() {
        val sorted = (albums + artists).sortedWith(libraryComparator(LibrarySort.CREATOR, collator = collator))
        assertEquals(listOf("apple", "Zebra", "Charlie"), sorted.map { it.name })
    }

    @Test
    fun listingShowsTopLevelOrOpenFolder() {
        val all = buildLibraryListing(playlists, albums, artists, emptyList(), LibraryQuery(sort = LibrarySort.ALPHABETICAL), emptySet(), collator = collator)
        assertEquals(listOf("apple", "Charlie", "Éclectique", "Moods", "road trip", "Zebra"), all.items.map { it.name })
        assertTrue(all.folders.isEmpty())

        val onlyPlaylists = buildLibraryListing(
            playlists, albums, artists, emptyList(),
            LibraryQuery(filter = LibraryFilter.PLAYLISTS, sort = LibrarySort.RECENTLY_ADDED), emptySet(), collator = collator,
        )
        assertEquals(listOf("road trip", "Moods", "Éclectique"), onlyPlaylists.items.map { it.name })

        val inFolder = buildLibraryListing(
            playlists, albums, artists, emptyList(),
            LibraryQuery(folderPath = listOf("spotify:user:me:folder:f1"), sort = LibrarySort.ALPHABETICAL), emptySet(), collator = collator,
        )
        assertEquals(listOf("Moods"), inFolder.folders.map { it.name })
        assertEquals(listOf("Calm", "Deep"), inFolder.items.map { it.name })
    }

    @Test
    fun searchLooksThroughFoldersAndCreators() {
        val byName = buildLibraryListing(playlists, albums, artists, emptyList(), LibraryQuery(text = "amb"), emptySet(), collator = collator)
        assertEquals(listOf("Ambient"), byName.items.map { it.name })
        val byCreator = buildLibraryListing(playlists, albums, artists, emptyList(), LibraryQuery(text = "alpha"), emptySet(), collator = collator)
        assertEquals(listOf("apple"), byCreator.items.map { it.name })
    }

    @Test
    fun downloadedFilterAndOfflineShowOnlyDownloads() {
        val downloaded = setOf("spotify:playlist:p3", "spotify:album:new")
        val filtered = buildLibraryListing(
            playlists, albums, artists, emptyList(),
            LibraryQuery(filter = LibraryFilter.DOWNLOADED, sort = LibrarySort.ALPHABETICAL), downloaded, collator = collator,
        )
        assertEquals(listOf("Ambient", "apple"), filtered.items.map { it.name })

        val offlineAlbums = buildLibraryListing(
            playlists, albums, artists, emptyList(),
            LibraryQuery(filter = LibraryFilter.ALBUMS, offline = true), downloaded, collator = collator,
        )
        assertEquals(listOf("apple"), offlineAlbums.items.map { it.name })
    }

    @Test
    fun pinnedRowsDependOnFilterAndState() {
        fun keys(query: LibraryQuery, likedDownloaded: Boolean = false) =
            pinnedEntries(query, likedCount = 12, likedDownloaded = likedDownloaded, downloadCount = 3).map { it.key }

        assertEquals(listOf("pinned:liked", "pinned:downloads"), keys(LibraryQuery()))
        assertEquals(listOf("pinned:liked"), keys(LibraryQuery(filter = LibraryFilter.PLAYLISTS)))
        assertEquals(listOf("pinned:episodes"), keys(LibraryQuery(filter = LibraryFilter.PODCASTS)))
        assertEquals(emptyList<String>(), keys(LibraryQuery(filter = LibraryFilter.ALBUMS)))
        assertEquals(listOf("pinned:downloads"), keys(LibraryQuery(filter = LibraryFilter.DOWNLOADED)))
        assertEquals(listOf("pinned:liked", "pinned:downloads"), keys(LibraryQuery(offline = true), likedDownloaded = true))
        assertEquals(emptyList<String>(), keys(LibraryQuery(text = "x")))
        assertEquals(emptyList<String>(), keys(LibraryQuery(folderPath = listOf("f"))))
    }

    @Test
    fun actionTargets() {
        val mine = playlists[0].actionTarget("me") as MediaActionTarget.PlaylistTarget
        assertTrue(mine.isOwned)
        val theirs = playlists[1].children[0].actionTarget("me") as MediaActionTarget.PlaylistTarget
        assertEquals(false, theirs.isOwned)
        assertNull(playlists[1].actionTarget("me"))
        assertTrue(albums[0].actionTarget(null) is MediaActionTarget.AlbumTarget)
    }
}
