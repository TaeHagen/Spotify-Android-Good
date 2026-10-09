package com.taehagen.spotifygood.ui.components

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.playback.OfflineMembers

// Pure "add to queue" bookkeeping (JVM-testable).

/**
 * The engine refuses `queue.add`s once this phone's user queue fills Connect's next-tracks window,
 * with UNAVAILABLE "The queue is full" (docs/ARCHITECTURE.md §6.2).
 */
internal fun isQueueFull(error: NativeErrorInfo): Boolean =
    error.code == NativeErrorCode.UNAVAILABLE && error.message.contains("queue is full", ignoreCase = true)

/** What an "add to queue" of [requested] items achieved. */
internal sealed interface QueueAddOutcome {
    val added: Int

    /** Everything was added. */
    data class Added(override val added: Int) : QueueAddOutcome

    /** This phone's queue filled up after [added] of [requested]. */
    data class QueueFull(override val added: Int, val requested: Int) : QueueAddOutcome

    /** Another failure ([error]) stopped the batch after [added]. */
    data class Stopped(override val added: Int, val requested: Int, val error: NativeErrorInfo) : QueueAddOutcome
}

/**
 * Outcome of a batch of [requested] adds that stopped at its first failure [error] after [added]
 * (null: nothing failed), from the engine's own error.
 */
internal fun queueAddOutcome(requested: Int, added: Int, error: NativeErrorInfo?): QueueAddOutcome = when {
    error == null || added >= requested -> QueueAddOutcome.Added(added)
    isQueueFull(error) -> QueueAddOutcome.QueueFull(added, requested)
    else -> QueueAddOutcome.Stopped(added, requested, error)
}

/** What "Add to queue" of a collection does ([collectionQueuePlan]). */
internal sealed interface CollectionQueuePlan {
    /** Queue [uris] (the queue add says how many went in). */
    data class Queue(val uris: List<String>) : CollectionQueuePlan

    /** The collection has nothing the queue can take (empty, or local files only). */
    data object NothingToQueue : CollectionQueuePlan

    /** Not online, and none of it is downloaded. */
    data object NothingDownloaded : CollectionQueuePlan

    /** Online, but its list couldn't be fetched ([error]; null: not in time), and none of it is downloaded. */
    data class Failed(val error: Throwable?) : CollectionQueuePlan
}

/**
 * "Add to queue" of a collection: the server's list [fromServer] (null: not fetched — not online,
 * or it failed with [failure], or took too long, [timedOut]), else its [downloaded] members; a
 * failure while online is said as such, not as "nothing downloaded".
 */
internal fun collectionQueuePlan(
    fromServer: List<String>?,
    downloaded: List<String>,
    failure: Throwable? = null,
    timedOut: Boolean = false,
): CollectionQueuePlan = when {
    fromServer != null -> if (fromServer.isEmpty()) CollectionQueuePlan.NothingToQueue else CollectionQueuePlan.Queue(fromServer)
    downloaded.isNotEmpty() -> CollectionQueuePlan.Queue(downloaded)
    failure != null || timedOut -> CollectionQueuePlan.Failed(failure)
    else -> CollectionQueuePlan.NothingDownloaded
}

/**
 * What "Add to queue" of a collection adds while the session can't stream: its downloaded
 * [members] in collection order, without [skipped] ones (explicit while filtered). An item that
 * isn't downloaded would be refused by the offline queue and stop the batch there.
 */
internal fun offlineQueueUris(members: OfflineMembers?, skipped: Set<String> = emptySet()): List<String> =
    members?.order.orEmpty().filter { it in members!!.downloaded && it !in skipped }.distinct()
