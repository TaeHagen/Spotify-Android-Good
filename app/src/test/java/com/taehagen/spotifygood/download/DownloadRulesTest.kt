package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.download.DownloadRules.FailureAction
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRulesTest {

    // ---- backoff / failure policy ------------------------------------------------------------------

    @Test
    fun backoffGrowsExponentiallyAndIsCapped() {
        assertEquals(5_000L, DownloadRules.backoffMs(1))
        assertEquals(20_000L, DownloadRules.backoffMs(2))
        assertEquals(80_000L, DownloadRules.backoffMs(3))
        assertEquals(DownloadRules.MAX_BACKOFF_MS, DownloadRules.backoffMs(10))
        assertEquals(DownloadRules.MAX_BACKOFF_MS, DownloadRules.backoffMs(Int.MAX_VALUE))
        assertEquals(5_000L, DownloadRules.backoffMs(0))
    }

    @Test
    fun serverRetryAfterWinsWhenLonger() {
        assertEquals(42_000L, DownloadRules.backoffMs(1, retryAfterMs = 42_000L))
        assertEquals(20_000L, DownloadRules.backoffMs(2, retryAfterMs = 1_000L))
        assertEquals(DownloadRules.MAX_BACKOFF_MS, DownloadRules.backoffMs(1, retryAfterMs = 60 * 60_000L))
    }

    @Test
    fun accountWideErrorsStopTheRun() {
        listOf(NativeErrorCode.PREMIUM_REQUIRED, NativeErrorCode.PLAYBACK_REFUSED, NativeErrorCode.BAD_CREDENTIALS).forEach {
            assertEquals(FailureAction.StopRun, DownloadRules.onFailure(it, previousAttempts = 0, online = true))
        }
    }

    @Test
    fun networkErrorWhileOfflineWaitsWithoutCountingAnAttempt() {
        assertEquals(FailureAction.WaitForNetwork, DownloadRules.onFailure(NativeErrorCode.NETWORK, 2, online = false))
        assertEquals(FailureAction.WaitForNetwork, DownloadRules.onFailure(NativeErrorCode.NOT_CONNECTED, 0, online = false))
    }

    @Test
    fun transientErrorsRetryThenFailAfterThreeAttempts() {
        assertEquals(FailureAction.Retry(1, 5_000L), DownloadRules.onFailure(NativeErrorCode.NETWORK, 0, online = true))
        assertEquals(FailureAction.Retry(2, 20_000L), DownloadRules.onFailure(NativeErrorCode.INTERNAL, 1, online = true))
        assertEquals(FailureAction.Fail(3), DownloadRules.onFailure(NativeErrorCode.INTERNAL, 2, online = true))
    }

    @Test
    fun rateLimitsAreNeverCountedAsAttempts() {
        // Even on the last attempt and with a server delay: the item waits with the queue.
        assertEquals(
            FailureAction.Throttled,
            DownloadRules.onFailure(NativeErrorCode.RATE_LIMITED, 0, online = true, retryAfterMs = 30_000L),
        )
        assertEquals(FailureAction.Throttled, DownloadRules.onFailure(NativeErrorCode.RATE_LIMITED, 2, online = true))
    }

    // ---- queue-wide pauses -----------------------------------------------------------------------------

    @Test
    fun aRateLimitPausesTheQueueForTheServerDelay() {
        val breaker = QueueBreaker()
        assertEquals(120_000L, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = 120_000L))
        // Longer than the per-item backoff cap: a server delay is honoured up to the queue cap.
        assertEquals(20 * 60_000L, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = 20 * 60_000L))
        assertEquals(DownloadRules.MAX_QUEUE_PAUSE_MS, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = 5 * 3_600_000L))
        assertEquals(DownloadRules.BASE_BACKOFF_MS, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = 0L))
    }

    @Test
    fun rateLimitsWithoutADelayPauseLongerEachTimeUntilADownloadSucceeds() {
        val breaker = QueueBreaker()
        assertEquals(60_000L, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = null))
        assertEquals(120_000L, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = null))
        assertEquals(240_000L, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = null))
        repeat(10) { breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = null) }
        assertEquals(DownloadRules.MAX_QUEUE_PAUSE_MS, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = null))
        breaker.onSuccess()
        assertEquals(60_000L, breaker.onFailure(NativeErrorCode.RATE_LIMITED, online = true, retryAfterMs = null))
    }

    @Test
    fun repeatedConnectivityFailuresWhileOnlinePauseTheQueue() {
        val breaker = QueueBreaker()
        // The CDN is unreachable while the session is online: three items in a row trip the breaker.
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        assertNull(breaker.onFailure(NativeErrorCode.NOT_CONNECTED, online = true, retryAfterMs = null))
        assertEquals(60_000L, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        // It keeps failing after the pause: the next trips pause longer.
        repeat(2) { assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null)) }
        assertEquals(240_000L, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        repeat(2) { breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null) }
        assertEquals(960_000L, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        repeat(2) { breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null) }
        assertEquals(DownloadRules.MAX_QUEUE_PAUSE_MS, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        breaker.onSuccess()
        repeat(2) { assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null)) }
        assertEquals(60_000L, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
    }

    @Test
    fun otherFailuresAndOfflineFailuresDoNotTripTheBreaker() {
        val breaker = QueueBreaker()
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        // An item-specific failure means the service answered: the run of failures is broken.
        assertNull(breaker.onFailure(NativeErrorCode.UNAVAILABLE, online = true, retryAfterMs = null))
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
        // The device went offline: the run waits for the network, the breaker stays put.
        repeat(5) { assertNull(breaker.onFailure(NativeErrorCode.NETWORK, online = false, retryAfterMs = null)) }
        assertEquals(60_000L, breaker.onFailure(NativeErrorCode.NETWORK, online = true, retryAfterMs = null))
    }

    @Test
    fun permanentErrorsFailImmediately() {
        assertEquals(FailureAction.Fail(1), DownloadRules.onFailure(NativeErrorCode.UNAVAILABLE, 0, online = true))
        assertEquals(FailureAction.Fail(1), DownloadRules.onFailure(NativeErrorCode.NOT_FOUND, 0, online = false))
    }

    // ---- membership ----------------------------------------------------------------------------------

    @Test
    fun anEmptyIncompleteResolutionDeletesNothing() {
        // The album's track metadata failed: catalog.album came back without tracks.
        val update = DownloadRules.updateMembership(listOf("a", "b", "c"), resolved = emptyList(), complete = false)
        assertEquals(listOf("a", "b", "c"), update.items)
        assertEquals(emptyList<String>(), update.dropped)
        assertEquals(emptyList<String>(), update.added)
    }

    @Test
    fun aShortResolutionOnlyAddsItems() {
        val update = DownloadRules.updateMembership(listOf("a", "b", "c"), resolved = listOf("b", "d"), complete = false)
        assertEquals(listOf("b", "d", "a", "c"), update.items)
        assertEquals(listOf("d"), update.added)
        assertEquals(emptyList<String>(), update.dropped)
    }

    @Test
    fun aCompleteResolutionReplacesTheMembership() {
        val update = DownloadRules.updateMembership(listOf("a", "b", "c"), resolved = listOf("b", "d", "b"), complete = true)
        assertEquals(listOf("b", "d"), update.items)
        assertEquals(listOf("d"), update.added)
        assertEquals(listOf("a", "c"), update.dropped)
    }

    @Test
    fun removingACollectionKeepsItemsSharedWithOtherCollections() {
        val album = listOf("t1", "t2", "t3")
        val playlist = listOf("t2", "t9")
        val deleted = DownloadRules.itemsToDelete(album, keptCollections = listOf(playlist), individual = emptySet())
        assertEquals(listOf("t1", "t3"), deleted)
    }

    @Test
    fun removingACollectionKeepsIndividuallyDownloadedItems() {
        val deleted = DownloadRules.itemsToDelete(listOf("t1", "t2", "t1"), keptCollections = emptyList(), individual = setOf("t2"))
        assertEquals(listOf("t1"), deleted)
    }

    @Test
    fun droppedItemsAreKeptWhenStillInTheNewMembership() {
        // Sync: an item moved position (dropped once, still present) must not be deleted.
        val deleted = DownloadRules.itemsToDelete(listOf("t1"), keptCollections = listOf(listOf("t1", "t2")), individual = emptySet())
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun diffReportsAddedAndDroppedInOrder() {
        val diff = DownloadRules.diff(old = listOf("a", "b", "c"), new = listOf("c", "d", "a", "e", "d"))
        assertEquals(listOf("d", "e"), diff.added)
        assertEquals(listOf("b"), diff.dropped)
    }

    // ---- collection status ---------------------------------------------------------------------------

    @Test
    fun statusIsNoneWhenNotDownloaded() {
        assertEquals(CollectionDownloadStatus.None, DownloadRules.collectionStatus(null, emptyMap()))
    }

    @Test
    fun emptyCollectionIsComplete() {
        assertEquals(CollectionDownloadStatus.Complete, DownloadRules.collectionStatus(emptyList(), emptyMap()))
    }

    @Test
    fun statusCountsCompletedAndReportsPendingWork() {
        val states = mapOf(
            "a" to DownloadState.COMPLETED,
            "b" to DownloadState.DOWNLOADING,
            "c" to DownloadState.FAILED,
        )
        assertEquals(
            CollectionDownloadStatus.InProgress(done = 1, total = 4, active = true),
            DownloadRules.collectionStatus(listOf("a", "b", "c", "d"), states),
        )
        assertEquals(
            CollectionDownloadStatus.InProgress(done = 1, total = 3, active = false),
            DownloadRules.collectionStatus(listOf("a", "c", "d"), states),
        )
    }

    @Test
    fun statusIsCompleteWhenEveryDistinctItemIsDownloaded() {
        val states = mapOf("a" to DownloadState.COMPLETED, "b" to DownloadState.COMPLETED)
        assertEquals(CollectionDownloadStatus.Complete, DownloadRules.collectionStatus(listOf("a", "b", "a"), states))
    }

    // ---- misc ----------------------------------------------------------------------------------------

    @Test
    fun estimateScalesWithBitrate() {
        assertEquals(9_600_000L, DownloadRules.estimateBytes(1, 320))
        assertEquals(2 * 4_800_000L, DownloadRules.estimateBytes(2, 160))
        assertEquals(0L, DownloadRules.estimateBytes(0, 160))
    }

    @Test
    fun hexRoundTrips() {
        val bytes = byteArrayOf(0, 1, 0x7f, -0x80, -1, 0x2a)
        assertEquals("00017f80ff2a", Hex.encode(bytes))
        assertArrayEquals(bytes, Hex.decode("00017f80ff2a"))
        assertArrayEquals(bytes, Hex.decode("00017F80FF2A"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hexRejectsOddLength() {
        Hex.decode("abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun hexRejectsNonHex() {
        Hex.decode("zz")
    }
}
