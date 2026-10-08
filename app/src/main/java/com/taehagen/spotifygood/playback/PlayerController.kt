package com.taehagen.spotifygood.playback

import android.util.Log
import com.taehagen.spotifygood.connect.DevicesRepository
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
import kotlinx.coroutines.flow.update
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

/** Outcome of [PlayerController.addToQueueCounted]: [added] items, then the first failure ([error]; null: none). */
data class QueueAddResult(val added: Int, val error: NativeErrorInfo?)

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
 * * whenever the engine cannot stream (offline mode, an offline session, or no network — also while
 *   the session still reads Online), context loads of playlists / Liked Songs / albums / shows are
 *   turned into loads of their downloads, which the engine's offline queue plays ([OfflineLoads]);
 *   nothing downloaded → "not available offline", as the engine itself answers (UNAVAILABLE).
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
    /**
     * Takes the Connect device picked while nothing played anywhere
     * ([DevicesRepository.consumePendingTarget]): the next user-started play goes there.
     */
    private val consumePendingTarget: () -> String? = { null },
) {
    constructor(
        scope: CoroutineScope,
        rpc: NativeRpc,
        playback: PlaybackRepository,
        resumeStore: ResumeStore,
        devices: DevicesRepository,
    ) : this(
        scope,
        { method, args -> rpc.callRaw(method, args) },
        rpc.json,
        playback.snapshot,
        resumeStore::read,
        devices::consumePendingTarget,
    )

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Human-readable errors for snackbars. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _failure = MutableStateFlow<PlaybackFailure?>(null)

    /**
     * The last failure to start playback while nothing was playing; cleared by the next attempt to
     * start playback, by [clearFailure] and as soon as something plays.
     */
    val failure: StateFlow<PlaybackFailure?> = _failure.asStateFlow()

    private val _userCommands = MutableStateFlow(0L)

    /**
     * Counts the user's playback commands — play, load, pause, toggle, skip, seek, radio — from the
     * app and from the media session, bumped when the command is issued. Commands the app sends by
     * itself (audio focus, headphones unplugged, the sleep timer, a refused background start) and
     * fallbacks inside a command do not count. A delayed start compares it to know whether the
     * user did something else meanwhile.
     */
    val userCommands: StateFlow<Long> = _userCommands.asStateFlow()

    private fun userCommand() {
        _userCommands.update { it + 1 }
    }

    /**
     * Invoked (on the calling thread) before a command that may start local playback, so the
     * [PlaybackService] is running before audio starts. Installed by [PlaybackCoordinator].
     */
    @Volatile var onPlaybackRequested: (() -> Unit)? = null

    /**
     * Invoked (on the calling thread) for a pause made on purpose: by the user (app, media
     * session, Bluetooth, Assistant), the sleep timer or a refused background start. A pending
     * audio-focus resume must not undo it ([PlaybackCoordinator] cancels it). Not for the pause of
     * a transient focus loss itself, nor for headphones unplugged (which cancels on its own).
     */
    @Volatile var onDeliberatePause: (() -> Unit)? = null

    /** Message source for [errors]; replaced with the resource-backed one by [PlaybackCoordinator]. */
    @Volatile var errorMessages: PlaybackErrorMessages = PlaybackErrorMessages.Fallback

    /** Engine / downloads knowledge for cold starts and offline loads; installed by [PlaybackCoordinator]. */
    @Volatile var environment: PlaybackEnvironment? = null

    /**
     * Where a play of an episode resumes when the request names no position: this phone's podcast
     * progress (`EpisodeProgressStore.resumeMs`, docs §6.5), null for none. Installed by the app graph.
     */
    @Volatile var episodeResume: ((episodeUri: String) -> Long?)? = null

    private class Command(
        val name: String,
        val conflateKey: String?,
        val timeoutMs: Long,
        /** Error codes that are expected here (nothing to act on): logged only, no snackbar. */
        val quietCodes: Set<String>,
        /** Starts playback: waits for a starting session, and its failure is kept in [failure]. */
        val startsPlayback: Boolean,
        /** Failures are the caller's to report (nothing on [errors]). */
        val silent: Boolean,
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
        /** `player.load` only: a user-started play, which may go to the pending Connect target. */
        var toPendingTarget = false
        /** `player.load` only: plays on this phone, also over an active remote device (`local`). */
        var onThisPhone = false
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

    /** An in-app play: it goes to the pending Connect target when there is one. */
    fun play(request: PlayRequest) {
        playAsync(request, toPendingTarget = true)
    }

    fun playContext(contextUri: String, startUri: String? = null, shuffle: Boolean? = null) {
        play(PlayRequest(contextUri = contextUri, startUri = startUri, shuffle = shuffle))
    }

    fun playTracks(trackUris: List<String>, startIndex: Int = 0) {
        if (trackUris.isEmpty()) return
        play(PlayRequest(trackUris = trackUris, startIndex = startIndex.coerceIn(0, trackUris.lastIndex)))
    }

    /** [user]: false for resumes the app sends by itself (audio focus regained), see [userCommands]. */
    fun resume(user: Boolean = true) {
        resumeAsync(user)
    }

    /**
     * [user]: false for pauses the app sends by itself (focus loss, unplugged, refused start).
     * [deliberate]: a pending focus resume is cancelled ([onDeliberatePause]).
     */
    fun pause(user: Boolean = true, deliberate: Boolean = user) {
        pauseAsync(user, deliberate)
    }

    fun togglePlayPause() {
        userCommand()
        if (snapshot.value.isPlayingOrLoading()) {
            onDeliberatePause?.invoke()
            pauseLike("player.togglePlay")
        } else {
            sendResuming("player.togglePlay")
        }
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
        clearQueueAsync()
    }

    /** `queue.clear`; bulk adds queued before it stop (their remaining items would refill the queue). */
    internal fun clearQueueAsync(): Deferred<Boolean> {
        val cancelled: List<BulkAdd>
        val done = synchronized(lock) {
            cancelled = cancelBulkAddsLocked()
            send("queue.clear")
        }
        completeCancelled(cancelled)
        return done
    }

    fun skipTo(uid: String) {
        skipToAsync(uid)
    }

    /** Starts a radio station seeded by [uri] (track/artist/album/playlist). */
    fun startRadio(uri: String) {
        userCommand()
        onPlaybackRequested?.invoke()
        enqueue("catalog.radio", timeoutMs = LOAD_TIMEOUT_MS, startsPlayback = true) {
            val radio = json.decodeFromJsonElement<RadioContext>(transport("catalog.radio", buildJsonObject { put("uri", uri) }))
            // The station is normally a playlist context; the fallback station may be a bare track list.
            val request = when {
                radio.contextUri != null -> PlayRequest(contextUri = radio.contextUri)
                radio.trackUris.isNotEmpty() -> PlayRequest(trackUris = radio.trackUris)
                else -> throw NativeException(NativeErrorInfo(NativeErrorCode.NOT_FOUND, "Radio station is empty"))
            }
            load(request, toPendingTarget = true)
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

    /**
     * `player.load` of [request]. [toPendingTarget]: an in-app play, sent to the pending Connect
     * target when there is one (see [pendingTargetFor]). [onThisPhone]: the media session's loads
     * (Auto, Assistant, watches, resumption, Tap to resume, "play something"), which play through
     * this phone's own audio path: `local` makes the engine play them here even while another
     * Connect device is active (a car must not start the speaker at home), never on a target.
     */
    internal fun playAsync(
        request: PlayRequest,
        toPendingTarget: Boolean = false,
        onThisPhone: Boolean = false,
    ): Deferred<Boolean> {
        userCommand()
        if (request.play) onPlaybackRequested?.invoke()
        val resolved = episodeResume?.let { withEpisodeResume(request, it) } ?: request
        lateinit var self: Command
        // A running bulk add keeps going: Spirc and remote devices keep the user queue across a
        // load, so its remaining items still belong to it.
        return enqueue(
            "player.load",
            timeoutMs = LOAD_TIMEOUT_MS,
            startsPlayback = true,
            onQueued = { command ->
                self = command
                command.request = resolved
                command.toPendingTarget = toPendingTarget && !onThisPhone
                command.onThisPhone = onThisPhone
                latestLoad = command
            },
        ) { sendLoad(self) }
    }

    /** Also used by the media session (play button, Bluetooth play after a cold start). */
    internal fun resumeAsync(user: Boolean = true): Deferred<Boolean> {
        if (user) userCommand()
        return sendResuming("player.play")
    }

    internal fun pauseAsync(user: Boolean = true, deliberate: Boolean = user): Deferred<Boolean> {
        if (user) userCommand()
        if (deliberate) onDeliberatePause?.invoke()
        return pauseLike("player.pause")
    }

    internal fun nextAsync(): Deferred<Boolean> {
        userCommand()
        return send("player.next", quietCodes = INACTIVE_CODES)
    }

    internal fun previousAsync(): Deferred<Boolean> {
        userCommand()
        return send("player.prev", quietCodes = INACTIVE_CODES)
    }

    internal fun seekAsync(positionMs: Long): Deferred<Boolean> {
        userCommand()
        return send(
            "player.seek",
            buildJsonObject { put("positionMs", positionMs.coerceAtLeast(0)) },
            conflateKey = "seek",
            quietCodes = INACTIVE_CODES,
        )
    }

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

    /**
     * Media3 controllers' adds (Auto, Wear): like [addToQueueCounted], but a failure is reported
     * on [errors].
     */
    internal fun addToQueueAsync(uris: List<String>): Deferred<Boolean> {
        if (uris.isEmpty()) return CompletableDeferred(true)
        val result = startBulkAdd(uris, reportFailure = true)
        val ok = CompletableDeferred<Boolean>()
        result.invokeOnCompletion { ok.complete(runCatching { result.getCompleted().error == null }.getOrDefault(false)) }
        return ok
    }

    /**
     * Adds [uris] in order (one `queue.add` each, at most [MAX_QUEUE_ADD]) and stops at the first
     * failure, which is returned instead of reported on [errors]: the caller says what happened
     * (e.g. the engine's "The queue is full" after part of an album).
     *
     * Every add is its own queued command with its own timeout (a remote device costs one request
     * per item), and the next one is queued only when the previous finished, so other commands
     * (pause, skip) are not held up behind a long add. Bulk adds run one after the other.
     */
    internal fun addToQueueCounted(uris: List<String>): Deferred<QueueAddResult> {
        if (uris.isEmpty()) return CompletableDeferred(QueueAddResult(0, null))
        return startBulkAdd(uris, reportFailure = false)
    }

    private class BulkAdd(val items: List<String>, val capped: Boolean, val reportFailure: Boolean) {
        val result = CompletableDeferred<QueueAddResult>()
        /** Index of the next item (guarded by [lock]). */
        var next = 0
        /** A queue clear came after it: no further items (guarded by [lock]). */
        var cancelled = false
    }

    /**
     * Must hold [lock]. A queue clear cancels every bulk add queued so far (their remaining items
     * would refill the cleared queue): the running one stops after its current item (already queued
     * ahead, so it keeps its place before the clear); waiting ones are removed and returned, to be
     * completed outside the lock ([completeCancelled]). Loads do not cancel: the user queue survives
     * them.
     */
    private fun cancelBulkAddsLocked(): List<BulkAdd> {
        if (bulkAdds.isEmpty()) return emptyList()
        bulkAdds.forEach { it.cancelled = true }
        val waiting = bulkAdds.drop(1)
        while (bulkAdds.size > 1) bulkAdds.removeLast()
        return waiting
    }

    private fun completeCancelled(bulks: List<BulkAdd>) {
        bulks.forEach { it.result.complete(QueueAddResult(it.next, QUEUE_CHANGED)) }
    }

    /** Running (first) and waiting bulk adds (guarded by [lock]). */
    private val bulkAdds = ArrayDeque<BulkAdd>()

    private fun startBulkAdd(uris: List<String>, reportFailure: Boolean): Deferred<QueueAddResult> {
        val bulk = BulkAdd(uris.take(MAX_QUEUE_ADD), capped = uris.size > MAX_QUEUE_ADD, reportFailure = reportFailure)
        synchronized(lock) {
            bulkAdds.addLast(bulk)
            // Queued right away when nothing else is being added, so it keeps its place in call order.
            if (bulkAdds.size == 1) enqueueBulkItem(bulk)
        }
        return bulk.result
    }

    private fun enqueueBulkItem(bulk: BulkAdd) {
        val uri = synchronized(lock) { bulk.items[bulk.next] }
        var error: NativeErrorInfo? = null
        val done = enqueue("queue.add", silent = !bulk.reportFailure) {
            try {
                call("queue.add", buildJsonObject { put("uri", uri) })
            } catch (e: NativeException) {
                error = e.info
                throw e
            }
        }
        done.invokeOnCompletion {
            val ok = runCatching { done.getCompleted() }.getOrDefault(false)
            onBulkItemDone(bulk, ok, error)
        }
    }

    private fun onBulkItemDone(bulk: BulkAdd, ok: Boolean, error: NativeErrorInfo?) {
        val next: BulkAdd?
        val cancelled: Boolean
        synchronized(lock) {
            if (ok) bulk.next++
            cancelled = bulk.cancelled
            if (ok && !cancelled && bulk.next < bulk.items.size) {
                enqueueBulkItem(bulk)
                return
            }
            bulkAdds.removeFirst()
            next = bulkAdds.firstOrNull()
        }
        val failure = when {
            cancelled -> QUEUE_CHANGED
            !ok -> error ?: NativeErrorInfo(NativeErrorCode.NETWORK, "The device didn't respond")
            // More than a queue holds: what is left would be refused anyway.
            bulk.capped -> NativeErrorInfo(NativeErrorCode.UNAVAILABLE, "The queue is full")
            else -> null
        }
        bulk.result.complete(QueueAddResult(bulk.next, failure))
        next?.let(::enqueueBulkItem)
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
        userCommand()
        onPlaybackRequested?.invoke()
        return enqueue("queue.skipTo", startsPlayback = true) { call("queue.skipTo", buildJsonObject { put("uid", uid) }) }
    }

    // ---- internals ------------------------------------------------------------------------------

    private suspend fun call(method: String, args: JsonObject = NativeRpc.EMPTY) {
        transport(method, args)
    }

    /** `player.load`, rewritten for the offline queue whenever the engine cannot stream ([OfflineLoads]). */
    private suspend fun load(request: PlayRequest, toPendingTarget: Boolean) {
        val prepared = prepare(withLoadableContext(request))
        val target = if (toPendingTarget) pendingTargetFor(prepared, prepared.request.play) else null
        call("player.load", loadArgs(prepared.request, deviceId = target))
    }

    /** [load] of a queued `player.load` [command], with a play merged in until the last moment. */
    private suspend fun sendLoad(command: Command) {
        val initial = synchronized(lock) { checkNotNull(command.request) }
        val prepared = prepare(withLoadableContext(initial))
        val play = synchronized(lock) {
            command.sent = true
            checkNotNull(command.request).play
        }
        val target = if (command.toPendingTarget) pendingTargetFor(prepared, play) else null
        call("player.load", loadArgs(prepared.request.copy(play = play), deviceId = target, local = command.onThisPhone))
    }

    /**
     * The pending Connect target for a user-started load, taken (it is used once) only when the
     * load plays, no device is active, and the engine will not play it from this phone's downloads
     * (offline, or rewritten for the offline queue): then the phone plays it itself.
     */
    private fun pendingTargetFor(prepared: PreparedLoad, play: Boolean): String? {
        if (!play || prepared.offline || snapshot.value.activeDevice != null) return null
        return consumePendingTarget()
    }

    /** A load after [prepare]; [offline]: the engine plays it from the downloads. */
    private class PreparedLoad(val request: PlayRequest, val offline: Boolean)

    private suspend fun prepareLoad(original: PlayRequest): PlayRequest = prepare(original).request

    private suspend fun prepare(original: PlayRequest): PreparedLoad {
        val env = environment ?: return PreparedLoad(original, offline = false)
        val reach = env.reach()
        val request = OfflineLoads.withoutSmartShuffle(original, reach)
        val asIs = PreparedLoad(request, offline = reach == EngineReach.OFFLINE)
        val context = request.contextUri ?: return asIs
        if (!request.trackUris.isNullOrEmpty()) return asIs
        if (reach == EngineReach.ONLINE) return asIs
        val members = try {
            env.downloadedMembers(context, request.startUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("Downloads of $context unavailable", e)
            return asIs
        }
        return when (val plan = OfflineLoads.plan(request, members, reach)) {
            is OfflineLoads.Plan.Load -> PreparedLoad(plan.request, offline = true)
            OfflineLoads.Plan.Unchanged -> asIs
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
        silent: Boolean = false,
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
            val command = Command(name, conflateKey, timeoutMs, quietCodes, startsPlayback, silent, block)
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
        if (command.silent) return
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
        /**
         * [request] starting at [resumeOf]'s position when its start item is an episode and it
         * names no position (0): every way of starting an episode (a show or episode page, the
         * downloads, search, Android Auto) resumes where it was left on this phone.
         */
        fun withEpisodeResume(request: PlayRequest, resumeOf: (String) -> Long?): PlayRequest {
            if (request.positionMs > 0) return request
            val start = request.startUri
                ?: request.trackUris?.getOrNull(request.startIndex ?: 0)?.takeIf { request.startUid == null }
                ?: request.contextUri?.takeIf { request.trackUris == null && request.startUid == null }
                ?: return request
            if (!start.startsWith(EPISODE_PREFIX)) return request
            val position = resumeOf(start)?.takeIf { it > 0 } ?: return request
            return request.copy(positionMs = position)
        }

        private const val EPISODE_PREFIX = "spotify:episode:"

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

        /** Most items one add-to-queue sends: Connect's queue window holds no more. */
        const val MAX_QUEUE_ADD = 80

        /** Result error of a bulk add stopped by a queue clear (the user's own action: callers stay silent). */
        private val QUEUE_CHANGED = NativeErrorInfo(NativeErrorCode.CANCELLED, "The queue changed")

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
         * [prepare] (offline: its downloads), on this phone (`local`: this phone's session, never
         * pushed onto a remote device that became active meanwhile). The original error is
         * rethrown when there is nothing to resume.
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
                call("player.load", loadArgs(prepare(state.toPlayRequest()), local = true))
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

        /**
         * `player.load` arguments; [deviceId]: play on that Connect device (the pending target);
         * [local]: play on this phone, pulling playback from an active remote device.
         */
        fun loadArgs(request: PlayRequest, deviceId: String? = null, local: Boolean = false): JsonObject = buildJsonObject {
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
            deviceId?.let { put("deviceId", it) }
            if (local) put("local", true)
        }
    }
}
