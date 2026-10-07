package com.taehagen.spotifygood.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException

class KeystoreErrorsTest {
    @Test
    fun transientKeystoreFailuresAreRetryableNeverPermanent() {
        // keystore2 BACKEND_BUSY / SYSTEM_ERROR reach the app wrapped like this.
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(ProviderException("Keystore operation failed")))
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(InvalidKeyException("Keystore operation failed", ProviderException())))
        assertEquals(
            KeystoreFailure.RETRYABLE,
            classifyKeystoreFailure(UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(ProviderException()) }),
        )
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(KeyStoreException("system error")))
    }

    @Test
    fun onlyATagMismatchIsData() {
        assertEquals(KeystoreFailure.DATA, classifyKeystoreFailure(AEADBadTagException("tag mismatch")))
        // A real tag mismatch also carries a keystore error (VERIFICATION_FAILED) as its cause.
        assertEquals(
            KeystoreFailure.DATA,
            classifyKeystoreFailure(AEADBadTagException("tag mismatch").apply { initCause(KeyStoreException("verification failed")) }),
        )
        // AndroidKeyStore reports other keystore failures in doFinal (keystore2 restarting, a
        // pruned operation handle) as IllegalBlockSizeException: transient, never corrupt data.
        assertEquals(
            KeystoreFailure.RETRYABLE,
            classifyKeystoreFailure(IllegalBlockSizeException().apply { initCause(KeyStoreException("system error")) }),
        )
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(IllegalBlockSizeException().apply { initCause(ProviderException()) }))
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(BadPaddingException("invalid argument")))
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(IllegalBlockSizeException()))
        assertEquals(KeystoreFailure.RETRYABLE, classifyKeystoreFailure(IOException("disk")))
    }

    @Test
    fun aTransientDoFinalFailureIsRetried() {
        var calls = 0
        val result = withKeystoreRetry("decrypt", sleep = {}, log = {}) {
            if (++calls < 3) throw IllegalBlockSizeException().apply { initCause(KeyStoreException("operation handle pruned")) }
            "plain"
        }
        assertEquals("plain", result)
        assertEquals(3, calls)
    }

    @Test
    fun retriesTransientFailuresThenReportsUnavailable() {
        val sleeps = mutableListOf<Long>()
        var calls = 0
        try {
            withKeystoreRetry("op", attempts = 4, initialDelayMs = 20, sleep = { sleeps += it }, log = {}) {
                calls++
                throw ProviderException("busy")
            }
            fail("expected KeystoreUnavailableException")
        } catch (e: KeystoreUnavailableException) {
            assertTrue(e.cause is ProviderException)
        }
        assertEquals(4, calls)
        assertEquals(listOf(20L, 40L, 80L), sleeps)

        // A failure that clears up is just retried.
        calls = 0
        val result = withKeystoreRetry("op", sleep = {}, log = {}) {
            if (++calls < 3) throw ProviderException("busy")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun dataFailuresAreNotRetried() {
        val bad = AEADBadTagException("tag mismatch")
        var calls = 0
        try {
            withKeystoreRetry("op", sleep = {}, log = {}) {
                calls++
                throw bad
            }
            fail("expected AEADBadTagException")
        } catch (e: AEADBadTagException) {
            assertSame(bad, e)
        }
        assertEquals(1, calls)
    }
}
