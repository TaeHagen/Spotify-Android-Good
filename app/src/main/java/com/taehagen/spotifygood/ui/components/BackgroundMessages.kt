package com.taehagen.spotifygood.ui.components

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Snackbar messages of work that outlives the page that started it, for one signed-in session at a
 * time. Each message carries the session generation it belongs to: once a session ends ([clear] on
 * release or logout), its messages are dropped, also ones posted later by writes still running in the
 * app scope, so they never reach the next launch or the next account. Messages that waited longer
 * than [maxAgeMs] for a collector are dropped too (out of context by then). Thread-safe.
 */
internal open class SessionMessageBus(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private class Tagged(val generation: Int, val text: String, val postedAtMs: Long)

    private val generation = AtomicInteger()
    private val channel = Channel<Tagged>(capacity = CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The current session; a ViewModel captures it when it is created and posts with it. */
    fun currentGeneration(): Int = generation.get()

    /** Posts [message] for session [generation]; dropped when that session has already ended. */
    fun post(message: String, generation: Int = currentGeneration()) {
        if (generation == this.generation.get()) channel.trySend(Tagged(generation, message, clock()))
    }

    /**
     * Fresh messages of the current session. Collected by the main scaffold only (each message is
     * delivered once). The generation is checked again here: a post can race [clear].
     */
    val messages: Flow<String> = channel.receiveAsFlow()
        .filter { it.generation == generation.get() && clock() - it.postedAtMs <= maxAgeMs }
        .map { it.text }

    /** The session ended (signed-in UI released, logout): drops its pending and later messages. */
    fun clear() {
        generation.incrementAndGet()
        var received = channel.tryReceive()
        while (received.isSuccess) received = channel.tryReceive()
    }

    private companion object {
        const val CAPACITY = 16
        const val MAX_AGE_MS = 10_000L
    }
}

/**
 * Posts one ViewModel's write results to [BackgroundMessages] for the signed-in session it was
 * created in (ViewModels live in that session's store): shown whichever page is visible, and
 * dropped once that session has ended.
 */
internal class SessionMessenger(private val context: Context) {
    private val generation = BackgroundMessages.currentGeneration()

    fun post(@StringRes res: Int, vararg args: Any) {
        BackgroundMessages.post(context.getString(res, *args), generation)
    }
}

/**
 * Process-wide [SessionMessageBus]: detail-page writes run in
 * [com.taehagen.spotifygood.AppGraph.appScope] and may finish after their page was popped and its
 * ViewModel cleared; the main scaffold shows their results whichever page is visible.
 */
internal object BackgroundMessages : SessionMessageBus()
