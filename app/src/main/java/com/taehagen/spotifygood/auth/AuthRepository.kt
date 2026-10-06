package com.taehagen.spotifygood.auth

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.taehagen.spotifygood.engine.SpotifyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

sealed interface LoginState {
    data object Idle : LoginState

    /** Device-code flow: show [userCode]; [verificationUriComplete] opens the approval page directly. */
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresAtMs: Long,
    ) : LoginState

    /** PKCE flow: browser open, waiting for the loopback redirect. */
    data object WaitingForBrowser : LoginState

    /** Zeroconf flow: advertised on the LAN, waiting for another Spotify app. */
    data object WaitingForDevice : LoginState

    data object Connecting : LoginState
    data class Failed(val message: String, val code: String? = null) : LoginState
    data object Success : LoginState
}

/** Login flows (docs/ARCHITECTURE.md §9.3). All flows end in [SpotifyEngine.loginWithAccessToken] (or zeroconf). */
class AuthRepository(
    context: Context,
    scope: CoroutineScope,
    engine: SpotifyEngine,
    credentialStore: CredentialStore,
    httpClient: OkHttpClient,
) {
    val state: StateFlow<LoginState> get() = TODO()

    /** Starts the device authorization grant and polls until approved/expired/cancelled. */
    fun startDeviceLogin(): Unit = TODO()

    /** Opens the device-code approval page in a Custom Tab (call after [startDeviceLogin]). */
    fun openApprovalPage(activity: Activity): Unit = TODO()

    /** Fallback: PKCE in a Custom Tab with a 127.0.0.1 loopback redirect. */
    fun startBrowserLogin(activity: Activity): Unit = TODO()

    /** Handles `spotifygood://auth...` redirects; returns true if consumed. */
    fun handleRedirect(uri: Uri): Boolean = TODO()

    fun startZeroconfLogin(context: Context): Unit = TODO()

    fun cancel(): Unit = TODO()
}
