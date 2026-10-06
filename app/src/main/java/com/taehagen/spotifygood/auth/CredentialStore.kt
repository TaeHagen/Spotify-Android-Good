package com.taehagen.spotifygood.auth

import android.content.Context
import com.taehagen.spotifygood.model.StoredCredentials

/**
 * Keystore-backed secret storage (AES-256-GCM key in AndroidKeyStore, ciphertext in
 * noBackupFilesDir). Holds the reusable librespot credentials, the optional OAuth refresh token
 * and encrypts per-download audio keys. Also owns the stable Connect device id.
 */
class CredentialStore(context: Context) {
    /** Stable random device id (hex, 40 chars), created on first use, survives logout. */
    val deviceId: String get() = TODO()

    fun loadCredentials(): StoredCredentials? = TODO()
    fun saveCredentials(credentials: StoredCredentials): Unit = TODO()

    fun loadRefreshToken(): String? = TODO()
    fun saveRefreshToken(token: String?): Unit = TODO()

    /** Removes credentials and tokens (not the device id). */
    fun clear(): Unit = TODO()

    fun encrypt(plain: ByteArray): ByteArray = TODO()
    fun decrypt(cipher: ByteArray): ByteArray = TODO()
}
