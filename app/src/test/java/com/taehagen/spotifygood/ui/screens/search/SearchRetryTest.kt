package com.taehagen.spotifygood.ui.screens.search

import com.taehagen.spotifygood.ui.screens.library.PageResult
import com.taehagen.spotifygood.ui.screens.library.PagedLoader
import com.taehagen.spotifygood.ui.screens.library.PagedState
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SearchRetryTest {
    private val failed = IOException("offline")

    @Test
    fun aFailedLaterPageIsRetriedInPlace() {
        val listedWithError = PagedState(items = listOf("a"), error = failed)
        assertTrue(retriesFailedPage(SearchFilter.SONGS, listedWithError))
        assertTrue(retriesFailedPage(SearchFilter.ALBUMS, listedWithError))
        // Everything else searches again.
        assertFalse("top results have no pager", retriesFailedPage(SearchFilter.TOP, listedWithError))
        assertFalse("a failed first page", retriesFailedPage(SearchFilter.SONGS, PagedState<String>(error = failed)))
        assertFalse("no results", retriesFailedPage(SearchFilter.SONGS, PagedState(items = emptyList<String>(), endReached = true)))
        assertFalse("nothing failed", retriesFailedPage(SearchFilter.SONGS, PagedState(items = listOf("a"))))
        assertFalse(retriesFailedPage(SearchFilter.SONGS, null))
    }

    @Test
    fun retryingTheFailedPageKeepsTheListAndAsksForTheNextOffset() = runTest {
        val requests = mutableListOf<Int>()
        var failPage2 = true
        val loader = PagedLoader(this, pageSize = 30, keyOf = { it }) { offset, limit ->
            requests += offset
            if (offset == 30 && failPage2) throw IOException("rate limited")
            PageResult((offset until offset + limit).map { "r$it" }, total = 120)
        }
        loader.loadMore()
        advanceUntilIdle()
        loader.loadMore()
        advanceUntilIdle()
        assertEquals(30, loader.state.value.items.size)
        assertTrue(retriesFailedPage(SearchFilter.SONGS, loader.state.value))

        failPage2 = false
        loader.loadMore() // what retry() does for a failed later page
        assertFalse("the list stays on screen", loader.state.value.isInitialLoading)
        advanceUntilIdle()
        assertEquals(listOf(0, 30, 30), requests)
        assertEquals(60, loader.state.value.items.size)
        assertNull(loader.state.value.error)
    }
}
