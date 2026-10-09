package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.db.LocatedRow
import com.taehagen.spotifygood.data.db.RetryRow
import com.taehagen.spotifygood.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Downloads on an SD card that died or was replaced can be downloaded again (docs §9.7). */
class UnusedCardTest {
    private val internal = "/data/user/0/pkg/no_backup/offline"
    private val oldCard = "/storage/AAAA-1111/Android/data/pkg/files/offline"
    private val newCard = "/storage/BBBB-2222/Android/data/pkg/files/offline"

    private val rows = listOf(
        LocatedRow("spotify:track:internal", "$internal/audio/a", null),
        LocatedRow("spotify:track:old", "$oldCard/audio/b", "$oldCard/images/b.jpg"),
        LocatedRow("spotify:track:new", "$newCard/audio/c", null),
    )

    @Test
    fun aCardThatIsNoLongerChosenStrandsItsDownloads() {
        // The new card is mounted and chosen; the old one never comes back.
        val replaced = DownloadRules.missingCard(rows, { it != oldCard }, "BBBB-2222", internal)
        assertEquals(setOf("spotify:track:old"), replaced.stranded)
        assertEquals(emptySet<String>(), replaced.waiting)
        // Back to internal storage while the old card is out: the same.
        assertEquals(setOf("spotify:track:old"), DownloadRules.missingCard(rows, { it != oldCard }, DownloadLocations.INTERNAL, internal).stranded)
    }

    @Test
    fun theChosenCardThatIsOnlyAwayKeepsItsDownloadsWaiting() {
        val away = DownloadRules.missingCard(rows, { it == internal }, "AAAA-1111", internal)
        assertEquals(setOf("spotify:track:old"), away.waiting)
        // The other card is not the chosen one: its downloads are stranded meanwhile.
        assertEquals(setOf("spotify:track:new"), away.stranded)
        // A volume chosen by its files directory (no UUID).
        assertTrue(DownloadRules.isLocation(oldCard, "/storage/AAAA-1111/Android/data/pkg/files", internal))
        assertTrue(DownloadRules.isLocation("/data/data/pkg/no_backup/offline", DownloadLocations.INTERNAL, internal))
        assertFalse(DownloadRules.isLocation(newCard, "AAAA-1111", internal))
    }

    @Test
    fun downloadingAgainQueuesStrandedRowsAndKeepsMembersMembers() {
        val missing = DownloadRules.MissingCard(waiting = setOf("spotify:track:waiting"), stranded = setOf("spotify:track:old"))
        val states = mapOf(
            "spotify:track:old" to DownloadState.COMPLETED, // a collection member on the dead card
            "spotify:track:waiting" to DownloadState.COMPLETED,
            "spotify:track:failed" to DownloadState.FAILED,
            "spotify:track:fine" to DownloadState.COMPLETED,
            "spotify:track:queued" to DownloadState.QUEUED,
        )
        val plan = DownloadRules.itemRequest(
            listOf("spotify:track:old", "spotify:track:waiting", "spotify:track:failed", "spotify:track:fine", "spotify:track:queued", "spotify:track:new"),
            states,
            missing,
        )
        assertEquals(listOf("spotify:track:old"), plan.redownload)
        assertEquals(listOf("spotify:track:waiting"), plan.waiting)
        assertEquals(listOf("spotify:track:failed"), plan.requeue)
        // Only a URI without a row becomes a single (individual) download.
        assertEquals(listOf("spotify:track:new"), plan.newSingles)
    }

    @Test
    fun downloadAndRetryNeverClaimToStartWhenNothingWill() {
        assertEquals(DownloadRequest.Outcome.STARTED, DownloadRequest(queued = 1).outcome)
        assertNull(DownloadRequest(queued = 1).notice)
        assertEquals(DownloadRequest.Outcome.WAITING_FOR_CARD, DownloadRequest(queued = 0, waitingForCard = 1).outcome)
        assertEquals(DownloadRequest.Outcome.LOCATION_MISSING, DownloadRequest(queued = 2, locationAvailable = false).outcome)
        assertEquals(DownloadRequest.Outcome.NOTHING, DownloadRequest().outcome)
        assertTrue(DownloadRequest(queued = 0, waitingForCard = 1).notice != null)
    }

    @Test
    fun theRowsSayWhichCardTheyWaitFor() {
        val items = listOf(
            DownloadItem("spotify:track:old", DownloadState.COMPLETED, 1, 1, null, null, null),
            DownloadItem("spotify:track:waiting", DownloadState.COMPLETED, 1, 1, null, null, null),
        )
        val missing = DownloadRules.MissingCard(waiting = setOf("spotify:track:waiting"), stranded = setOf("spotify:track:old"))
        val shown = DownloadRules.withUnavailable(
            DownloadRules.withUnavailable(items, missing.waiting, "away"),
            missing.stranded,
            "no longer used",
        )
        assertEquals(listOf("no longer used", "away"), shown.map { it.error })
        // "Retry failed" counts the stranded one, not the one waiting for its card.
        val counts = DownloadRules.failedCounts(shown.map { RetryRow(it.uri, it.state, it.error) }, missing.waiting, "unplayable")
        assertEquals(1, counts.retryable)
        assertEquals(1, counts.unavailable)
    }
}
