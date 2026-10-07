package com.taehagen.spotifygood.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Collects keys offered in quick succession and hands them to [action] together, [delayMs] after
 * the last offer: liking ten songs in a row becomes one sync. [action] calls never overlap, and a
 * later offer never cancels one that is running: keys offered meanwhile go to the next call.
 */
internal class EditCoalescer(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val action: suspend (Set<String>) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashSet<String>()
    private var timer: Job? = null
    private val running = Mutex()

    fun offer(key: String) {
        synchronized(lock) {
            pending += key
            timer?.cancel()
            timer = scope.launch {
                delay(delayMs)
                // Not part of [timer]: a later offer only restarts the wait, never stops a flush.
                scope.launch { flush() }
            }
        }
    }

    private suspend fun flush() {
        running.withLock {
            val batch = synchronized(lock) { LinkedHashSet(pending).also { pending.clear() } }
            if (batch.isNotEmpty()) action(batch)
        }
    }
}
