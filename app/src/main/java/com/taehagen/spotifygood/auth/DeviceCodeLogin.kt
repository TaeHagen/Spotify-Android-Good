package com.taehagen.spotifygood.auth

import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

// OAuth 2.0 Device Authorization Grant (RFC 8628) against accounts.spotify.com with the desktop
// client id (docs/ARCHITECTURE.md §9.3). No local server and no redirect: the user approves the
// code in a browser (on this phone or another device) while the app polls the token endpoint.

/** A device code waiting for approval. Persisted (encrypted) so polling survives process death. */
@Serializable
internal data class PendingDeviceLogin(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String? = null,
    val expiresAtMs: Long,
    val intervalSec: Int = DEFAULT_INTERVAL_SEC,
) {
    override fun toString(): String = "PendingDeviceLogin(userCode=$userCode, expiresAtMs=$expiresAtMs, intervalSec=$intervalSec)"

    companion object {
        const val DEFAULT_INTERVAL_SEC = 5
    }
}

@Serializable
internal data class DeviceAuthorizationResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 600,
    val interval: Int = PendingDeviceLogin.DEFAULT_INTERVAL_SEC,
)

/** Parses the device authorization response; throws [OAuthException] / [IOException] (5xx). */
internal fun parseDeviceAuthorization(result: HttpResult, nowMs: Long): PendingDeviceLogin {
    if (result.code >= 500) throw IOException("Spotify accounts HTTP ${result.code}")
    if (!result.isSuccessful) {
        val error = parseOAuthError(result.body)
        throw OAuthException(error?.error ?: "http_${result.code}", error?.description)
    }
    val response = try {
        oauthJson.decodeFromString<DeviceAuthorizationResponse>(result.body)
    } catch (e: SerializationException) {
        throw OAuthException(OAuthException.INVALID_RESPONSE)
    } catch (e: IllegalArgumentException) {
        throw OAuthException(OAuthException.INVALID_RESPONSE)
    }
    if (response.deviceCode.isBlank() || response.userCode.isBlank() || response.verificationUri.isBlank()) {
        throw OAuthException(OAuthException.INVALID_RESPONSE)
    }
    return PendingDeviceLogin(
        deviceCode = response.deviceCode,
        userCode = response.userCode,
        verificationUri = response.verificationUri,
        verificationUriComplete = response.verificationUriComplete?.takeIf { it.isNotBlank() },
        expiresAtMs = nowMs + response.expiresIn.coerceAtLeast(0) * 1000,
        intervalSec = response.interval.coerceAtLeast(1),
    )
}

/** Outcome of one token poll (RFC 8628 §3.5). */
internal sealed interface TokenPollResult {
    data class Success(val token: TokenResponse) : TokenPollResult
    /** `authorization_pending`: keep polling at the current interval. */
    data object Pending : TokenPollResult
    /** `slow_down`: keep polling, interval += 5 s. */
    data object SlowDown : TokenPollResult
    /** Terminal OAuth error (`expired_token`, `access_denied`, `invalid_grant`, ...). */
    data class Failure(val error: String, val description: String? = null) : TokenPollResult
    /** Network failure or server error: retry with backoff. */
    data class Retryable(val reason: String) : TokenPollResult
}

internal fun parseTokenPoll(result: HttpResult): TokenPollResult {
    if (result.isSuccessful) {
        return try {
            TokenPollResult.Success(parseTokenResponse(result))
        } catch (e: OAuthException) {
            TokenPollResult.Failure(e.error, e.description)
        }
    }
    if (result.code >= 500) return TokenPollResult.Retryable("http_${result.code}")
    if (result.code == 429) return TokenPollResult.SlowDown
    val error = parseOAuthError(result.body) ?: return TokenPollResult.Failure("http_${result.code}")
    return when (error.error) {
        "authorization_pending" -> TokenPollResult.Pending
        "slow_down" -> TokenPollResult.SlowDown
        else -> TokenPollResult.Failure(error.error.orEmpty(), error.description)
    }
}

