package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.download.DownloadRules.UnavailableMembers
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnavailableRecheckTest {
    private val day = DownloadRules.UNAVAILABLE_RECHECK_MS
    private val now = 100 * day
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun aGreyedOutLikedSongIsLookedUpAgainAndCountsOnceItIsPlayable() {
        // Liked Songs: e was recorded unavailable (explicit filter) and never got a row.
        val liked = UnavailableMembers("spotify:collection", setOf("e"), checkedAt = now - 2 * day)
        val recheck = DownloadRules.unavailableToRecheck(listOf(liked), states = emptyMap(), now = now)
        assertEquals(setOf("e"), recheck.members)
        assertEquals(setOf("spotify:collection"), recheck.collections)
        // The filter is off now: catalog.tracks reports it playable, so it leaves the set and is queued.
        val found = CollectionResolver.trackItem(Track(uri = "e", name = "Explicit song", playable = true), json)
        assertEquals(false, found.unavailable)
        val unavailable = DownloadRules.adjustUnavailable(listOf("a", "e"), setOf("e"), gone = emptySet(), playableAgain = setOf(found.uri))
        assertEquals(emptySet<String>(), unavailable)
        val status = DownloadRules.collectionStatus(listOf("a", "e"), mapOf("a" to DownloadState.COMPLETED, "e" to DownloadState.QUEUED), unavailable!!)
        assertEquals(CollectionDownloadStatus.InProgress(1, 2, active = true), status)
    }

    @Test
    fun anUnchangedPlaylistsUnavailableMembersAreRecheckedOnceADay() {
        // A playlist whose revision never changes: its listing is skipped, the re-check is not.
        val playlist = UnavailableMembers("spotify:playlist:p", setOf("x", "y", "z"), checkedAt = now - day)
        val states = mapOf("x" to DownloadState.FAILED, "y" to DownloadState.COMPLETED, "z" to DownloadState.QUEUED)
        // Only members without a row or with a FAILED one are looked up.
        assertEquals(setOf("x"), DownloadRules.unavailableToRecheck(listOf(playlist), states, now).members)
        // Checked less than a day ago, or nothing recorded: no lookup at all.
        val recent = playlist.copy(checkedAt = now - day / 2)
        assertTrue(DownloadRules.unavailableToRecheck(listOf(recent), states, now).collections.isEmpty())
        val none = UnavailableMembers("spotify:album:a", emptySet(), checkedAt = null)
        assertTrue(DownloadRules.unavailableToRecheck(listOf(none), states, now).collections.isEmpty())
        // Never checked: due at once.
        assertEquals(setOf("x"), DownloadRules.unavailableToRecheck(listOf(playlist.copy(checkedAt = null)), states, now).members)
    }
}
