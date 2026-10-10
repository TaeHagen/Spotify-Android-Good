package com.taehagen.spotifygood.ui.screens.library

import androidx.compose.runtime.Immutable
import com.taehagen.spotifygood.data.ResponseCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Pages kept of a list's windows ([PageWindows]): about this many rows' worth stay loaded. */
internal const val MAX_WINDOW_PAGES = 8

/** A window loads once the rows on screen stayed put this long (a fast drag passes many). */
internal const val WINDOW_SETTLE_MS = 150L

/** A window that failed loads again after this, while it is still on screen; twice as long after each failure since. */
internal const val WINDOW_RETRY_MS = 3_000L

/** At most this long between the loads of a window that keeps failing. */
internal const val WINDOW_RETRY_MAX_MS = 60_000L

/** A page's rows as fetched; [partial]: some of them are placeholders (their metadata failed). */
internal class WindowPage<T>(val rows: List<T>, val partial: Boolean = false)

/** Loaded windows of a list by page ([PageWindows]); [get] is the row at an absolute index. */
@Immutable
class RowWindows<T>(val pageSize: Int, val pages: Map<Int, List<T>> = emptyMap()) {
    operator fun get(index: Int): T? = if (index < 0) null else pages[index / pageSize]?.getOrNull(index % pageSize)

    val isEmpty: Boolean get() = pages.isEmpty()

    override fun equals(other: Any?): Boolean = other is RowWindows<*> && other.pageSize == pageSize && other.pages == pages
    override fun hashCode(): Int = 31 * pageSize + pages.hashCode()
}

/**
 * Random access to a long paged list (playlist, Liked Songs) past its loaded prefix: the pages
 * holding the rows on screen ([show]) load on their own, by offset, so a fast scroll to row 4,000
 * of 5,000 fetches that page, not the 39 before it. Pages are aligned to [pageSize]; a load starts
 * once the rows on screen stayed put [settleMs] (dragging fast loads nothing on the way), a load
 * for a page scrolled away is cancelled at once, and at most [maxPages] pages are kept (the ones
 * farthest from the screen go). [fetch] returns a page's rows, or null when they can't be used
 * (failed, or from another revision of the list): the page loads again while it is still on screen,
 * after [retryMs], twice as long after each failure since (at most [maxRetryMs]), and only once
 * [ready] returns (the session can reach the server). A [WindowPage.partial] page shows what came
 * and loads again up to [partialRetries] times while on screen (after [partialRetryMs], then twice
 * that), each result replacing it. [hide] stops every load and retry while the rows are off
 * screen. Main thread only.
 */
