package com.taehagen.spotifygood.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Removing the item being downloaded stops it for good: the run does not pick the row the
 * cancellation put back into the queue before the removal deleted it ([awaitItem]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoveWhileDownloadingTest {
    @Test
    fun theRunPicksTheNextItemOnlyAfterTheRemovalDeletedTheRow() = runTest {
        val lock = Mutex() // the manager's mutation lock (the runner's commit lock)
        val queue = mutableListOf("episode", "next") // rows, in pick order
        val started = mutableListOf<String>() // download.track calls
        var current: kotlinx.coroutines.Deferred<String>? = null

        val run = launch {
            while (true) {
                val uri = queue.firstOrNull() ?: break
                started += uri
                val job = async {
                    try {
                        if (uri == "episode") awaitCancellation() // a long download, removed meanwhile
                        "done"
                    } catch (e: CancellationException) {
                        // As downloadItem does: back to the queue (the row is the first pick again).
                        yield()
                        throw e
                    }
                }
                current = job
                val result = awaitItem(job, lock, cancelled = "cancelled")
                current = null
                if (result == "done") queue.remove(uri)
            }
        }
        runCurrent()
        assertEquals(listOf("episode"), started)

        // The removal: under the lock, cancel the item, then delete its row.
        lock.withLock {
            current!!.cancelAndJoin()
            runCurrent() // the run would pick again now if it did not wait for the lock
            yield()
            queue.remove("episode")
        }
        run.join()
        assertEquals("the removed item is not downloaded again", listOf("episode", "next"), started)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun aStopOfTheWholeRunIsRethrown() = runTest {
        val lock = Mutex()
        var rethrown = false
        val run = launch {
            val job = async<String> { awaitCancellation() }
            try {
                awaitItem(job, lock, cancelled = "cancelled")
            } catch (e: CancellationException) {
                rethrown = true
                throw e
            }
        }
        runCurrent()
        run.cancelAndJoin()
        assertTrue(rethrown)
    }
}
