package com.taehagen.spotifygood.ui.screens.album

import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.AlbumType
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.components.isPlaceholder
import com.taehagen.spotifygood.ui.screens.library.DownloadMetadata
import com.taehagen.spotifygood.ui.screens.library.DownloadedCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedPagesTest {
    private val albumRef = AlbumRef(
        uri = "spotify:album:a",
        name = "Album",
        artists = listOf(ArtistRef("spotify:artist:x", "Artist")),
        releaseDate = "2021-05-01",
        albumType = AlbumType.ALBUM,
    )

    private fun track(n: Int) = Track(uri = "spotify:track:$n", name = "Track $n", album = albumRef, trackNumber = n, discNumber = 1)

    private fun item(uri: String) = DownloadItem(uri, DownloadState.COMPLETED, 0, 0, metadataJson = "{}", imagePath = null, error = null)

    private fun page(
        uris: List<String>,
        metadata: Map<String, DownloadMetadata>,
        type: CollectionType = CollectionType.ALBUM,
        uri: String = "spotify:album:a",
    ): DownloadedPage {
        val collection = DownloadedCollection(uri, type, "Downloaded", "https://i.scdn.co/image/c", uris, addedAt = 0)
        val items = uris.associateWith(::item)
        return downloadedPage(collection, items) { metadata[it.uri] }
    }

    @Test
    fun anAlbumIsRebuiltFromItsDownloadInCollectionOrder() {
        val uris = (1..3).map { "spotify:track:$it" }
        val album = page(uris, uris.mapIndexed { i, uri -> uri to DownloadMetadata.OfTrack(track(i + 1)) }.toMap()).toAlbum()
        assertEquals(listOf("Track 1", "Track 2", "Track 3"), album.tracks.map { it.name })
        assertEquals("Downloaded", album.name)
        assertEquals("https://i.scdn.co/image/c", album.images.single().url)
        assertEquals(listOf("Artist"), album.artists.map { it.name })
        assertEquals("2021-05-01", album.releaseDate)
        assertEquals(AlbumType.ALBUM, album.albumType)
        assertFalse(album.partial)
        // Disc and track numbers come along, so the page can group by disc as online.
        assertEquals(listOf(1, 2, 3), album.tracks.map { it.trackNumber })
    }

    @Test
    fun itemsWithoutStoredMetadataArePlaceholders() {
        val uris = listOf("spotify:track:1", "spotify:track:2")
        val copy = page(uris, mapOf(uris[0] to DownloadMetadata.OfTrack(track(1))))
        assertTrue(copy.partial)
        val tracks = copy.tracks()
        assertEquals(uris, tracks.map { it.uri })
        assertFalse(tracks[0].isPlaceholder)
        assertTrue(tracks[1].isPlaceholder)
        assertFalse(tracks[1].playable)
    }

    @Test
    fun anOfflinePlaylistListsEveryDownloadedRowPastTheCachedFirstPage() {
        // 300 downloaded rows, the cached first page holds 100: rows 101-300 come from the download.
        val uris = (1..300).map { "spotify:track:$it" }
        val copy = page(uris, uris.mapIndexed { i, uri -> uri to DownloadMetadata.OfTrack(track(i + 1)) }.toMap(), CollectionType.PLAYLIST, "spotify:playlist:p")
        val rest = copy.remainingPlaylistItems(loaded = 100)
        assertEquals(200, rest.size)
        assertEquals("spotify:track:101", rest.first().uri)
        assertEquals("spotify:track:300", rest.last().uri)
        // Without any cached page the whole playlist is shown.
        val playlist = copy.toPlaylist()
        assertEquals(300, playlist.total)
        assertEquals(300, playlist.items.size)
        assertFalse(playlist.canEdit)
    }

    @Test
    fun aPlaylistPlaceholderKeepsItsKind() {
        val uris = listOf("spotify:track:1", "spotify:episode:e")
        val items = page(uris, emptyMap(), CollectionType.PLAYLIST, "spotify:playlist:p").playlistItems()
        assertTrue(items[0].track!!.isPlaceholder)
        assertTrue(items[1].episode!!.isPlaceholder)
    }

    @Test
    fun aShowListsItsDownloadedEpisodes() {
        val show = ShowRef("spotify:show:s", "Show", publisher = "Publisher")
        val episodes = (1..3).map { Episode(uri = "spotify:episode:$it", name = "Episode $it", show = show) }
        val copy = page(episodes.map { it.uri }, episodes.associate { it.uri to DownloadMetadata.OfEpisode(it) }, CollectionType.SHOW, show.uri)
        assertEquals("Publisher", copy.toShow().publisher)
        assertEquals(listOf("Episode 1", "Episode 2", "Episode 3"), copy.episodes().map { it.name })
    }

    @Test
    fun offlineEpisodePagingAddsTheDownloadedEpisodesNotListedYet() {
        val e = (1..5).map { Episode(uri = "spotify:episode:$it", name = "E$it") }
        // Cached first page: E1, E2. Downloaded: E2, E4, E5 (newest first).
        val newest = appendDownloadedEpisodes(e.take(2), listOf(e[1], e[3], e[4]), newestFirst = true)
        assertEquals(listOf("E1", "E2", "E4", "E5"), newest.map { it.name })
        val oldest = appendDownloadedEpisodes(emptyList(), listOf(e[1], e[3], e[4]), newestFirst = false)
        assertEquals(listOf("E5", "E4", "E2"), oldest.map { it.name })
    }
}
