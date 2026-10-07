package com.taehagen.spotifygood.download

import org.junit.Assert.assertEquals
import org.junit.Test

class RecheckConfirmTest {
    @Test
    fun membersOfACollectionRemovedDuringTheLookupAreNotQueued() {
        // The lookup found a, b playable; meanwhile the user removed the playlist that recorded them.
        val confirmed = DownloadRules.confirmRecheck(found = listOf("a", "b"), liveUnavailable = emptyList())
        assertEquals(emptyList<String>(), confirmed)
    }

    @Test
    fun aMemberTheUserRemovedDuringTheLookupIsNotQueued() {
        // removeItems took b out of every unavailable set while the lookup was running.
        val confirmed = DownloadRules.confirmRecheck(found = listOf("a", "b"), liveUnavailable = listOf(listOf("a", "c")))
        assertEquals(listOf("a"), confirmed)
    }

    @Test
    fun aMemberStillRecordedByAnotherCollectionIsQueued() {
        // Its first collection is gone, a second one still records it as unavailable.
        val confirmed = DownloadRules.confirmRecheck(found = listOf("a", "a"), liveUnavailable = listOf(emptyList(), listOf("a")))
        assertEquals(listOf("a"), confirmed)
    }
}
