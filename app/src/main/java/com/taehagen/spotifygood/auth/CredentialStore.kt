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
 *
 * Failure policy (see [classifyKeystoreFailure]): the key seals the credentials and every
 * downloaded track's audio key, so it is replaced only when it is gone for good
 * ([android.security.keystore.KeyPermanentlyInvalidatedException], a corrupted or missing key).
 * Corrupted ciphertext deletes only that secret. Every other Keystore failure (keystore2 busy,
 * system errors) is retried and then thrown as [KeystoreUnavailableException], with nothing
 * deleted and nothing cached, so a later call tries again.
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
    // Written under fileLock; volatile so [hasCredentials] can read them without the lock.
    @Volatile private var credentialsLoaded = false
    @Volatile private var cachedCredentials: StoredCredentials? = null
    private var refreshTokenLoaded = false
    private var cachedRefreshToken: String? = null
    private val owner by lazy { AccountOwnerFile(File(dir, ACCOUNT_OWNER_FILE)) }

    /** Stable random device id (hex, 40 chars), created on first use, survives logout. */
    val deviceId: String
        get() = cachedDeviceId ?: synchronized(fileLock) {
            cachedDeviceId ?: loadOrCreateDeviceId().also { cachedDeviceId = it }
        }

    /**
     * Whether reusable credentials are stored (i.e. logged in), cheap enough for the main thread
     * (media button receiver): the loaded value once known, otherwise whether the file exists. No
     * Keystore access and no lock, so it never waits for a concurrent [loadCredentials].
     */
    fun hasCredentials(): Boolean =
        if (credentialsLoaded) cachedCredentials != null else File(dir, CREDENTIALS_FILE).exists()

    /**
     * The stored credentials, or `null` if there are none. Throws [KeystoreUnavailableException]
     * (or an [IOException]) when they could not be read right now; nothing is cached then.
     */
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
            // Installs from before the owner file: the stored credentials' account owns the data
            // (retried at the next process start if the file can't be written now).
            cachedCredentials?.let { stored ->
                try {
                    if (owner.read() == null) owner.write(stored.username)
                } catch (e: IOException) {
                    Log.w(TAG, "Recording the account owner failed", e)
                }
            }
        }
        cachedCredentials
    }

    /**
     * The account the data on this device belongs to (downloads, caches, history, the resume
     * state), kept outside `credentials.bin`: rejected credentials ([clearCredentials]) and
     * [clear] keep it, so a login as another account afterwards is noticed
     * ([com.taehagen.spotifygood.engine.AccountGuard]). Throws [IOException] if unreadable.
     */
    fun accountOwner(): String? = synchronized(fileLock) { owner.read() }

    fun setAccountOwner(username: String): Unit = synchronized(fileLock) { owner.write(username) }

    /** The account's data is gone (the end of a complete logout): nobody owns the device now. */
    fun forgetAccountOwner(): Unit = synchronized(fileLock) { owner.delete() }

    fun saveCredentials(credentials: StoredCredentials): Unit = synchronized(fileLock) {
        // Keep them in memory even if persisting fails: the running session stays usable.
        cachedCredentials = credentials
        credentialsLoaded = true
        writeSecret(CREDENTIALS_FILE, json.encodeToString(StoredCredentials.serializer(), credentials).encodeToByteArray())
    }

    /** The stored refresh token; `null` if there is none or it can't be read right now. */
    fun loadRefreshToken(): String? = synchronized(fileLock) {
        if (!refreshTokenLoaded) {
            val token = try {
                readSecret(REFRESH_TOKEN_FILE)
            } catch (e: Exception) {
                // Transient: not cached, the next call tries again.
                Log.w(TAG, "Refresh token unreadable for now (${e.javaClass.simpleName})")
                return@synchronized null
            }
            cachedRefreshToken = token?.decodeToString()?.takeIf { it.isNotBlank() }
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

    /**
     * Removes credentials and tokens (not the device id, not the [accountOwner]: logout forgets it
     * only once the account's data is gone, see [forgetAccountOwner]).
     */
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
     * A permanently invalidated key is replaced (data sealed with it was unreadable anyway).
     * Throws [KeystoreUnavailableException] when the Keystore keeps failing; the key is kept.
     */
    fun encrypt(plain: ByteArray): ByteArray = withKeystoreRetry("encrypt") {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, key())
        } catch (e: Exception) {
            if (classifyKeystoreFailure(e) != KeystoreFailure.PERMANENT) throw e
            Log.w(TAG, "Keystore key permanently invalid (${e.javaClass.simpleName}), regenerating")
            resetKey()
            cipher.init(Cipher.ENCRYPT_MODE, key())
        }
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "Unexpected GCM IV length ${iv.size}" }
        iv + cipher.doFinal(plain)
    }

    /**
     * Decrypts the output of [encrypt]. Throws [AEADBadTagException] when the data is corrupt or
     * was sealed with another key, [KeystoreUnavailableException] when the Keystore keeps failing
     * (try again later: nothing is lost), or another [GeneralSecurityException] when the key is
     * permanently invalid ([classifyKeystoreFailure] tells them apart).
     */
    fun decrypt(cipher: ByteArray): ByteArray {
        if (cipher.size < IV_BYTES + TAG_BYTES) throw AEADBadTagException("Ciphertext too short")
        return withKeystoreRetry("decrypt") {
            val c = Cipher.getInstance(TRANSFORMATION)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, cipher, 0, IV_BYTES))
            c.doFinal(cipher, IV_BYTES, cipher.size - IV_BYTES)
        }
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
                // Usually a transient keystore2 failure: rethrown (the caller retries), the key
                // stays. Only a key that is gone for good is replaced.
                if (classifyKeystoreFailure(e) != KeystoreFailure.PERMANENT) throw e
                Log.w(TAG, "Keystore key permanently unrecoverable, regenerating")
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

    /**
     * True only when the key definitely can't round-trip data (a silently broken Keystore): a
     * probe sealed with it right now does not authenticate, or it is permanently invalid. A probe
     * that can't be sealed or fails for any other reason says nothing about the key.
     */
    private fun keyIsBroken(): Boolean {
        val probe = byteArrayOf(0x53, 0x47)
        val sealed = try {
            encrypt(probe) // replaces a permanently invalid key by itself
        } catch (e: Exception) {
            Log.w(TAG, "Key probe could not encrypt (${e.javaClass.simpleName}); keeping the key")
            return false
        }
        return try {
            !decrypt(sealed).contentEquals(probe)
        } catch (e: Exception) {
            when (classifyKeystoreFailure(e)) {
                KeystoreFailure.DATA, KeystoreFailure.PERMANENT -> true
                KeystoreFailure.RETRYABLE -> false
            }
        }
    }

    // ---- files (callers hold fileLock) ------------------------------------------------------

    /**
     * The decrypted secret, `null` if absent or discarded (corrupt, or the key is gone for good).
     * Throws [KeystoreUnavailableException] or [IOException] when it can't be read right now:
     * nothing is deleted then.
     */
    private fun readSecret(name: String): ByteArray? {
        val file = File(dir, name)
        if (!file.exists()) return null
        val blob = file.readBytes()
        return try {
            decrypt(blob)
        } catch (e: Exception) {
            when (classifyKeystoreFailure(e)) {
                KeystoreFailure.DATA -> {
                    // The tag does not match: the file is corrupt, or sealed by a key that no
                    // longer exists. Only then is the key itself probed.
                    Log.w(TAG, "$name does not authenticate, discarding")
                    deleteFile(name)
                    if (keyIsBroken()) discardKeyAndSecrets()
                    null
                }
                KeystoreFailure.PERMANENT -> {
                    // The key itself is gone for good, so nothing sealed with it is readable.
                    Log.w(TAG, "Keystore key permanently invalid reading $name (${e.javaClass.simpleName}), discarding secrets")
                    discardKeyAndSecrets()
                    null
                }
                KeystoreFailure.RETRYABLE -> {
                    Log.w(TAG, "Reading $name failed for now (${e.javaClass.simpleName}); keeping it")
                    throw e
                }
            }
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
        const val ACCOUNT_OWNER_FILE = "account_owner"
        const val DEVICE_ID_BYTES = 20
        val DEVICE_ID_REGEX = Regex("[0-9a-f]{40}")
    }
}

/**
 * The username of the account that owns this device's data, as a plain file (a username is no
 * secret, and the owner must survive a Keystore key that is gone for good). Read failures are
 * thrown: an owner that can't be read must not look like "nobody".
 */
internal class AccountOwnerFile(private val file: File) {
    fun read(): String? {
        if (!file.exists()) return null
        return file.readText().trim().takeIf { it.isNotEmpty() }
    }

    fun write(username: String) {
        val name = username.trim()
        if (name.isEmpty()) return
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(name.encodeToByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Renaming ${tmp.name} failed")
        }
    }

    fun delete() {
        if (file.exists() && !file.delete()) throw IOException("Deleting ${file.name} failed")
    }
}
