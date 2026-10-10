package com.taehagen.spotifygood.ui.screens.library

import com.taehagen.spotifygood.data.ResponseCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Random access into a long paged list by window: what loads, when, and what is cancelled. */
@OptIn(ExperimentalCoroutinesApi::class)
class PageWindowsTest {
    private val total = 5_000

    private fun row(index: Int) = "row$index"

    /**
     * Windows over rows "row0".."row4999" whose fetches are recorded with their time (and held by
     * [gate], if set). The next [fail] fetches fail, the next [partial] come back partial ("?"
     * rows); retries wait for [online].
     */
    private class Fixture(
        scope: TestScope,
        val rows: (Int) -> String,
        total: Int,
        windowScope: CoroutineScope = scope.backgroundScope,
    ) {
        val fetched = mutableListOf<Int>()
        val times = mutableListOf<Long>()
        val cancelled = mutableListOf<Int>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = 0
        var partial = 0
        val online = MutableStateFlow(true)
        val windows = PageWindows(
            windowScope,
            pageSize = 100,
            maxPages = 4,
            ready = { online.first { it } },
        ) { offset, limit ->
            fetched += offset
            times += scope.testScheduler.currentTime
            try {
                gate?.await()
            } catch (e: CancellationException) {
                cancelled += offset
                throw e
            }
            val indices = offset until minOf(total, offset + limit)
            when {
                fail > 0 -> {
                    fail--
                    null
                }
                partial > 0 -> {
                    partial--
                    WindowPage(indices.map { "?" }, partial = true)
                }
                else -> WindowPage(indices.map(rows))
            }
        }
    }

    private fun TestScope.fixture() = Fixture(this, ::row, total)

    private fun TestScope.settle() {
        advanceTimeBy(WINDOW_SETTLE_MS + 1)
        runCurrent()
    }

    @Test
    fun aJumpLoadsOnlyThePageOnScreen() = runTest {
        val f = fixture()
        // Rows 4,000–4,010 of 5,000 on screen, the first 100 loaded.
        f.windows.show(4_000, 4_010, from = 100, total = total)
        runCurrent()
        assertTrue("nothing before the rows stay put", f.fetched.isEmpty())
        settle()
        assertEquals(listOf(4_000), f.fetched)
        assertEquals("row4005", f.windows.windows.value[4_005])
        assertNull(f.windows.windows.value[3_999])
    }

    @Test
    fun rowsAcrossTwoPagesLoadBoth() = runTest {
        val f = fixture()
        f.windows.show(4_095, 4_105, from = 100, total = total)
        settle()
        assertEquals(listOf(4_000, 4_100), f.fetched)
        assertEquals("row4100", f.windows.windows.value[4_100])
    }

    @Test
    fun aFastDragLoadsWhereItStops() = runTest {
        val f = fixture()
        f.windows.show(1_000, 1_010, from = 100, total = total)
        advanceTimeBy(WINDOW_SETTLE_MS / 2)
        f.windows.show(2_000, 2_010, from = 100, total = total)
        advanceTimeBy(WINDOW_SETTLE_MS / 2)
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        assertEquals(listOf(3_000), f.fetched)
    }

    @Test
    fun aLoadForAPageScrolledAwayIsCancelled() = runTest {
        val f = fixture()
        f.gate = CompletableDeferred()
        f.windows.show(1_000, 1_010, from = 100, total = total)
        settle()
        assertEquals(setOf(10), f.windows.loading)
        f.windows.show(3_000, 3_010, from = 100, total = total)
        runCurrent()
        assertEquals(listOf(1_000), f.cancelled)
        assertTrue(f.windows.loading.isEmpty())
        settle()
        assertEquals(setOf(30), f.windows.loading)
        f.gate!!.complete(Unit)
        runCurrent()
        assertEquals("row3000", f.windows.windows.value[3_000])
        assertNull(f.windows.windows.value[1_000])
    }

    @Test
    fun theLoadedPrefixAndTheEndAreNotFetched() = runTest {
        val f = fixture()
        // Rows 50–150 on screen; the first 100 are loaded: only page 1.
        f.windows.show(50, 150, from = 100, total = total)
        settle()
        assertEquals(listOf(100), f.fetched)
        // Past the end: nothing.
        f.windows.show(5_000, 5_010, from = 100, total = total)
        settle()
        assertEquals(listOf(100), f.fetched)
        // Within the prefix: nothing.
        f.windows.show(0, 20, from = 100, total = total)
        settle()
        assertEquals(listOf(100), f.fetched)
    }

    @Test
    fun atMostMaxPagesStayTheFarthestGo() = runTest {
        val f = fixture()
        for (page in listOf(10, 20, 30, 40, 45)) {
            f.windows.show(page * 100, page * 100 + 5, from = 100, total = total)
            settle()
        }
        val kept = f.windows.windows.value.pages.keys
        assertEquals(4, kept.size)
        assertTrue(45 in kept)
        assertTrue("the farthest from the screen went", 10 !in kept)
    }

    @Test
    fun aPageSeenAgainIsNotFetchedAgain() = runTest {
        val f = fixture()
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        assertEquals(listOf(3_000, 2_000), f.fetched)
    }

    @Test
    fun pagesTheLoadedRowsReachAreDropped() = runTest {
        val f = fixture()
        f.windows.show(150, 350, from = 100, total = total)
        settle()
        assertEquals(setOf(1, 2, 3), f.windows.windows.value.pages.keys)
        f.windows.dropBelow(300)
        assertEquals(setOf(3), f.windows.windows.value.pages.keys)
    }

