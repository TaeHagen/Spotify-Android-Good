package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.download.DownloadRules.DownloadMode
import com.taehagen.spotifygood.download.DownloadRules.IdleStep
import com.taehagen.spotifygood.download.DownloadRules.PausedStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Continuous downloads or bursts (docs/ARCHITECTURE.md §9.7 "Who continues a paused queue"). */
class DownloadModeTest {
    private val now = 1_000_000L
    private val min = 60_000L

    @Test
    fun visibleOrPluggedInIsContinuousInAHostThatMayRunLong() {
        assertEquals(DownloadMode.CONTINUOUS, DownloadRules.downloadMode(visible = true, pluggedIn = false, longRunningHost = true))
        assertEquals(DownloadMode.CONTINUOUS, DownloadRules.downloadMode(visible = false, pluggedIn = true, longRunningHost = true))
        assertEquals(DownloadMode.CONTINUOUS, DownloadRules.downloadMode(visible = true, pluggedIn = true, longRunningHost = true))
        // In the background on battery: bursts, nothing awake between them.
        assertEquals(DownloadMode.BURSTS, DownloadRules.downloadMode(visible = false, pluggedIn = false, longRunningHost = true))
        // An ordinary job is stopped after about 10 min: bursts, also on power.
        assertEquals(DownloadMode.BURSTS, DownloadRules.downloadMode(visible = false, pluggedIn = true, longRunningHost = false))
        assertEquals(DownloadMode.BURSTS, DownloadRules.downloadMode(visible = true, pluggedIn = false, longRunningHost = false))
    }

    @Test
    fun aRunWaitsShortlyInBurstsAndLongerWhenContinuous() {
        // The next batch of 3 keys in 105 s: waited inline when continuous, the burst ends otherwise.
        assertEquals(IdleStep.Wait(105_000L), DownloadRules.idleStep(now + 105_000L, now, DownloadMode.CONTINUOUS))
        assertEquals(IdleStep.Pause, DownloadRules.idleStep(now + 105_000L, now, DownloadMode.BURSTS))
        // The engine's spacing and an item's first retries stay inline in a burst.
        assertEquals(IdleStep.Wait(20_000L), DownloadRules.idleStep(now + 20_000L, now, DownloadMode.BURSTS))
        assertEquals(IdleStep.Wait(DownloadRules.BURST_INLINE_WAIT_MS), DownloadRules.idleStep(now + DownloadRules.BURST_INLINE_WAIT_MS, now, DownloadMode.BURSTS))
        // A longer pause ends the run both ways: continuous, the host waits it out without the engine.
        assertEquals(IdleStep.Pause, DownloadRules.idleStep(now + 10 * min, now, DownloadMode.CONTINUOUS))
    }

    @Test
    fun aUserInitiatedJobThatPausesWhileVisibleOrOnPowerKeepsWaiting() {
        val job = { visible: Boolean, plugged: Boolean -> DownloadRules.pausedStep(DownloadRules.downloadMode(visible, plugged, longRunningHost = true)) }
        assertEquals(PausedStep.WAIT_IN_HOST, job(true, false))
        assertEquals(PausedStep.WAIT_IN_HOST, job(false, true))
        // The app left on battery: the queue goes to the WorkManager resume.
        assertEquals(PausedStep.HAND_OFF, job(false, false))
        // A worker without a foreground service always hands off.
        assertEquals(PausedStep.HAND_OFF, DownloadRules.pausedStep(DownloadRules.downloadMode(true, true, longRunningHost = false)))
    }

    @Test
    fun aBurstResumesForABatchOfKeysNotTheNextOne() {
        // The engine's next key is due in 85 s (the rows' retryAt), 9 keys in about 5 min.
        val nextKey = now + 85_000L
        val batch = 315_000L
        assertEquals(now + batch, DownloadRules.burstResumeAt(nextKey, now, batch, pluggedIn = false))
        // A cool-down that ends later than the batch is waited for (the rows hold it).
        assertEquals(now + 20 * min, DownloadRules.burstResumeAt(now + 20 * min, now, 12 * min, pluggedIn = false))
        // The engine counts its cool-down in the batch time too.
        assertEquals(now + 25 * min, DownloadRules.burstResumeAt(nextKey, now, 25 * min, pluggedIn = false))
        // On power the next burst comes with the next key (no job quota while charging).
        assertEquals(nextKey, DownloadRules.burstResumeAt(nextKey, now, batch, pluggedIn = true))
        // Without the engine's answer: the rows' own time.
        assertEquals(nextKey, DownloadRules.burstResumeAt(nextKey, now, null, pluggedIn = false))
        assertEquals(now + batch, DownloadRules.burstResumeAt(null, now, batch, pluggedIn = false))
        assertEquals(now + DownloadRules.MAX_KEY_PAUSE_MS, DownloadRules.burstResumeAt(nextKey, now, 10 * 60 * min, pluggedIn = false))
    }

    @Test
    fun aBurstInAnOrdinaryJobEndsBeforeItsExecutionLimit() {
        val deadline = DownloadRules.BURST_ITEM_DEADLINE_MS
        assertFalse(DownloadRules.burstDeadlineReached(deadline - 1, DownloadMode.BURSTS, longRunningHost = false))
        assertTrue(DownloadRules.burstDeadlineReached(deadline, DownloadMode.BURSTS, longRunningHost = false))
        // Comfortably within the ~10 min an ordinary job gets: the last item has 3 min.
        assertTrue(deadline <= 7 * min)
        // A job or foreground worker has no such limit, and a continuous run none either.
        assertFalse(DownloadRules.burstDeadlineReached(60 * min, DownloadMode.BURSTS, longRunningHost = true))
        assertFalse(DownloadRules.burstDeadlineReached(60 * min, DownloadMode.CONTINUOUS, longRunningHost = true))
    }

    @Test
    fun theQueueGoesBackToAUserInitiatedJobWhenTheAppIsVisible() {
        assertTrue(DownloadRules.handsToUserInitiatedJob(sdkInt = 34, visible = true, pending = 12))
        assertTrue(DownloadRules.handsToUserInitiatedJob(sdkInt = 36, visible = true, pending = 1))
        // Before Android 14 there are no such jobs; in the background none can be scheduled.
        assertFalse(DownloadRules.handsToUserInitiatedJob(sdkInt = 33, visible = true, pending = 12))
        assertFalse(DownloadRules.handsToUserInitiatedJob(sdkInt = 34, visible = false, pending = 12))
        assertFalse(DownloadRules.handsToUserInitiatedJob(sdkInt = 34, visible = true, pending = 0))
        // The waiting WorkManager resume goes then, never work that runs.
        assertTrue(DownloadRules.cancelsWaitingResume(listOf(false to true)))
        assertFalse(DownloadRules.cancelsWaitingResume(listOf(true to false, false to true)))
        assertFalse(DownloadRules.cancelsWaitingResume(listOf(false to false)))
        assertFalse(DownloadRules.cancelsWaitingResume(emptyList()))
    }

    @Test
    fun burstsDownloadAsManySongsAnHourAsAContinuousRun() {
        // 9 keys per burst at one key per 35 s refill: about 100 an hour, like the continuous pace.
        val perHour = 3_600_000L / (DownloadRules.BURST_KEYS * 35_000L) * DownloadRules.BURST_KEYS
        assertTrue("$perHour", perHour in 90..110)
    }
}
