package com.taehagen.spotifygood.engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WallClockGraceTest {
    @Test
    fun theIdleStopGraceIsWallTime() {
        var wall = 1_000L
        val idle = WallClockGrace({ wall }, 60_000)
        assertNull(idle.deadline)
        assertEquals(61_000L, idle.arm())
        wall += 30_000
        assertEquals("an armed grace keeps its deadline", 61_000L, idle.arm())
        assertFalse(idle.isDue())
        // 30 s more of deep sleep: due, though hardly any awake time passed.
        wall += 30_000
        assertTrue(idle.isDue())
        idle.cancel()
        assertNull(idle.deadline)
        assertFalse(idle.isDue())
        // The paused service let go: no grace left.
        assertEquals(wall, idle.arm(immediate = true))
        assertTrue(idle.isDue())
    }

    @Test
    fun delayUntilLooksAtTheClockInChunks() = runTest {
        var wall = 0L
        var done = false
        launch {
            delayUntil(60_000, { wall })
            done = true
        }
        runCurrent()
        // Deep sleep: the wall clock jumps, coroutine time doesn't.
        wall = 60_000
        assertFalse(done)
        advanceTimeBy(DEADLINE_CHECK_MS + 1)
        runCurrent()
        assertTrue("noticed within one chunk of awake time", done)
    }
}
