package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.download.DownloadRules.RunNotice
import com.taehagen.spotifygood.model.DownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class Round7Test {
    // ---- likes / playlist edits → one sync a few seconds after the last edit ----------------------------

    @Test
    fun aBurstOfEditsBecomesOneSyncAfterTheLastOne() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<Set<String>>()
        val coalescer = EditCoalescer(scope, delayMs = 5_000) { calls += it }
        coalescer.offer("liked")
        advanceTimeBy(3_000)
        coalescer.offer("playlist")
        coalescer.offer("liked")
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(emptyList<Set<String>>(), calls) // 4 s after the last edit
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(setOf("liked", "playlist")), calls)
    }

    @Test
    fun anEditDuringARunningSyncGetsItsOwnSyncAndDoesNotCancelIt() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<Set<String>>()
        val gate = CompletableDeferred<Unit>()
        var finished = 0
        val coalescer = EditCoalescer(scope, delayMs = 1_000) { batch ->
            calls += batch
            if (calls.size == 1) gate.await()
            finished++
        }
        coalescer.offer("a")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(setOf("a")), calls) // running, waiting on the gate
        coalescer.offer("b")
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(1, calls.size) // waits for the running sync instead of overlapping it
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(setOf("a"), setOf("b")), calls)
        assertEquals(2, finished) // the first sync was not cancelled
    }

    // ---- end-of-run notice --------------------------------------------------------------------------------

    @Test
    fun completeIsShownOnlyWhenTheQueueIsEmpty() {
        assertEquals(RunNotice.COMPLETE, DownloadRules.runNotice(stoppedWithMessage = false, cancelledByUser = false, pending = 0, processed = 120))
        // Wi-Fi lost after 120 of 300: paused, not "120 downloads complete".
        assertEquals(RunNotice.PAUSED, DownloadRules.runNotice(stoppedWithMessage = false, cancelledByUser = false, pending = 180, processed = 120))
        // Nothing done yet, a user Cancel, or a stop that posted its own reason: nothing more.
        assertEquals(RunNotice.NONE, DownloadRules.runNotice(stoppedWithMessage = false, cancelledByUser = false, pending = 180, processed = 0))
        assertEquals(RunNotice.NONE, DownloadRules.runNotice(stoppedWithMessage = false, cancelledByUser = true, pending = 180, processed = 120))
        assertEquals(RunNotice.NONE, DownloadRules.runNotice(stoppedWithMessage = true, cancelledByUser = false, pending = 180, processed = 120))
        assertEquals(RunNotice.NONE, DownloadRules.runNotice(stoppedWithMessage = false, cancelledByUser = false, pending = 0, processed = 0))
    }

    // ---- live progress without database writes -------------------------------------------------------------

    @Test
    fun theActiveItemShowsLiveBytes() {
        val items = listOf(
            DownloadItem("a", DownloadState.COMPLETED, 10, 10, null, null, null),
            DownloadItem("b", DownloadState.DOWNLOADING, 100, 1_000, null, null, null),
        )
        val live = DownloadRules.withLiveProgress(items, currentUri = "b", bytes = 640, totalBytes = 1_000)
        assertEquals(listOf(10L, 640L), live.map { it.bytes })
        // Size not known yet, or nothing running: the rows as stored.
        assertEquals(items, DownloadRules.withLiveProgress(items, currentUri = "b", bytes = 0, totalBytes = 0))
        assertEquals(items, DownloadRules.withLiveProgress(items, currentUri = null, bytes = 640, totalBytes = 1_000))
    }
}
