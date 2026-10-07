package com.taehagen.spotifygood.playback

import android.util.Log
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.PlaybackSnapshot
import com.taehagen.spotifygood.model.PlaybackSource
import com.taehagen.spotifygood.model.PlaybackStatus
import com.taehagen.spotifygood.model.RepeatMode
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** What to play (maps to `player.load`, docs/ARCHITECTURE.md §6.2). */
data class PlayRequest(
    val contextUri: String? = null,
    val trackUris: List<String>? = null,
    val startUri: String? = null,
    val startIndex: Int? = null,
    val startUid: String? = null,
    val positionMs: Long = 0,
    val shuffle: Boolean? = null,
    val smartShuffle: Boolean? = null,
    val repeat: RepeatMode? = null,
    val play: Boolean = true,
)

/** A failed attempt to start playback while nothing was playing (Media3's player error). */
data class PlaybackFailure(val kind: PlaybackErrorKind, val message: String)

/**
 * Playback commands. All commands are routed natively to the active device (this phone or a
 * remote Connect device). Methods never throw: failures are reported on [errors] (user-visible
 * message) and logged. Commands are serialised in call order.
 *
 * One consumer coroutine executes the queued commands one after the other (each bounded by a
 * timeout, so a lost native reply cannot wedge the queue). Bursty commands (seek, volume) are
 * conflated: a new value replaces a still-queued one of the same kind if nothing was queued in
 * between, so ordering relative to other commands is preserved.
 *
 * With an [environment] (installed by [PlaybackCoordinator]):
 * * commands that start playback (load, play, skip-to, radio) first wait (bounded) while the
 *   session is still starting, so a Bluetooth play, a resumption or Android Auto on a cold engine
 *   reach an Online session; a pause cancels such a waiting command;
 * * while the session is not Online, context loads of playlists / Liked Songs / albums / shows are
 *   turned into loads of their downloads ([OfflineLoads]); nothing downloaded → "not available
 *   offline".
 *
 * Play / resume with no active Connect device (NOT_ACTIVE_DEVICE: cold start, or the last device
 * went away) or without a session (NOT_CONNECTED: the session did not come up in time, or no
 * network; UNAVAILABLE while still connecting) load the last local session from the resume store
 * instead — offline that plays its downloads ([shouldResumeLast]); next / previous / seek then fail
 * silently.
 *
 * The last failure to start playback while nothing played is kept in [failure] (until the next
 * attempt or until something plays), so the media session can publish it as a player error.
 */
