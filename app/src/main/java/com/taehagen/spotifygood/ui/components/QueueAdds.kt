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

/**
 * What "Add to queue" of a collection adds while the session can't stream: its downloaded
 * [members] in collection order, without [skipped] ones (explicit while filtered). An item that
 * isn't downloaded would be refused by the offline queue and stop the batch there.
 */
internal fun offlineQueueUris(members: OfflineMembers?, skipped: Set<String> = emptySet()): List<String> =
    members?.order.orEmpty().filter { it in members!!.downloaded && it !in skipped }.distinct()
