package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnavailableMembersTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun uriOnly(uri: String) = CollectionResolver.Item(uri, metadataJson = null)

    @Test
    fun aGreyedOutLikedSongIsNotQueuedAndTheCollectionCompletes() {
        // Liked Songs list URIs only; the new members were looked up with catalog.tracks.
        val greyed = CollectionResolver.trackItem(Track(uri = "spotify:track:g", name = "Not here", playable = false), json)
        val fine = CollectionResolver.trackItem(Track(uri = "spotify:track:f", name = "Song"), json)
        val items = listOf(fine, greyed, uriOnly("spotify:track:done"))
        val (checked, complete) = DownloadRules.availabilityOf(items, resolutionComplete = true)
        assertEquals(listOf(fine, greyed), checked)
        assertEquals(false, complete) // "done" was not looked up (completed row)
        val availability = DownloadRules.updateAvailability(
            old = emptySet(),
            listed = checked.mapTo(HashSet()) { it.uri },
            listedUnavailable = checked.filter { it.unavailable }.mapTo(HashSet()) { it.uri },
            complete = complete,
        )
        assertEquals(setOf("spotify:track:g"), availability.unavailable)
        // Queued: only playable members (as applyMembershipLocked filters).
        assertEquals(listOf("spotify:track:f", "spotify:track:done"), items.filter { !it.unavailable }.map { it.uri })
        val states = mapOf("spotify:track:f" to DownloadState.COMPLETED, "spotify:track:done" to DownloadState.COMPLETED)
        assertEquals(
            CollectionDownloadStatus.Complete,
            DownloadRules.collectionStatus(items.map { it.uri }, states, availability.unavailable),
        )
    }

    @Test
    fun aMemberThatWasNotLookedUpAgainIsNotRevived() {
        // g was recorded unavailable; this sync only looked up the new member n.
        val n = CollectionResolver.trackItem(Track(uri = "spotify:track:n", name = "New"), json)
        val items = listOf(uriOnly("spotify:track:g"), n)
        val (checked, complete) = DownloadRules.availabilityOf(items, resolutionComplete = true)
        val availability = DownloadRules.updateAvailability(
            old = setOf("spotify:track:g"),
            listed = checked.mapTo(HashSet()) { it.uri },
            listedUnavailable = checked.filter { it.unavailable }.mapTo(HashSet()) { it.uri },
            complete = complete,
        )
        assertEquals(setOf("spotify:track:g"), availability.unavailable)
        assertEquals(emptySet<String>(), availability.revived)
    }

    @Test
    fun aRevalidatedGoneMemberOfAnUnchangedPlaylistDoesNotHoldItBack() {
        val members = listOf("a", "b", "c")
        // b was downloaded; 30-day re-validation finds it not playable here any more.
        val updated = DownloadRules.adjustUnavailable(members, unavailable = emptySet(), gone = setOf("b", "other"), playableAgain = emptySet())
        assertEquals(setOf("b"), updated)
        val states = mapOf("a" to DownloadState.COMPLETED, "b" to DownloadState.FAILED, "c" to DownloadState.COMPLETED)
        assertEquals(CollectionDownloadStatus.Complete, DownloadRules.collectionStatus(members, states, updated!!))
        // Playable again (and queued): it counts again.
        assertEquals(emptySet<String>(), DownloadRules.adjustUnavailable(members, updated, gone = emptySet(), playableAgain = setOf("b")))
        // Not a member: nothing changes.
        assertNull(DownloadRules.adjustUnavailable(members, emptySet(), gone = setOf("x"), playableAgain = emptySet()))
    }
}
