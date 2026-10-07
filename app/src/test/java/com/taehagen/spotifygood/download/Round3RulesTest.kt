package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.auth.KeystoreUnavailableException
import com.taehagen.spotifygood.download.DownloadRules.KeyFailure
import com.taehagen.spotifygood.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.ProviderException

class Round3RulesTest {
    // ---- Keystore busy while sealing a finished download's key ----------------------------------------

    @Test
    fun aBusyKeystoreWhileSealingIsTransient() {
        val busy = KeystoreUnavailableException("encrypt: Keystore unavailable", ProviderException("BACKEND_BUSY"))
        assertEquals(KeyFailure.RETRY_LATER, DownloadRules.keyFailure(busy))
    }

    @Test
    fun keystorePausesGrowUntilTheRunHandsBack() {
        val breaker = QueueBreaker()
        val pauses = (1..8).map { breaker.onKeystoreBusy() }
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L), pauses.take(4))
        // From the 4th the pause exceeds what a run waits inline (2 min): it reschedules.
        assertTrue(pauses[3] > 2 * 60_000L)
        assertEquals(DownloadRules.MAX_QUEUE_PAUSE_MS, pauses.last())
        breaker.onSuccess()
        assertEquals(30_000L, breaker.onKeystoreBusy())
    }

    // ---- revived members with a failed row ---------------------------------------------------------------

    @Test
    fun aRevivedMemberWithAFailedRowIsRequeuedBySync() {
        // T was downloaded, then failed by re-validation (explicit filter on) and listed as unplayable;
        // the filter is off again and the next sync lists T as playable.
        val first = DownloadRules.updateAvailability(emptySet(), listed = setOf("a", "t"), listedUnavailable = setOf("t"), complete = true)
        val later = DownloadRules.updateAvailability(first.unavailable, listed = setOf("a", "t"), listedUnavailable = emptySet(), complete = true)
        assertEquals(setOf("t"), later.revived)
        // Sync queues new and revived members; only the revived ones leave a failed row.
        assertEquals(listOf("t"), DownloadRules.requeueOnSync(listOf("new", "t"), later.revived))
        // Once requeued, the collection counts it as pending instead of stuck.
        val status = DownloadRules.collectionStatus(listOf("a", "t"), mapOf("a" to DownloadState.COMPLETED, "t" to DownloadState.QUEUED), later.unavailable)
        assertEquals(CollectionDownloadStatus.InProgress(1, 2, active = true), status)
    }

    // ---- scheduled work for an emptied queue -------------------------------------------------------------

    @Test
    fun scheduledWorkIsCancelledOnlyForAnIdleEmptyQueue() {
        assertTrue(DownloadRules.cancelIdleWork(pending = 0, running = false, jobExecuting = false))
        assertEquals(false, DownloadRules.cancelIdleWork(pending = 3, running = false, jobExecuting = false))
        // A run finds the empty queue itself; an executing job is never cancelled from here.
        assertEquals(false, DownloadRules.cancelIdleWork(pending = 0, running = true, jobExecuting = false))
        assertEquals(false, DownloadRules.cancelIdleWork(pending = 0, running = false, jobExecuting = true))
    }
}
