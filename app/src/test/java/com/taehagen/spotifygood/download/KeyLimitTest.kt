package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.db.RetryRow
import com.taehagen.spotifygood.download.DownloadRules.FailureAction
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotify's audio-key limit as the download queue sees it (docs/ARCHITECTURE.md §9.7). */
class KeyLimitTest {
    private val rateLimited = NativeErrorCode.RATE_LIMITED

    @Test
    fun theEnginesKeyPacingIsNotAFailure() {
        // Never an attempt, whatever the attempts so far: the queue waits for the engine's delay.
        assertEquals(FailureAction.Paced, DownloadRules.onFailure(rateLimited, 0, online = true, 90_000L, DownloadRules.KEY_PACING))
        assertEquals(FailureAction.Paced, DownloadRules.onFailure(rateLimited, 2, online = true, 90_000L, DownloadRules.KEY_PACING))
        val breaker = QueueBreaker()
        // Exactly its delay, again and again (no growth: it is the normal pace of a big download).
        repeat(5) { assertEquals(90_000L, breaker.onFailure(rateLimited, online = true, retryAfterMs = 90_000L, context = DownloadRules.KEY_PACING)) }
        // It did not count as a rate limit: the first real one still follows the server.
        assertEquals(120_000L, breaker.onFailure(rateLimited, online = true, retryAfterMs = 120_000L))
        assertEquals(DownloadRules.MIN_PACING_PAUSE_MS, QueueBreaker().onFailure(rateLimited, true, 1L, DownloadRules.KEY_PACING))
    }

