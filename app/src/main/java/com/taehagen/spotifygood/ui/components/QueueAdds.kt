package com.taehagen.spotifygood.ui.components

import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.TrackProvider

// Pure "add to queue" bookkeeping (JVM-testable).

/**
 * Tracks this phone can hold in its user queue: Connect's next-tracks window. Further `queue.add`s
 * fail with UNAVAILABLE "The queue is full" (docs/ARCHITECTURE.md §6.2). The playback snapshot lists
 * up to this many next tracks, queued ones first.
 */
internal const val LOCAL_QUEUE_LIMIT = 80

/** What an "add to queue" of [requested] items achieved. */
internal sealed interface QueueAddOutcome {
    val added: Int

    /** Everything was added. */
    data class Added(override val added: Int) : QueueAddOutcome

    /** This phone's queue filled up after [added] of [requested]. */
    data class QueueFull(override val added: Int, val requested: Int) : QueueAddOutcome

    /** Another failure stopped the batch after [added]; the player already reported it. */
    data class Stopped(override val added: Int, val requested: Int) : QueueAddOutcome
}

/** Length of the user queue while this phone plays (the limit is this device's); null otherwise. */
internal fun localQueuedCount(snapshot: PlaybackSnapshot): Int? =
    if (snapshot.source == PlaybackSource.LOCAL) snapshot.nextTracks.count { it.provider == TrackProvider.QUEUE } else null

/**
 * Outcome of a batch that stopped at its first failed add after [added] of [requested]
 * ([added] == [requested]: nothing failed). [queuedBefore] ([localQueuedCount] at the start) tells
 * a full queue from other failures.
 */
internal fun queueAddOutcome(requested: Int, added: Int, queuedBefore: Int?): QueueAddOutcome = when {
    added >= requested -> QueueAddOutcome.Added(added)
    queuedBefore != null && queuedBefore + added >= LOCAL_QUEUE_LIMIT -> QueueAddOutcome.QueueFull(added, requested)
    else -> QueueAddOutcome.Stopped(added, requested)
}
