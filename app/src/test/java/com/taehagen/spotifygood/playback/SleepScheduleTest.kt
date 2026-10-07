package com.taehagen.spotifygood.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SleepScheduleTest {
    private val now = 1_000_000L

    @Test
    fun theAlarmWindowEndsAtTheTimersEnd() {
        val endsAt = now + 30 * 60_000L
        assertEquals(endsAt - SleepSchedule.LEAD_MS, SleepSchedule.windowStart(endsAt, now))
        assertEquals(SleepSchedule.LEAD_MS, SleepSchedule.windowLength(endsAt, now))
    }

    @Test
    fun aShortTimerStartsTheWindowNow() {
        val endsAt = now + 5 * 60_000L
        assertEquals(now, SleepSchedule.windowStart(endsAt, now))
        assertEquals(5 * 60_000L, SleepSchedule.windowLength(endsAt, now))
        // Already due: a minimal window, never a negative one.
        assertEquals(now, SleepSchedule.windowStart(now - 10, now))
        assertEquals(1L, SleepSchedule.windowLength(now - 10, now))
    }

    @Test
    fun awakeTimeCoversTheRestPlusThePauseRequestAndIsBounded() {
        assertEquals(4 * 60_000L + SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeMs(now + 4 * 60_000L, now))
        assertEquals(SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeMs(now - 1_000, now))
        assertEquals(SleepSchedule.LEAD_MS + SleepSchedule.PAUSE_SLACK_MS, SleepSchedule.awakeMs(now + 3_600_000L, now))
    }

    @Test
    fun trackEndIsTheRemainingTimeMinusTheMargin() {
        assertEquals(now + 149_600, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 50_000))
        assertEquals(now, SleepSchedule.trackEndsAt(now, durationMs = 200_000, positionMs = 199_900))
    }
}
