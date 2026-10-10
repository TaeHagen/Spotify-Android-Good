package com.taehagen.spotifygood.ui.screens.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
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

    /** Windows over rows "row0".."row4999" whose fetches are recorded (and held by [gate], if set). */
    private class Fixture(scope: TestScope, val rows: (Int) -> String, total: Int) {
        val fetched = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = 0
        val windows = PageWindows(scope.backgroundScope, pageSize = 100, maxPages = 4) { offset, limit ->
            fetched += offset
            try {
                gate?.await()
            } catch (e: CancellationException) {
                cancelled += offset
                throw e
            }
            if (fail > 0) {
                fail--
                null
            } else {
                (offset until minOf(total, offset + limit)).map(rows)
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
}
