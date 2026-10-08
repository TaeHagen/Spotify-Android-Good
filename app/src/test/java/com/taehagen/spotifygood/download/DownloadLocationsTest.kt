package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.data.db.LocatedRow
import com.taehagen.spotifygood.model.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Downloads on internal storage or an SD card (docs §9.7). */
class DownloadLocationsTest {
    private val internal = "/data/user/0/com.taehagen.spotifygood/no_backup/offline"
    private val card = "/storage/1234-ABCD/Android/data/com.taehagen.spotifygood/files/offline"
    private val a = "aa".repeat(20)
    private val b = "bb".repeat(20)

    @Test
    fun aFileBelongsToTheLocationAboveItsFolder() {
        assertEquals(internal, DownloadRules.rootOf("$internal/audio/$a"))
        assertEquals(card, DownloadRules.rootOf("$card/images/$b.jpg"))
        // The /data/data alias of internal storage is the same location.
        assertEquals(internal, DownloadRules.rootOf("/data/data/com.taehagen.spotifygood/no_backup/offline/audio/$a"))
        assertEquals(internal, DownloadRules.normalizeRoot("$internal/"))
        assertEquals(listOf("$card/audio/$a"), DownloadRules.pathsUnder(card, listOf("$internal/audio/$a", "$card/audio/$a")))
    }

    @Test
    fun movingToTheCardCopiesEveryFileOnceAndLeavesAnUnmountedLocationAlone() {
        val other = "/storage/9999-0000/Android/data/com.taehagen.spotifygood/files/offline"
        val rows = listOf(
            LocatedRow("spotify:track:1", "$internal/audio/$a", "$internal/images/c.jpg"),
            // Shares the file and the cover with track 1 (relinking).
            LocatedRow("spotify:track:2", "$internal/audio/$a", "$internal/images/c.jpg"),
            // Already there.
            LocatedRow("spotify:track:3", "$card/audio/$b", "$card/images/d.jpg"),
            // On a card that is not mounted: waits for it.
            LocatedRow("spotify:track:4", "$other/audio/$b", null),
            LocatedRow("spotify:track:5", null, null),
        )
        val plan = DownloadRules.relocationPlan(rows, card) { it != other }
        assertEquals(
            listOf(
                DownloadRules.FileMove("$internal/audio/$a", "$card/audio/$a", image = false),
                DownloadRules.FileMove("$internal/images/c.jpg", "$card/images/c.jpg", image = true),
            ),
            plan,
        )
        // And back to internal storage.
        assertEquals(
            listOf(
                DownloadRules.FileMove("$card/audio/$b", "$internal/audio/$b", image = false),
                DownloadRules.FileMove("$card/images/d.jpg", "$internal/images/d.jpg", image = true),
            ),
            DownloadRules.relocationPlan(rows, internal) { it != other },
        )
    }

    @Test
    fun downloadsOnARemovedCardAreUnavailableNotGone() {
        val rows = listOf(
            LocatedRow("spotify:track:1", "$internal/audio/$a", null),
            LocatedRow("spotify:track:2", "$card/audio/$b", "$card/images/d.jpg"),
        )
        assertEquals(setOf("spotify:track:2"), DownloadRules.unavailableUris(rows) { it == internal })
        assertEquals(emptySet<String>(), DownloadRules.unavailableUris(rows) { true })

        val items = listOf(
            DownloadItem("spotify:track:1", DownloadState.COMPLETED, 1, 1, null, null, null),
            DownloadItem("spotify:track:2", DownloadState.COMPLETED, 1, 1, null, null, null),
        )
        val shown = DownloadRules.withUnavailable(items, setOf("spotify:track:2"), "card missing")
        assertEquals(DownloadState.COMPLETED, shown[0].state)
        assertEquals(DownloadState.FAILED, shown[1].state)
        assertEquals("card missing", shown[1].error)
        assertNull(shown[0].error)
        // Not offered for "Retry": they come back with the card.
        val counts = DownloadRules.failedCounts(shown.map { com.taehagen.spotifygood.data.db.RetryRow(it.uri, it.state, it.error) }, setOf("spotify:track:2"), "unplayable")
        assertEquals(0, counts.retryable)
        assertEquals(1, counts.unavailable)
    }

    @Test
    fun sharedFilesAreMatchedPerLocation() {
        // Track 1 (internal) is removed; track 2 still uses a file of the same name on the card.
        val deleted = DownloadRules.audioToDelete(
            removedPaths = listOf("$internal/audio/$a"),
            remainingPaths = listOf("$card/audio/$a"),
            remainingFileIds = emptyList(),
        )
        assertEquals(listOf("$internal/audio/$a"), deleted)
        // An unfinished download writing that file keeps it wherever it is.
        assertEquals(emptyList<String>(), DownloadRules.audioToDelete(listOf("$internal/audio/$a"), emptyList(), listOf(a)))
        // The same location (and its alias) keeps it.
        assertEquals(
            emptyList<String>(),
            DownloadRules.audioToDelete(listOf("/data/data/com.taehagen.spotifygood/no_backup/offline/audio/$a"), listOf("$internal/audio/$a"), emptyList()),
        )
    }
}