    @Test
    fun clearDropsALateResult() = runTest {
        val f = fixture()
        f.gate = CompletableDeferred()
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        f.windows.clear()
        f.gate!!.complete(Unit)
        runCurrent()
        assertTrue(f.windows.windows.value.isEmpty)
        // Shown again after the clear: loads again.
        f.gate = null
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertEquals("row2000", f.windows.windows.value[2_000])
    }

    @Test
    fun aFailedPageLoadsAgainWhileOnScreen() = runTest {
        val f = fixture()
        f.fail = 1
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertNull(f.windows.windows.value[2_000])
        advanceTimeBy(WINDOW_RETRY_MS + 1)
        runCurrent()
        assertEquals(listOf(2_000, 2_000), f.fetched)
        assertEquals("row2000", f.windows.windows.value[2_000])
    }

    @Test
    fun aFailedPageScrolledAwayIsNotRetried() = runTest {
        val f = fixture()
        f.fail = 1
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        f.windows.show(0, 10, from = 100, total = total)
        advanceTimeBy(WINDOW_RETRY_MS * 2)
        runCurrent()
        assertEquals(listOf(2_000), f.fetched)
    }

    @Test
    fun theRetriesComeLessOftenUpToAMinute() = runTest {
        val f = fixture()
        f.fail = 100
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        advanceTimeBy(213_000)
        runCurrent()
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 48_000L, 60_000L, 60_000L), f.times.zipWithNext { a, b -> b - a })
        assertNull(f.windows.windows.value[2_000])
    }

    @Test
    fun aRetryWaitsForTheSession() = runTest {
        val f = fixture()
        f.fail = 1
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        f.online.value = false
        advanceTimeBy(WINDOW_RETRY_MAX_MS * 10)
        runCurrent()
        assertEquals(listOf(2_000), f.fetched)
        f.online.value = true
        runCurrent()
        assertEquals(listOf(2_000, 2_000), f.fetched)
        assertEquals("row2000", f.windows.windows.value[2_000])
    }

    @Test
    fun aFailedPageIsNotRetriedAfterHide() = runTest {
        val f = fixture()
        f.fail = 100
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertEquals("waiting to retry", setOf(20), f.windows.loading)
        // The list left the screen.
        f.windows.hide()
        assertTrue(f.windows.loading.isEmpty())
        advanceTimeBy(WINDOW_RETRY_MAX_MS * 10)
        runCurrent()
        assertEquals(listOf(2_000), f.fetched)
    }

    @Test
    fun loadsWaitingToRetryCancelInPlaceWithoutBreakingHideOrClear() = runTest {
        // Main.immediate on the main thread: a load waiting in its retry delay completes inside
        // cancel() (as with an unconfined dispatcher), and leaves the map as it completes.
        val unconfined = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        for (forget in listOf(false, true)) {
            val f = Fixture(this, ::row, total, unconfined)
            f.fail = 100
            // Rows 1,495–1,505: pages 14 and 15 both load, fail and wait to retry.
            f.windows.show(1_495, 1_505, from = 100, total = total)
            settle()
            assertEquals("both waiting to retry", setOf(14, 15), f.windows.loading)
            if (forget) f.windows.clear() else f.windows.hide()
            assertTrue(f.windows.loading.isEmpty())
            advanceTimeBy(WINDOW_RETRY_MAX_MS * 10)
            runCurrent()
            assertEquals(listOf(1_400, 1_500), f.fetched.sorted())
        }
    }

    @Test
    fun afterHideShowLoadsWhatIsMissingAgain() = runTest {
        val f = fixture()
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        f.gate = CompletableDeferred()
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        // Hidden: the load on its way stops; the loaded page stays.
        f.windows.hide()
        runCurrent()
        assertEquals(listOf(2_000), f.cancelled)
        assertEquals("row3000", f.windows.windows.value[3_000])
        // The same rows on screen again: the missing page loads, the loaded one doesn't.
        f.gate = null
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertEquals("row2000", f.windows.windows.value[2_000])
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        assertEquals(listOf(3_000, 2_000, 2_000), f.fetched)
    }

    @Test
    fun aPartialPageLoadsAgainWhileOnScreenUntilComplete() = runTest {
        val f = fixture()
        f.partial = 1
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        // What came shows, placeholders included; the page loads again a little later.
        assertEquals("?", f.windows.windows.value[2_000])
        advanceTimeBy(ResponseCache.PARTIAL_RETRY_DELAY_MS + 1)
        runCurrent()
        assertEquals("row2000", f.windows.windows.value[2_000])
        // Complete: not loaded again, also when seen again.
        advanceTimeBy(WINDOW_RETRY_MAX_MS * 10)
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertEquals(listOf(2_000, 2_000, 3_000), f.fetched)
    }

    @Test
    fun aPartialPageLoadsAgainAtMostTwice() = runTest {
        val f = fixture()
        f.partial = 100
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        advanceTimeBy(WINDOW_RETRY_MAX_MS * 10)
        runCurrent()
        assertEquals(1 + ResponseCache.PARTIAL_RETRIES, f.fetched.size)
        assertEquals("?", f.windows.windows.value[2_000])
        // Seen again later: not loaded again either.
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertEquals(listOf(2_000, 2_000, 2_000, 3_000), f.fetched)
    }

    @Test
    fun aPartialPageScrolledAwayLoadsAgainWhenBack() = runTest {
        val f = fixture()
        f.partial = 1
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        // Scrolled away before its load again: that one is cancelled, and runs once it is back.
        f.windows.show(3_000, 3_010, from = 100, total = total)
        settle()
        f.windows.show(2_000, 2_010, from = 100, total = total)
        settle()
        assertEquals(listOf(2_000, 3_000, 2_000), f.fetched)
        assertEquals("row2000", f.windows.windows.value[2_000])
    }
}
