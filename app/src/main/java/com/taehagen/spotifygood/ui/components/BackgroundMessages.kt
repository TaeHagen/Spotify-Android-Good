package com.taehagen.spotifygood.ui.components

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Snackbar messages of work that outlives the page that started it: detail-page writes run in
 * [com.taehagen.spotifygood.AppGraph.appScope] and may finish after their page was popped and its
 * ViewModel cleared. Process-wide like that scope; the main scaffold shows them whichever page is
 * visible. Thread-safe.
 */
internal object BackgroundMessages {
    private val channel = Channel<String>(capacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Collected by the main scaffold only (each message is delivered once). */
    val messages: Flow<String> = channel.receiveAsFlow()

    fun post(message: String) {
        channel.trySend(message)
    }

    /** Drops undelivered messages (the signed-in UI went away: logout, activity finished). */
    fun clear() {
        var received = channel.tryReceive()
        while (received.isSuccess) received = channel.tryReceive()
    }

    private const val CAPACITY = 16
}
