package com.taehagen.spotifygood.engine

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.auth.KeystoreUnavailableException
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.data.settings.toEngineSettings
import com.taehagen.spotifygood.model.EngineSettings
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.model.SessionEvent
import com.taehagen.spotifygood.model.SessionState
import com.taehagen.spotifygood.model.StoredCredentials
import com.taehagen.spotifygood.model.User
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
 *
 * Reconnect/backoff is done natively; this class only re-issues `session.start` when the network
 * comes back (or a holder is re-acquired) after the session ended in a retryable error.
 *
 * [EngineState.error] carries account-level problems (`PREMIUM_REQUIRED`, `PLAYBACK_REFUSED`)
 * stickily until [clearError], logout or a new login, so the UI can show dedicated screens.
 */
class SpotifyEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val rpc: NativeRpc,
    private val events: NativeEvents,
    private val settings: SettingsRepository,
    private val credentialStore: CredentialStore,
    private val audioSink: AudioSinkBridge,
) {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private val networkMonitor = NetworkMonitor(appContext)
    private val deviceName = defaultDeviceName()

    // ---- public state (all mirrors of _state, written together under stateLock) ---------------

    private val stateLock = Any()
    private val _state = MutableStateFlow(EngineState())
    private val _loggedIn = MutableStateFlow(false)
    private val _user = MutableStateFlow<User?>(null)
    private val _online = MutableStateFlow(false)
    private val _networkAvailable = MutableStateFlow(true)
    private val _running = MutableStateFlow(false)

    val state: StateFlow<EngineState> = _state.asStateFlow()
    val isLoggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()
    val user: StateFlow<User?> = _user.asStateFlow()
    /** True while the native session is ONLINE. */
    val isOnline: StateFlow<Boolean> = _online.asStateFlow()
    val isNetworkAvailable: StateFlow<Boolean> = _networkAvailable.asStateFlow()
    /** True while the native session is started (any state but STOPPED); listeners may register. */
    val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    // ---- holders -------------------------------------------------------------------------------

    private val holderLock = Any()
    private val holderCounts = IntArray(HolderType.entries.size)
    private var holderTotal = 0

    /** Number of holders currently held. */
    val holderCount: Int get() = synchronized(holderLock) { holderTotal }

    // ---- lifecycle; mutated under [lifecycle] (volatile ones are also read outside) -------------

    private val lifecycle = Mutex()
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var credentialsLoaded = false
    @Volatile private var credentials: StoredCredentials? = null
    /** Bumped whenever new reusable credentials were persisted (login waits for this). */
    private val credentialsVersion = MutableStateFlow(0)
    @Volatile private var running = false
    @Volatile private var loginPending = false
    /** Incremented on every start/stop; results of older `session.start` calls are ignored. */
    @Volatile private var generation = 0L
    @Volatile private var accountError: NativeErrorInfo? = null
    private var stopTimer: Job? = null
    private var runningJob: Job? = null
    private var startCall: Deferred<Unit>? = null
    @Volatile private var lastSentNetwork: NetworkStatus? = null
    @Volatile private var lastSentSettings: EngineSettings? = null
    @Volatile private var offlineIndexPushed = false
    @Volatile private var offlineIndexProvider: (suspend () -> List<OfflineTrackRecord>)? = null

    init {
        launchSafe("load-credentials") {
            try {
                val stored = try {
                    loadStoredCredentials()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Nothing was deleted: the next process start reads them again.
                    Log.e(TAG, "Loading credentials failed", e)
                    null
                }
                lifecycle.withLock {
                    if (!credentialsLoaded) {
                        credentials = stored
                        credentialsLoaded = true
                    }
                    val loggedIn = credentials != null
                    updateState { it.copy(loggedIn = it.loggedIn || loggedIn) }
                    Log.i(TAG, "Credentials loaded (loggedIn=$loggedIn)")
                    reconcileLocked(acquired = false)
                }
            } finally {
                ready.complete(Unit)
            }
        }
        launchSafe("session-events") { events.session.collect { onSessionEvent(it) } }
        launchSafe("credential-events") { events.credentials.collect { onCredentials(it) } }
        launchSafe("error-events") { events.errors.collect { onErrorEvent(it) } }
    }

    /**
     * Reads the stored credentials. A Keystore that is briefly unavailable (keystore2 busy right
     * after boot) is retried; CredentialStore never deletes anything for such a failure.
     */
    private suspend fun loadStoredCredentials(): StoredCredentials? {
        var delayMs = CREDENTIALS_RETRY_DELAY_MS
        repeat(CREDENTIALS_LOAD_ATTEMPTS - 1) { attempt ->
            try {
                return withContext(Dispatchers.IO) { credentialStore.loadCredentials() }
            } catch (e: KeystoreUnavailableException) {
                Log.w(TAG, "Credentials not readable yet (attempt ${attempt + 1}), retrying")
            } catch (e: IOException) {
                Log.w(TAG, "Credentials not readable yet (attempt ${attempt + 1}): ${e.javaClass.simpleName}, retrying")
            }
            delay(delayMs)
            delayMs *= 3
        }
        return withContext(Dispatchers.IO) { credentialStore.loadCredentials() }
    }

    /** Supplies decrypted download records pushed to `offline.setIndex` whenever the engine starts. */
    fun setOfflineIndexProvider(provider: suspend () -> List<OfflineTrackRecord>) {
        offlineIndexProvider = provider
    }

    fun acquire(type: HolderType): EngineHolder {
        synchronized(holderLock) {
            holderCounts[type.ordinal]++
            holderTotal++
        }
        Log.d(TAG, "acquire $type")
        requestReconcile(acquired = true)
        return Holder(type)
    }

    /** Suspends until the stored credentials have been read; afterwards [isLoggedIn] is accurate. */
    suspend fun awaitReady() {
        ready.await()
    }

    /** Suspends until ONLINE (acquire a holder first) or the timeout/error; returns success. */
    suspend fun awaitOnline(timeoutMs: Long = 30_000): Boolean {
        val online = withTimeoutOrNull(timeoutMs) {
            ready.await()
            combine(state, settings.settings) { s, prefs -> s to prefs.offlineMode }
                .first { (s, offlineMode) -> s.session == SessionState.ONLINE || offlineMode || cannotGoOnline(s) }
                .first.session == SessionState.ONLINE
        }
        return online == true
    }

    /** First login: hands the OAuth access token to the engine; reusable credentials follow via events. */
    suspend fun loginWithAccessToken(accessToken: String, expiresAtMs: Long) {
        ready.await()
        ensureNativeAvailable()
        disableOfflineModeForLogin()
        val holder = acquire(HolderType.UI)
        var versionBefore = credentialsVersion.value
        var loggedIn = false
        try {
            val start = lifecycle.withLock {
                loginPending = true
                accountError = null
                if (running) stopLocked()
                versionBefore = credentialsVersion.value
                startLocked(credentials = null, accessToken = accessToken)
            }
            var failure: NativeException? = null
            val completed = try {
                withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
                    awaitStart(start)
                    callQuietly(
                        "session.setOAuthToken",
                        buildJsonObject {
                            put("accessToken", accessToken)
                            put("expiresAtMs", expiresAtMs)
                        },
                    )
                    credentialsVersion.first { it > versionBefore }
                    true
                } ?: false
            } catch (e: NativeException) {
                failure = e
                false
            }
            val gotCredentials = credentialsVersion.value > versionBefore
            when {
                completed -> loggedIn = true
                gotCredentials && failure?.code != NativeErrorCode.BAD_CREDENTIALS -> {
                    // Spotify accepted the login (reusable credentials exist); what failed is the
                    // session afterwards. Premium is reported via state.error; network problems
                    // recover on their own.
                    loggedIn = true
                    if (failure?.code == NativeErrorCode.PREMIUM_REQUIRED) {
                        markLoggedIn()
                        setAccountError(failure.info)
                        throw failure
                    }
                }
                else -> throw failure ?: NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "Timed out waiting for Spotify"))
            }
            markLoggedIn()
            Log.i(TAG, "Logged in with access token")
        } finally {
            finishLogin(loggedIn, discardNewCredentials = !loggedIn && credentialsVersion.value > versionBefore)
            holder.release()
        }
    }

    /** Login by letting another Spotify app on the LAN hand over credentials (Spotify Connect zeroconf). */
    suspend fun loginWithZeroconf(timeoutMs: Long = 180_000) {
        ready.await()
        ensureNativeAvailable()
        val result = try {
            withTimeout(timeoutMs + ZEROCONF_GRACE_MS) {
                rpc.call<ZeroconfResult>("session.zeroconfLogin", buildJsonObject { put("timeoutMs", timeoutMs) })
            }
        } catch (e: TimeoutCancellationException) {
            throw NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "No Spotify app handed over a login"))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw t.asNativeException()
        }
        disableOfflineModeForLogin()
        withContext(Dispatchers.IO) { credentialStore.saveCredentials(result.credentials) }
        val holder = acquire(HolderType.UI)
        var loggedIn = true
        try {
            val start = lifecycle.withLock {
                loginPending = true
                accountError = null
                credentials = result.credentials
                credentialsLoaded = true
                credentialsVersion.update { it + 1 }
                updateState { it.copy(loggedIn = true) }
                if (running) stopLocked()
                startLocked(credentials = result.credentials, accessToken = null)
            }
            val failure = try {
                withTimeoutOrNull(LOGIN_TIMEOUT_MS) { awaitStart(start) }
                null
            } catch (e: NativeException) {
                e
            }
            when (failure?.code) {
                null -> Log.i(TAG, "Logged in via zeroconf")
                NativeErrorCode.BAD_CREDENTIALS -> {
                    loggedIn = false
                    throw failure
                }
                NativeErrorCode.PREMIUM_REQUIRED -> {
                    setAccountError(failure.info)
                    throw failure
                }
                // Credentials are valid; the session recovers once the network allows.
                else -> Log.w(TAG, "Zeroconf login: session start failed (${failure.code}), staying logged in")
            }
        } finally {
            finishLogin(loggedIn, discardNewCredentials = !loggedIn)
            holder.release()
        }
    }

    /**
     * Logs out: stops the engine, wipes credentials and user data (downloads included).
     * Not cancellable: a half-done logout would leave the account behind.
     */
    suspend fun logout(): Unit = withContext(NonCancellable) {
        lifecycle.withLock {
            loginPending = false
            stopTimer?.cancel()
            stopTimer = null
            runningJob?.cancel()
            runningJob = null
            generation++
            // Forget the stored credentials first, so a process death during the native
            // teardown can't log the account back in on the next start.
            withContext(Dispatchers.IO) { credentialStore.clear() }
            credentials = null
            credentialsLoaded = true
            // Natively: forgets the account, stops the session, deletes its caches. It always
            // runs to the end (a timeout here only stops the wait) and a later session.start
            // waits for it.
            callQuietly("session.logout", timeoutMs = LOGOUT_TIMEOUT_MS)
            startCall?.cancel()
            startCall = null
            networkMonitor.stop()
            audioSink.release()
            running = false
            offlineIndexPushed = false
            lastSentNetwork = null
            lastSentSettings = null
            accountError = null
            updateState { EngineState(networkAvailable = it.networkAvailable) }
            _running.value = false
        }
        Log.i(TAG, "Logged out")
    }

    /** Re-issues `session.start` after a retryable error (e.g. from a "Retry" button). */
    fun retry() {
        launchSafe("retry") {
            lifecycle.withLock {
                if (running && !loginPending && holderCount > 0 && needsRestart(_state.value)) {
                    restartLocked()
                } else {
                    reconcileLocked(acquired = false)
                }
            }
        }
    }

    /** Dismisses the sticky account error (PREMIUM_REQUIRED / PLAYBACK_REFUSED). */
    fun clearError() {
        accountError = null
        updateState { it.copy(error = null) }
    }

    // ---- holders ---------------------------------------------------------------------------

    private inner class Holder(override val type: HolderType) : EngineHolder {
        private val released = AtomicBoolean(false)

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(holderLock) {
                holderCounts[type.ordinal]--
                holderTotal--
            }
            Log.d(TAG, "release $type")
            requestReconcile(acquired = false)
        }

        override fun toString(): String = "EngineHolder($type, released=${released.get()})"
    }

    private fun requestReconcile(acquired: Boolean) {
        launchSafe("reconcile") { lifecycle.withLock { reconcileLocked(acquired) } }
    }

    /** Brings the native session in line with the holders and credentials. Caller holds [lifecycle]. */
    private suspend fun reconcileLocked(acquired: Boolean) {
        if (holderCount > 0) {
            stopTimer?.cancel()
            stopTimer = null
            if (!credentialsLoaded || loginPending) return
            val creds = credentials ?: return
            if (!running) {
                startLocked(credentials = creds, accessToken = null)
            } else if (acquired && needsRestart(_state.value)) {
                restartLocked()
            }
        } else if (running && stopTimer == null && !loginPending) {
            stopTimer = launchSafe("idle-stop") {
                delay(IDLE_GRACE_MS)
                lifecycle.withLock {
                    // Clear first: stopLocked() cancels stopTimer, which must not be this job.
                    stopTimer = null
                    if (holderCount == 0 && !loginPending) {
                        Log.i(TAG, "No holders for ${IDLE_GRACE_MS}ms, stopping the session")
                        stopLocked()
                    }
                }
            }
        }
    }

    // ---- start / stop (caller holds [lifecycle]) --------------------------------------------

    private suspend fun startLocked(credentials: StoredCredentials?, accessToken: String?): Deferred<Unit> {
        stopTimer?.cancel()
        stopTimer = null
        if (!NativeStatus.isAvailable) {
            val info = nativeUnavailableInfo()
            updateState { it.copy(session = SessionState.ERROR, error = info) }
            return CompletableDeferred<Unit>().apply { completeExceptionally(NativeException(info)) }
        }
        val gen = ++generation
        running = true
        _running.value = true
        offlineIndexPushed = false
        networkMonitor.start()
        val network = networkMonitor.status.value
        lastSentNetwork = network
        updateState {
            it.copy(session = SessionState.CONNECTING, error = accountError, nextRetryMs = null, networkAvailable = network.available)
        }
        callQuietly("session.setNetworkAvailable", networkArgs(network))
        val engineSettings = settings.awaitLoaded().toEngineSettings(network.metered, deviceName)
        lastSentSettings = engineSettings
        runningJob?.cancel()
        runningJob = launchRunningCollectors()
        val args = SessionStartArgs(
            credentials = credentials,
            accessToken = accessToken,
            settings = engineSettings,
            initialVolume = initialVolume(),
        )
        Log.i(TAG, "session.start (${if (accessToken != null) "access token" else "stored credentials"})")
        return scope.async { runStart(gen, args) }.also { startCall = it }
    }

    /** Re-issues `session.start` with the stored credentials while running. */
    private suspend fun restartLocked() {
        val creds = credentials ?: return
        startCall?.cancel()
        val gen = ++generation
        val network = networkMonitor.status.value
        val engineSettings = lastSentSettings ?: settings.awaitLoaded().toEngineSettings(network.metered, deviceName)
        updateState { it.copy(session = SessionState.CONNECTING, error = accountError, nextRetryMs = null) }
        val args = SessionStartArgs(credentials = creds, settings = engineSettings, initialVolume = initialVolume())
        Log.i(TAG, "session.start (retry)")
        startCall = scope.async { runStart(gen, args) }
    }

    private suspend fun stopLocked(releasePlayer: Boolean = false) {
        stopTimer?.cancel()
        stopTimer = null
        if (!running) return
        generation++
        runningJob?.cancel()
        runningJob = null
        Log.i(TAG, "session.stop")
        callQuietly("session.stop", buildJsonObject { put("releasePlayer", releasePlayer) }, STOP_TIMEOUT_MS)
        startCall?.cancel()
        startCall = null
        networkMonitor.stop()
        audioSink.release()
        running = false
        offlineIndexPushed = false
        lastSentNetwork = null
        lastSentSettings = null
        updateState { it.copy(session = SessionState.STOPPED, error = accountError, nextRetryMs = null) }
        _running.value = false
    }

    private suspend fun runStart(gen: Long, args: SessionStartArgs) {
        try {
            rpc.callUnit("session.start", rpc.args(args))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val error = t.asNativeException()
            if (gen == generation) onStartFailed(error)
            throw error
        }
        if (gen == generation) {
            updateState { if (it.session == SessionState.CONNECTING) it.copy(session = SessionState.ONLINE, error = accountError) else it }
        }
    }

    private fun onStartFailed(error: NativeException) {
        Log.w(TAG, "session.start failed: ${error.code}")
        if (error.code == NativeErrorCode.CANCELLED) return
        if (error.code == NativeErrorCode.PREMIUM_REQUIRED || error.code == NativeErrorCode.PLAYBACK_REFUSED) {
            setAccountError(error.info)
        }
        // The native state event may have been conflated away; make sure we don't stay CONNECTING.
        updateState {
            if (it.session == SessionState.CONNECTING) it.copy(session = SessionState.ERROR, error = accountError ?: error.info) else it
        }
        if (error.code == NativeErrorCode.BAD_CREDENTIALS && !loginPending) onBadCredentials(error.info)
    }

    // ---- while running ----------------------------------------------------------------------

    private fun launchRunningCollectors(): Job = scope.launch {
        launchLogged("network") {
            networkMonitor.status.collect { onNetworkStatus(it) }
        }
        launchLogged("settings") {
            combine(settings.persisted, networkMonitor.status) { prefs, network -> prefs.toEngineSettings(network.metered, deviceName) }
                .distinctUntilChanged()
                .collect { engineSettings ->
                    if (engineSettings == lastSentSettings) return@collect
                    lastSentSettings = engineSettings
                    Log.d(TAG, "session.updateSettings")
                    callQuietly("session.updateSettings", rpc.args(engineSettings))
                }
        }
        launchLogged("offline-index") {
            combine(state.map { it.session }, settings.persisted.map { it.offlineMode }) { session, offlineMode ->
                offlineMode || session == SessionState.ONLINE || session == SessionState.OFFLINE
            }
                .distinctUntilChanged()
                .collect { due -> if (due && !offlineIndexPushed) pushOfflineIndex() }
        }
    }

    private suspend fun onNetworkStatus(status: NetworkStatus) {
        val previous = lastSentNetwork
        updateState { it.copy(networkAvailable = status.available) }
        if (status == previous) return
        lastSentNetwork = status
        Log.d(TAG, "network available=${status.available} metered=${status.metered}")
        callQuietly("session.setNetworkAvailable", networkArgs(status))
        if (status.available && previous?.available == false) {
            lifecycle.withLock {
                if (running && !loginPending && holderCount > 0 && needsRestart(_state.value)) restartLocked()
            }
        }
    }

    private suspend fun pushOfflineIndex() {
        val provider = offlineIndexProvider
        if (provider == null) {
            offlineIndexPushed = true
            return
        }
        val tracks = try {
            withContext(Dispatchers.IO) { provider() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Loading offline records failed", t)
            return
        }
        if (callQuietly("offline.setIndex", rpc.args(OfflineIndexArgs(tracks)), OFFLINE_INDEX_TIMEOUT_MS)) {
            offlineIndexPushed = true
            Log.d(TAG, "Offline index pushed (${tracks.size} tracks)")
        }
    }

    // ---- native events ------------------------------------------------------------------------

    private fun onSessionEvent(event: SessionEvent) {
        // Late events of a session we already stopped must not resurrect its state.
        if (!running && event.state != SessionState.STOPPED) return
        updateState { s ->
            s.copy(
                session = event.state,
                user = event.user ?: s.user,
                nextRetryMs = event.nextRetryMs,
                error = accountError ?: event.error.takeIf { event.state != SessionState.ONLINE },
            )
        }
        if (event.state == SessionState.ERROR) {
            val error = event.error ?: return
            when (error.code) {
                NativeErrorCode.BAD_CREDENTIALS -> if (!loginPending) onBadCredentials(error)
                NativeErrorCode.PREMIUM_REQUIRED, NativeErrorCode.PLAYBACK_REFUSED -> setAccountError(error)
            }
        }
    }

    private fun onErrorEvent(error: NativeErrorInfo) {
        when (error.code) {
            NativeErrorCode.PREMIUM_REQUIRED, NativeErrorCode.PLAYBACK_REFUSED -> setAccountError(error)
            NativeErrorCode.BAD_CREDENTIALS ->
                if (!loginPending && (error.context == null || error.context == "session")) onBadCredentials(error)
        }
    }

    private suspend fun onCredentials(stored: StoredCredentials) {
        lifecycle.withLock {
            if (!loginPending && !_state.value.loggedIn) {
                Log.w(TAG, "Ignoring credentials event while logged out")
                return@withLock
            }
            withContext(Dispatchers.IO) { credentialStore.saveCredentials(stored) }
            credentials = stored
            credentialsLoaded = true
            credentialsVersion.update { it + 1 }
            Log.i(TAG, "Reusable credentials stored")
        }
    }

    /** Spotify rejected the stored credentials: forget them (the refresh token is kept) and stop. */
    private fun onBadCredentials(error: NativeErrorInfo) {
        launchSafe("bad-credentials") {
            lifecycle.withLock {
                if (loginPending || (credentials == null && !_state.value.loggedIn)) return@withLock
                Log.w(TAG, "Stored credentials rejected by Spotify, logging out locally")
                withContext(Dispatchers.IO) { credentialStore.clearCredentials() }
                credentials = null
                updateState { it.copy(loggedIn = false, user = null) }
                stopLocked()
                updateState { it.copy(error = error) }
            }
        }
    }

    private fun setAccountError(error: NativeErrorInfo) {
        accountError = error
        updateState { it.copy(error = error) }
    }

    // ---- login helpers ------------------------------------------------------------------------

    private suspend fun markLoggedIn() {
        lifecycle.withLock { updateState { it.copy(loggedIn = true) } }
    }

    /**
     * Ends a login attempt. On failure the half-started session is torn down and credentials that
     * arrived during the attempt are dropped; credentials from before the attempt (re-login while
     * logged in) stay and the session restarts with them.
     */
    private suspend fun finishLogin(success: Boolean, discardNewCredentials: Boolean) {
        withContext(NonCancellable) {
            lifecycle.withLock {
                loginPending = false
                if (success) return@withLock
                if (discardNewCredentials && credentials != null) {
                    withContext(Dispatchers.IO) { credentialStore.clearCredentials() }
                    credentials = null
                }
                stopLocked()
                if (credentials == null) {
                    updateState { it.copy(loggedIn = false) }
                } else {
                    reconcileLocked(acquired = false)
                }
            }
        }
    }

    private suspend fun awaitStart(start: Deferred<Unit>) {
        try {
            start.await()
        } catch (e: CancellationException) {
            // Our own cancellation propagates; the start call being cancelled (logout) is a failure.
            currentCoroutineContext().ensureActive()
            throw NativeException(NativeErrorInfo(NativeErrorCode.CANCELLED, "Login interrupted"))
        }
    }

    private suspend fun disableOfflineModeForLogin() {
        // A login needs the network; offline mode would keep the session from ever going online.
        if (settings.awaitLoaded().offlineMode) settings.update { it.copy(offlineMode = false) }
    }

    private fun ensureNativeAvailable() {
        if (!NativeStatus.isAvailable) throw NativeException(nativeUnavailableInfo())
    }

    // ---- misc -------------------------------------------------------------------------------

    private fun cannotGoOnline(s: EngineState): Boolean =
        !NativeStatus.isAvailable ||
            (!s.loggedIn && !loginPending) ||
            (s.session == SessionState.ERROR && s.error?.code in FATAL_CODES)

    private fun needsRestart(s: EngineState): Boolean =
        s.session == SessionState.STOPPED || (s.session == SessionState.ERROR && s.error?.code !in FATAL_CODES)

    private fun initialVolume(): Int {
        val am = audioManager ?: return CONNECT_VOLUME_MAX / 2
        return try {
            connectVolume(am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        } catch (e: RuntimeException) {
            CONNECT_VOLUME_MAX / 2
        }
    }

    private fun networkArgs(status: NetworkStatus): JsonObject = buildJsonObject {
        put("available", status.available)
        put("metered", status.metered)
    }

    private inline fun updateState(transform: (EngineState) -> EngineState) {
        synchronized(stateLock) {
            val next = transform(_state.value)
            _state.value = next
            _loggedIn.value = next.loggedIn
            _user.value = next.user
            _online.value = next.session == SessionState.ONLINE
            _networkAvailable.value = next.networkAvailable
        }
    }

    /** Native call whose failure is only logged. Returns success. */
    private suspend fun callQuietly(method: String, args: JsonObject = NativeRpc.EMPTY, timeoutMs: Long = RPC_TIMEOUT_MS): Boolean =
        try {
            withTimeout(timeoutMs) { rpc.callUnit(method, args) }
            true
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "$method timed out after ${timeoutMs}ms")
            false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "$method failed: ${t.message}")
            false
        }

    private fun launchSafe(name: String, block: suspend CoroutineScope.() -> Unit): Job = scope.launchLogged(name, block)

    private fun CoroutineScope.launchLogged(name: String, block: suspend CoroutineScope.() -> Unit): Job = launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "$name failed", t)
        }
    }

    @Serializable
    private data class SessionStartArgs(
        val credentials: StoredCredentials? = null,
        val accessToken: String? = null,
        val settings: EngineSettings,
        val initialVolume: Int,
    ) {
        override fun toString(): String = "SessionStartArgs(<redacted>)"
    }

    @Serializable
    private data class ZeroconfResult(val credentials: StoredCredentials)

    @Serializable
    private data class OfflineIndexArgs(val tracks: List<OfflineTrackRecord>)

    companion object {
        const val IDLE_GRACE_MS = 60_000L

        private const val TAG = "SpotifyEngine"
        private const val LOGIN_TIMEOUT_MS = 60_000L
        private const val ZEROCONF_GRACE_MS = 15_000L
        private const val RPC_TIMEOUT_MS = 10_000L
        /**
         * Above the native bound of `session.stop` (10 s, docs/ARCHITECTURE.md §4.2), so the
         * result is normally the real one. A timeout is harmless: the native stop runs to the
         * end and a following `session.start` waits for it.
         */
        private const val STOP_TIMEOUT_MS = 15_000L
        /** `session.logout` = the stop plus deleting the streaming cache. */
        private const val LOGOUT_TIMEOUT_MS = 30_000L
        private const val OFFLINE_INDEX_TIMEOUT_MS = 30_000L
        private const val CREDENTIALS_LOAD_ATTEMPTS = 4
        private const val CREDENTIALS_RETRY_DELAY_MS = 200L
        private val FATAL_CODES = setOf(NativeErrorCode.BAD_CREDENTIALS, NativeErrorCode.PREMIUM_REQUIRED)

        private fun nativeUnavailableInfo() =
            NativeErrorInfo(NativeErrorCode.INTERNAL, "The playback engine could not be loaded", context = "session")
    }
}

/** Largest Spotify Connect / mixer volume. */
internal const val CONNECT_VOLUME_MAX = 65_535

/** Linear, rounded mapping of an Android stream volume index to the Connect range 0..65535. */
internal fun connectVolume(index: Int, maxIndex: Int): Int {
    if (maxIndex <= 0) return 0
    val clamped = index.coerceIn(0, maxIndex).toLong()
    return ((clamped * CONNECT_VOLUME_MAX + maxIndex / 2) / maxIndex).toInt()
}

private fun Throwable.asNativeException(): NativeException =
    this as? NativeException ?: NativeException(NativeErrorInfo(NativeErrorCode.INTERNAL, message ?: javaClass.simpleName))
