package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepScheduleTest {
    private val now = 1_000_000L
    private val minute = 60_000L

    /** AlarmManager may round the 0.75 factor down: the window ends by the end, at most 1 ms early. */
    private fun assertWindowEndsAt(endsAt: Long, from: Long = now) {
        val end = SleepSchedule.windowEnd(SleepSchedule.stageTrigger(endsAt, from), from)
        assertTrue("window end $end vs $endsAt", end in (endsAt - 2)..endsAt)
    }

    @Test
    fun theInexactWindowEndsAtTheTimersEnd() {
        // The 5-minute option, the usual ones, end of track and a very long timer.
        for (length in listOf(5 * minute, 15 * minute, 30 * minute, 60 * minute, 150_000L, 3 * 60 * minute)) {
            assertWindowEndsAt(now + length)
        }
        assertEquals(now + 30 * minute * 4 / 7, SleepSchedule.stageTrigger(now + 30 * minute, now))
    }

    @Test
    fun longTimersStartTheCappedWindowAnHourBeforeTheEnd() {
        val endsAt = now + 3 * 60 * minute
        assertEquals(endsAt - SleepSchedule.MAX_WINDOW_MS, SleepSchedule.stageTrigger(endsAt, now))
    }

    @Test
    fun theLastSecondsAreExact() {
        assertEquals(now + 9_000, SleepSchedule.stageTrigger(now + 9_000, now))
        assertEquals(now + 9_000, SleepSchedule.windowEnd(now + 9_000, now))
        assertEquals("never in the past", now, SleepSchedule.stageTrigger(now - 5, now))
        assertFalse(SleepSchedule.needsAnotherStage(now + 10_000, now))
        assertTrue(SleepSchedule.needsAnotherStage(now + 10_001, now))
    }

    @Test
    fun earlyDeliveriesConvergeInAFewStages() {
        // Worst case: every stage is delivered at the start of its window.
        val endsAt = now + 60 * minute
        var t = now
        var stages = 0
        while (SleepSchedule.needsAnotherStage(endsAt, t)) {
            t = SleepSchedule.stageTrigger(endsAt, t)
            stages++
            assertTrue(t <= endsAt)
        }
        assertTrue("well within the allow-while-idle quota: $stages", stages <= 10)
    }

    @Test
    fun awakeTimeCoversTheRestPlusThePauseRequestAndIsBounded() {
        assertEquals(4 * minute + SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeMs(now + 4 * minute, now))
        assertEquals(SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeMs(now - 1_000, now))
        assertEquals(SleepSchedule.LEAD_MS + SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeMs(now + 60 * minute, now))
    }

    @Test
    fun longHoldsOnlyForAPlayingRemoteDeviceNearTheEndOrTheFinalStage() {
        val endsAt = now + 6 * minute
        assertEquals(6 * minute + SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeHoldMs(endsAt, now, remotePlaying = true))
        assertEquals(SleepSchedule.POKE_AWAKE_MS, SleepSchedule.awakeHoldMs(endsAt, now, remotePlaying = false))
        // More than the lead before the end: a long hold would expire before it anyway.
        assertEquals(SleepSchedule.POKE_AWAKE_MS, SleepSchedule.awakeHoldMs(now + 26 * minute, now, remotePlaying = true))
        // Final stage, whatever plays.
        assertEquals(8_000 + SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeHoldMs(now + 8_000, now, remotePlaying = false))
    }

    @Test
    fun trackEndIsTheRemainingTimeMinusTheMargin() {
        assertEquals(now + 149_600, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000))
        assertEquals(now, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 199_900))
    }

    @Test
    fun theSameItemIsItsUriWithThePositionGoingOn() {
        val track = "spotify:track:1"
        // The same uid: the same item, a seek back included.
        assertTrue(SleepSchedule.sameItem(track, "u1", 100_000, track, "u1", 100_400))
        assertTrue(SleepSchedule.sameItem(track, "u1", 100_000, track, "u1", 10_000))
        // A uid the engine re-made (offline hand-off o<i>, a restore's q<n>), the position going on.
        assertTrue(SleepSchedule.sameItem(track, "c7", 100_000, track, "o0", 100_300))
        assertTrue(SleepSchedule.sameItem(track, "q3", 100_000, track, "q1", 97_000))
        // A new uid from the start: repeat-one, or the same track reached again.
        assertFalse(SleepSchedule.sameItem(track, "u1", 100_000, track, "u2", 0))
        // Another uri.
        assertFalse(SleepSchedule.sameItem(track, "u1", 100_000, "spotify:track:2", "u1", 100_400))
    }

    @Test
    fun trackEndFollowsThePodcastSpeed() {
        // 150 s of media left: 75 s at 2x, 300 s at 0.5x, 187.5 s at 0.8x; the margin is wall time.
        assertEquals(now + 75_000 - 400, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000, speed = 2.0))
        assertEquals(now + 300_000 - 400, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000, speed = 0.5))
        assertEquals(now + 187_500 - 400, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000, speed = 0.8))
        // Not a usable speed (0 while not playing): 1x.
        assertEquals(now + 149_600, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000, speed = 0.0))
        assertEquals(now + 149_600, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000, speed = Double.NaN))
    }
}
