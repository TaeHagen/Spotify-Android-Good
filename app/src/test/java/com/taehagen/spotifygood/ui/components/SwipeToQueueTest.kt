package com.taehagen.spotifygood.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe to queue: the threshold, the release decision (distance and fling), the lock, eligibility. */
class SwipeToQueueTest {
    // A 400 dp phone at xxhdpi (2.75): 1,100 px wide, 96 dp = 264 px.
    private val maxPx = 96 * 2.75f
    private val threshold = swipeQueueThreshold(widthPx = 1_100f, maxPx = maxPx)
    private val fling = 800 * 2.75f

    @Test
    fun theThresholdIsTheSmallerOfAShareOfTheWidthAnd96dp() {
        // 28 % of 1,100 px is 308 px: 96 dp is smaller.
        assertEquals(maxPx, threshold, 0f)
        // A narrow row (a split screen): its share.
        assertEquals(600f * SWIPE_QUEUE_WIDTH_SHARE, swipeQueueThreshold(600f, maxPx), 0.01f)
    }

    @Test
    fun releasingPastTheThresholdAdds() {
        assertTrue(swipeQueueCommits(threshold, velocity = 0f, threshold, fling))
        assertTrue(swipeQueueCommits(threshold * 1.5f, velocity = -500f, threshold, fling))
        // Short of it, slowly: springs back.
        assertFalse(swipeQueueCommits(threshold - 1f, velocity = 0f, threshold, fling))
        assertFalse(swipeQueueCommits(threshold * 0.9f, velocity = fling * 0.9f, threshold, fling))
    }

    @Test
    fun aFastFlingCountsByDistancePlusVelocity() {
        // A third of the way at three times the fling speed: projected well past.
        assertTrue(swipeQueueCommits(threshold / 3f, velocity = fling * 3f, threshold, fling))
        // Too little distance for a fling, however fast (a flick while scrolling).
        assertFalse(swipeQueueCommits(threshold * 0.1f, velocity = fling * 10f, threshold, fling))
        // Slower than a fling, short of the threshold: no.
        assertFalse(swipeQueueCommits(threshold * 0.6f, velocity = fling * 0.5f, threshold, fling))
        // Flung back toward the start: no.
        assertFalse(swipeQueueCommits(threshold * 0.9f, velocity = -fling * 3f, threshold, fling))
        // No threshold yet (not laid out): no.
        assertFalse(swipeQueueCommits(500f, velocity = fling * 3f, threshold = 0f, flingVelocity = fling))
    }

    @Test
    fun theRowFollowsTheFingerThenResists() {
        assertEquals(0f, swipeQueueRowOffset(-40f, threshold, 600f), 0f)
        assertEquals(100f, swipeQueueRowOffset(100f, threshold, 600f), 0f)
        assertEquals(threshold, swipeQueueRowOffset(threshold, threshold, 600f), 0f)
        val past = swipeQueueRowOffset(threshold + 100f, threshold, 600f)
        assertTrue(past > threshold && past < threshold + 100f)
        assertEquals(600f, swipeQueueRowOffset(10_000f, threshold, 600f), 0f)
    }

    @Test
    fun onlyAMostlyHorizontalStartToEndMoveLocks() {
        assertTrue(swipeQueueLocks(forward = 10f, vertical = 4f))
        assertTrue(swipeQueueLocks(forward = 10f, vertical = -9f))
        // Mostly vertical: the list scrolls.
        assertFalse(swipeQueueLocks(forward = 6f, vertical = 9f))
        assertFalse(swipeQueueLocks(forward = 0f, vertical = 12f))
        // End to start: not this swipe.
        assertFalse(swipeQueueLocks(forward = -12f, vertical = 0f))
    }

    @Test
    fun whichRowsOfferTheSwipe() {
        assertTrue(swipeToQueueEligible(allowed = true, placeholder = false, playable = true, enabled = true))
        // The Queue, playlist edit mode, a picker.
        assertFalse(swipeToQueueEligible(allowed = false, placeholder = false, playable = true, enabled = true))
        // A placeholder (metadata failed), an unavailable item, a row that can't start now.
        assertFalse(swipeToQueueEligible(allowed = true, placeholder = true, playable = true, enabled = true))
        assertFalse(swipeToQueueEligible(allowed = true, placeholder = false, playable = false, enabled = true))
        assertFalse(swipeToQueueEligible(allowed = true, placeholder = false, playable = true, enabled = false))
    }

    @Test
    fun theStateIsPastOnlyAtTheThreshold() {
        val state = SwipeToQueueState()
        state.offset = 500f
        assertFalse("no threshold before layout", state.past)
        state.threshold = threshold
        state.offset = threshold - 1f
        assertFalse(state.past)
        state.offset = threshold
        assertTrue(state.past)
    }
}