class PlayerController internal constructor(
    private val scope: CoroutineScope,
    private val transport: suspend (method: String, args: JsonObject) -> JsonElement,
    private val json: Json,
    private val snapshot: StateFlow<PlaybackSnapshot>,
    private val lastSession: suspend () -> ResumeState?,
) {
    constructor(scope: CoroutineScope, rpc: NativeRpc, playback: PlaybackRepository, resumeStore: ResumeStore) :
        this(scope, { method, args -> rpc.callRaw(method, args) }, rpc.json, playback.snapshot, resumeStore::read)

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Human-readable errors for snackbars. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)

    /**
     * The last failure to start playback while nothing was playing; cleared by the next attempt to
     * start playback, by [clearFailure] and as soon as something plays.
     */
    val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    /**
     * Invoked (on the calling thread) before a command that may start local playback, so the
     * [PlaybackService] is running before audio starts. Installed by [PlaybackCoordinator].
     */
    @Volatile var onPlaybackRequested: (() -> Unit)? = null

    /** Message source for [errors]; replaced with the resource-backed one by [PlaybackCoordinator]. */
    @Volatile var errorMessages: PlaybackErrorMessages = PlaybackErrorMessages.Fallback

    /** Engine / downloads knowledge for cold starts and offline loads; installed by [PlaybackCoordinator]. */
    @Volatile var environment: PlaybackEnvironment? = null

    private class Command(
        val name: String,
        val conflateKey: String?,
        val timeoutMs: Long,
        /** Error codes that are expected here (nothing to act on): logged only, no snackbar. */
        val quietCodes: Set<String>,
        /** Starts playback: waits for a starting session, and its failure is kept in [failure]. */
        val startsPlayback: Boolean,
        /** Replaced (under [lock]) when a newer value of the same conflatable command arrives. */
        var block: suspend () -> Unit,
    ) {
        val done = CompletableDeferred<Boolean>()
        /** Completed by a pause while this command waits for the session ([awaitSession]). */
        val abort = CompletableDeferred<Unit>()
        var started = false
        /** Enqueue order (guarded by [lock]); see [cancelStartsBefore]. */
        var seq = 0L
        /**
         * `player.load` only: what to load. A play that follows a paused load before it is sent
         * merges into it (play = true) instead of racing it (guarded by [lock]).
         */
        var request: PlayRequest? = null
        /** The load request was handed to the engine (no more merging; guarded by [lock]). */
        var sent = false
    }

    /** How the last start-playback command ended (queue consumer only). */
    private class StartOutcome(val wasLoad: Boolean, val ok: Boolean, val atNanos: Long)

    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val lock = Any()
    /** The most recently queued command (guarded by [lock]); only it may absorb a newer value. */
    private var lastQueued: Command? = null
    /** The command currently waiting for the session to start (guarded by [lock]). */
    private var waitingForSession: Command? = null
    /** Sequence number of the next queued command (guarded by [lock]). */
    private var nextSeq = 0L
    /**
     * Start-playback commands queued before this sequence number were cancelled by a pause while
     * the session was starting (guarded by [lock]): the pause wins over every pending start.
     */
    private var cancelStartsBefore = 0L
    /** The most recent `player.load` (guarded by [lock]). */
    private var latestLoad: Command? = null
    @Volatile private var lastStart: StartOutcome? = null

    /** Optimistic tri-state values so quick repeated taps cycle correctly before the snapshot catches up. */
    @Volatile private var pendingShuffle: Pending<ShuffleMode>? = null
    @Volatile private var pendingRepeat: Pending<RepeatMode>? = null

    private data class Pending<T>(val value: T, val atNanos: Long)

    init {
        scope.launch {
            for (command in queue) {
                synchronized(lock) {
                    command.started = true
                    if (lastQueued === command) lastQueued = null
                }
                execute(command)
            }
        }
        scope.launch {
            // Something plays: whatever failed before is no longer the state to show.
            snapshot.collect { if (it.isPlayingOrLoading()) _failure.value = null }
        }
    }

    fun play(request: PlayRequest) {
        playAsync(request)
    }

    fun playContext(contextUri: String, startUri: String? = null, shuffle: Boolean? = null) {
        play(PlayRequest(contextUri = contextUri, startUri = startUri, shuffle = shuffle))
    }

    fun playTracks(trackUris: List<String>, startIndex: Int = 0) {
        if (trackUris.isEmpty()) return
        play(PlayRequest(trackUris = trackUris, startIndex = startIndex.coerceIn(0, trackUris.lastIndex)))
    }

    fun resume() {
        resumeAsync()
    }

    fun pause() {
        pauseAsync()
    }

    fun togglePlayPause() {
        if (snapshot.value.isPlayingOrLoading()) pauseLike("player.togglePlay") else sendResuming("player.togglePlay")
    }

    fun next() {
        nextAsync()
    }

    fun previous() {
        previousAsync()
    }

    fun seekTo(positionMs: Long) {
        seekAsync(positionMs)
    }

    fun setShuffle(enabled: Boolean) {
        setShuffleAsync(enabled)
    }

    fun setSmartShuffle(enabled: Boolean) {
        setSmartShuffleAsync(enabled)
    }

    /** off → shuffle → smart shuffle → off (smart shuffle skipped when unavailable). */
    fun cycleShuffle() {
        cycleShuffleAsync()
    }

    fun setRepeat(mode: RepeatMode) {
        setRepeatAsync(mode)
    }

    /** off → context → track → off. */
    fun cycleRepeat() {
        val s = snapshot.value
        if (!s.restrictions.canToggleRepeat) return
        val current = pendingRepeat.validOr(s.repeat)
        setRepeat(PlaybackModes.nextRepeat(current))
    }

    /** Connect volume of the active device, 0..65535. */
    fun setVolume(volume: Int) {
        setVolumeAsync(volume)
    }

    fun addToQueue(uri: String) {
        addToQueue(listOf(uri))
    }

    fun addToQueue(uris: List<String>) {
        addToQueueAsync(uris)
    }

    fun removeFromQueue(uid: String) {
        removeFromQueueAsync(uid)
    }

    fun moveInQueue(uid: String, toIndex: Int) {
        moveInQueueAsync(uid, toIndex)
    }

    fun clearQueue() {
        send("queue.clear")
    }

    fun skipTo(uid: String) {
        skipToAsync(uid)
    }

    /** Starts a radio station seeded by [uri] (track/artist/album/playlist). */
    fun startRadio(uri: String) {
        onPlaybackRequested?.invoke()
        enqueue("catalog.radio", timeoutMs = LOAD_TIMEOUT_MS, startsPlayback = true) {
            val radio = json.decodeFromJsonElement<RadioContext>(transport("catalog.radio", buildJsonObject { put("uri", uri) }))
            // The station is normally a playlist context; the fallback station may be a bare track list.
            val request = when {
                radio.contextUri != null -> PlayRequest(contextUri = radio.contextUri)
                radio.trackUris.isNotEmpty() -> PlayRequest(trackUris = radio.trackUris)
                else -> throw NativeException(NativeErrorInfo(NativeErrorCode.NOT_FOUND, "Radio station is empty"))
            }
            load(request)
        }
    }

    /** Records a failure reported outside a command (native `error` events), see [failure]. */
    fun noteFailure(kind: PlaybackErrorKind, detail: String?) {
        if (!snapshot.value.isPlayingOrLoading()) _failure.value = PlaybackFailure(kind, errorMessages.message(kind, detail))
    }

    /** Forgets [failure] (e.g. a controller retries with `prepare()`). */
    fun clearFailure() {
        _failure.value = null
    }

    // ---- awaitable variants (used by the media session player) --------------------------------

    internal fun playAsync(request: PlayRequest): Deferred<Boolean> {
        if (request.play) onPlaybackRequested?.invoke()
        lateinit var self: Command
        return enqueue(
            "player.load",
            timeoutMs = LOAD_TIMEOUT_MS,
            startsPlayback = true,
            onQueued = { command ->
                self = command
                command.request = request
                latestLoad = command
            },
        ) { sendLoad(self) }
    }

    /** Also used by the media session (play button, Bluetooth play after a cold start). */
    internal fun resumeAsync(): Deferred<Boolean> = sendResuming("player.play")

    internal fun pauseAsync(): Deferred<Boolean> = pauseLike("player.pause")

    internal fun nextAsync(): Deferred<Boolean> = send("player.next", quietCodes = INACTIVE_CODES)

    internal fun previousAsync(): Deferred<Boolean> = send("player.prev", quietCodes = INACTIVE_CODES)

    internal fun seekAsync(positionMs: Long): Deferred<Boolean> = send(
        "player.seek",
        buildJsonObject { put("positionMs", positionMs.coerceAtLeast(0)) },
        conflateKey = "seek",
        quietCodes = INACTIVE_CODES,
    )

    internal fun setShuffleAsync(enabled: Boolean): Deferred<Boolean> {
        pendingShuffle = null
        return send("player.setShuffle", buildJsonObject { put("enabled", enabled) })
    }

    internal fun setSmartShuffleAsync(enabled: Boolean): Deferred<Boolean> {
        pendingShuffle = null
        return send("player.setSmartShuffle", buildJsonObject { put("enabled", enabled) })
    }

    internal fun cycleShuffleAsync(): Deferred<Boolean> {
        val s = snapshot.value
        if (!s.restrictions.canToggleShuffle) return CompletableDeferred(false)
        val current = pendingShuffle.validOr(s.shuffleMode)
        val target = PlaybackModes.nextShuffle(current, s.isSmartShuffleAvailable)
        pendingShuffle = Pending(target, System.nanoTime())
        val done = enqueue("cycleShuffle") {
            when (target) {
                ShuffleMode.SHUFFLE -> call("player.setShuffle", enabledArgs(true))
                ShuffleMode.SMART -> call("player.setSmartShuffle", enabledArgs(true))
                ShuffleMode.OFF -> {
                    if (current == ShuffleMode.SMART) call("player.setSmartShuffle", enabledArgs(false))
                    call("player.setShuffle", enabledArgs(false))
                }
            }
        }
        done.invokeOnCompletion { cause -> if (cause != null || !done.getCompleted()) pendingShuffle = null }
        return done
    }

    internal fun setRepeatAsync(mode: RepeatMode): Deferred<Boolean> {
        pendingRepeat = Pending(mode, System.nanoTime())
        val done = send("player.setRepeat", buildJsonObject { put("mode", PlaybackModes.wire(mode)) })
        done.invokeOnCompletion { cause -> if (cause != null || !done.getCompleted()) pendingRepeat = null }
        return done
    }

    internal fun setVolumeAsync(volume: Int): Deferred<Boolean> = send(
        "player.setVolume",
        buildJsonObject {
            put("volume", volume.coerceIn(0, VolumeMath.CONNECT_MAX))
            put("fromSystem", false)
        },
        conflateKey = "volume",
    )

    internal fun addToQueueAsync(uris: List<String>): Deferred<Boolean> {
        if (uris.isEmpty()) return CompletableDeferred(true)
        return enqueue("queue.add") {
            uris.forEach { call("queue.add", buildJsonObject { put("uri", it) }) }
        }
    }

    internal fun removeFromQueueAsync(uid: String): Deferred<Boolean> =
        send("queue.remove", buildJsonObject { put("uid", uid) })

    internal fun moveInQueueAsync(uid: String, toIndex: Int): Deferred<Boolean> = send(
        "queue.move",
        buildJsonObject {
            put("uid", uid)
            put("toIndex", toIndex.coerceAtLeast(0))
        },
    )

    internal fun skipToAsync(uid: String): Deferred<Boolean> {
        onPlaybackRequested?.invoke()
        return enqueue("queue.skipTo", startsPlayback = true) { call("queue.skipTo", buildJsonObject { put("uid", uid) }) }
    }

    // ---- internals ------------------------------------------------------------------------------

    private suspend fun call(method: String, args: JsonObject = NativeRpc.EMPTY) {
        transport(method, args)
    }

    /** `player.load`, rewritten for the offline queue while the session is not Online. */
    private suspend fun load(request: PlayRequest) {
        call("player.load", loadArgs(prepareLoad(withLoadableContext(request))))
    }

    /** [load] of a queued `player.load` [command], with a play merged in until the last moment. */
    private suspend fun sendLoad(command: Command) {
        val initial = synchronized(lock) { checkNotNull(command.request) }
        val prepared = prepareLoad(withLoadableContext(initial))
        val play = synchronized(lock) {
            command.sent = true
            checkNotNull(command.request).play
        }
        call("player.load", loadArgs(prepared.copy(play = play)))
    }

    private suspend fun prepareLoad(request: PlayRequest): PlayRequest {
        val env = environment ?: return request
        val context = request.contextUri ?: return request
        if (!request.trackUris.isNullOrEmpty()) return request
        val reach = env.reach()
        if (reach == EngineReach.ONLINE) return request
        val members = try {
            env.downloadedMembers(context, request.startUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Downloads of $context unavailable", e)
            return request
        }
        return when (val plan = OfflineLoads.plan(request, members, reach)) {
            is OfflineLoads.Plan.Load -> plan.request
            OfflineLoads.Plan.Unchanged -> request
            OfflineLoads.Plan.NotDownloaded -> throw NotAvailableOfflineException(context)
        }
    }

    private fun send(
        method: String,
        args: JsonObject = NativeRpc.EMPTY,
        conflateKey: String? = null,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        quietCodes: Set<String> = emptySet(),
    ): Deferred<Boolean> = enqueue(method, conflateKey, timeoutMs, quietCodes) { call(method, args) }

    /** Pause / toggle-to-pause: also cancels a play still waiting for the session to start. */
    private fun pauseLike(method: String): Deferred<Boolean> {
        synchronized(lock) {
            // While the session is starting, a pause cancels every start queued before it (e.g.
            // Media3's load + play of a cold Bluetooth resumption), not only the one waiting.
            val waiting = waitingForSession
            if (waiting != null || environment?.reach() == EngineReach.CONNECTING) {
                cancelStartsBefore = nextSeq
                waiting?.abort?.complete(Unit)
            }
        }
        return send(method, quietCodes = INACTIVE_CODES)
    }

    /**
     * [method] (play / toggle) with the last-session fallback, in one queued command. A play that
     * follows a paused `player.load` (Media3: setMediaItems, then play) is merged into that load
     * while it has not been sent; right after a load it never falls back to the stored session.
     */
    private fun sendResuming(method: String): Deferred<Boolean> {
        onPlaybackRequested?.invoke()
        val followsPendingLoad = synchronized(lock) {
            val load = latestLoad
            val nothingAfter = load != null && (lastQueued === load || (lastQueued == null && load.started))
            if (load != null && nothingAfter && !load.sent && !load.done.isCompleted) {
                load.request = load.request?.copy(play = true)
                return load.done
            }
            load != null && !load.done.isCompleted
        }
        return enqueue(method, timeoutMs = LOAD_TIMEOUT_MS, startsPlayback = true) { resume(method, followsPendingLoad) }
    }

    /**
     * [followsPendingLoad]: queued while a `player.load` was still pending — the play belongs to
     * that load (Media3: setMediaItems, prepare, play), so it is dropped if the load failed. A play
     * shortly after a successful load starts that content too. Neither uses the stored session.
     */
    private suspend fun resume(method: String, followsPendingLoad: Boolean) {
        val previous = lastStart
        val recentLoad = previous != null && previous.wasLoad && System.nanoTime() - previous.atNanos < FOLLOWS_LOAD_NANOS
        if (followsPendingLoad && recentLoad && previous?.ok == false) throw SupersededException("the load before it failed")
        if (followsPendingLoad || (recentLoad && previous?.ok == true)) {
            playAfterLoad(method)
            return
        }
        resumeOrLoadLast(
            method = method,
            call = { m, args -> call(m, args) },
            fallBackOn = { code -> shouldResumeLast(code, snapshot.value.source == PlaybackSource.REMOTE, environment?.reach()) },
            prepare = ::prepareLoad,
            last = lastSession,
        )
    }

    /**
     * Play after a load that may not have activated this device yet (the engine activates
     * asynchronously): on NOT_ACTIVE_DEVICE wait (bounded) for the local session, then once more.
     */
    private suspend fun playAfterLoad(method: String) {
        try {
            call(method)
        } catch (e: NativeException) {
            if (e.code != NativeErrorCode.NOT_ACTIVE_DEVICE) throw e
            withTimeoutOrNull(ACTIVATION_WAIT_MS) { snapshot.first { it.source == PlaybackSource.LOCAL && it.track != null } } ?: throw e
            call(method)
        }
    }

    private fun enqueue(
        name: String,
        conflateKey: String? = null,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        quietCodes: Set<String> = emptySet(),
        startsPlayback: Boolean = false,
        onQueued: (Command) -> Unit = {},
        block: suspend () -> Unit,
    ): Deferred<Boolean> {
        // A new attempt replaces whatever failed before.
        if (startsPlayback) _failure.value = null
        synchronized(lock) {
            val last = lastQueued
            if (conflateKey != null && last != null && !last.started && last.conflateKey == conflateKey) {
                // A burst (seek bar drag, volume slider): only the newest value is sent. Merging
                // only with the last queued command keeps the order relative to other commands.
                last.block = block
                return last.done
            }
            val command = Command(name, conflateKey, timeoutMs, quietCodes, startsPlayback, block)
            command.seq = nextSeq++
            onQueued(command)
            lastQueued = command
            if (queue.trySend(command).isFailure) command.done.complete(false)
            return command.done
        }
    }

    private suspend fun execute(command: Command) {
        var ok = false
        var cancelled = false
        try {
            val env = environment
            if (command.startsPlayback) {
                cancelled = cancelledByPause(command) || (env != null && awaitSession(command, env)) || cancelledByPause(command)
                if (cancelled) {
                    warn("${command.name} cancelled by a pause while the session was starting")
                    command.done.complete(false)
                    return
                }
            }
            val block = synchronized(lock) { command.block }
            // Native commands wait briefly for a connecting session themselves: allow for it.
            val allowance = if (env?.reach() == EngineReach.CONNECTING) NATIVE_ONLINE_WAIT_MS else 0L
            withTimeout(command.timeoutMs + allowance) { block() }
            ok = true
            command.done.complete(true)
        } catch (e: SupersededException) {
            warn("${command.name} skipped: ${e.message}")
            command.done.complete(false)
        } catch (e: TimeoutCancellationException) {
            warn("${command.name} timed out")
            report(command, PlaybackErrorKind.TIMEOUT, null)
            command.done.complete(false)
        } catch (e: CancellationException) {
            command.done.complete(false)
            throw e
        } catch (e: NotAvailableOfflineException) {
            warn("${command.name}: ${e.message}")
            report(command, PlaybackErrorKind.NOT_AVAILABLE_OFFLINE, null)
            command.done.complete(false)
        } catch (e: NativeException) {
            warn("${command.name} failed: ${e.code}")
            if (e.code !in command.quietCodes) kindOf(command, e)?.let { report(command, it, e.info.message) }
            command.done.complete(false)
        } catch (e: Exception) {
            warn("${command.name} failed", e)
            report(command, PlaybackErrorKind.GENERIC, e.message)
            command.done.complete(false)
        } finally {
            if (command.startsPlayback && !cancelled) {
                lastStart = StartOutcome(wasLoad = command.request != null, ok = ok, atNanos = System.nanoTime())
            }
        }
    }

    private fun cancelledByPause(command: Command): Boolean = synchronized(lock) { command.seq < cancelStartsBefore }

    /**
     * Waits (bounded) for a starting session before [command]. Returns true when a pause cancelled
     * the command meanwhile.
     */
    private suspend fun awaitSession(command: Command, env: PlaybackEnvironment): Boolean {
        synchronized(lock) { waitingForSession = command }
        try {
            return withTimeoutOrNull(SESSION_WAIT_MAX_MS) {
                coroutineScope {
                    val ready = async {
                        try {
                            env.awaitSessionStart()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            warn("Waiting for the session failed", e)
                        }
                    }
                    select {
                        ready.onAwait { false }
                        command.abort.onAwait { true }
                    }.also { ready.cancel() }
                }
            } ?: false
        } finally {
            synchronized(lock) { if (waitingForSession === command) waitingForSession = null }
        }
    }

    /** A native "not available offline" while offline gets the dedicated message. */
    private fun kindOf(command: Command, e: NativeException): PlaybackErrorKind? =
        if (e.code == NativeErrorCode.UNAVAILABLE && command.startsPlayback && environment?.reach() == EngineReach.OFFLINE) {
            PlaybackErrorKind.NOT_AVAILABLE_OFFLINE
        } else {
            PlaybackErrorKind.fromCode(e.code)
        }

    private fun report(command: Command, kind: PlaybackErrorKind, detail: String?) {
        val message = errorMessages.message(kind, detail)
        _errors.tryEmit(message)
        if (command.startsPlayback && !snapshot.value.isPlayingOrLoading()) _failure.value = PlaybackFailure(kind, message)
    }

    private fun <T> Pending<T>?.validOr(actual: T): T {
        val p = this ?: return actual
        return if (System.nanoTime() - p.atNanos < PENDING_VALID_NANOS) p.value else actual
    }

    private fun enabledArgs(enabled: Boolean) = buildJsonObject { put("enabled", enabled) }

    /** Logs without failing where android.util.Log is a stub (JVM unit tests). */
    private fun warn(message: String, t: Throwable? = null) {
        try {
            Log.w(TAG, message, t)
        } catch (_: RuntimeException) {
        }
    }

    @Serializable
    private data class RadioContext(val contextUri: String? = null, val trackUris: List<String> = emptyList())

    /** The command no longer applies (e.g. a play after a failed load); dropped without a message. */
    private class SupersededException(message: String) : Exception(message)

    /** Offline, and nothing of [contextUri] is downloaded. */
    internal class NotAvailableOfflineException(contextUri: String) : Exception("$contextUri is not downloaded")

    internal companion object {
        private const val TAG = "PlayerController"
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val PENDING_VALID_NANOS = 2_000_000_000L

        /** Upper bound of [PlaybackEnvironment.awaitSessionStart] (it is bounded itself). */
        private const val SESSION_WAIT_MAX_MS = 25_000L

        /** Extra time for a command the engine holds back until a connecting session is Online. */
        private const val NATIVE_ONLINE_WAIT_MS = 20_000L

        /** A play this soon after a load belongs to that load (Media3: setMediaItems, prepare, play). */
        private const val FOLLOWS_LOAD_NANOS = 10_000_000_000L

        /** How long a play after a load waits for the engine to activate this device. */
        private const val ACTIVATION_WAIT_MS = 3_000L

        /** Nothing is (or can be) playing: nothing to pause / skip / seek, not worth a message. */
        private val INACTIVE_CODES = setOf(NativeErrorCode.NOT_ACTIVE_DEVICE, NativeErrorCode.NOT_CONNECTED)

        private fun PlaybackSnapshot.isPlayingOrLoading(): Boolean =
            status == PlaybackStatus.PLAYING || status == PlaybackStatus.LOADING

        /**
         * Whether a failed play / toggle with [code] means "nothing to resume here", so the last
         * local session is loaded instead:
         * * NOT_ACTIVE_DEVICE — no Connect device is active (fresh session, last device gone);
         * * NOT_CONNECTED — no session (it did not come up in time, or no network: the load then
         *   plays the session's downloads);
         * * UNAVAILABLE while the session is still connecting.
         *
         * Never while [remote] playback is mirrored: losing the connection to that device is not
         * "nothing playing", and must not start audio on this phone.
         */
        fun shouldResumeLast(code: String, remote: Boolean, reach: EngineReach?): Boolean = when (code) {
            NativeErrorCode.NOT_ACTIVE_DEVICE -> true
            NativeErrorCode.NOT_CONNECTED -> !remote
            NativeErrorCode.UNAVAILABLE -> !remote && reach == EngineReach.CONNECTING
            else -> false
        }

        /**
         * Runs [method] (`player.play` / `player.togglePlay`). When it fails with a code accepted by
         * [fallBackOn] (see [shouldResumeLast]) it loads the [last] local session instead, through
         * [prepare] (offline: its downloads). The original error is rethrown when there is nothing to
         * resume.
         */
        suspend fun resumeOrLoadLast(
            method: String,
            call: suspend (method: String, args: JsonObject) -> Unit,
            fallBackOn: (code: String) -> Boolean = { shouldResumeLast(it, remote = false, reach = null) },
            prepare: suspend (PlayRequest) -> PlayRequest = { it },
            last: suspend () -> ResumeState?,
        ) {
            try {
                call(method, NativeRpc.EMPTY)
            } catch (e: NativeException) {
                if (!fallBackOn(e.code)) throw e
                val state = last() ?: throw e
                call("player.load", loadArgs(prepare(state.toPlayRequest())))
            }
        }

        /**
         * A load of a context that cannot be loaded (`spotify:web-api`, the context of a plain track
         * list) without `trackUris` plays its start track instead of being rejected; a
         * non-loadable context next to `trackUris` is dropped. Single items given as context stay.
         */
        fun withLoadableContext(request: PlayRequest): PlayRequest {
            val context = request.contextUri ?: return request
            if (MediaIds.isResolvableContext(context) || MediaIds.isItemUri(context)) return request
            if (!request.trackUris.isNullOrEmpty()) return request.copy(contextUri = null)
            val start = request.startUri ?: return request
            return request.copy(contextUri = null, trackUris = listOf(start), startIndex = 0, startUid = null)
        }

        fun loadArgs(request: PlayRequest): JsonObject = buildJsonObject {
            request.contextUri?.let { put("contextUri", it) }
            request.trackUris?.let { uris -> putJsonArray("trackUris") { uris.forEach { add(it) } } }
            request.startUri?.let { put("startUri", it) }
            request.startIndex?.let { put("startIndex", it) }
            request.startUid?.let { put("startUid", it) }
            put("positionMs", request.positionMs.coerceAtLeast(0))
            request.shuffle?.let { put("shuffle", it) }
            request.smartShuffle?.let { put("smartShuffle", it) }
            request.repeat?.let { put("repeat", PlaybackModes.wire(it)) }
            put("play", request.play)
        }
    }
}
