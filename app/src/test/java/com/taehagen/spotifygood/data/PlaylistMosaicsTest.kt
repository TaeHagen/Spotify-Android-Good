package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Image
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.ShowRef
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.MosaicBitmaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val P = "spotify:playlist:p"

private fun cover(album: String) = listOf(
    Image("https://i.scdn.co/image/${album}64", 64, 64),
    Image("https://i.scdn.co/image/${album}300", 300, 300),
    Image("https://i.scdn.co/image/${album}640", 640, 640),
)

private fun song(n: Int, album: String = "a$n", playable: Boolean = true, explicit: Boolean = false) = PlaylistItem(
    track = Track(
        uri = "spotify:track:$n",
        name = "Song $n",
        album = AlbumRef("spotify:album:$album", album, images = cover(album)),
        playable = playable,
        explicit = explicit,
    ),
)

private fun MosaicCover.album(): String = url(640)!!.removePrefix("https://i.scdn.co/image/").removeSuffix("640")

class MosaicCoversTest {
    @Test
    fun theFirstFourDistinctAlbumCoversMakeTheMosaic() {
        val items = listOf(song(1, "x"), song(2, "x"), song(3, "y"), song(4, "z"), song(5, "y"), song(6, "w"), song(7, "v"))
        assertEquals(listOf("x", "y", "z", "w"), mosaicCovers(items).map { it.album() })
    }

    @Test
    fun localFilesRowsWithoutArtAndUnavailableRowsAreSkipped() {
        val items = listOf(
            PlaylistItem(track = Track(uri = "spotify:local:Artist:Album:Song:215", name = "Song", playable = false)),
            PlaylistItem(track = Track(uri = "spotify:track:gone", name = "", playable = false)), // a placeholder
            song(1, "unavailable", playable = false),
            PlaylistItem(episode = Episode(uri = "spotify:episode:e", name = "No art")),
            PlaylistItem(track = Track(uri = "spotify:track:noalbum", name = "No album")),
            song(2, "a"),
            // Hidden only by Hide explicit content: still the playlist's art.
            song(3, "b", playable = false, explicit = true),
            PlaylistItem(episode = Episode(uri = "spotify:episode:s", name = "Show art", show = ShowRef("spotify:show:s", "S", images = cover("c")))),
            song(4, "d"),
        )
        assertEquals(listOf("a", "b", "c", "d"), mosaicCovers(items).map { it.album() })
    }

    @Test
    fun fewerThanFourDistinctCoversShowTheFirstSongsAlone() {
        assertEquals(listOf("x"), mosaicCovers(listOf(song(1, "x"), song(2, "y"), song(3, "x"), song(4, "z"))).map { it.album() })
        assertEquals(listOf("x"), mosaicCovers(listOf(song(1, "x"))).map { it.album() })
    }

    @Test
    fun anEmptyPlaylistKeepsThePlaceholder() {
        assertTrue(mosaicCovers(emptyList()).isEmpty())
        assertTrue(mosaicCovers(listOf(PlaylistItem(track = Track(uri = "spotify:local:a:b:c:1", name = "c")))).isEmpty())
    }

    @Test
    fun onlyPlaylistsWithoutAnImageGetOne() {
        assertTrue(needsMosaic(P, null))
        assertTrue(needsMosaic("spotify:user:bob:playlist:p", ""))
        assertFalse(needsMosaic(P, "https://i.scdn.co/image/own"))
        assertFalse("Liked Songs keeps its own art", needsMosaic("spotify:user:bob:collection", null))
        assertFalse(needsMosaic("spotify:album:a", null))
    }

    @Test
    fun theTilesCoverTheSquareEdgeToEdge() {
        for (size in listOf(640, 641, 56, 1)) {
            val tiles = mosaicTiles(size, size)
            assertEquals(4, tiles.size)
            // Every pixel in exactly one tile: no gap, no overlap.
            val covered = IntArray(size * size)
            tiles.forEach { t -> for (y in t.top until t.bottom) for (x in t.left until t.right) covered[y * size + x]++ }
            assertTrue("size $size", covered.all { it == 1 })
            assertEquals(MosaicTile(0, 0, size / 2, size / 2), tiles[0])
            assertEquals(size, tiles[3].right)
            assertEquals(size, tiles[3].bottom)
        }
        assertEquals(MosaicTile(80, 0, 560, 480), centerSquare(640, 480))
        assertEquals(MosaicTile(0, 0, 300, 300), centerSquare(300, 300))
    }

