package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.db.RetryRow
import com.taehagen.spotifygood.download.DownloadRules.FailureAction
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import org.junit.Assert.assertEquals
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
    fun aRefusedSongFailsAloneAndAnAccountRefusalStopsTheRun() {
        assertEquals(FailureAction.Fail(1), DownloadRules.onFailure(NativeErrorCode.UNAVAILABLE, 0, online = true, null, DownloadRules.KEY_REFUSED))
        assertEquals(FailureAction.StopRun, DownloadRules.onFailure(NativeErrorCode.PLAYBACK_REFUSED, 0, online = true))
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
