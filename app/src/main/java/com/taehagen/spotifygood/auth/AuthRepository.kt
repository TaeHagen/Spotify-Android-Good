package com.taehagen.spotifygood.auth

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Log
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.engine.NativeStatus
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.lang.ref.WeakReference
import java.net.BindException

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

/**
 * Stable values of [LoginState.Failed.code]. Native error codes (`PREMIUM_REQUIRED`,
 * `PLAYBACK_REFUSED`, `BAD_CREDENTIALS`, ...) are passed through unchanged.
 */
object LoginErrorCode {
    const val EXPIRED = "expired_token"
    const val DENIED = "access_denied"
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val PORT_IN_USE = "port_in_use"
    const val NO_BROWSER = "no_browser"
    const val ZEROCONF = "zeroconf"
    const val ENGINE_UNAVAILABLE = "engine_unavailable"
}

/**
 * Login flows (docs/ARCHITECTURE.md §9.3). All flows end in [SpotifyEngine.loginWithAccessToken] (or zeroconf).
 *
 * * [startDeviceLogin] (primary): first tries a stored OAuth refresh token silently, then runs the
 *   device authorization grant; the device code is persisted so polling resumes after process
 *   death when the login screen calls [startDeviceLogin] again.
 * * [startBrowserLogin] (fallback): PKCE with a 127.0.0.1 loopback redirect.
 * * [startZeroconfLogin]: "use another device" (Spotify Connect hand-over on the LAN).
 *
 * Only one flow runs at a time; starting one stops the others. Flows run in the app scope so they
 * survive configuration changes. The login screen reports its visibility:
 * * [onLoginScreenHidden] (app in the background, screen left): zeroconf advertising stops, and
 *   device-code polling pauses until [onLoginScreenShown] (the code stays persisted, so it
 *   resumes where it was, e.g. after approving it in the browser). The PKCE flow keeps waiting:
 *   the browser is in front of the app on purpose.
 * * [onLoginScreenLeft] (activity finished): every flow that has no token yet is cancelled.
 *
 * [LoginState.Success] is reset to [LoginState.Idle] whenever the engine reports logged out
 * (logout, rejected credentials), so the login screen always shows its options again.
 */
class AuthRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val engine: SpotifyEngine,
    private val credentialStore: CredentialStore,
    httpClient: OkHttpClient,
) {
    private val appContext = context.applicationContext
    private val accounts = SpotifyAccountsClient(httpClient)
    private val pendingStore = PendingDeviceLoginStore(
        file = { File(appContext.noBackupFilesDir, PENDING_DEVICE_LOGIN_FILE) },
        seal = credentialStore::encrypt,
        open = credentialStore::decrypt,
    )

    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state.asStateFlow()

    private enum class FlowKind { DEVICE, BROWSER, ZEROCONF }

    /** Guards the fields below; state writes of a flow are ignored once [flowId] moved on. */
    private val lock = Any()
    private var flowId = 0L
    private var flowKind: FlowKind? = null
    private var flowJob: Job? = null
    private var loopback: LoopbackServer? = null
    private var zeroconf: ZeroconfLogin? = null
    /** The flow got a token: its engine login finishes even without the screen. */
    private var tokenObtained = false
    /** The device flow was paused by [onLoginScreenHidden]; [onLoginScreenShown] resumes it. */
    private var devicePaused = false

    /** Held by [whileLoggingOut]; a flow waits for it before handing a login to the engine. */
    private val logoutGate = Mutex()

    init {
        // A finished login is undone by a logout or by Spotify rejecting the credentials: show
        // the options again instead of a "Connecting" card that never ends. (Success is only set
        // after the engine reported logged in, so this can't race a login in progress.)
        scope.launch {
            combine(engine.isLoggedIn, _state) { loggedIn, state -> !loggedIn && state == LoginState.Success }
                .collect { stale ->
                    if (stale) synchronized(lock) {
                        if (_state.value == LoginState.Success && !engine.isLoggedIn.value) {
                            stopFlowLocked()
                            _state.value = LoginState.Idle
                        }
                    }
                }
        }
    }

    /** Starts the device authorization grant and polls until approved/expired/cancelled. */
    fun startDeviceLogin() {
        synchronized(lock) {
            // Idempotent while running (recomposition, configuration change, screen re-entry).
            if (flowKind == FlowKind.DEVICE && flowJob?.isActive == true) return
            startFlowLocked(FlowKind.DEVICE) { id -> runDeviceLogin(id) }
        }
    }

    /**
     * True when a device code persisted before process death can still be resumed (not about to
     * expire): the login screen then calls [startDeviceLogin] instead of showing the options.
     */
    suspend fun hasPendingDeviceLogin(): Boolean = withContext(Dispatchers.IO) {
        val pending = pendingStore.load() ?: return@withContext false
        pending.expiresAtMs - System.currentTimeMillis() > MIN_REMAINING_MS
    }

    /** Opens the device-code approval page in a Custom Tab (call after [startDeviceLogin]). */
    fun openApprovalPage(activity: Activity) {
        val awaiting = _state.value as? LoginState.AwaitingApproval ?: return
        val url = awaiting.verificationUriComplete ?: awaiting.verificationUri
        // Without a browser the code can still be approved from another device; keep polling.
        if (!BrowserLauncher.open(activity, url)) Log.w(TAG, "No browser to open the approval page")
    }

    /** Fallback: PKCE in a Custom Tab with a 127.0.0.1 loopback redirect. */
    fun startBrowserLogin(activity: Activity) {
        val activityRef = WeakReference(activity)
        synchronized(lock) {
            startFlowLocked(FlowKind.BROWSER) { id -> runBrowserLogin(id, activityRef) }
        }
    }

    /** Handles `spotifygood://auth...` redirects; returns true if consumed. */
    fun handleRedirect(uri: Uri): Boolean =
        uri.scheme.equals(REDIRECT_SCHEME, ignoreCase = true) && uri.host.equals(REDIRECT_HOST, ignoreCase = true)
    // Nothing else to do: the loopback server already holds the code; the intent only brought
    // the app to the foreground (closing the Custom Tab on top of our task).

    fun startZeroconfLogin(context: Context) {
        val login = ZeroconfLogin(context.applicationContext, engine)
        synchronized(lock) {
            startFlowLocked(FlowKind.ZEROCONF) { id ->
                logoutGate.withLock {}
                setState(id, LoginState.WaitingForDevice)
                login.run()
                setState(id, LoginState.Success)
            }
            zeroconf = login
        }
    }

    /** Stops any flow (polling, loopback server, zeroconf) and forgets a pending device code. */
    fun cancel() {
        synchronized(lock) {
            stopFlowLocked()
            _state.value = LoginState.Idle
        }
        scope.launch(Dispatchers.IO) { clearPendingDeviceLogin() }
    }

    /**
     * The login screen became visible (also on its first composition): resumes a device login
     * that was paused, or one persisted before process death.
     */
    fun onLoginScreenShown() {
        scope.launch {
            val idle = synchronized(lock) {
                if (devicePaused && flowJob?.isActive != true) {
                    // Resumes the persisted code (or requests a new one if it never got one).
                    startDeviceLogin()
                    return@launch
                }
                _state.value == LoginState.Idle && flowJob?.isActive != true
            }
            if (idle && hasPendingDeviceLogin()) {
                synchronized(lock) {
                    if (_state.value == LoginState.Idle && flowJob?.isActive != true) startDeviceLogin()
                }
            }
        }
    }

    /**
     * The login screen is no longer visible (app in the background, or the screen left
     * composition): stops zeroconf advertising and pauses device-code polling. A flow that
     * already has a token finishes its engine login.
     */
    fun onLoginScreenHidden() {
        synchronized(lock) {
            when (flowKind) {
                FlowKind.ZEROCONF -> if (_state.value == LoginState.WaitingForDevice) {
                    Log.i(TAG, "Login screen hidden: stopping zeroconf")
                    stopFlowLocked()
                    _state.value = LoginState.Idle
                }
                FlowKind.DEVICE -> if (!tokenObtained && flowJob?.isActive == true) {
                    Log.i(TAG, "Login screen hidden: pausing the device login")
                    stopFlowLocked()
                    devicePaused = true
                }
                // The browser is in front of the app on purpose; the loopback server waits.
                FlowKind.BROWSER, null -> Unit
            }
        }
    }

    /** The login screen is gone for good (activity finished): cancels flows without a token. */
    fun onLoginScreenLeft() {
        val abandon = synchronized(lock) { devicePaused || (flowKind != null && !tokenObtained) }
        if (abandon) cancel()
    }

    /**
     * Logout: runs [block] (the wipe) while no flow can hand a new login to the engine, after
     * stopping every flow and forgetting the pending device code. A login started meanwhile
     * waits until the wipe is done.
     */
    suspend fun <T> whileLoggingOut(block: suspend () -> T): T = logoutGate.withLock {
        synchronized(lock) {
            stopFlowLocked()
            _state.value = LoginState.Idle
        }
        withContext(Dispatchers.IO) { clearPendingDeviceLogin() }
        block()
    }

    private fun clearPendingDeviceLogin() {
        try {
            pendingStore.clear()
        } catch (e: Exception) {
            Log.w(TAG, "Clearing the pending device login failed", e)
        }
    }

    // ---- flow management --------------------------------------------------------------------

    private fun startFlowLocked(kind: FlowKind, block: suspend (id: Long) -> Unit) {
        stopFlowLocked()
        val id = ++flowId
        flowKind = kind
        tokenObtained = false
        flowJob = scope.launch {
            try {
                block(id)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "$kind login failed: ${t.javaClass.simpleName} ${(t as? NativeException)?.code ?: (t as? OAuthException)?.error ?: ""}")
                setState(id, failureFor(kind, t))
            } finally {
                synchronized(lock) {
                    if (id == flowId) {
                        loopback = null
                        zeroconf = null
                    }
                }
            }
        }
    }

    private fun stopFlowLocked() {
        flowId++
        flowKind = null
        devicePaused = false
        flowJob?.cancel()
        flowJob = null
        loopback?.close()
        loopback = null
        zeroconf?.release()
        zeroconf = null
    }

    private fun setState(id: Long, state: LoginState) {
        synchronized(lock) {
            if (id == flowId) _state.value = state
        }
    }

    private fun markTokenObtained(id: Long) {
        synchronized(lock) {
            if (id == flowId) tokenObtained = true
        }
    }

    // ---- device authorization grant ---------------------------------------------------------

    private suspend fun runDeviceLogin(id: Long) {
        engine.awaitReady()
        if (engine.isLoggedIn.value) {
            // Already logged in (e.g. the login screen re-entered right after success).
            setState(id, LoginState.Success)
            return
        }
        if (tryRefreshLogin(id)) return
        val resumed = withContext(Dispatchers.IO) { pendingStore.load() }
            ?.takeIf { it.expiresAtMs - System.currentTimeMillis() > MIN_REMAINING_MS }
        val login = resumed ?: run {
            setState(id, LoginState.Connecting)
            accounts.requestDeviceAuthorization().also { withContext(Dispatchers.IO) { pendingStore.save(it) } }
        }
        Log.i(TAG, if (resumed != null) "Resuming device login" else "Device login started")
        setState(
            id,
            LoginState.AwaitingApproval(login.userCode, login.verificationUri, login.verificationUriComplete, login.expiresAtMs),
        )
        var persisted = login
        val poller = DeviceCodePoller(
            poll = accounts::pollDeviceToken,
            clock = System::currentTimeMillis,
            onIntervalChanged = { intervalSec ->
                persisted = persisted.copy(intervalSec = intervalSec)
                withContext(Dispatchers.IO) { pendingStore.save(persisted) }
            },
        )
        val token = try {
            poller.awaitToken(login)
        } catch (e: OAuthException) {
            // Expired, denied or rejected: this code is dead. (Network failures keep it for a resume.)
            withContext(Dispatchers.IO) { pendingStore.clear() }
            throw e
        }
        withContext(Dispatchers.IO) { pendingStore.clear() }
        markTokenObtained(id)
        completeLogin(id, token)
    }

    /** Silent re-login with a stored refresh token (e.g. after Spotify rejected the stored credentials). */
    private suspend fun tryRefreshLogin(id: Long): Boolean {
        val refreshToken = withContext(Dispatchers.IO) { credentialStore.loadRefreshToken() } ?: return false
        setState(id, LoginState.Connecting)
        val token = try {
            accounts.refresh(refreshToken)
        } catch (e: OAuthException) {
            Log.i(TAG, "Stored refresh token rejected (${e.error})")
            if (e.error == OAuthException.INVALID_GRANT || e.error.startsWith("invalid")) {
                withContext(Dispatchers.IO) { credentialStore.saveRefreshToken(null) }
            }
            return false
        } catch (e: IOException) {
            Log.i(TAG, "Refreshing the token failed: ${e.javaClass.simpleName}")
            return false
        }
        markTokenObtained(id)
        return try {
            completeLogin(id, token)
            true
        } catch (e: NativeException) {
            // Logged in after all (the account's credentials exist): its account problem is final
            // and shown by the account screens.
            if (isAccountRejection(e.code) && engine.isLoggedIn.value) throw e
            // Otherwise the interactive login follows, where another account can be chosen
            // (completeLogin dropped the token of an account the engine refused).
            Log.i(TAG, "Login with refreshed token failed (${e.code}), falling back to device login")
            synchronized(lock) {
                if (id == flowId) tokenObtained = false
            }
            false
        }
    }

    // ---- PKCE fallback ------------------------------------------------------------------------

    private suspend fun runBrowserLogin(id: Long, activityRef: WeakReference<Activity>) {
        val pkce = Pkce.generate()
        val server = withContext(Dispatchers.IO) { LoopbackServer.bind(LOOPBACK_PORTS) }
        synchronized(lock) {
            if (id != flowId) {
                server.close()
                return
            }
            loopback = server
        }
        server.use {
            val redirectUri = server.redirectUri
            val authorizeUrl = SpotifyOAuth.authorizeUrl(redirectUri, pkce.challenge, pkce.state)
            setState(id, LoginState.WaitingForBrowser)
            val opened = withContext(Dispatchers.Main) {
                val activity = activityRef.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                activity != null && BrowserLauncher.open(activity, authorizeUrl)
            }
            if (!opened) throw BrowserUnavailableException()
            Log.i(TAG, "Browser login started on port ${server.port}")
            when (val result = server.awaitResult(pkce.state, LOOPBACK_TIMEOUT_MS, loopbackPage())) {
                is LoopbackServer.Result.Code -> {
                    setState(id, LoginState.Connecting)
                    val token = accounts.exchangeAuthorizationCode(result.code, redirectUri, pkce.verifier)
                    markTokenObtained(id)
                    completeLogin(id, token)
                }
                is LoopbackServer.Result.Error -> throw OAuthException(result.error)
            }
        }
    }

    private fun loopbackPage() = LoopbackServer.Page(
        title = appContext.getString(R.string.foundation_login_page_title),
        successMessage = appContext.getString(R.string.foundation_login_page_success),
        failureMessage = appContext.getString(R.string.foundation_login_page_failure),
        linkText = appContext.getString(R.string.foundation_login_page_link),
    )

    // ---- common -------------------------------------------------------------------------------

    private suspend fun completeLogin(id: Long, token: TokenResponse) {
        setState(id, LoginState.Connecting)
        // Stored before the engine login so a transient engine failure can retry without
        // re-approving (startDeviceLogin tries the refresh token first).
        // A logout still wiping the previous account finishes first.
        logoutGate.withLock {}
        token.refreshToken?.let { refreshToken ->
            withContext(Dispatchers.IO) { credentialStore.saveRefreshToken(refreshToken) }
        }
        try {
            engine.loginWithAccessToken(token.accessToken, System.currentTimeMillis() + token.expiresIn * 1000)
        } catch (e: NativeException) {
            if (!keepsRefreshToken(e.code, engine.isLoggedIn.value)) {
                // This account can't use the app (not Premium, refused, rejected): a later
                // "Log in" must not silently log it in again instead of offering a new code.
                Log.i(TAG, "Login refused (${e.code}); forgetting its refresh token")
                withContext(NonCancellable + Dispatchers.IO) { credentialStore.saveRefreshToken(null) }
            }
            throw e
        }
        setState(id, LoginState.Success)
    }

    private fun failureFor(kind: FlowKind, t: Throwable): LoginState.Failed {
        fun failed(res: Int, code: String) = LoginState.Failed(appContext.getString(res), code)
        return when (t) {
            is OAuthException -> when (t.error) {
                OAuthException.EXPIRED_TOKEN -> failed(R.string.foundation_login_error_expired, LoginErrorCode.EXPIRED)
                OAuthException.ACCESS_DENIED -> failed(R.string.foundation_login_error_denied, LoginErrorCode.DENIED)
                else -> LoginState.Failed(appContext.getString(R.string.foundation_login_error_generic, t.error), t.error)
            }
            is NativeException -> when (t.code) {
                NativeErrorCode.PREMIUM_REQUIRED -> failed(R.string.foundation_login_error_premium, t.code)
                NativeErrorCode.PLAYBACK_REFUSED -> failed(R.string.foundation_login_error_playback_refused, t.code)
                NativeErrorCode.BAD_CREDENTIALS -> failed(R.string.foundation_login_error_rejected, t.code)
                else -> if (!NativeStatus.isAvailable) {
                    failed(R.string.foundation_login_error_engine, LoginErrorCode.ENGINE_UNAVAILABLE)
                } else if (kind == FlowKind.ZEROCONF) {
                    failed(R.string.foundation_login_error_zeroconf, LoginErrorCode.ZEROCONF)
                } else if (t.isNetwork) {
                    failed(R.string.foundation_login_error_network, LoginErrorCode.NETWORK)
                } else {
                    LoginState.Failed(appContext.getString(R.string.foundation_login_error_generic, t.code), t.code)
                }
            }
            is BindException -> failed(R.string.foundation_login_error_port_in_use, LoginErrorCode.PORT_IN_USE)
            is BrowserUnavailableException -> failed(R.string.foundation_login_error_no_browser, LoginErrorCode.NO_BROWSER)
            is InterruptedIOException -> failed(R.string.foundation_login_error_timeout, LoginErrorCode.TIMEOUT)
            is IOException -> failed(R.string.foundation_login_error_network, LoginErrorCode.NETWORK)
            else -> LoginState.Failed(appContext.getString(R.string.foundation_login_error_generic, t.javaClass.simpleName), null)
        }
    }

    private class BrowserUnavailableException : Exception("No browser available")

    private companion object {
        const val TAG = "AuthRepository"
        const val REDIRECT_SCHEME = "spotifygood"
        const val REDIRECT_HOST = "auth"
        const val PENDING_DEVICE_LOGIN_FILE = "device_login.bin"
        /** A persisted code with less time left than this is not worth resuming. */
        const val MIN_REMAINING_MS = 30_000L
        const val LOOPBACK_TIMEOUT_MS = 5 * 60_000L
    }
}

/** Engine refusals of the account itself (not of one attempt). */
internal fun isAccountRejection(code: String?): Boolean =
    code == NativeErrorCode.PREMIUM_REQUIRED || code == NativeErrorCode.PLAYBACK_REFUSED || code == NativeErrorCode.BAD_CREDENTIALS

/**
 * Whether the refresh token of a login that failed with [code] is kept: yes for transient
 * failures (a retry needs no new approval) and while the engine stays logged in; no when the
 * engine refused a logged-out account, or every later "Log in" would silently reuse it.
 */
internal fun keepsRefreshToken(code: String?, loggedIn: Boolean): Boolean = loggedIn || !isAccountRejection(code)
