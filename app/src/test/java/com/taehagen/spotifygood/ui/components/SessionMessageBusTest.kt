package com.taehagen.spotifygood.ui.components

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionMessageBusTest {
    private var now = 0L
    private val bus = SessionMessageBus(clock = { now }, maxAgeMs = 10_000)

    private suspend fun next(): String? = withTimeoutOrNull(100) { bus.messages.first() }

    @Test
    fun messagesOfTheCurrentSessionAreShown() = runTest {
        bus.post("Download removed")
        bus.post("Added to your library", bus.currentGeneration())
        assertEquals(listOf("Download removed", "Added to your library"), bus.messages.take(2).toList())
    }

    @Test
    fun aClearedSessionsPendingMessagesAreDropped() = runTest {
        bus.post("Couldn't download")
        bus.clear() // logout / app closed before the scaffold showed it
        assertNull(next())
    }

    @Test
    fun aWriteFinishingAfterItsSessionEndedReportsNothing() = runTest {
        val oldSession = bus.currentGeneration()
        bus.clear() // logout
        // The next account signs in; the old account's removal finishes now.
        bus.post("Download removed", oldSession)
        bus.post("Saved to your library")
        assertEquals("Saved to your library", next())
    }

    @Test
    fun aMessageThatWaitedTooLongIsDropped() = runTest {
        bus.post("Couldn't download")
        now += 10_001
        bus.post("Download removed")
        assertEquals("Download removed", next())
    }
}
