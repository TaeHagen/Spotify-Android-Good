package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadItem
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsLogicTest {
    private fun item(uri: String, state: DownloadState, bytes: Long = 0, total: Long = 0) =
        DownloadItem(uri, state, bytes, total, metadataJson = null, imagePath = null, error = null)

    private fun collection(uri: String, type: CollectionType, items: List<String>, addedAt: Long = 0) =
        DownloadedCollection(uri, type, uri.substringAfterLast(':'), null, items, addedAt)

    private val metadata: (DownloadItem) -> DownloadMetadata? = { item ->
        if (item.uri.startsWith("spotify:episode:")) {
            DownloadMetadata.OfEpisode(Episode(item.uri, "Episode ${item.uri.last()}"))
        } else {
            DownloadMetadata.OfTrack(Track(item.uri, "Track ${item.uri.last()}"))
        }
    }

    @Test
    fun groupsCollectionsAndIndividualItems() {
        val items = listOf(
            item("spotify:track:1", DownloadState.COMPLETED),
            item("spotify:track:2", DownloadState.COMPLETED),
            item("spotify:track:3", DownloadState.DOWNLOADING, bytes = 50, total = 200),
            item("spotify:episode:4", DownloadState.FAILED),
            item("spotify:track:5", DownloadState.QUEUED),
            item("spotify:track:6", DownloadState.CANCELLED),
        )
        val collections = listOf(
            collection("spotify:playlist:p", CollectionType.PLAYLIST, listOf("spotify:track:1", "spotify:track:3")),
            collection("spotify:album:a", CollectionType.ALBUM, listOf("spotify:track:2")),
            collection("spotify:user:me:collection", CollectionType.LIKED_SONGS, listOf("spotify:track:1")),
        )
        val content = buildDownloadsContent(items, collections, metadata)

        assertEquals(listOf(CollectionType.LIKED_SONGS, CollectionType.PLAYLIST), content.playlists.map { it.type })
        assertEquals(listOf("spotify:album:a"), content.albums.map { it.uri })
        assertTrue(content.podcasts.isEmpty())
        // Newest first; collection items and cancelled items are not listed individually.
        assertEquals(listOf("spotify:track:5"), content.songs.map { it.uri })
        assertEquals(listOf("spotify:episode:4"), content.episodes.map { it.uri })
        assertEquals("Episode 4", content.episodes.single().episode?.name)
        assertEquals(2, content.completedCount)
        assertEquals(1, content.failedCount)
        assertEquals(2, content.pendingCount)
        assertEquals(setOf("spotify:track:1", "spotify:track:2"), content.completed)
        // The transferring item is reported even when it belongs to a collection.
        assertEquals("spotify:track:3", content.active?.uri)
        assertEquals(0.25f, content.active?.progress)
        assertEquals("Track 3", content.active?.track?.name)
    }

    @Test
    fun emptyContent() {
        val content = buildDownloadsContent(emptyList(), emptyList(), metadata)
        assertTrue(content.isEmpty)
        assertEquals(null, content.active)
    }

    @Test
    fun offlinePlaybackUsesOnlyCompletedItems() {
        val c = collection("spotify:playlist:p", CollectionType.PLAYLIST, listOf("a", "b", "c"))
        assertEquals(listOf("a", "c"), c.playableUris(setOf("a", "c"), offline = true))
        assertEquals(listOf("a", "b", "c"), c.playableUris(setOf("a"), offline = false))
    }
}