    @Test
    fun aComposedMosaicIsKeyedByItsCoversIds() {
        val covers = listOf("a", "b", "c", "d").map { "https://i.scdn.co/image/$it" }
        assertEquals(MosaicBitmaps.key(covers), MosaicBitmaps.key(covers.map { it.replace("i.scdn.co", "mosaic.scdn.co") }))
        assertFalse(MosaicBitmaps.key(covers) == MosaicBitmaps.key(covers.reversed()))
        assertEquals(40, MosaicBitmaps.key(covers).length)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMosaicStoreTest {
    private var now = 1_000L
    private var online = true
    private val fetched = mutableListOf<String>()
    private var revision = "r1"
    private var items = listOf(song(1, "w"), song(2, "x"), song(3, "y"), song(4, "z"))

    private fun TestScope.store(
        concurrency: Int = PlaylistMosaicStore.MAX_CONCURRENT_FETCHES,
        downloaded: suspend (String) -> List<PlaylistItem>? = { null },
        cache: ResponseCache? = null,
        firstPage: suspend (String) -> Playlist = { uri ->
            fetched += uri
            Playlist(uri = uri, name = "P", revision = revision, items = items)
        },
    ) = PlaylistMosaicStore(backgroundScope, cache, firstPage, downloaded, online = { online }, clock = { now }, concurrency)

    /** A row showing [uri]'s art as `rememberPlaylistMosaic` does; gives what it shows now. */
    private fun TestScope.row(store: PlaylistMosaicStore, uri: String): () -> PlaylistMosaic? {
        var shown: PlaylistMosaic? = null
        backgroundScope.launch {
            shown = store.mosaic(uri)
            store.changesOf(uri).collect { shown = store.mosaic(uri) }
        }
        return { shown }
    }

    /** A response cache holding [P]'s mosaic as an earlier process learned it, the library listing it at r1. */
    private suspend fun learnedEarlier(): ResponseCache = ResponseCache(FakeResponseCacheDao(), Json).also {
        it.put(CacheKeys.playlistMosaic(P), PlaylistMosaic.serializer(), PlaylistMosaic("r1", mosaicCovers(items), listed = "r1"))
    }

    @Test
    fun aPlaylistShownAgainIsNotFetchedAgain() = runTest {
        val store = store()
        assertTrue(store.mosaic(P)!!.isMosaic)
        repeat(3) { store.mosaic(P) }
        assertEquals(listOf(P), fetched)
        assertEquals(listOf("w", "x", "y", "z"), store.peek(P)!!.covers.map { it.album() })
    }

    @Test
    fun theLibraryListingAnotherRevisionMakesItStale() = runTest {
        val store = store()
        store.noteRevisions(mapOf(P to "r1"))
        store.mosaic(P)
        var asks = 0
        backgroundScope.launch { store.changesOf(P).collect { asks++ } }
        runCurrent()
        // Same revision: kept.
        store.noteRevisions(mapOf(P to "r1"))
        store.mosaic(P)
        runCurrent()
        assertEquals(0, asks)
        assertEquals(1, fetched.size)
        // Edited elsewhere: its rows ask again, and it is learned again.
        items = listOf(song(9, "q")) + items
        store.noteRevisions(mapOf(P to "r2"))
        runCurrent()
        assertEquals(1, asks)
        assertEquals(listOf("q", "w", "x", "y"), store.mosaic(P)!!.covers.map { it.album() })
        assertEquals(2, fetched.size)
        store.mosaic(P)
        assertEquals(2, fetched.size)
    }

    @Test
    fun aPlaylistEditedElsewhereIsLearnedAgainWhenTheFirstRootlistAfterAColdStartListsIt() = runTest {
        val store = store(cache = learnedEarlier())
        // Shown before any rootlist is known (a new process): kept, the day's TTL.
        assertEquals(listOf("w", "x", "y", "z"), store.mosaic(P)!!.covers.map { it.album() })
        val shown = row(store, P)
        runCurrent()
        assertTrue(fetched.isEmpty())
        // Edited on another device: the first rootlist fetched lists it at r2. Its row asks again.
        items = listOf(song(9, "q")) + items
        store.noteRevisions(mapOf(P to "r2"))
        runCurrent()
        assertEquals(listOf("q", "w", "x", "y"), shown()!!.covers.map { it.album() })
        assertEquals(listOf(P), fetched)
        // Pull-to-refresh listing the same revision: nothing more.
        store.noteRevisions(mapOf(P to "r2"))
        runCurrent()
        assertEquals(listOf(P), fetched)
    }

    @Test
    fun theCachedRootlistsRevisionsCountUntilAFetchedOneTellsOthers() = runTest {
        val store = store(cache = learnedEarlier())
        // A new process: the cached rootlist (fresh, so nothing is fetched) lists it at r2 already,
        // as the earlier process saw after it learned this mosaic.
        items = listOf(song(9, "q")) + items
        store.noteRevisions(mapOf(P to "r2"), fetched = false)
        assertEquals(listOf("q", "w", "x", "y"), store.mosaic(P)!!.covers.map { it.album() })
        assertEquals(1, fetched.size)
        val shown = row(store, P)
        runCurrent()
        // Edited elsewhere again: pull-to-refresh fetches the rootlist, at r3. Its row asks again.
        items = listOf(song(8, "u")) + items
        store.noteRevisions(mapOf(P to "r2"), fetched = false)
        runCurrent()
        assertEquals(1, fetched.size)
        store.noteRevisions(mapOf(P to "r3"))
        runCurrent()
        assertEquals(listOf("u", "q", "w", "x"), shown()!!.covers.map { it.album() })
        assertEquals(2, fetched.size)
        // The cached rootlist read again (another screen) doesn't undo what the fetch told.
        store.noteRevisions(mapOf(P to "r2"), fetched = false)
        store.mosaic(P)
        runCurrent()
        assertEquals(2, fetched.size)
    }

    @Test
    fun anEditMadeHereMakesItStale() = runTest {
        val store = store()
        store.mosaic(P)
        store.invalidate(P)
        store.mosaic(P)
        assertEquals(2, fetched.size)
    }

    @Test
    fun aPlaylistWhoseRevisionIsntKnownIsLearnedAgainAfterADay() = runTest {
        val store = store()
        store.mosaic(P)
        now += PlaylistMosaicStore.TTL_MS - 1
        store.mosaic(P)
        assertEquals(1, fetched.size)
        now += 1
        store.mosaic(P)
        assertEquals(2, fetched.size)
    }

    @Test
    fun thePlaylistPageGivesItsMosaicToTheListsWithoutAFetch() = runTest {
        val store = store()
        store.noteRevisions(mapOf(P to "r1"))
        store.record(P, "r1", listOf(song(1, "a"), song(2, "b"), song(3, "c"), song(4, "d")))
        assertEquals(listOf("a", "b", "c", "d"), store.mosaic(P)!!.covers.map { it.album() })
        assertTrue(fetched.isEmpty())
    }

    @Test
    fun aRowListedWithoutAnImageWhileThePlaylistHasOneShowsThatOne() = runTest {
        val store = store(firstPage = { uri -> Playlist(uri = uri, name = "P", images = cover("own"), items = items) })
        assertEquals(listOf("own"), store.mosaic(P)!!.covers.map { it.album() })
    }

    @Test
    fun offlineTheDownloadedRowsGiveIt() = runTest {
        online = false
        val store = store(downloaded = { uri -> if (uri == P) listOf(song(1, "d1"), song(2, "d2"), song(3, "d3"), song(4, "d4")) else null })
        assertEquals(listOf("d1", "d2", "d3", "d4"), store.mosaic(P)!!.covers.map { it.album() })
        assertTrue(fetched.isEmpty())
        // Not downloaded: nothing, and tried again once online (not held back as a failure).
        assertNull(store.mosaic("spotify:playlist:other"))
        online = true
        assertTrue(store.mosaic("spotify:playlist:other")!!.isMosaic)
    }

    @Test
    fun rowsShownWhileTheSessionConnectsFillInOnceItIsOnline() = runTest {
        online = false
        val store = store()
        // A cold start: the rows show from the cache before the session is online, more of them
        // than the per-playlist changes buffer holds.
        val uris = (0 until 200).map { "spotify:playlist:$it" }
        val rows = uris.map { row(store, it) }
        runCurrent()
        assertTrue(rows.all { it() == null })
        assertTrue(fetched.isEmpty())
        online = true
        store.onOnline()
        runCurrent()
        assertTrue("every row fills in", rows.all { it()?.isMosaic == true })
        assertEquals("each learned once", uris.toSet(), fetched.toSet())
        assertEquals(uris.size, fetched.size)
        // Online again later (a reconnect): nothing learned is asked for again.
        store.onOnline()
        runCurrent()
        assertEquals(uris.size, fetched.size)
    }

    @Test
    fun aRowListeningOnlyOnceTheSessionIsOnlineStillAsksAgain() = runTest {
        online = false
        val store = store()
        assertNull(store.mosaic(P))
        online = true
        store.onOnline()
        // Its row starts listening for changes only now.
        var asks = 0
        backgroundScope.launch { store.changesOf(P).collect { asks++ } }
        runCurrent()
        assertEquals(1, asks)
    }

    @Test
    fun aFailedFetchIsNotRetriedAtOnce() = runTest {
        var calls = 0
        val store = store(firstPage = { calls++; error("offline") })
        assertNull(store.mosaic(P))
        assertNull(store.mosaic(P))
        assertEquals(1, calls)
        now += PlaylistMosaicStore.RETRY_MS
        store.mosaic(P)
        assertEquals(2, calls)
    }

    @Test
    fun fetchesAreBoundedAndOncePerPlaylist() = runTest {
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var most = 0
        var calls = 0
        val store = store(firstPage = { uri ->
            calls++
            running++
            most = maxOf(most, running)
            gate.await()
            running--
            Playlist(uri = uri, name = "P", items = items)
        })
        // A long library scrolled into view: 10 playlists, the first asked for 5 times.
        val asks = (0 until 10).map { "spotify:playlist:$it" } + List(4) { "spotify:playlist:0" }
        val results = asks.map { uri -> async { store.mosaic(uri) } }
        runCurrent()
        assertEquals(PlaylistMosaicStore.MAX_CONCURRENT_FETCHES, running)
        gate.complete(Unit)
        assertTrue(results.awaitAll().all { it!!.isMosaic })
        assertEquals(PlaylistMosaicStore.MAX_CONCURRENT_FETCHES, most)
        assertEquals("once per playlist", 10, calls)
    }

    @Test
    fun aLearnNoRowAwaitsAnyMoreGivesWayToTheRowsOnScreen() = runTest {
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val store = store(firstPage = { uri ->
            started += uri
            gate.await()
            Playlist(uri = uri, name = "P", items = items)
        })
        // A fling through the library: 10 rows composed on the way, 3 learning, 7 waiting their turn.
        val uris = (0 until 10).map { "spotify:playlist:$it" }
        val rows = uris.map { uri -> launch { store.mosaic(uri) } }
        // Row 4's playlist shows elsewhere too (Home): still awaited.
        val home = async { store.mosaic(uris[4]) }
        runCurrent()
        assertEquals(uris.take(3), started)
        // The fling stops at row 9: the others left the screen.
        rows.take(9).forEach { it.cancel() }
        runCurrent()
        gate.complete(Unit)
        rows[9].join()
        assertTrue(home.await()!!.isMosaic)
        // The 3 learning finished (kept for when they show again); of those waiting, only the ones
        // still awaited were learned, once each.
        assertEquals(uris.take(3) + uris[4] + uris[9], started)
        assertTrue(uris.take(3).all { store.peek(it)!!.isMosaic })
        assertNull(store.peek(uris[5]))
        // Shown again later: learned then.
        assertTrue(store.mosaic(uris[5])!!.isMosaic)
        assertEquals(uris[5], started.last())
    }
}
