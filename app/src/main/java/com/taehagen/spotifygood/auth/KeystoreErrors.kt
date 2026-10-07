package com.taehagen.spotifygood.auth

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException

/**
 * The Keystore could not be used right now (keystore2 busy, a binder or system error), even
 * after retries. Nothing was deleted: the key and everything sealed with it are intact, so the
 * caller should fail this operation and try again later. Never a reason to discard data.
 */
class KeystoreUnavailableException(message: String, cause: Throwable) : GeneralSecurityException(message, cause)

/** How a Keystore / cipher failure is handled. */
internal enum class KeystoreFailure {
    /** The key is gone for good (invalidated, corrupted, deleted): only now may it be replaced. */
    PERMANENT,

    /**
     * The data does not match the key (corrupt, or sealed by an older key): an
     * [AEADBadTagException], the only definite signal for that.
     */
    DATA,

    /**
     * Everything else: retried, then reported as [KeystoreUnavailableException]; never deletes
     * anything.
     */
    RETRYABLE,
}

/**
 * Classifies a failure by its cause chain. Only definite signals count:
 * * [PERMANENT]: a [KeyPermanentlyInvalidatedException], or (API 33+) a non-transient keystore
 *   error saying the key is corrupted or does not exist.
 * * [DATA]: an [AEADBadTagException] (checked before the causes: a real one also carries a
 *   VERIFICATION_FAILED keystore error).
 * * [RETRYABLE]: everything else. AndroidKeyStore reports any other keystore failure in
 *   `doFinal` (a binder / SYSTEM_ERROR while keystore2 restarts, an operation handle pruned
 *   under slot pressure) as a plain `IllegalBlockSizeException` caused by the KeyStoreException,
 *   and transient init failures as ProviderException / InvalidKeyException /
 *   UnrecoverableKeyException. Treating those as corrupt data deleted valid credentials and
 *   downloads; losing the key would make every downloaded track undecryptable.
 */
internal fun classifyKeystoreFailure(e: Throwable): KeystoreFailure {
    val chain = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES).toList()
    if (chain.any { it is KeyPermanentlyInvalidatedException }) return KeystoreFailure.PERMANENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && chain.any(::isPermanentKeystoreError)) {
        return KeystoreFailure.PERMANENT
    }
    if (e is AEADBadTagException) return KeystoreFailure.DATA
    return KeystoreFailure.RETRYABLE
}

private fun isPermanentKeystoreError(e: Throwable): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val keystoreError = e as? android.security.KeyStoreException ?: return false
    if (keystoreError.isTransientFailure) return false
    return keystoreError.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_CORRUPTED ||
        keystoreError.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST
}

/**
 * Runs a Keystore operation, retrying [KeystoreFailure.RETRYABLE] failures [attempts] times
 * with exponential backoff from [initialDelayMs]. Other failures are rethrown at once; retryable
 * ones that persist become a [KeystoreUnavailableException].
 */
internal fun <T> withKeystoreRetry(
    what: String,
    attempts: Int = KEYSTORE_ATTEMPTS,
    initialDelayMs: Long = KEYSTORE_RETRY_DELAY_MS,
    sleep: (Long) -> Unit = Thread::sleep,
    log: (String) -> Unit = { Log.w(KEYSTORE_TAG, it) },
    block: () -> T,
): T {
    var delayMs = initialDelayMs
    var attempt = 1
    while (true) {
        try {
            return block()
        } catch (e: Exception) {
            if (classifyKeystoreFailure(e) != KeystoreFailure.RETRYABLE) throw e
            if (attempt >= attempts) throw KeystoreUnavailableException("$what: Keystore unavailable", e)
            log("$what: Keystore failure (${e.javaClass.simpleName}), retrying")
            sleep(delayMs)
            delayMs *= 2
            attempt++
        }
    }
}

internal const val KEYSTORE_TAG = "CredentialStore"
internal const val KEYSTORE_ATTEMPTS = 4
internal const val KEYSTORE_RETRY_DELAY_MS = 20L
private const val MAX_CAUSES = 16
