package com.taehagen.spotifygood.ui.components

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.playback.OfflineMembers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAddsTest {
    private val full = NativeErrorInfo(NativeErrorCode.UNAVAILABLE, "The queue is full")
    private val network = NativeErrorInfo(NativeErrorCode.NETWORK, "offline")

    @Test
    fun theEnginesQueueFullErrorIsRecognised() {
        assertTrue(isQueueFull(full))
        assertFalse(isQueueFull(NativeErrorInfo(NativeErrorCode.UNAVAILABLE, "This track isn't available")))
        assertFalse(isQueueFull(network))
    }

    @Test
    fun everythingAddedReportsTheRealCount() {
        assertEquals(QueueAddOutcome.Added(12), queueAddOutcome(requested = 12, added = 12, error = null))
        assertEquals(QueueAddOutcome.Added(1), queueAddOutcome(requested = 1, added = 1, error = null))
    }

    @Test
    fun aBatchStoppedByTheQueueLimitSaysTheQueueIsFull() {
        // An album of 15 into a nearly full queue: the 11th add is refused.
        assertEquals(QueueAddOutcome.QueueFull(added = 10, requested = 15), queueAddOutcome(15, 10, full))
        assertEquals(QueueAddOutcome.QueueFull(added = 0, requested = 1), queueAddOutcome(1, 0, full))
    }

    @Test
    fun otherFailuresKeepTheirReason() {
        assertEquals(QueueAddOutcome.Stopped(added = 3, requested = 15, error = network), queueAddOutcome(15, 3, network))
        assertEquals(QueueAddOutcome.Stopped(added = 0, requested = 5, error = network), queueAddOutcome(5, 0, network))
    }

    @Test
    fun offlineAQueuedPlaylistIsItsDownloadedMembersInOrder() {
        val members = OfflineMembers(order = listOf("a", "b", "c", "d"), downloaded = setOf("d", "a", "c"))
        assertEquals(listOf("a", "c", "d"), offlineQueueUris(members))
        assertEquals("explicit ones while filtered", listOf("a", "d"), offlineQueueUris(members, skipped = setOf("c")))
        assertEquals(emptyList<String>(), offlineQueueUris(null))
    }

    @Test
    fun aPartlyDownloadedAlbumQueuesEveryDownloadedTrack() {
        // Track 3 is missing: the other 11 are queued, not just 1-2 (the offline queue refuses 3).
        val order = (1..12).map { "spotify:track:$it" }
        val members = OfflineMembers(order, downloaded = (order - "spotify:track:3").toSet())
        val queued = offlineQueueUris(members)
        assertEquals(11, queued.size)
        assertEquals(order - "spotify:track:3", queued)
    }

    @Test
    fun aCollectionQueuesTheServersListElseItsDownloads() {
        val list = listOf("spotify:track:1", "spotify:track:2")
        val downloads = listOf("spotify:track:9")
        assertEquals(CollectionQueuePlan.Queue(list), collectionQueuePlan(list, downloaded = emptyList()))
        assertEquals("an empty playlist, or local files only", CollectionQueuePlan.NothingToQueue, collectionQueuePlan(emptyList(), emptyList()))
        // Not online, or it couldn't be fetched: its downloads.
        assertEquals(CollectionQueuePlan.Queue(downloads), collectionQueuePlan(null, downloads))
        val reset = java.io.IOException("reset")
        assertEquals(CollectionQueuePlan.Queue(downloads), collectionQueuePlan(null, downloads, failure = reset))
        assertEquals(CollectionQueuePlan.Queue(downloads), collectionQueuePlan(null, downloads, timedOut = true))
        // Online, but it failed or took too long and nothing is downloaded: said as such.
        assertEquals(CollectionQueuePlan.Failed(reset), collectionQueuePlan(null, emptyList(), failure = reset))
        assertEquals(CollectionQueuePlan.Failed(null), collectionQueuePlan(null, emptyList(), timedOut = true))
        assertEquals(CollectionQueuePlan.NothingDownloaded, collectionQueuePlan(null, emptyList()))
    }
}
