package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.db.RetryRow
import com.taehagen.spotifygood.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class RetryFailedTest {
    private val unplayable = "No longer available on Spotify."

    @Test
    fun retryFailedSkipsOnlyDownloadsThatAreNotPlayableHere() {
        val rows = listOf(
            RetryRow("network", DownloadState.FAILED, "Network error. Will retry."),
            RetryRow("cancelled", DownloadState.CANCELLED, null),
            // A greyed-out member of a downloaded collection: it would only fail again.
            RetryRow("greyed", DownloadState.FAILED, "Not available on Spotify."),
            // Failed by re-validation: sync queues it once it is playable again.
            RetryRow("revalidated", DownloadState.FAILED, unplayable),
            // Unreadable key: a retry downloads it again and repairs it.
            RetryRow("key", DownloadState.FAILED, "Re-download required: this download can no longer be decrypted."),
            // Not available as an individual download (no collection says so): still retried.
            RetryRow("individual", DownloadState.FAILED, "Not available on Spotify."),
        )
        assertEquals(
            listOf("network", "cancelled", "key", "individual"),
            DownloadRules.retryable(rows, unavailable = setOf("greyed"), unplayableReason = unplayable),
        )
    }

    @Test
    fun aCancelledRowIsRetriedEvenWithTheUnplayableReasonButNotWhenUnavailable() {
        val rows = listOf(
            RetryRow("a", DownloadState.CANCELLED, unplayable),
            RetryRow("b", DownloadState.CANCELLED, null),
        )
        assertEquals(listOf("a"), DownloadRules.retryable(rows, unavailable = setOf("b"), unplayableReason = unplayable))
    }
}
