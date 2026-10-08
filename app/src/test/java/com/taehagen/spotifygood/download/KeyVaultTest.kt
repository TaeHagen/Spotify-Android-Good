package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.auth.KeystoreUnavailableException
import java.io.File
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The downloads' data key ([KeyVault]): a cold start opens every audio key with one Keystore
 * operation, and only a Keystore that is gone for good (never a busy one) costs the data key.
 */
class KeyVaultTest {
    private val dir: File = Files.createTempDirectory("key-vault").toFile()
    private val file = File(dir, "offline/datakey.bin")

    /** Stands in for the Keystore (CredentialStore.encrypt / decrypt), counting its operations. */
    private class FakeKeystore {
        var key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        var wraps = 0
        var unwraps = 0
        var busy = false

        fun wrap(plain: ByteArray): ByteArray {
            wraps++
            if (busy) throw KeystoreUnavailableException("encrypt: Keystore unavailable", RuntimeException())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plain)
        }

        fun unwrap(sealed: ByteArray): ByteArray {
            unwraps++
            if (busy) throw KeystoreUnavailableException("decrypt: Keystore unavailable", RuntimeException())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }

        /** The Keystore key was replaced (e.g. invalidated): nothing it sealed opens any more. */
        fun replaceKey() {
            key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")
        }
    }

    private val keystore = FakeKeystore()

    private fun vault() = KeyVault(file, keystore::wrap, keystore::unwrap)

    private fun audioKey(n: Int) = ByteArray(16) { (it * 7 + n).toByte() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a cold start opens thousands of keys with one Keystore operation`() {
        val writer = vault()
        val sealed = (0 until 3_000).map { writer.seal(audioKey(it), "spotify:track:$it") }
        assertEquals("the data key is created once", 1, keystore.wraps)
        assertEquals(0, keystore.unwraps)

        // Next process: a new vault over the same file.
        val reader = vault()
        sealed.forEachIndexed { i, s -> assertArrayEquals(audioKey(i), reader.unseal(s, "spotify:track:$i")) }
        assertEquals("one TEE round trip for the whole index", 1, keystore.unwraps)
        assertEquals(1, keystore.wraps)
    }

    @Test
    fun `a sealed key opens only for its own row`() {
        val vault = vault()
        val sealed = vault.seal(audioKey(1), "spotify:track:a")
        assertThrows(AEADBadTagException::class.java) { vault.unseal(sealed, "spotify:track:b") }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) { vault.unseal(tampered, "spotify:track:a") }
        // A key sealed by the Keystore itself (from before the data key) is not taken for one.
        assertThrows(AEADBadTagException::class.java) { vault.unseal(keystore.wrap(audioKey(1)), "spotify:track:a") }
    }

    @Test
    fun `a busy Keystore keeps the data key and is retried`() {
        val sealed = vault().seal(audioKey(1), "spotify:track:a")
        val stored = file.readBytes()
        keystore.busy = true
        val vault = vault()
        // Classified as RETRY_LATER (the download stays and is registered later).
        val opening = assertThrows(KeystoreUnavailableException::class.java) { vault.unseal(sealed, "spotify:track:a") }
        assertEquals(DownloadRules.KeyFailure.RETRY_LATER, DownloadRules.keyFailure(opening))
        assertThrows(KeystoreUnavailableException::class.java) { vault.seal(audioKey(2), "spotify:track:b") }
        assertArrayEquals("never replaced while the Keystore is busy", stored, file.readBytes())

        keystore.busy = false
        assertArrayEquals(audioKey(1), vault.unseal(sealed, "spotify:track:a"))
    }

    @Test
    fun `a data key that no longer opens fails rows at once and is replaced by the next seal`() {
        val old = vault().seal(audioKey(1), "spotify:track:a")
        keystore.replaceKey()
        val vault = vault()
        val failure = assertThrows(GeneralSecurityException::class.java) { vault.unseal(old, "spotify:track:a") }
        assertFalse(failure is KeystoreUnavailableException)
        assertEquals(DownloadRules.KeyFailure.UNREADABLE, DownloadRules.keyFailure(failure))
        val unwraps = keystore.unwraps
        repeat(100) { assertThrows(AEADBadTagException::class.java) { vault.unseal(old, "spotify:track:a") } }
        assertEquals("no Keystore call per unreadable row", unwraps, keystore.unwraps)

        // A new download gets a new data key, which the next process opens.
        val fresh = vault.seal(audioKey(2), "spotify:track:b")
        assertArrayEquals(audioKey(2), vault().unseal(fresh, "spotify:track:b"))
        assertThrows(AEADBadTagException::class.java) { vault().unseal(old, "spotify:track:a") }
    }

    @Test
    fun `without a data key nothing opens, and removing all downloads forgets it`() {
        val vault = vault()
        assertThrows(AEADBadTagException::class.java) { vault.unseal(ByteArray(40) { 1 }, "spotify:track:a") }
        assertEquals("no data key is created just to open", 0, keystore.wraps)
        assertFalse(file.exists())

        val sealed = vault.seal(audioKey(1), "spotify:track:a")
        assertTrue(file.isFile)
        assertFalse(File(file.path + ".tmp").exists())

        // Remove all: the file goes with the downloads, then the vault forgets the key.
        file.parentFile!!.deleteRecursively()
        vault.reset()
        assertThrows(AEADBadTagException::class.java) { vault.unseal(sealed, "spotify:track:a") }
        val next = vault.seal(audioKey(2), "spotify:track:b")
        assertEquals(2, keystore.wraps)
        assertArrayEquals(audioKey(2), vault().unseal(next, "spotify:track:b"))
    }

    @Test
    fun `each seal uses a fresh nonce`() {
        val vault = vault()
        val a = vault.seal(audioKey(1), "spotify:track:a")
        val b = vault.seal(audioKey(1), "spotify:track:a")
        assertFalse(a.contentEquals(b))
        assertArrayEquals(audioKey(1), vault.unseal(a, "spotify:track:a"))
        assertArrayEquals(audioKey(1), vault.unseal(b, "spotify:track:a"))
    }
}
