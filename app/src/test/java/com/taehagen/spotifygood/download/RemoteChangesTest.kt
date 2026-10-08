package com.taehagen.spotifygood.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Changes made on another device reach a downloaded collection when its page loads. */
class RemoteChangesTest {
    private val now = 1_000_000_000L

    @Test
    fun aPlaylistPageSyncsTheDownloadOnlyForAnotherRevision() {
        assertFalse("same revision: nothing changed", DownloadRules.syncRequestDue("r1", "r1", null, null, now))
        assertTrue(DownloadRules.syncRequestDue("r2", "r1", null, now - 60_000, now))
        // Asked moments ago (the sync may still run, or was incomplete and kept r1).
        assertFalse(DownloadRules.syncRequestDue("r2", "r1", now - 5_000, null, now))
        assertTrue(DownloadRules.syncRequestDue("r2", "r1", now - DownloadRules.REQUEST_SYNC_CHANGED_MS, null, now))
        // Never synced to a revision yet.
        assertTrue(DownloadRules.syncRequestDue("r1", null, null, null, now))
    }

    @Test
    fun likedSongsPagesAskAtMostEveryFewMinutes() {
        assertTrue(DownloadRules.syncRequestDue(null, null, null, null, now))
        assertFalse(DownloadRules.syncRequestDue(null, null, now - 60_000, null, now))
        assertFalse("synced a minute ago", DownloadRules.syncRequestDue(null, null, null, now - 60_000, now))
        assertTrue(DownloadRules.syncRequestDue(null, null, now - DownloadRules.REQUEST_SYNC_MIN_MS, now - DownloadRules.REQUEST_SYNC_MIN_MS, now))
    }

    @Test
    fun theForegroundRelistsAfterHalfAnHour() {
        val synced = now - DownloadRules.FOREGROUND_STALE_MS
        assertEquals(now, DownloadRules.nextSyncAt(synced, synced, 0, DownloadRules.FOREGROUND_STALE_MS))
        // The default cadence is unchanged.
        assertEquals(synced + DownloadRules.SYNC_STALE_MS, DownloadRules.nextSyncAt(synced, synced, 0))
        // A collection whose syncs fail keeps its backoff.
        assertTrue(DownloadRules.nextSyncAt(synced, now, 3, DownloadRules.FOREGROUND_STALE_MS) > now)
    }
}
