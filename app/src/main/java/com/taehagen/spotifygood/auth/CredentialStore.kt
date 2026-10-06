package com.taehagen.spotifygood.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.taehagen.spotifygood.model.StoredCredentials
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore-backed secret storage (AES-256-GCM key in AndroidKeyStore, ciphertext in
 * noBackupFilesDir). Holds the reusable librespot credentials, the optional OAuth refresh token
 * and encrypts per-download audio keys. Also owns the stable Connect device id.
 *
 * All methods are thread-safe and blocking (Keystore operations are binder calls): call them off
 * the main thread, except [deviceId], which only touches a tiny plain file once per process.
 * Corrupted ciphertext or an unusable Keystore key never crash: the affected secrets are deleted
 * and the load returns `null`, so the user simply logs in again.
 */
class CredentialStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir: File by lazy { appContext.noBackupFilesDir }

    private val json = Json { ignoreUnknownKeys = true }

    /** Guards key creation/deletion. */
    private val keyLock = Any()
    @Volatile private var cachedKey: SecretKey? = null

    /** Guards the files and their in-memory copies. */
    private val fileLock = Any()
    @Volatile private var cachedDeviceId: String? = null
    private var credentialsLoaded = false
    private var cachedCredentials: StoredCredentials? = null
    private var refreshTokenLoaded = false
    private var cachedRefreshToken: String? = null

    /** Stable random device id (hex, 40 chars), created on first use, survives logout. */
    val deviceId: String
        get() = cachedDeviceId ?: synchronized(fileLock) {
            cachedDeviceId ?: loadOrCreateDeviceId().also { cachedDeviceId = it }
        }

    fun loadCredentials(): StoredCredentials? = synchronized(fileLock) {
        if (!credentialsLoaded) {
            cachedCredentials = readSecret(CREDENTIALS_FILE)?.let { bytes ->
                try {
                    json.decodeFromString<StoredCredentials>(bytes.decodeToString())
                } catch (e: SerializationException) {
                    Log.w(TAG, "Stored credentials unreadable, discarding")
                    deleteFile(CREDENTIALS_FILE)
                    null
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Stored credentials unreadable, discarding")
                    deleteFile(CREDENTIALS_FILE)
                    null
                }
            }
            credentialsLoaded = true
        }
        cachedCredentials
    }

    fun saveCredentials(credentials: StoredCredentials): Unit = synchronized(fileLock) {
        // Keep them in memory even if persisting fails: the running session stays usable.
        cachedCredentials = credentials
        credentialsLoaded = true
        writeSecret(CREDENTIALS_FILE, json.encodeToString(StoredCredentials.serializer(), credentials).encodeToByteArray())
    }

    fun loadRefreshToken(): String? = synchronized(fileLock) {
        if (!refreshTokenLoaded) {
            cachedRefreshToken = readSecret(REFRESH_TOKEN_FILE)?.decodeToString()?.takeIf { it.isNotBlank() }
            refreshTokenLoaded = true
        }
        cachedRefreshToken
    }

    fun saveRefreshToken(token: String?): Unit = synchronized(fileLock) {
        cachedRefreshToken = token?.takeIf { it.isNotBlank() }
        refreshTokenLoaded = true
        val value = cachedRefreshToken
        if (value == null) deleteFile(REFRESH_TOKEN_FILE) else writeSecret(REFRESH_TOKEN_FILE, value.encodeToByteArray())
    }

    /** Removes only the reusable librespot credentials (rejected by Spotify); keeps the refresh token. */
    fun clearCredentials(): Unit = synchronized(fileLock) {
        cachedCredentials = null
        credentialsLoaded = true
        deleteFile(CREDENTIALS_FILE)
    }

    /** Removes credentials and tokens (not the device id). */
    fun clear(): Unit = synchronized(fileLock) {
        cachedCredentials = null
        credentialsLoaded = true
        cachedRefreshToken = null
        refreshTokenLoaded = true
        deleteFile(CREDENTIALS_FILE)
        deleteFile(REFRESH_TOKEN_FILE)
    }

    /**
     * Encrypts [plain] with the Keystore key. Output: 12-byte IV || ciphertext || 16-byte tag.
     * A permanently unusable key is replaced (data sealed with it was unreadable anyway).
     */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: Exception) {
            if (e !is GeneralSecurityException && e !is ProviderException) throw e
            Log.w(TAG, "Keystore key unusable (${e.javaClass.simpleName}), regenerating")
            resetKey()
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    /**
     * Decrypts the output of [encrypt]. Throws [GeneralSecurityException] when the data is
     * corrupt or was sealed with another key ([AEADBadTagException]) or the key is unusable.
     */
    fun decrypt(cipher: ByteArray): ByteArray {
        if (cipher.size < IV_BYTES + TAG_BYTES) throw GeneralSecurityException("Ciphertext too short")
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, cipher, 0, IV_BYTES))
        return c.doFinal(cipher, IV_BYTES, cipher.size - IV_BYTES)
    }

    // ---- key management ---------------------------------------------------------------------

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(keyLock) {
            cachedKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = try {
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            } catch (e: UnrecoverableKeyException) {
                Log.w(TAG, "Keystore key unrecoverable, regenerating")
                runCatching { keyStore.deleteEntry(KEY_ALIAS) }
                null
            }
            val key = existing ?: generateKey()
            cachedKey = key
            return key
        }
    }

    private fun generateKey(): SecretKey {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // No user authentication / unlocked-device requirement: playback resumption (Bluetooth
            // button, Connect) must be able to decrypt while the screen is locked.
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun resetKey() {
        synchronized(keyLock) {
            cachedKey = null
            runCatching {
                KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
            }.onFailure { Log.w(TAG, "Deleting Keystore key failed", it) }
        }
    }

    /** True when the current key can round-trip data (detects a silently broken Keystore). */
    private fun keyWorks(): Boolean = try {
        val probe = byteArrayOf(0x53, 0x47)
        decrypt(encrypt(probe)).contentEquals(probe)
    } catch (e: Exception) {
        false
    }

    // ---- files (callers hold fileLock) ------------------------------------------------------

    private fun readSecret(name: String): ByteArray? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val blob = try {
            file.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "Reading $name failed", e)
            return null
        }
        return try {
            decrypt(blob)
        } catch (e: AEADBadTagException) {
            // The file does not match the key: corrupt, or sealed by a key that no longer exists.
            Log.w(TAG, "$name does not authenticate, discarding")
            deleteFile(name)
            if (!keyWorks()) discardKeyAndSecrets()
            null
        } catch (e: Exception) {
            // KeyPermanentlyInvalidatedException, UnrecoverableKeyException, KeyStoreException,
            // ProviderException ...: the key itself is unusable, so nothing sealed with it is.
            Log.w(TAG, "Keystore failure reading $name (${e.javaClass.simpleName}), discarding secrets")
            discardKeyAndSecrets()
            null
        }
    }

    private fun discardKeyAndSecrets() {
        resetKey()
        deleteFile(CREDENTIALS_FILE)
        deleteFile(REFRESH_TOKEN_FILE)
        cachedCredentials = null
        cachedRefreshToken = null
    }

    private fun writeSecret(name: String, plain: ByteArray) {
        try {
            writeAtomically(File(dir, name), encrypt(plain))
        } catch (e: Exception) {
            Log.e(TAG, "Persisting $name failed (${e.javaClass.simpleName})")
        }
    }

    private fun deleteFile(name: String) {
        val file = File(dir, name)
        if (file.exists() && !file.delete()) Log.w(TAG, "Deleting $name failed")
    }

    private fun loadOrCreateDeviceId(): String {
        val file = File(dir, DEVICE_ID_FILE)
        try {
            if (file.exists()) {
                val stored = file.readText().trim().lowercase()
                if (DEVICE_ID_REGEX.matches(stored)) return stored
            }
        } catch (e: IOException) {
            Log.w(TAG, "Reading device id failed", e)
        }
        val bytes = ByteArray(DEVICE_ID_BYTES).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        try {
            writeAtomically(file, id.encodeToByteArray())
        } catch (e: IOException) {
            Log.w(TAG, "Persisting device id failed", e)
        }
        return id
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("Renaming ${tmp.name} failed")
        }
    }

    private companion object {
        const val TAG = "CredentialStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "spotifygood_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val CREDENTIALS_FILE = "credentials.bin"
        const val REFRESH_TOKEN_FILE = "refresh_token.bin"
        const val DEVICE_ID_FILE = "device_id"
        const val DEVICE_ID_BYTES = 20
        val DEVICE_ID_REGEX = Regex("[0-9a-f]{40}")
    }
}
