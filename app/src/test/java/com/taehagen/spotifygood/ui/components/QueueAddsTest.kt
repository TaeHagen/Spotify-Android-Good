package com.taehagen.spotifygood.ui.components

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackTrack
import com.taehagen.spotifygood.model.TrackProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueAddsTest {
    private fun local(queued: Int, context: Int = 10) = PlaybackSnapshot(
        source = PlaybackSource.LOCAL,
        nextTracks = List(queued) { PlaybackTrack(uri = "spotify:track:q$it", provider = TrackProvider.QUEUE) } +
            List(context) { PlaybackTrack(uri = "spotify:track:c$it", provider = TrackProvider.CONTEXT) },
    )

    @Test
    fun theUserQueueIsCountedOnlyWhileThisPhonePlays() {
        assertEquals(12, localQueuedCount(local(queued = 12)))
        assertNull(localQueuedCount(local(queued = 12).copy(source = PlaybackSource.REMOTE)))
        assertNull(localQueuedCount(PlaybackSnapshot.EMPTY))
    }

    @Test
    fun everythingAddedReportsTheRealCount() {
        assertEquals(QueueAddOutcome.Added(12), queueAddOutcome(requested = 12, added = 12, queuedBefore = 0))
        assertEquals(QueueAddOutcome.Added(1), queueAddOutcome(requested = 1, added = 1, queuedBefore = null))
    }

    @Test
    fun aBatchStoppedByTheQueueLimitSaysTheQueueIsFull() {
        // 70 queued, an album of 15: the 11th add is refused.
        assertEquals(QueueAddOutcome.QueueFull(added = 10, requested = 15), queueAddOutcome(15, 10, queuedBefore = 70))
        // Already full: nothing added.
        assertEquals(QueueAddOutcome.QueueFull(added = 0, requested = 1), queueAddOutcome(1, 0, queuedBefore = LOCAL_QUEUE_LIMIT))
    }

    @Test
    fun otherFailuresAreNotBlamedOnTheQueue() {
        assertEquals(QueueAddOutcome.Stopped(added = 3, requested = 15), queueAddOutcome(15, 3, queuedBefore = 10))
        // A remote device plays (its queue is not this phone's): never "full".
        assertEquals(QueueAddOutcome.Stopped(added = 0, requested = 5), queueAddOutcome(5, 0, queuedBefore = null))
    }
}
