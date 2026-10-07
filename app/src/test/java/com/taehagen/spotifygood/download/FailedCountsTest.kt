package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.db.RetryRow
import com.taehagen.spotifygood.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class FailedCountsTest {
    private val unplayable = "No longer available on Spotify"

    @Test
    fun theRetryButtonCountsOnlyWhatRetryPutsBack() {
        val rows = listOf(
            RetryRow("spotify:track:net", DownloadState.FAILED, "Network error"),
            RetryRow("spotify:track:gone", DownloadState.FAILED, unplayable),
            RetryRow("spotify:track:greyed", DownloadState.FAILED, "Network error"),
            // Cancelled rows are retried too, but are not "failed" downloads.
            RetryRow("spotify:track:cancelled", DownloadState.CANCELLED, null),
        )
        val counts = DownloadRules.failedCounts(rows, unavailable = setOf("spotify:track:greyed"), unplayableReason = unplayable)
        assertEquals(FailedCounts(retryable = 1, unavailable = 2), counts)
        assertEquals(3, counts.total)
        // The same split as retryFailed(): exactly the retryable failed rows are requeued.
        val requeued = DownloadRules.retryable(rows.filter { it.state == DownloadState.FAILED }, setOf("spotify:track:greyed"), unplayable)
        assertEquals(listOf("spotify:track:net"), requeued)
    }

    @Test
    fun nothingFailed() {
        assertEquals(FailedCounts(), DownloadRules.failedCounts(emptyList(), emptySet(), unplayable))
    }
}
