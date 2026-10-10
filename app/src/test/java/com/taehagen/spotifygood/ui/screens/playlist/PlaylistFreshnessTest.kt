package com.taehagen.spotifygood.ui.screens.playlist

import com.taehagen.spotifygood.model.Playlist
import com.taehagen.spotifygood.model.PlaylistItem
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.ui.screens.album.assignRowKeys
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An open playlist page and changes made elsewhere (docs §9.9): a push of another revision
 * refreshes the rows in place, the revision already shown does nothing, the page start's revision
 * check refreshes only when it changed, and nothing happens while the page is off screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistFreshnessTest {
    /** The page as [PlaylistViewModel] wires it: what it shows, the server, the session. */
    private class Page(scope: TestScope) {
        var shown: String? = "r1"
        var server = "r1"
        var online = true
        var refreshOk = true
        var checks = 0
        var refreshes = 0
        /** Runs inside a refresh (a push arriving meanwhile). */
        var duringRefresh: () -> Unit = {}

        val freshness: PlaylistFreshness = PlaylistFreshness(
            scope.backgroundScope,
            shown = { shown },
            online = { online },
            fetchRevision = {
                checks++
                server
            },
            refresh = { refresh() },
            clock = { scope.currentTime },
        )

        private fun refresh(): Boolean {
            refreshes++
            if (refreshOk) {
                // The page now shows the server's rows, and says so (as refreshLoaded does).
                val fetched = server
                shown = fetched
                duringRefresh()
                freshness.onPage(fetched, fromServer = true)
            }
            return refreshOk
        }
    }

    /**
     * Runs what is due within a few seconds (longer than a settle delay, shorter than the check
     * interval and the echo window). Not advanceUntilIdle: it leaves the background scope's work.
     */
    private fun TestScope.settle() {
        advanceTimeBy(SETTLE_STEP_MS)
        runCurrent()
    }

    /** An open page whose first rows came from the server. */
    private fun TestScope.openPage(): Page = Page(this).also { page ->
        page.freshness.onPage("r1", fromServer = true)
        page.freshness.onStart()
        runCurrent()
    }

    @Test
    fun aPushOfAnotherRevisionRefreshesTheOpenPageOnceAfterTheBurst() = runTest {
        val page = openPage()
        assertEquals("the server just told the revision: no check at the start", 0, page.checks)
        page.server = "r3"
        page.freshness.onPushed("r2")
        advanceTimeBy(PLAYLIST_PUSH_SETTLE_MS / 2)
        page.freshness.onPushed("r3")
        runCurrent()
        assertEquals("waits for the burst to settle", 0, page.refreshes)
        settle()
        assertEquals(1, page.refreshes)
        assertEquals("r3", page.shown)
        assertEquals("a push needs no revision check", 0, page.checks)
    }

    @Test
    fun aPushOfTheRevisionShownDoesNothing() = runTest {
        val page = openPage()
        page.freshness.onPushed("r1")
        page.freshness.onPushed("R1") // hex in another case is the same revision
        settle()
        assertEquals(0, page.refreshes)
        assertEquals(0, page.checks)
        // An edit made here: the page takes the revision it returned before the push arrives.
        page.freshness.onPage("r2", fromServer = true)
        page.shown = "r2"
        page.freshness.onPushed("r2")
        settle()
        assertEquals(0, page.refreshes)
    }

    @Test
    fun aPushWithoutARevisionRefreshes() = runTest {
        val page = openPage()
        page.server = "r2"
        page.freshness.onPushed(null)
        settle()
        assertEquals(1, page.refreshes)
        assertEquals("r2", page.shown)
    }

    @Test
    fun aPushWhileOffScreenRefreshesWhenThePageIsBackWithoutACheck() = runTest {
        val page = openPage()
        page.freshness.onStop()
        page.server = "r2"
        page.freshness.onPushed("r2")
        settle()
        assertEquals("nothing while off screen", 0, page.refreshes)
        page.freshness.onStart()
        settle()
        assertEquals(1, page.refreshes)
        assertEquals(0, page.checks)
        assertEquals("r2", page.shown)
    }

    @Test
    fun leavingThePageCancelsARefreshStillSettling() = runTest {
        val page = openPage()
        page.server = "r2"
        page.freshness.onPushed("r2")
        runCurrent()
        page.freshness.onStop()
        settle()
        assertEquals(0, page.refreshes)
        page.freshness.onStart()
        settle()
        assertEquals(1, page.refreshes)
    }

    @Test
    fun theStartCheckRefreshesOnlyWhenTheRevisionChanged() = runTest {
        val page = Page(this)
        // Opened from the cache while on screen: checked once.
        page.freshness.onStart()
        page.freshness.onPage("r1", fromServer = false)
        settle()
        assertEquals(1, page.checks)
        assertEquals("the same revision: no refresh", 0, page.refreshes)

        // Back later (another page, the app in the background): checked again, changed now.
        page.freshness.onStop()
        advanceTimeBy(PLAYLIST_START_CHECK_INTERVAL_MS)
        page.server = "r2"
        page.freshness.onStart()
        settle()
        assertEquals(2, page.checks)
        assertEquals(1, page.refreshes)
        assertEquals("r2", page.shown)
    }

    @Test
    fun theStartCheckIsSkippedRightAfterTheServerToldTheRevision() = runTest {
        val page = openPage()
        // A rotation, a quick look at another page.
        page.freshness.onStop()
        advanceTimeBy(PLAYLIST_START_CHECK_INTERVAL_MS / 3)
        page.freshness.onStart()
        settle()
        assertEquals(0, page.checks)
    }

    @Test
    fun theSessionComingBackChecksAndAReconnectOffScreenMakesTheNextStartCheck() = runTest {
        val page = openPage()
        page.server = "r2"
        page.freshness.onOnline()
        settle()
        assertEquals(1, page.checks)
        assertEquals(1, page.refreshes)

        page.freshness.onStop()
        page.freshness.onOnline() // reconnected while off screen: pushes may have been lost
        settle()
        assertEquals(1, page.checks)
        page.freshness.onStart() // within the interval, yet checked
        settle()
        assertEquals(2, page.checks)
        assertEquals("unchanged: no refresh", 1, page.refreshes)
    }

    @Test
    fun withoutTheSessionAPushWaitsForIt() = runTest {
        val page = openPage()
        page.online = false
        page.server = "r2"
        page.freshness.onPushed("r2")
        settle()
        assertEquals(0, page.refreshes)
        page.online = true
        page.freshness.onOnline()
        settle()
        assertEquals(1, page.refreshes)
        assertEquals(0, page.checks)
    }

    @Test
    fun aPageThatCantBeRefreshedNowRefreshesOnceItCan() = runTest {
        val page = openPage()
        // An edit is pending (or the page is reloading): nothing to refresh in place yet.
        page.shown = null
        page.server = "r2"
        page.freshness.onPushed("r2")
        settle()
        assertEquals(0, page.refreshes)
        // The edit's end shows the server's revision of before the change.
        page.shown = "r1b"
        page.freshness.onPage("r1b", fromServer = true)
        settle()
        assertEquals(1, page.refreshes)
        assertEquals("r2", page.shown)
    }

    @Test
    fun aFailedRefreshIsNotRetriedOnATimer() = runTest {
        val page = openPage()
        page.refreshOk = false
        page.server = "r2"
        page.freshness.onPushed("r2")
        advanceTimeBy(10 * 60_000L)
        runCurrent()
        assertEquals(1, page.refreshes)
        page.refreshOk = true
        page.freshness.onStop()
        page.freshness.onStart()
        settle()
        assertEquals("the next start tries again", 2, page.refreshes)
    }

    @Test
    fun aPushDuringARefreshRefreshesOnceMore() = runTest {
        val page = openPage()
        page.server = "r2"
        page.duringRefresh = {
            page.duringRefresh = {}
            // Changed again while this refresh runs (its rows are of r2).
            page.server = "r3"
            page.freshness.onPushed("r3")
        }
        page.freshness.onPushed("r2")
        settle()
        assertEquals(2, page.refreshes)
        assertEquals("r3", page.shown)
    }

    // ---- the refresh keeps the list's place -------------------------------------------------------

    private fun item(i: Int) = PlaylistItem(uid = "u$i", track = Track(uri = "spotify:track:${i.toString().padStart(22, '0')}", name = "Song $i"))

    /** A playlist [items] long, served in pages as `catalog.playlist` serves them. */
    private class Server(var items: List<PlaylistItem>, val revision: String) {
        val requests = mutableListOf<Pair<Int, Int>>()

        fun page(offset: Int, limit: Int): Playlist {
            requests += offset to limit
            return Playlist(
                uri = "spotify:playlist:x",
                name = "x",
                revision = revision,
                offset = offset,
                total = items.size,
                items = items.drop(offset).take(limit),
            )
        }
    }

    @Test
    fun aRefreshFetchesTheWholeLoadedRangeAndTheRowsKeepTheirKeys() = runTest {
        val before = (0 until 250).map(::item)
        val oldKeys = assignRowKeys(before, HashSet())
        // A song added at the top on another device.
        val server = Server(listOf(item(1000)) + before, revision = "r2")
        val range = fetchLoadedRange(loaded = before.size, pageSize = 100) { offset, limit -> server.page(offset, limit) }
        assertEquals(listOf(0 to 100, 100 to 100, 200 to 100), server.requests)
        assertTrue("the list doesn't shrink under the user", range.items.size >= before.size)
        assertEquals("r2", range.first.revision)
        val newKeys = assignRowKeys(range.items, HashSet())
        // The row on screen (say the 120th) is the same row, one further down: the list keeps it
        // in place instead of jumping.
        assertEquals(121, newKeys.indexOf(oldKeys[120]))
        assertEquals(oldKeys, newKeys.drop(1).take(before.size))
    }

    @Test
    fun aRefreshOfTheFirstPageOnlyFetchesOnePage() = runTest {
        val server = Server((0 until 30).map(::item), revision = "r2")
        val range = fetchLoadedRange(loaded = 30, pageSize = 100) { offset, limit -> server.page(offset, limit) }
        assertEquals(listOf(0 to 100), server.requests)
        assertEquals(30, range.items.size)
        // Songs removed elsewhere: it ends where the playlist does now.
        server.items = server.items.take(10)
        server.requests.clear()
        val shorter = fetchLoadedRange(loaded = 30, pageSize = 100) { offset, limit -> server.page(offset, limit) }
        assertEquals(listOf(0 to 100), server.requests)
        assertEquals(10, shorter.items.size)
    }

    private companion object {
        const val SETTLE_STEP_MS = 5_000L
    }
}
