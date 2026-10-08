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

    private val off = ExplicitFilterWatch.Filter.OFF
    private val app = ExplicitFilterWatch.Filter.APP
    private val account = ExplicitFilterWatch.Filter.ACCOUNT

    /** Both sources known off: the app setting applied, the account reported without its filter. */
    private fun settledOff() = ExplicitFilterWatch().apply {
        updateAccount(false)
        settleApp(updateApp(false))
    }

    private fun ExplicitFilterWatch.now() = filterAt(begin())

    // ---- ExplicitFilterWatch ---------------------------------------------------------------------

    @Test
    fun aFilterMayApplyUntilItIsKnownOff() {
        val watch = ExplicitFilterWatch()
        assertEquals("before the first values", account, watch.now())
        watch.updateAccount(false)
        assertEquals("the app setting is not known yet", app, watch.now())
        val generation = watch.updateApp(false)
        assertEquals("off, but the engine may still filter", app, watch.now())
        watch.settleApp(generation)
        assertEquals(off, watch.now())
        watch.settleApp(watch.updateApp(true))
        assertEquals(app, watch.now())
        watch.updateAccount(true)
        assertEquals(account, watch.now())
        watch.updateAccount(null)
        assertEquals("no longer reported: may still be on", account, watch.now())
    }

    @Test
    fun aChangeDuringALookupVoidsItsAnswer() {
        val watch = settledOff()
        val lookup = watch.begin()
        // Turned on while the lookup ran, and even back off before it answered.
        watch.settleApp(watch.updateApp(true))
        watch.settleApp(watch.updateApp(false))
        assertEquals(app, watch.filterAt(lookup))
        assertEquals(off, watch.now())

        // The account turned its filter on and off again while a lookup ran.
        val second = watch.begin()
        watch.updateAccount(true)
        watch.updateAccount(false)
        assertEquals(account, watch.filterAt(second))
        assertEquals(off, watch.now())

        // Started while on, answered after it went off: still filtered.
        val on = ExplicitFilterWatch().apply {
            updateAccount(false)
            settleApp(updateApp(true))
        }
        val whileOn = on.begin()
        on.settleApp(on.updateApp(false))
        assertEquals(app, on.filterAt(whileOn))
    }

    @Test
    fun aLateSettleOfAnOlderValueIsIgnored() {
        val watch = ExplicitFilterWatch()
        watch.updateAccount(false)
        val old = watch.updateApp(false)
        watch.updateApp(true)
        watch.settleApp(old) // the engine applied the old value; the new one is not applied yet
        assertEquals(app, watch.now())
    }

    // ---- verdicts --------------------------------------------------------------------------------

    @Test
    fun anExplicitItemHasNoVerdictWhileOnlyTheAppSettingMayApply() {
        // Members (queueing).
        assertNull(DownloadRules.memberVerdict(playable = false, explicit = true, filter = app))
        assertEquals(false, DownloadRules.memberVerdict(playable = false, explicit = true, filter = off))
        assertEquals("not downloaded for a filtered account", false, DownloadRules.memberVerdict(playable = false, explicit = true, filter = account))
        // Region / restriction: a verdict whatever the filter.
        assertEquals(false, DownloadRules.memberVerdict(playable = false, explicit = false, filter = app))
        assertEquals(true, DownloadRules.memberVerdict(playable = true, explicit = true, filter = account))
        assertNull(DownloadRules.memberVerdict(playable = false, explicit = false, filter = off, resolved = false))
        // Download rows: no filter ever fails one.
        assertNull(DownloadRules.rowVerdict(playable = false, explicit = true, filter = app))
        assertNull(DownloadRules.rowVerdict(playable = false, explicit = true, filter = account))
        assertEquals(false, DownloadRules.rowVerdict(playable = false, explicit = true, filter = off))
        assertEquals(false, DownloadRules.rowVerdict(playable = false, explicit = false, filter = account))
        assertEquals(true, DownloadRules.rowVerdict(playable = true, explicit = true, filter = app))
    }

    @Test
    fun aFilteredAccountDoesNotQueueExplicitMembers() {
        val item = CollectionResolver.trackItem(explicitGreyed("spotify:track:e"), json, account)
        assertTrue(item.unavailable)
        assertTrue(item.checked)
        // Re-checked (and queued) once the account's filter goes off.
        val again = CollectionResolver.trackItem(Track(uri = "spotify:track:e", name = "Explicit song", explicit = true), json, off)
        assertFalse(again.unavailable)
        assertTrue(again.checked)
    }

    @Test
    fun anExplicitMemberWhileFilteredIsQueuedAndStoredWithoutTheFilter() {
        val item = CollectionResolver.trackItem(explicitGreyed("spotify:track:e"), json, filter = app)
        assertFalse(item.unavailable)
        assertFalse(item.checked)
        val stored = json.decodeFromString(Track.serializer(), item.metadataJson!!)
        assertTrue("display and playback apply the filter themselves", stored.playable)
        assertTrue(stored.explicit)

        val episode = CollectionResolver.episodeItem(
            Episode(uri = "spotify:episode:e", name = "Explicit episode", explicit = true, playable = false),
            json,
            filter = app,
        )
        assertFalse(episode.unavailable)
        assertFalse(episode.checked)
    }

    @Test
    fun withTheFilterKnownOffAGreyedOutExplicitMemberIsUnavailable() {
        val item = CollectionResolver.trackItem(explicitGreyed("spotify:track:e"), json, filter = off)
        assertTrue(item.unavailable)
        assertTrue(item.checked)
        // A region-locked member stays unavailable while filtered too.
        val region = CollectionResolver.trackItem(Track(uri = "spotify:track:r", name = "Not here", playable = false), json, filter = app)
        assertTrue(region.unavailable)
        assertTrue(region.checked)
    }

    /** The scenario of the finding: Liked Songs synced while "Hide explicit content" is on. */
    @Test
    fun aSyncWhileFilteredQueuesNewExplicitLikesAndKeepsWhatWasKnown() {
        val newLike = CollectionResolver.trackItem(explicitGreyed("spotify:track:new"), json, filter = app)
        // Recorded unavailable earlier, with the filter off (really not playable here).
        val gone = CollectionResolver.trackItem(explicitGreyed("spotify:track:gone"), json, filter = app)
        val fine = CollectionResolver.trackItem(Track(uri = "spotify:track:f", name = "Song"), json, filter = app)
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
