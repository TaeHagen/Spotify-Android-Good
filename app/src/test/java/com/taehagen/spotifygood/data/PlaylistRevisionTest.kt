package com.taehagen.spotifygood.data

import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlaylistRevisionTest {
    private val conflict = NativeException(NativeErrorInfo(NativeErrorCode.INVALID_ARGUMENT, "stale revision"))

    @Test
    fun conflictDetection() {
        assertTrue(conflict.isRevisionConflict)
        assertTrue(NativeException(NativeErrorInfo(NativeErrorCode.INVALID_ARGUMENT, "Revision mismatch")).isRevisionConflict)
        assertFalse(NativeException(NativeErrorInfo(NativeErrorCode.INVALID_ARGUMENT, "bad index")).isRevisionConflict)
        assertFalse(NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "revision")).isRevisionConflict)
    }

    @Test
    fun conflictRetriesOnceWithTheCurrentRevision() = runTest {
        val sent = mutableListOf<String?>()
        val result = retryOnStaleRevision("r1", { "r2" }) { rev ->
            sent += rev
            if (rev == "r1") throw conflict
            "r3"
        }
        assertEquals("r3", result)
        assertEquals(listOf("r1", "r2"), sent)
    }

    @Test
    fun otherErrorsAreNotRetried() = runTest {
        var fetched = false
        val error = NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "offline"))
        val thrown = runCatching { retryOnStaleRevision("r1", { fetched = true; "r2" }) { throw error } }.exceptionOrNull()
        assertSame(error, thrown)
        assertFalse(fetched)
    }

    @Test
    fun noUsableRevisionRethrowsTheConflict() = runTest {
        for (fetch in listOf<suspend () -> String?>({ null }, { "r1" }, { throw IllegalStateException("offline") })) {
            var calls = 0
            val thrown = runCatching {
                retryOnStaleRevision("r1", fetch) {
                    calls++
                    throw conflict
                }
            }.exceptionOrNull()
            assertSame(conflict, thrown)
            assertEquals(1, calls)
        }
    }

    @Test
    fun onlyOneRetry() = runTest {
        var calls = 0
        try {
            retryOnStaleRevision("r1", { "r${calls + 1}" }) {
                calls++
                throw conflict
            }
            fail("expected the conflict")
        } catch (e: NativeException) {
            assertSame(conflict, e)
        }
        assertEquals(2, calls)
    }
}
