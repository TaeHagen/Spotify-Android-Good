package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.download.CollectionDownloadStatus
import com.taehagen.spotifygood.download.CollectionRef
import com.taehagen.spotifygood.download.CollectionType
import com.taehagen.spotifygood.download.DownloadedCollection as StoredCollection
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.MediaType
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackContext
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class BrowseLogicTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun native(code: String) = NativeException(NativeErrorInfo(code, "boom"))

    @Test
    fun errorsMapToUserFacingCategories() {
        assertEquals(BrowseError.OFFLINE, native(NativeErrorCode.NETWORK).toBrowseError())
        assertEquals(BrowseError.OFFLINE, native(NativeErrorCode.NOT_CONNECTED).toBrowseError())
        assertEquals(BrowseError.RATE_LIMITED, native(NativeErrorCode.RATE_LIMITED).toBrowseError())
        assertEquals(BrowseError.NOT_FOUND, native(NativeErrorCode.NOT_FOUND).toBrowseError())
        assertEquals(BrowseError.GENERIC, native(NativeErrorCode.INTERNAL).toBrowseError())
        assertEquals(BrowseError.OFFLINE, IOException().toBrowseError())
        assertEquals(BrowseError.GENERIC, IllegalStateException().toBrowseError())
    }

    @Test
    fun likedSongsUris() {
        assertEquals("spotify:user:alice:collection", likedSongsUri("alice"))
        assertTrue(isLikedSongsUri("spotify:user:alice:collection"))
        assertFalse(isLikedSongsUri("spotify:playlist:abc"))
    }

    @Test
    fun mergeUniqueDropsDuplicates() {
        val merged = mergeUnique(listOf("a", "b"), listOf("b", "c", "c"), { it })
        assertEquals(listOf("a", "b", "c"), merged)
        val same = listOf("a")
        assertTrue(mergeUnique(same, listOf("a"), { it }) === same)
    }

    @Test
    fun pagerLoadsPagesUntilTotal() = runTest {
        val requests = mutableListOf<Int>()
        val loader = PagedLoader(this, pageSize = 2, keyOf = { it }) { offset, limit ->
            requests += offset
            PageResult(List(limit) { "item${offset + it}" }.take((5 - offset).coerceAtLeast(0)), total = 5)
        }
        loader.loadMore()
        assertTrue(loader.state.value.isInitialLoading)
        advanceUntilIdle()
        assertEquals(listOf("item0", "item1"), loader.state.value.items)
        assertTrue(loader.state.value.canLoadMore)

        loader.loadMore()
        advanceUntilIdle()
        loader.loadMore()
        advanceUntilIdle()
        val state = loader.state.value
        assertEquals(5, state.items.size)
        assertEquals(5, state.total)
        assertTrue(state.endReached)

        loader.loadMore()
        advanceUntilIdle()
        assertEquals(listOf(0, 2, 4), requests)
    }

    @Test
    fun pagerKeepsGoingAfterAShortPageWhenTotalSaysThereIsMore() = runTest {
        // An older engine dropped items without metadata: 99 of a 100-item window.
        val requests = mutableListOf<Int>()
        val loader = PagedLoader(this, pageSize = 100, keyOf = { it }) { offset, limit ->
            requests += offset
            val window = (offset until minOf(offset + limit, 250)).map { "t$it" }
            PageResult(if (offset == 0) window - "t57" else window, total = 250)
        }
        loader.loadMore()
        advanceUntilIdle()
        assertEquals(99, loader.state.value.items.size)
        assertFalse(loader.state.value.endReached)
        loader.loadMore()
        advanceUntilIdle()
        loader.loadMore()
        advanceUntilIdle()
        assertEquals("windows advance by the page size, without overlap", listOf(0, 100, 200), requests)
        assertEquals(249, loader.state.value.items.size)
        assertTrue(loader.state.value.endReached)
    }

    @Test
    fun pageSteps() {
        assertEquals(100 to false, pageStep(offset = 0, pageSize = 100, received = 99, total = 250))
        assertEquals(300 to true, pageStep(offset = 200, pageSize = 100, received = 50, total = 250))
        assertEquals(100 to true, pageStep(offset = 0, pageSize = 100, received = 100, total = 100))
        assertTrue("an empty page ends the list", pageStep(offset = 0, pageSize = 100, received = 0, total = 250).second)
        // Unknown total (search): a short page is the end.
        assertEquals(30 to false, pageStep(offset = 0, pageSize = 30, received = 30, total = null))
        assertEquals(42 to true, pageStep(offset = 30, pageSize = 30, received = 12, total = null))
    }

    @Test
    fun pagerIgnoresConcurrentLoadsAndRecoversFromErrors() = runTest {
        var fail = true
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val loader = PagedLoader(this, pageSize = 10, keyOf = { it }) { _, _ ->
            calls++
            gate.await()
            if (fail) throw IOException("offline")
            PageResult(listOf("x"))
        }
        loader.loadMore()
        loader.loadMore()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(loader.state.value.error is IOException)
        assertFalse(loader.state.value.canLoadMore)

        fail = false
        loader.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("x"), loader.state.value.items)
        assertNull(loader.state.value.error)
        assertTrue(loader.state.value.endReached)
    }

    @Test
    fun pagerReloadReplacesItems() = runTest {
        var generation = 0
        val loader = PagedLoader(this, pageSize = 2, keyOf = { it }) { offset, _ ->
            PageResult(listOf("g$generation-$offset", "g$generation-${offset + 1}"), total = 4)
        }
        loader.loadMore()
        advanceUntilIdle()
        loader.loadMore()
        advanceUntilIdle()
        assertEquals(4, loader.state.value.items.size)

        generation = 1
        loader.reload()
        assertEquals(4, loader.state.value.items.size) // still visible while reloading
        advanceUntilIdle()
        assertEquals(listOf("g1-0", "g1-1"), loader.state.value.items)
        assertFalse(loader.state.value.endReached)
    }

    @Test
    fun downloadedCollectionMapping() {
        val stored = StoredCollection(
            ref = CollectionRef("spotify:user:me:collection", CollectionType.LIKED_SONGS, "Liked Songs", "https://i.scdn.co/image/x"),
            itemUris = listOf("spotify:track:a", "spotify:track:b"),
            addedAt = 1,
        )
        val collection = stored.toDownloadedCollection()
        assertEquals("spotify:user:me:collection", collection.uri)
        assertEquals(CollectionType.LIKED_SONGS, collection.type)
        assertEquals(MediaType.COLLECTION, collection.mediaType)
        assertEquals("https://i.scdn.co/image/x", collection.imageUrl)
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), collection.itemUris)
        assertEquals(1L, collection.addedAt)
        assertEquals(2, stored.itemCount)
    }

    @Test
    fun collectionTypesFromWire() {
        assertEquals(CollectionType.LIKED_SONGS, CollectionType.fromWire("collection"))
        assertEquals(CollectionType.ALBUM, CollectionType.fromWire("album"))
        assertNull(CollectionType.fromWire("unknown"))
    }

    @Test
    fun downloadMetadataDecodesTracksAndEpisodes() {
        val track = decodeDownloadMetadata(json, "spotify:track:a", """{"uri":"spotify:track:a","name":"Song","extra":1}""")
        assertEquals("Song", (track as DownloadMetadata.OfTrack).track.name)
        val episode = decodeDownloadMetadata(json, "spotify:episode:e", """{"uri":"spotify:episode:e","name":"Ep"}""")
        assertEquals("Ep", (episode as DownloadMetadata.OfEpisode).episode.name)
        assertNull(decodeDownloadMetadata(json, "spotify:track:a", null))
        assertNull(decodeDownloadMetadata(json, "spotify:track:a", "{broken"))
    }

    @Test
    fun collectionStatusIndicator() {
        assertNull(CollectionDownloadStatus.None.toIndicatorState())
        assertEquals(DownloadState.COMPLETED, CollectionDownloadStatus.Complete.toIndicatorState())
        assertEquals(DownloadState.DOWNLOADING, CollectionDownloadStatus.InProgress(1, 3, active = true).toIndicatorState())
        assertEquals(DownloadState.QUEUED, CollectionDownloadStatus.InProgress(1, 3, active = false).toIndicatorState())
    }

    @Test
    fun nowPlayingFromSnapshot() {
        val snapshot = PlaybackSnapshot(
            status = PlaybackStatus.PLAYING,
            context = PlaybackContext("spotify:album:a"),
            track = PlaybackTrack("spotify:track:t"),
        )
        val now = snapshot.toNowPlaying()
        assertTrue(now.isCurrent("spotify:track:t"))
        assertTrue(now.isPlayingContext("spotify:album:a"))
        assertFalse(now.isPlayingContext(null))
        assertEquals(NowPlaying(), PlaybackSnapshot.EMPTY.toNowPlaying())
    }
}