/**
 * Polls the token endpoint until the user approves (returns the token) or the flow ends:
 * throws [OAuthException] (`expired_token` when [PendingDeviceLogin.expiresAtMs] passes, or the
 * server's terminal error) or [IOException] after [MAX_RETRYABLE_FAILURES] consecutive network
 * failures. Waits `interval` before every poll, adds 5 s on `slow_down` (reported through
 * [onIntervalChanged] so it can be persisted) and backs off exponentially on network errors.
 * Cancellation stops it immediately (the wait is a plain `delay`).
 */
internal class DeviceCodePoller(
    private val poll: suspend (deviceCode: String) -> TokenPollResult,
    private val clock: () -> Long,
    private val onIntervalChanged: suspend (intervalSec: Int) -> Unit = {},
) {
    suspend fun awaitToken(login: PendingDeviceLogin): TokenResponse {
        var intervalSec = login.intervalSec.coerceIn(MIN_INTERVAL_SEC, MAX_INTERVAL_SEC)
        var failures = 0
        while (true) {
            val remainingMs = login.expiresAtMs - clock()
            if (remainingMs <= 0) throw OAuthException(OAuthException.EXPIRED_TOKEN)
            val intervalMs = intervalSec * 1000L
            val waitMs = if (failures == 0) intervalMs else minOf(intervalMs shl minOf(failures, 6), MAX_BACKOFF_MS)
            delay(minOf(waitMs, remainingMs))
            if (clock() >= login.expiresAtMs) throw OAuthException(OAuthException.EXPIRED_TOKEN)
            when (val result = poll(login.deviceCode)) {
                is TokenPollResult.Success -> return result.token
                TokenPollResult.Pending -> failures = 0
                TokenPollResult.SlowDown -> {
                    failures = 0
                    intervalSec = (intervalSec + SLOW_DOWN_STEP_SEC).coerceAtMost(MAX_INTERVAL_SEC)
                    onIntervalChanged(intervalSec)
                }
                is TokenPollResult.Failure -> throw OAuthException(result.error, result.description)
                is TokenPollResult.Retryable -> {
                    failures++
                    if (failures > MAX_RETRYABLE_FAILURES) throw IOException("Token polling failed: ${result.reason}")
                }
            }
        }
    }

    companion object {
        const val MIN_INTERVAL_SEC = 1
        const val MAX_INTERVAL_SEC = 60
        const val SLOW_DOWN_STEP_SEC = 5
        const val MAX_BACKOFF_MS = 60_000L
        const val MAX_RETRYABLE_FAILURES = 8
    }
}

/**
 * Persists the [PendingDeviceLogin] (sealed with the Keystore key) in noBackupFilesDir so the
 * login screen can resume polling after process death. Blocking: call on Dispatchers.IO.
 */
internal class PendingDeviceLoginStore(
    private val file: () -> File,
    private val seal: (ByteArray) -> ByteArray,
    private val open: (ByteArray) -> ByteArray,
) {
    @Synchronized
    fun load(): PendingDeviceLogin? {
        val f = file()
        if (!f.exists()) return null
        return try {
            oauthJson.decodeFromString<PendingDeviceLogin>(open(f.readBytes()).decodeToString())
        } catch (e: Exception) {
            // Unreadable (corrupt, other key): forget it, a new code will be requested.
            f.delete()
            null
        }
    }

    @Synchronized
    fun save(login: PendingDeviceLogin) {
        val f = file()
        try {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeBytes(seal(oauthJson.encodeToString(PendingDeviceLogin.serializer(), login).encodeToByteArray()))
            if (!tmp.renameTo(f)) tmp.delete()
        } catch (e: Exception) {
            // Not fatal: polling continues, it just would not survive process death.
        }
    }

    @Synchronized
    fun clear() {
        file().delete()
    }
}
