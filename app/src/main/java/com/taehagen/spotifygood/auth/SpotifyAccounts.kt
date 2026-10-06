package com.taehagen.spotifygood.auth

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OAuth parameters of Spotify's desktop ("keymaster") client, the only one librespot sessions accept. */
internal object SpotifyOAuth {
    const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    const val ACCOUNTS_BASE_URL = "https://accounts.spotify.com"
    const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

    /** Spotify desktop scope list (librespot `OAUTH_SCOPES`). */
    val SCOPES: List<String> = listOf(
        "app-remote-control", "playlist-modify", "playlist-modify-private", "playlist-modify-public",
        "playlist-read", "playlist-read-collaborative", "playlist-read-private", "streaming",
        "ugc-image-upload", "user-follow-modify", "user-follow-read", "user-library-modify",
        "user-library-read", "user-modify", "user-modify-playback-state", "user-modify-private",
        "user-personalized", "user-read-birthdate", "user-read-currently-playing", "user-read-email",
        "user-read-play-history", "user-read-playback-position", "user-read-playback-state",
        "user-read-private", "user-read-recently-played", "user-top-read",
    )

    val scope: String = SCOPES.joinToString(" ")

    /** Authorization Code + PKCE (S256) authorize URL. */
    fun authorizeUrl(redirectUri: String, codeChallenge: String, state: String, baseUrl: String = ACCOUNTS_BASE_URL): String =
        "$baseUrl/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("state", state)
            .addQueryParameter("scope", scope)
            .build()
            .toString()
}

/** An OAuth error response (`error` per RFC 6749 §5.2 / RFC 8628 §3.5, e.g. `expired_token`). */
internal class OAuthException(val error: String, val description: String? = null) :
    Exception(if (description.isNullOrBlank()) error else "$error: $description") {
    companion object {
        const val EXPIRED_TOKEN = "expired_token"
        const val ACCESS_DENIED = "access_denied"
        const val INVALID_GRANT = "invalid_grant"
        const val INVALID_RESPONSE = "invalid_response"
    }
}

/** Token endpoint success response. Never log it. */
@Serializable
internal data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("refresh_token") val refreshToken: String? = null,
    val scope: String? = null,
) {
    override fun toString(): String = "TokenResponse(<redacted>, expiresIn=$expiresIn)"
}

@Serializable
internal data class OAuthErrorBody(
    val error: String? = null,
    @SerialName("error_description") val description: String? = null,
)

internal data class HttpResult(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
    override fun toString(): String = "HttpResult($code)"
}

internal val oauthJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Parses an OAuth error body; `null` when the body is not one. */
internal fun parseOAuthError(body: String): OAuthErrorBody? = try {
    oauthJson.decodeFromString<OAuthErrorBody>(body).takeIf { !it.error.isNullOrBlank() }
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

/** Parses a token endpoint response; throws [OAuthException] for errors, [IOException] for 5xx. */
internal fun parseTokenResponse(result: HttpResult): TokenResponse {
    if (result.code >= 500) throw IOException("Spotify accounts HTTP ${result.code}")
    if (!result.isSuccessful) {
        val error = parseOAuthError(result.body)
        throw OAuthException(error?.error ?: "http_${result.code}", error?.description)
    }
    return try {
        oauthJson.decodeFromString<TokenResponse>(result.body).also {
            if (it.accessToken.isBlank()) throw OAuthException(OAuthException.INVALID_RESPONSE)
        }
    } catch (e: SerializationException) {
        throw OAuthException(OAuthException.INVALID_RESPONSE)
    } catch (e: IllegalArgumentException) {
        throw OAuthException(OAuthException.INVALID_RESPONSE)
    }
}

/**
 * Minimal client for accounts.spotify.com (device authorization, token exchange, refresh).
 * Uses the app's OkHttp client (shared pool) with explicit timeouts. Never logs tokens.
 */
internal class SpotifyAccountsClient(
    baseClient: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    baseUrl: String = SpotifyOAuth.ACCOUNTS_BASE_URL,
) {
    private val client: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val deviceAuthorizeUrl: HttpUrl = "$baseUrl/oauth2/device/authorize".toHttpUrl()
    private val tokenUrl: HttpUrl = "$baseUrl/api/token".toHttpUrl()

    /** RFC 8628 §3.1. Retries transient failures (bounded, with backoff). */
    suspend fun requestDeviceAuthorization(): PendingDeviceLogin = withRetries {
        val result = post(deviceAuthorizeUrl, mapOf("client_id" to SpotifyOAuth.CLIENT_ID, "scope" to SpotifyOAuth.scope))
        parseDeviceAuthorization(result, clock())
    }

    /** One RFC 8628 §3.4 token poll. Never throws for expected outcomes. */
    suspend fun pollDeviceToken(deviceCode: String): TokenPollResult = try {
        parseTokenPoll(
            post(
                tokenUrl,
                mapOf(
                    "grant_type" to SpotifyOAuth.DEVICE_CODE_GRANT,
                    "device_code" to deviceCode,
                    "client_id" to SpotifyOAuth.CLIENT_ID,
                ),
            ),
        )
    } catch (e: IOException) {
        TokenPollResult.Retryable(e.javaClass.simpleName)
    }

    /** Authorization Code + PKCE exchange (RFC 7636 §4.5). */
    suspend fun exchangeAuthorizationCode(code: String, redirectUri: String, codeVerifier: String): TokenResponse =
        withRetries(attempts = 2) {
            parseTokenResponse(
                post(
                    tokenUrl,
                    mapOf(
                        "grant_type" to "authorization_code",
                        "code" to code,
                        "redirect_uri" to redirectUri,
                        "client_id" to SpotifyOAuth.CLIENT_ID,
                        "code_verifier" to codeVerifier,
                    ),
                ),
            )
        }

    suspend fun refresh(refreshToken: String): TokenResponse = withRetries {
        parseTokenResponse(
            post(
                tokenUrl,
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to SpotifyOAuth.CLIENT_ID,
                ),
            ),
        )
    }

    private suspend fun post(url: HttpUrl, form: Map<String, String>): HttpResult {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(body)
            .build()
        return client.newCall(request).await()
    }

    /** Retries [IOException]s (network, 5xx) up to [attempts] times with exponential backoff. */
    private suspend fun <T> withRetries(attempts: Int = 3, block: suspend () -> T): T {
        var backoffMs = 1_000L
        repeat(attempts - 1) {
            try {
                return block()
            } catch (e: IOException) {
                delay(backoffMs)
                backoffMs *= 2
            }
        }
        return block()
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
internal suspend fun Call.await(): HttpResult = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use { HttpResult(it.code, it.body.string()) }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(result)
            }
        },
    )
}
