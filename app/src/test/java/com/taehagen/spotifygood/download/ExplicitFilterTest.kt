package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Hide explicit content" (or the account's filter) never fails or excludes downloads: the
 * catalog's `playable:false` for an explicit item says nothing while the filter may have applied.
 */
class ExplicitFilterTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun explicitGreyed(uri: String) = Track(uri = uri, name = "Explicit song", explicit = true, playable = false)

    private fun settledOff() = ExplicitFilterWatch().apply { settle(update(false)) }

    // ---- ExplicitFilterWatch ---------------------------------------------------------------------

    @Test
    fun theFilterMayApplyUntilItIsKnownOffAndApplied() {
        val watch = ExplicitFilterWatch()
        assertTrue("before the first value", watch.mayFilter(watch.begin()))
        val generation = watch.update(false)
        assertTrue("off, but the engine may still filter", watch.mayFilter(watch.begin()))
        watch.settle(generation)
        assertFalse(watch.mayFilter(watch.begin()))
        watch.settle(watch.update(true))
        assertTrue(watch.mayFilter(watch.begin()))
    }

    @Test
    fun aChangeDuringALookupVoidsItsAnswer() {
        val watch = settledOff()
        val lookup = watch.begin()
        // Turned on while the lookup ran, and even back off before it answered.
        watch.settle(watch.update(true))
        watch.settle(watch.update(false))
        assertTrue(watch.mayFilter(lookup))
        assertFalse(watch.mayFilter(watch.begin()))

        // Started while on, answered after it went off: still filtered.
        val on = ExplicitFilterWatch().apply { settle(update(true)) }
        val whileOn = on.begin()
        on.settle(on.update(false))
        assertTrue(on.mayFilter(whileOn))
    }

    @Test
    fun aLateSettleOfAnOlderValueIsIgnored() {
        val watch = ExplicitFilterWatch()
        val off = watch.update(false)
        watch.update(true)
        watch.settle(off) // the engine applied the old value; the new one is not applied yet
        assertTrue(watch.mayFilter(watch.begin()))
        assertTrue(watch.filtered)
    }

    // ---- verdicts --------------------------------------------------------------------------------

    @Test
    fun onlyAnExplicitItemWhileFilteredHasNoVerdict() {
        assertNull(DownloadRules.downloadVerdict(playable = false, explicit = true, filterMayApply = true))
        assertEquals(false, DownloadRules.downloadVerdict(playable = false, explicit = true, filterMayApply = false))
        // Region / restriction: a verdict whatever the filter.
        assertEquals(false, DownloadRules.downloadVerdict(playable = false, explicit = false, filterMayApply = true))
        assertEquals(true, DownloadRules.downloadVerdict(playable = true, explicit = true, filterMayApply = true))
        assertNull(DownloadRules.downloadVerdict(playable = false, explicit = false, filterMayApply = false, resolved = false))
    }

    @Test
    fun anExplicitMemberWhileFilteredIsQueuedAndStoredWithoutTheFilter() {
        val item = CollectionResolver.trackItem(explicitGreyed("spotify:track:e"), json, filterMayApply = true)
        assertFalse(item.unavailable)
        assertFalse(item.checked)
        val stored = json.decodeFromString(Track.serializer(), item.metadataJson!!)
        assertTrue("display and playback apply the filter themselves", stored.playable)
        assertTrue(stored.explicit)

        val episode = CollectionResolver.episodeItem(
            Episode(uri = "spotify:episode:e", name = "Explicit episode", explicit = true, playable = false),
            json,
            filterMayApply = true,
        )
        assertFalse(episode.unavailable)
        assertFalse(episode.checked)
    }

    @Test
    fun withTheFilterKnownOffAGreyedOutExplicitMemberIsUnavailable() {
        val item = CollectionResolver.trackItem(explicitGreyed("spotify:track:e"), json, filterMayApply = false)
        assertTrue(item.unavailable)
        assertTrue(item.checked)
        // A region-locked member stays unavailable while filtered too.
        val region = CollectionResolver.trackItem(Track(uri = "spotify:track:r", name = "Not here", playable = false), json, filterMayApply = true)
        assertTrue(region.unavailable)
        assertTrue(region.checked)
    }

    /** The scenario of the finding: Liked Songs synced while "Hide explicit content" is on. */
    @Test
    fun aSyncWhileFilteredQueuesNewExplicitLikesAndKeepsWhatWasKnown() {
        val newLike = CollectionResolver.trackItem(explicitGreyed("spotify:track:new"), json, filterMayApply = true)
        // Recorded unavailable earlier, with the filter off (really not playable here).
        val gone = CollectionResolver.trackItem(explicitGreyed("spotify:track:gone"), json, filterMayApply = true)
        val fine = CollectionResolver.trackItem(Track(uri = "spotify:track:f", name = "Song"), json, filterMayApply = true)
        val items = listOf(fine, newLike, gone)
        val (checked, complete) = DownloadRules.availabilityOf(items, resolutionComplete = true)
        assertEquals(listOf(fine), checked)
        assertFalse(complete)
        val availability = DownloadRules.updateAvailability(
            old = setOf("spotify:track:gone"),
            listed = checked.mapTo(HashSet()) { it.uri },
            listedUnavailable = checked.filter { it.unavailable }.mapTo(HashSet()) { it.uri },
            complete = complete,
        )
        // No verdict: neither recorded unavailable nor revived.
        assertEquals(setOf("spotify:track:gone"), availability.unavailable)
        assertEquals(emptySet<String>(), availability.revived)
        // New members are queued as applyMembershipLocked does (download.track ignores the filter).
        val added = setOf("spotify:track:new")
        assertEquals(listOf("spotify:track:new"), items.filter { it.uri in added + availability.revived && !it.unavailable }.map { it.uri })
    }

    // ---- repair of earlier versions' failures ----------------------------------------------------

    @Test
    fun theRepairRestoresFinishedExplicitDownloadsAndRetriesRefusedOnes() {
        val unplayable = "No longer available on Spotify."
        val unavailable = "Not available on Spotify."
        val rows = listOf(
            DownloadRules.RepairRow("restore", unplayable, finished = true, explicit = true),
            DownloadRules.RepairRow("clean", unplayable, finished = true, explicit = false),
            DownloadRules.RepairRow("noFile", unplayable, finished = false, explicit = true),
            DownloadRules.RepairRow("refused", unavailable, finished = false, explicit = true),
            DownloadRules.RepairRow("member", unavailable, finished = false, explicit = true),
            DownloadRules.RepairRow("regionRefused", unavailable, finished = false, explicit = false),
        )
        val repair = DownloadRules.explicitRepair(rows, unplayable, unavailable, unavailableMembers = setOf("member"))
        assertEquals(listOf("restore"), repair.restore)
        assertEquals(listOf("refused"), repair.requeue)
    }

    @Test
    fun explicitIsReadFromTheMetadataOrTheRecord() {
        val track = json.encodeToString(Track.serializer(), Track(uri = "spotify:track:1", name = "x", explicit = true))
        assertTrue(DownloadRules.storedExplicit(json, track, null))
        val clean = json.encodeToString(Track.serializer(), Track(uri = "spotify:track:1", name = "x"))
        assertFalse(DownloadRules.storedExplicit(json, clean, null))
        assertTrue(DownloadRules.storedExplicit(json, null, """{"uri":"spotify:track:1","track":{"uri":"spotify:track:1","explicit":true}}"""))
        assertTrue(DownloadRules.storedExplicit(json, "not json", """{"episode":{"explicit":true}}"""))
        assertFalse(DownloadRules.storedExplicit(json, null, """{"track":{"explicit":"yes"}}"""))
        assertFalse(DownloadRules.storedExplicit(json, null, null))
    }

    @Test
    fun aRestoredDownloadCountsAsDownloadedAgain() {
        // Restored COMPLETED and taken out of the unavailable sets.
        val members = listOf("a", "e")
        val sets = DownloadRules.adjustUnavailable(members, unavailable = setOf("e"), gone = emptySet(), playableAgain = setOf("e"))
        assertEquals(emptySet<String>(), sets)
        val states = mapOf("a" to DownloadState.COMPLETED, "e" to DownloadState.COMPLETED)
        assertEquals(CollectionDownloadStatus.Complete, DownloadRules.collectionStatus(members, states, sets!!))
    }
}
