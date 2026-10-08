package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedIdleTest {
    private val ten = PausedIdle.TIMEOUT_MS

    @Test
    fun theDeadlineIsWallTimeAfterThePause() {
        val idle = PausedIdle()
        assertNull(idle.update(busy = true, now = 0))
        // Paused at 1 000 (elapsedRealtime: it counts deep sleep, unlike Media3's Handler timer).
        assertEquals(1_000 + ten, idle.update(busy = false, now = 1_000))
        // Later updates while still paused keep the same deadline.
        assertEquals(1_000 + ten, idle.update(busy = false, now = 300_000))
        assertFalse(idle.isDue(busy = false, now = 1_000 + ten - 1))
        // Hours of screen-off time pass with only a few wake-ups: due at the first one after it.
        assertTrue(idle.isDue(busy = false, now = 5 * 3_600_000L))
    }

    @Test
    fun playingOrPresenceCancelsIt() {
        val idle = PausedIdle()
        idle.update(busy = false, now = 0)
        assertNull(idle.update(busy = true, now = 5_000)) // playing again, or presence on
        assertFalse(idle.isDue(busy = true, now = ten * 2))
        // Paused again: a new window.
        assertEquals(ten * 2 + ten, idle.update(busy = false, now = ten * 2))
        assertFalse(idle.isDue(busy = false, now = ten * 2 + 1))
    }

    @Test
    fun afterLettingGoNothingIsArmedUntilPlaybackIsAskedForAgain() {
        val idle = PausedIdle()
        idle.update(busy = false, now = 0)
        assertTrue(idle.isDue(busy = false, now = ten))
        idle.expire()
        assertFalse(idle.isDue(busy = false, now = ten * 3))
        assertNull(idle.update(busy = false, now = ten * 3))
        // A seek while paused (a playback command) counts from then.
        idle.restart(now = ten * 4)
        assertEquals(ten * 5, idle.update(busy = false, now = ten * 4 + 10))
        assertTrue(idle.isDue(busy = false, now = ten * 5))
        // Or playback starts: busy resets everything.
        idle.expire()
        idle.update(busy = true, now = ten * 6)
        assertEquals(ten * 7, idle.update(busy = false, now = ten * 6))
    }
}
