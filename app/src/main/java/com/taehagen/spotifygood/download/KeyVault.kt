package com.taehagen.spotifygood.download

import com.taehagen.spotifygood.auth.KeystoreUnavailableException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Envelope encryption of the downloads' audio keys: one random AES-256 data key, stored in [file]
 * sealed with the Keystore ([wrap] / [unwrap], i.e. CredentialStore.encrypt / decrypt), unsealed
 * once per process and kept in memory; each audio key is then sealed in software (AES-GCM, the row's
 * URI as associated data). A cold start costs one Keystore operation instead of one per download
 * (thousands of TEE round trips that held the offline index back past the engine's wait).
 *
 * Failures: a busy Keystore surfaces as [KeystoreUnavailableException] (retry later, nothing is
 * lost; [unwrap] turns every passing Keystore failure into one). Any other failure of [unwrap] means
 * the data key can no longer be unsealed (the Keystore key was replaced, the file is corrupt): what
 * it sealed is unreadable, so opening fails at once from then on, and the next seal replaces it. A
 * sealed key that does not open throws [AEADBadTagException] (that download must be fetched again).
 * An [IOException] reading the file never replaces the data key. Blocking (file and Keystore I/O):
 * call off the main thread.
 */
internal class KeyVault(
    private val file: File,
    private val wrap: (ByteArray) -> ByteArray,
    private val unwrap: (ByteArray) -> ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    @Volatile private var key: SecretKey? = null

    /** The stored data key cannot be unsealed for good: rows fail at once (no Keystore call each). */
    @Volatile private var unreadable = false

    /** Seals [plain] (an audio key) for the row [uri]. Creates the data key on first use. */
    fun seal(plain: ByteArray, uri: String): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyForSealing(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(uri.toByteArray(Charsets.UTF_8))
        return byteArrayOf(FORMAT) + iv + cipher.doFinal(plain)
    }

    /** Opens what [seal] produced for the row [uri]. */
    fun unseal(sealed: ByteArray, uri: String): ByteArray {
        if (sealed.size < 1 + IV_BYTES + TAG_BITS / 8 || sealed[0] != FORMAT) throw AEADBadTagException("Not a vault-sealed key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        cipher.updateAAD(uri.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }

    /** Forgets the in-memory data key (Remove all / logout delete [file] with the downloads). */
    fun reset() {
        synchronized(lock) {
            key = null
            unreadable = false
        }
    }

    /** The data key; [AEADBadTagException] when there is none, or it can no longer be unsealed. */
    private fun existingKey(): SecretKey {
        key?.let { return it }
        synchronized(lock) {
            key?.let { return it }
            if (unreadable || !file.isFile) throw AEADBadTagException("No readable data key")
            val stored = file.readBytes()
            try {
                return SecretKeySpec(unwrap(stored), "AES").also { key = it }
            } catch (e: KeystoreUnavailableException) {
                throw e
            } catch (e: Exception) {
                unreadable = true
                throw e
            }
        }
    }

    /**
     * The data key, created when there is none. A data key that can no longer be unsealed (the
     * Keystore key was replaced) is replaced too: what it sealed was unreadable anyway. A busy
     * Keystore is never a reason to replace it.
     */
    private fun keyForSealing(): SecretKey {
        key?.let { return it }
        synchronized(lock) {
            key?.let { return it }
            if (file.isFile && !unreadable) {
                val stored = file.readBytes()
                try {
                    return SecretKeySpec(unwrap(stored), "AES").also { key = it }
                } catch (e: KeystoreUnavailableException) {
                    throw e
                } catch (e: Exception) {
                    // Unreadable for good: replaced by a new data key below.
                }
            }
            val raw = ByteArray(KEY_BYTES).also(random::nextBytes)
            store(wrap(raw))
            unreadable = false
            return SecretKeySpec(raw, "AES").also { key = it }
        }
    }

    /** Replaces [file] atomically and durably: every download's key depends on it. */
    private fun store(sealed: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(sealed)
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not store the data key in $file")
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
    }
}