    @Test
    fun pacingLeavesTheConnectivityCountAlone() {
        val breaker = QueueBreaker()
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        breaker.onFailure(rateLimited, online = true, retryAfterMs = 30_000L, context = DownloadRules.KEY_PACING)
        assertEquals(DownloadRules.CONNECTIVITY_PAUSE_MS, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
    }

    @Test
    fun aKeyThrottlePausesTheQueueForTheEnginesCoolDown() {
        assertEquals(FailureAction.Throttled, DownloadRules.onFailure(rateLimited, 2, online = true, 600_000L, DownloadRules.KEY_THROTTLED))
        val breaker = QueueBreaker()
        assertEquals(600_000L, breaker.onFailure(rateLimited, online = true, retryAfterMs = 600_000L, context = DownloadRules.KEY_THROTTLED))
        // The engine's cool-down grows by itself; the queue's own growth never shortens it.
        assertEquals(780_000L, breaker.onFailure(rateLimited, online = true, retryAfterMs = 780_000L, context = DownloadRules.KEY_THROTTLED))
        // Longer than a run waits inline: the run hands the queue back to the system.
        assertTrue(DownloadRules.throttleBudgetSpent(600_000L))
    }

    @Test
    fun aRefusedSongFailsAloneAndAnAccountRefusalKeepsTheRestQueued() {
        assertEquals(FailureAction.Fail(1), DownloadRules.onFailure(NativeErrorCode.UNAVAILABLE, 0, online = true, null, DownloadRules.KEY_REFUSED))
        // The engine's account judgement stops the run without failing what was not tried.
        assertEquals(FailureAction.AccountRefused, DownloadRules.onFailure(NativeErrorCode.PLAYBACK_REFUSED, 0, online = true))
        assertEquals(FailureAction.AccountRefused, DownloadRules.onFailure(NativeErrorCode.PLAYBACK_REFUSED, 2, online = false))
    }

    @Test
    fun retryQueuesRefusedSongsAfterTheOthers() {
        val refusedSong = "Spotify refused to provide this song's audio."
        val rows = listOf(
            RetryRow("spotify:track:gated1", DownloadState.FAILED, refusedSong),
            RetryRow("spotify:track:gated2", DownloadState.FAILED, refusedSong),
            RetryRow("spotify:track:net", DownloadState.FAILED, "Network error. Will retry."),
            RetryRow("spotify:track:cancelled", DownloadState.CANCELLED, null),
        )
        val uris = listOf("spotify:track:gated1", "spotify:track:net", "spotify:track:cancelled")
        // gated2 is not retried here (not in uris): only what is requeued is split.
        assertEquals(setOf("spotify:track:gated1"), DownloadRules.keepsAttempts(uris, rows, refusedSong))
    }

    @Test
    fun theEnginesKeyCoolDownIsFollowedInFullUpToAnHour() {
        // Level 4: a 30 min cool-down plus the refill after it, longer than the queue's own cap.
        val engine = 50 * 60_000L
        assertEquals(engine, QueueBreaker().onFailure(rateLimited, true, engine, DownloadRules.KEY_THROTTLED))
        assertEquals(DownloadRules.MAX_KEY_PAUSE_MS, QueueBreaker().onFailure(rateLimited, true, 5 * 3_600_000L, DownloadRules.KEY_THROTTLED))
        // A CDN 429 keeps the queue's cap.
        assertEquals(DownloadRules.MAX_QUEUE_PAUSE_MS, QueueBreaker().onFailure(rateLimited, true, engine))
    }

    @Test
    fun aLongPauseEndsTheRunUntilItEnds() {
        val now = 1_000_000L
        assertEquals(DownloadRules.IdleStep.Finish, DownloadRules.idleStep(null, now))
        assertEquals(DownloadRules.IdleStep.Wait(90_000L), DownloadRules.idleStep(now + 90_000L, now))
        assertEquals(DownloadRules.IdleStep.Wait(DownloadRules.MAX_INLINE_WAIT_MS), DownloadRules.idleStep(now + DownloadRules.MAX_INLINE_WAIT_MS, now))
        assertEquals(DownloadRules.IdleStep.Wait(DownloadRules.MIN_WAIT_MS), DownloadRules.idleStep(now - 5_000L, now))
        // A key cool-down (10 min): the run ends PAUSED, the resume comes at retryAt.
        assertEquals(DownloadRules.IdleStep.Pause, DownloadRules.idleStep(now + 10 * 60_000L, now))
        assertEquals(10 * 60_000L, DownloadRules.resumeDelayMs(now + 10 * 60_000L, now))
        assertEquals(0L, DownloadRules.resumeDelayMs(now - 1L, now))
        assertEquals(DownloadRules.MAX_KEY_PAUSE_MS, DownloadRules.resumeDelayMs(now + 24 * 3_600_000L, now))
    }

    @Test
    fun aPauseIsResumedWithoutSpendingTheWorkersRetries() {
        val step = { outcome: RunOutcome, attempts: Int -> DownloadRules.workerStep(outcome, attempts, maxRetries = 8) }
        // However many times the queue paused: one resume at its time, never the backoff, never the cap.
        listOf(0, 7, 8, 50).forEach { assertEquals(DownloadRules.WorkerStep.RESUME, step(RunOutcome.PAUSED, it)) }
        // Failing to make progress (no network, not online) keeps the bounded system backoff.
        assertEquals(DownloadRules.WorkerStep.RETRY, step(RunOutcome.RESCHEDULE, 7))
        assertEquals(DownloadRules.WorkerStep.SUCCESS, step(RunOutcome.RESCHEDULE, 8))
        assertEquals(DownloadRules.WorkerStep.SUCCESS, step(RunOutcome.FINISHED, 0))
        assertEquals(DownloadRules.WorkerStep.SUCCESS, step(RunOutcome.STOPPED, 0))
    }

    @Test
    fun aUserActionStartsAWaitingResumeAtOnce() {
        // The delayed resume waits (fresh request: no run attempts) ...
        assertTrue(DownloadRules.replaceWork(replace = false, kick = true, enqueued = true, stale = false, runAttempts = 0, resume = true))
        // ... but other scheduling (a sync adding songs) leaves it to its time.
        assertFalse(DownloadRules.replaceWork(replace = false, kick = false, enqueued = true, stale = false, runAttempts = 0, resume = true))
        // A resume already running is never replaced.
        assertFalse(DownloadRules.replaceWork(replace = false, kick = true, enqueued = false, stale = false, runAttempts = 0, resume = true))
    }

    @Test
    fun theKeyLimitIsShownWithWhenItEnds() {
        assertEquals(
            DownloadPause(DownloadPause.Reason.PACING, 91_000L),
            DownloadRules.pauseFor(rateLimited, DownloadRules.KEY_PACING, 90_000L, now = 1_000L),
        )
        assertEquals(
            DownloadPause(DownloadPause.Reason.LIMITED, 601_000L),
            DownloadRules.pauseFor(rateLimited, DownloadRules.KEY_THROTTLED, 600_000L, now = 1_000L),
        )
        // A CDN 429 is Spotify limiting downloads too.
        assertEquals(DownloadPause.Reason.LIMITED, DownloadRules.pauseFor(rateLimited, null, 60_000L, 0L)?.reason)
        // Connectivity and Keystore pauses are not the key limit.
        assertNull(DownloadRules.pauseFor(NativeErrorCode.NETWORK, null, 60_000L, 0L))
    }

    @Test
    fun minutesLeftAreRoundedUp() {
        assertEquals(1, DownloadRules.minutesUntil(1_001L, 1_000L))
        assertEquals(1, DownloadRules.minutesUntil(61_000L, 1_000L))
        assertEquals(2, DownloadRules.minutesUntil(61_001L, 1_000L))
        assertEquals(10, DownloadRules.minutesUntil(600_000L, 0L))
        assertEquals(0, DownloadRules.minutesUntil(1_000L, 1_000L))
        assertEquals(0, DownloadRules.minutesUntil(0L, 5_000L))
    }

    @Test
    fun theRepairRequeuesOnlyWhatTheThrottleFailed() {
        val refused = "Spotify refused to provide audio for this account."
        val network = "Network error. Will retry."
        val limited = "Spotify is limiting requests. Will retry."
        val rows = listOf(
            RetryRow("spotify:track:refused", DownloadState.FAILED, refused),
            RetryRow("spotify:track:network", DownloadState.FAILED, network),
            RetryRow("spotify:track:limited", DownloadState.FAILED, limited),
            // Not the throttle's doing: Spotify no longer has it, another error, or the user's cancel.
            RetryRow("spotify:track:gone", DownloadState.FAILED, "No longer available on Spotify."),
            RetryRow("spotify:track:other", DownloadState.FAILED, null),
            RetryRow("spotify:track:cancelled", DownloadState.CANCELLED, refused),
        )
        assertEquals(
            listOf("spotify:track:refused", "spotify:track:network", "spotify:track:limited"),
            DownloadRules.throttleRepair(rows, setOf(refused, network, limited)),
        )
    }
}
