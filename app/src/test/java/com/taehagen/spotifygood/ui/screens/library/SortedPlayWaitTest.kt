package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.model.AlbumRef
import com.taehagen.spotifygood.model.ArtistRef
import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.SavedTrack
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.playback.EngineReach
import com.taehagen.spotifygood.playback.PlayRequest
import com.taehagen.spotifygood.ui.screens.album.RichText
import com.taehagen.spotifygood.ui.screens.playlist.PlaylistData
import com.taehagen.spotifygood.ui.screens.playlist.PlaylistRow
import com.taehagen.spotifygood.ui.screens.playlist.playlistPlayWaits
import com.taehagen.spotifygood.ui.screens.playlist.sortedPlayableUris
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sorted play started while the list's pages still load ("Loading songs… n of total") waits for
 * every page, then plays the whole sorted order from the tapped song; bounded, coalesced, and
 * dropped by anything else the user plays meanwhile. Liked Songs is driven as its view model does:
 * the real pager, the sort loop fetching the remaining pages, and the same wait / order / plan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SortedPlayWaitTest {
    private val count = 250

    /** Liked Songs, newest first; titles run the other way, so the Title order mixes the pages. */
    private val liked = (0 until count).map { i ->
        SavedTrack(
            addedAt = (count - i).toLong(),
            track = Track(
                uri = "spotify:track:$i",
                name = "t%03d".format(count - 1 - i),
                artists = listOf(ArtistRef("spotify:artist:a", "A")),
                album = AlbumRef("spotify:album:b", "B"),
            ),
        )
    }

    /** The first [loaded] liked songs in Title order: the oldest of them first. */
    private fun titleOrder(loaded: Int) = (loaded - 1 downTo 0).map { "spotify:track:$it" }

    /** Liked Songs as its view model runs it, with the third page held until [lastPage] completes. */
    private class LikedPage(scope: CoroutineScope, all: List<SavedTrack>, lastPage: CompletableDeferred<Unit>, failLast: Boolean = false) {
        val reach = MutableStateFlow(EngineReach.ONLINE)
        val sort = MutableStateFlow(TrackSort.TITLE)
        val pager = PagedLoader<SavedTrack>(scope, 100, { it.track.uri }) { offset, limit ->
            if (offset >= 200) {
                lastPage.await()
                if (failLast) error("page failed")
            }
            PageResult(all.subList(offset, minOf(all.size, offset + limit)), all.size)
        }

        init {
            // The view model's loop: sorted and ONLINE, the remaining pages are fetched one by one.
            combine(pager.state, reach, sort) { page, reach, sort ->
                sort != TrackSort.RECENTLY_ADDED && reach == EngineReach.ONLINE && page.canLoadMore
            }.onEach { if (it) pager.loadMore() }.launchIn(scope)
            pager.loadMore()
        }

        fun waits(): Boolean = likedPlayWaits(reach.value, fromDownload = false, pager.state.value)

        suspend fun awaitRows() = awaitLikedPages(pager.state, reach, sort.map { it != TrackSort.RECENTLY_ADDED })

        /** What the view model sends: the order of every loaded page now, from [startUri]. */
        fun plan(startUri: String?): SortedStart {
            val page = pager.state.value
            return planSortedPlay(likedSortedUris(page.items, page.total, LikedPatch(), sort.value), startUri, reach.value, emptySet())
        }
    }

    private class Sent {
        val requests = mutableListOf<PlayRequest>()
        fun send(plan: SortedStart) {
            requests += (plan as SortedStart.Load).request
        }
    }

    private fun TestScope.likedPage(lastPage: CompletableDeferred<Unit>, failLast: Boolean = false): LikedPage {
        val page = LikedPage(backgroundScope, liked, lastPage, failLast)
        runCurrent()
        // Two pages are in, the third is on its way.
        assertEquals(200, page.pager.state.value.items.size)
        assertTrue(page.pager.state.value.isLoading)
        return page
    }

    @Test
    fun aSortedPlaySendsNothingUntilTheLastPageThenTheWholeSortedWindow() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val sent = Sent()
        // A row of the second page.
        val tapped = "spotify:track:100"
        assertTrue(page.waits())
        starter.play(awaitRows = page::awaitRows) { sent.send(page.plan(tapped)) }
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(sent.requests.isEmpty())
        assertTrue(starter.waiting.value)

        lastPage.complete(Unit)
        runCurrent()
        val request = sent.requests.single()
        // All 250 in Title order (not the 200 loaded at the tap), from the tapped song.
        assertEquals(titleOrder(count), request.trackUris)
        assertEquals(tapped, request.trackUris!![request.startIndex!!])
        assertEquals(149, request.startIndex)
        assertEquals(false, request.shuffle)
        assertFalse(starter.waiting.value)
        assertFalse(page.waits())
    }

    @Test
    fun playFromTheTopStartsAtTheFirstSongOfTheCompleteOrder() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val sent = Sent()
        starter.play(awaitRows = page::awaitRows) { sent.send(page.plan(null)) }
        runCurrent()
        assertTrue(sent.requests.isEmpty())
        lastPage.complete(Unit)
        runCurrent()
        val request = sent.requests.single()
        // "t000" is the oldest like, on the last page.
        assertEquals("spotify:track:249", request.trackUris!!.first())
        assertEquals(0, request.startIndex)
        assertEquals(count, request.trackUris!!.size)
    }

    @Test
    fun afterTheTimeoutTheLoadedRowsPlay() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val sent = Sent()
        val tapped = "spotify:track:100"
        starter.play(awaitRows = page::awaitRows) { sent.send(page.plan(tapped)) }
        runCurrent()
        advanceTimeBy(SORTED_PLAY_WAIT_MS - 1)
        runCurrent()
        assertTrue(sent.requests.isEmpty())
        assertTrue(starter.waiting.value)

        advanceTimeBy(2)
        runCurrent()
        val request = sent.requests.single()
        // The 200 rows loaded by then, in Title order, still from the tapped song.
        assertEquals(titleOrder(200), request.trackUris)
        assertEquals(tapped, request.trackUris!![request.startIndex!!])
        assertFalse(starter.waiting.value)
        // The page arriving later sends nothing more.
        lastPage.complete(Unit)
        runCurrent()
        assertEquals(1, sent.requests.size)
    }

    @Test
    fun aFailedPageEndsTheWaitWithTheLoadedRows() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage, failLast = true)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val sent = Sent()
        starter.play(awaitRows = page::awaitRows) { sent.send(page.plan(null)) }
        runCurrent()
        assertTrue(sent.requests.isEmpty())
        lastPage.complete(Unit)
        runCurrent()
        assertEquals(titleOrder(200), sent.requests.single().trackUris)
    }

    @Test
    fun theSessionLeavingOnlineEndsTheWait() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        var started = 0
        starter.play(awaitRows = page::awaitRows) { started++ }
        runCurrent()
        assertEquals(0, started)
        page.reach.value = EngineReach.CONNECTING
        runCurrent()
        assertEquals(1, started)
        assertFalse(page.waits())
    }

    @Test
    fun theDefaultOrderChosenMeanwhileEndsTheWait() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        var started = 0
        starter.play(awaitRows = page::awaitRows) { started++ }
        runCurrent()
        page.sort.value = TrackSort.RECENTLY_ADDED
        runCurrent()
        assertEquals(1, started)
    }

    @Test
    fun aNewerTapReplacesTheWaitingOneAndOnlyItPlays() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val started = mutableListOf<String>()
        starter.play(awaitRows = page::awaitRows) { started += "first" }
        runCurrent()
        advanceTimeBy(SORTED_PLAY_WAIT_MS - 1_000)
        starter.play(awaitRows = page::awaitRows) { started += "second" }
        runCurrent()
        assertTrue(started.isEmpty())
        assertTrue(starter.waiting.value)
        // The wait is the first tap's: repeated taps don't extend it.
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("second"), started)
        lastPage.complete(Unit)
        runCurrent()
        assertEquals(listOf("second"), started)
    }

    @Test
    fun aPlayThatDoesNotWaitDropsTheWaitingOne() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val started = mutableListOf<String>()
        starter.play(awaitRows = page::awaitRows) { started += "sorted" }
        runCurrent()
        // Shuffle: the context, no wait.
        starter.play(awaitRows = null) { started += "shuffle" }
        runCurrent()
        assertEquals(listOf("shuffle"), started)
        assertFalse(starter.waiting.value)
        lastPage.complete(Unit)
        runCurrent()
        assertEquals(listOf("shuffle"), started)
    }

    @Test
    fun anyPlaybackCommandMeanwhileDropsTheWaitingPlay() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val commands = MutableStateFlow(7L)
        val starter = SortedPlayStarter(backgroundScope, commands)
        var started = 0
        starter.play(awaitRows = page::awaitRows) { started++ }
        runCurrent()
        // E.g. an album played after leaving the page, or a pause from the notification.
        commands.value = 8L
        runCurrent()
        assertFalse(starter.waiting.value)
        lastPage.complete(Unit)
        runCurrent()
        assertEquals(0, started)
    }

    @Test
    fun cancelDropsTheWaitingPlay() = runTest {
        val lastPage = CompletableDeferred<Unit>()
        val page = likedPage(lastPage)
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        var started = 0
        starter.play(awaitRows = page::awaitRows) { started++ }
        runCurrent()
        starter.cancel()
        assertFalse(starter.waiting.value)
        lastPage.complete(Unit)
        runCurrent()
        assertEquals(0, started)
        // The next tap plays again.
        starter.play(awaitRows = page::awaitRows) { started++ }
        runCurrent()
        assertEquals(1, started)
    }

    @Test
    fun aFullyLoadedOrUnreachableListDoesNotWait() {
        val loaded = PagedState(items = liked, total = count, endReached = true)
        assertFalse(likedPlayWaits(EngineReach.ONLINE, fromDownload = false, loaded))
        val partial = PagedState(items = liked.take(100), total = count)
        assertTrue(likedPlayWaits(EngineReach.ONLINE, fromDownload = false, partial))
        // After an error too: the play tries the remaining pages once more.
        assertTrue(likedPlayWaits(EngineReach.ONLINE, fromDownload = false, partial.copy(error = IllegalStateException())))
        assertFalse(likedPlayWaits(EngineReach.CONNECTING, fromDownload = false, partial))
        assertFalse(likedPlayWaits(EngineReach.OFFLINE, fromDownload = true, partial))
    }

    @Test
    fun theLikedOrderComesFromThePagesWithTheInAppLikes() {
        val newest = Track(uri = "spotify:track:new", name = "t999")
        val patch = LikedPatch().liked(listOf(newest)).unliked(listOf("spotify:track:0"))
        val uris = likedSortedUris(liked.take(3), 3, patch, TrackSort.TITLE)
        // t247 (2), t248 (1), t999 (new); t249 (0) was unliked.
        assertEquals(listOf("spotify:track:2", "spotify:track:1", "spotify:track:new"), uris)
    }

    // -- Playlists ------------------------------------------------------------------------------

    private fun rows(n: Int) = (0 until n).map { i ->
        PlaylistRow("k$i", PlaylistItem(uid = "u$i", track = Track(uri = "spotify:track:p$i", name = "p%03d".format(n - 1 - i))))
    }

    private fun playlist(rows: List<PlaylistRow>, total: Int, downloadedCopy: Boolean = false) = PlaylistData(
        meta = Playlist(uri = "spotify:playlist:x", name = "x"),
        description = RichText.EMPTY,
        rows = rows,
        total = total,
        revision = "r",
        downloadedCopy = downloadedCopy,
    )

    @Test
    fun aSortedPlaylistPlaySendsNothingUntilEveryRowIsIn() = runTest {
        val all = rows(150)
        val shown = MutableStateFlow(playlist(all.take(100), total = 150))
        // The playlist's load-all job, its last page still on its way.
        val loadAll = CompletableDeferred<Unit>()
        val starter = SortedPlayStarter(backgroundScope, MutableStateFlow(0L))
        val sent = Sent()
        val tapped = "spotify:track:p10"
        assertTrue(playlistPlayWaits(EngineReach.ONLINE, shown.value))
        starter.play(awaitRows = { loadAll.await() }) {
            // Re-read when it starts, as the view model does.
            sent.send(planSortedPlay(sortedPlayableUris(shown.value.rows, TrackSort.TITLE), tapped, EngineReach.ONLINE, emptySet()))
        }
        runCurrent()
        assertTrue(sent.requests.isEmpty())

        shown.value = playlist(all, total = 150)
        loadAll.complete(Unit)
        runCurrent()
        val request = sent.requests.single()
        assertEquals((149 downTo 0).map { "spotify:track:p$it" }, request.trackUris)
        assertEquals(tapped, request.trackUris!![request.startIndex!!])
        assertFalse(playlistPlayWaits(EngineReach.ONLINE, shown.value))
    }

    @Test
    fun aPlaylistFromTheDownloadOrNotOnlineDoesNotWait() {
        val partial = rows(100)
        assertFalse(playlistPlayWaits(EngineReach.CONNECTING, playlist(partial, total = 150)))
        assertFalse(playlistPlayWaits(EngineReach.ONLINE, playlist(partial, total = 150, downloadedCopy = true)))
        assertFalse(playlistPlayWaits(EngineReach.ONLINE, null))
    }

    @Test
    fun sortedPlaylistUrisLeaveOutWhatCannotPlay() {
        val items = listOf(
            PlaylistRow("a", PlaylistItem(track = Track(uri = "spotify:track:b", name = "b"))),
            PlaylistRow("b", PlaylistItem(track = Track(uri = "spotify:local:x", name = "a"))),
            PlaylistRow("c", PlaylistItem(track = Track(uri = "spotify:track:c", name = ""))),
            PlaylistRow("d", PlaylistItem(track = Track(uri = "spotify:track:d", name = "c", playable = false))),
            PlaylistRow("e", PlaylistItem(track = Track(uri = "spotify:track:a", name = "a"))),
        )
        assertEquals(listOf("spotify:track:a", "spotify:track:b"), sortedPlayableUris(items, TrackSort.TITLE))
    }
}
