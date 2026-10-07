package com.taehagen.spotifygood.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetachedTest {
    /** Like the app scope DownloadManager runs on: long-lived, supervisor. */
    private fun TestScope.ownerScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun aCancelledCallerDoesNotStopTheWriteHalfWay() = runTest {
        val owner = ownerScope()
        val steps = mutableListOf<String>()
        // A page removes a download and is popped right after the database commit.
        val page = launch {
            owner.detached {
                steps += "rows deleted"
                delay(1_000)
                steps += "files deleted"
                steps += "offline.remove"
            }
        }
        runCurrent()
        page.cancel()
        advanceUntilIdle()
        assertTrue(page.isCancelled)
        assertEquals(listOf("rows deleted", "files deleted", "offline.remove"), steps)
    }

    @Test
    fun theResultAndFailuresStillReachAWaitingCaller() = runTest {
        val owner = ownerScope()
        assertEquals(42, owner.detached { 42 })
        val failure = runCatching { owner.detached<Unit> { throw IllegalStateException("resolve failed") } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        // A failed write does not take the owner scope down with it.
        assertTrue(owner.isActive)
        assertEquals("next", owner.detached { "next" })
    }
}
