package com.taehagen.spotifygood.engine

import android.content.Context
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.SessionState
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** Who needs the native session to run (docs/ARCHITECTURE.md §9.2). */
enum class HolderType { UI, PLAYBACK, DOWNLOAD, PRESENCE }

/** A claim on the engine. Release exactly once (idempotent). */
interface EngineHolder : AutoCloseable {
    val type: HolderType
    fun release()
    override fun close() = release()
}

data class EngineState(
    val session: SessionState = SessionState.STOPPED,
    val loggedIn: Boolean = false,
    val user: User? = null,
    val error: NativeErrorInfo? = null,
    val nextRetryMs: Long? = null,
    val networkAvailable: Boolean = true,
)

/**
 * Owns the native engine lifecycle: starts the librespot session while any [EngineHolder] is held
 * and credentials exist, stops it [IDLE_GRACE_MS] after the last holder is released, forwards
 * network changes, persists reusable credentials, and pushes engine settings.
 */
class SpotifyEngine(
    context: Context,
    scope: CoroutineScope,
    rpc: NativeRpc,
    events: NativeEvents,
    settings: SettingsRepository,
    credentialStore: CredentialStore,
    audioSink: AudioSinkBridge,
) {
    val state: StateFlow<EngineState> get() = TODO()
    val isLoggedIn: StateFlow<Boolean> get() = TODO()
    val user: StateFlow<User?> get() = TODO()
    /** True while the native session is ONLINE. */
    val isOnline: StateFlow<Boolean> get() = TODO()
    val isNetworkAvailable: StateFlow<Boolean> get() = TODO()
    /** True while the native session is started (any state but STOPPED); listeners may register. */
    val isRunning: StateFlow<Boolean> get() = TODO()

    /** Supplies decrypted download records pushed to `offline.setIndex` whenever the engine starts. */
    fun setOfflineIndexProvider(provider: suspend () -> List<com.taehagen.spotifygood.model.OfflineTrackRecord>): Unit = TODO()

    fun acquire(type: HolderType): EngineHolder = TODO()

    /** Suspends until ONLINE (acquire a holder first) or the timeout/error; returns success. */
    suspend fun awaitOnline(timeoutMs: Long = 30_000): Boolean = TODO()

    /** First login: hands the OAuth access token to the engine; reusable credentials follow via events. */
    suspend fun loginWithAccessToken(accessToken: String, expiresAtMs: Long): Unit = TODO()

    /** Login by letting another Spotify app on the LAN hand over credentials (Spotify Connect zeroconf). */
    suspend fun loginWithZeroconf(timeoutMs: Long = 180_000): Unit = TODO()

    /** Logs out: stops the engine, wipes credentials and user data (downloads included). */
    suspend fun logout(): Unit = TODO()

    companion object {
        const val IDLE_GRACE_MS = 60_000L
    }
}