internal class PageWindows<T>(
    private val scope: CoroutineScope,
    val pageSize: Int,
    private val maxPages: Int = MAX_WINDOW_PAGES,
    private val settleMs: Long = WINDOW_SETTLE_MS,
    private val retryMs: Long = WINDOW_RETRY_MS,
    private val maxRetryMs: Long = WINDOW_RETRY_MAX_MS,
    private val partialRetries: Int = ResponseCache.PARTIAL_RETRIES,
    private val partialRetryMs: Long = ResponseCache.PARTIAL_RETRY_DELAY_MS,
    private val ready: suspend () -> Unit = {},
    private val fetch: suspend (offset: Int, limit: Int) -> WindowPage<T>?,
) {
    private val _windows = MutableStateFlow(RowWindows<T>(pageSize))
    val windows: StateFlow<RowWindows<T>> = _windows.asStateFlow()

    private val loads = HashMap<Int, Job>()
    /** Pages shown partial ([WindowPage.partial]): how often each was loaded again since. */
    private val partial = HashMap<Int, Int>()
    private var settle: Job? = null
    private var wanted: IntRange = IntRange.EMPTY
    /** Bumped by [clear]: a load started before drops its rows. */
    private var generation = 0

    /** Pages being fetched (tests). */
    val loading: Set<Int> get() = loads.keys.toSet()

    /**
     * Rows [first]..[last] are on screen in a list of [total]; those from [from] on (the rows
     * before are the loaded prefix, or its next page on its way) come from windows.
     */
    fun show(first: Int, last: Int, from: Int, total: Int) {
        val start = maxOf(first, from, 0)
        val end = minOf(last, total - 1)
        val needed = if (start > end) IntRange.EMPTY else (start / pageSize)..(end / pageSize)
        if (needed == wanted) return
        wanted = needed
        // Pages scrolled away: their loads stop now.
        loads.keys.filter { it !in needed }.forEach { loads.remove(it)?.cancel() }
        settle?.cancel()
        if (needed.isEmpty()) return
        settle = scope.launch {
            delay(settleMs)
            for (page in needed) {
                // Missing, or partial with loads again left (its last one was cancelled).
                val due = page !in _windows.value.pages || (partial[page] ?: partialRetries) < partialRetries
                if (due && page !in loads) load(page)
            }
        }
    }

    /**
     * The rows are off screen (the list left the screen, or lists no windows now): loads and
     * retries stop. Loaded pages stay; [show] loads what is missing again.
     */
    fun hide() {
        settle?.cancel()
        cancelLoads()
        wanted = IntRange.EMPTY
    }

    /**
     * Cancels every load, out of [loads] first: a load waiting on the main thread (a retry's delay
     * or [ready]) completes inside cancel() under Main.immediate, and its completion handler would
     * remove itself from the map mid-iteration.
     */
    private fun cancelLoads() {
        val pending = loads.values.toList()
        loads.clear()
        pending.forEach(Job::cancel)
    }

    /** Loads [page]; a retry waits [wait], then for [ready]. [failures]: its failed loads in a row. */
    private fun load(page: Int, wait: Long = 0L, failures: Int = 0) {
        val gen = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (wait > 0) {
                delay(wait)
                ready()
            }
            val result = try {
                fetch(page * pageSize, pageSize)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (gen != generation) return@launch
            val reloads = partial[page]
            when {
                // Tried again while it is on screen, less often each time (a fast scroll
                // elsewhere, [hide] or [clear] cancels this).
                result == null && reloads == null -> load(page, minOf(retryMs shl minOf(failures, 16), maxRetryMs), failures + 1)
                // Some rows are placeholders, or loading such a page again failed: what came
                // shows, and the page loads again a few times while it is on screen.
                result == null || result.partial -> {
                    if (result != null) put(page, result.rows)
                    val done = if (reloads == null) 0 else reloads + 1
                    partial[page] = done
                    if (done < partialRetries) load(page, partialRetryMs * (done + 1))
                }
                else -> {
                    partial.remove(page)
                    put(page, result.rows)
                }
            }
        }
        loads[page] = job
        job.invokeOnCompletion { if (loads[page] === job) loads.remove(page) }
        job.start()
    }

    private fun put(page: Int, rows: List<T>) {
        _windows.update { RowWindows(pageSize, keepNear(it.pages + (page to rows), wanted)) }
        // A partial page dropped (far from the screen) loads afresh when it is back.
        partial.keys.retainAll(_windows.value.pages.keys)
    }

    /** At most [maxPages] pages: the ones farthest from the screen go first. */
    private fun keepNear(pages: Map<Int, List<T>>, near: IntRange): Map<Int, List<T>> {
        if (pages.size <= maxPages) return pages
        val center = if (near.isEmpty()) 0 else (near.first + near.last) / 2
        return pages.entries
            .sortedBy { (page, _) -> if (page in near) -1 else abs(page - center) }
            .take(maxPages)
            .associate { it.key to it.value }
    }

    /** The prefix now has [loaded] rows: pages it covers are dropped. */
    fun dropBelow(loaded: Int) {
        val covered = loaded / pageSize
        if (covered <= 0) return
        loads.keys.filter { it < covered }.forEach { loads.remove(it)?.cancel() }
        partial.keys.removeAll { it < covered }
        if (_windows.value.pages.keys.any { it < covered }) {
            _windows.update { current -> RowWindows(pageSize, current.pages.filterKeys { it >= covered }) }
        }
    }

    /** Forgets every window (the list reloaded, changed, or was filtered or sorted). */
    fun clear() {
        generation++
        settle?.cancel()
        cancelLoads()
        partial.clear()
        wanted = IntRange.EMPTY
        if (!_windows.value.isEmpty) _windows.value = RowWindows(pageSize)
    }
}
