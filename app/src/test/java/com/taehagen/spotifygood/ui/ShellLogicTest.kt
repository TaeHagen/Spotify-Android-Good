package com.taehagen.spotifygood.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwaitLoginStateTest {
    @Test
    fun loggedInAnswersWithoutWaiting() = runTest {
        var waited = false
        assertTrue(awaitLoginState({ true }, { waited = true }, timeoutMs = 1_000))
        assertFalse(waited)
    }

    @Test
    fun coldStartWaitsForTheCredentialLoad() = runTest {
        // Voice search on a cold start: credentials are still being read.
        var loggedIn = false
        val ready = CompletableDeferred<Unit>()
        val result = async { awaitLoginState({ loggedIn }, { ready.await() }, timeoutMs = 10_000) }
        advanceTimeBy(1_500)
        runCurrent()
        assertFalse(result.isCompleted)
        loggedIn = true
        ready.complete(Unit)
        assertTrue(result.await())
    }

    @Test
    fun loggedOutOnceTheCredentialsAreKnown() = runTest {
        assertFalse(awaitLoginState({ false }, {}, timeoutMs = 1_000))
    }

    @Test
    fun aCredentialLoadThatNeverFinishesCountsAsLoggedOut() = runTest {
        val never = CompletableDeferred<Unit>()
        assertFalse(awaitLoginState({ false }, { never.await() }, timeoutMs = 1_000))
    }
}
