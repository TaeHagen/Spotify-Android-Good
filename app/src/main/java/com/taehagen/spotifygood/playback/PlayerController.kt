package com.taehagen.spotifygood.playback

import android.util.Log
import com.taehagen.spotifygood.model.NativeErrorInfo
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
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
 * With no active Connect device (cold start, or the last device went away) the engine rejects
 * transport commands with NOT_ACTIVE_DEVICE: play / resume then load the last local session from
 * [resumeStore] instead, and next / previous / seek fail silently.
 */
class PlayerController(
    private val scope: CoroutineScope,
    private val rpc: NativeRpc,
    private val playback: PlaybackRepository,
    private val resumeStore: ResumeStore,
) {
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Human-readable errors for snackbars. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * Invoked (on the calling thread) before a command that may start local playback, so the
     * [PlaybackService] is running before audio starts. Installed by [PlaybackCoordinator].
     */
    @Volatile var onPlaybackRequested: (() -> Unit)? = null

    /** Message source for [errors]; replaced with the resource-backed one by [PlaybackCoordinator]. */
    @Volatile var errorMessages: PlaybackErrorMessages = PlaybackErrorMessages.Fallback

    private class Command(
        val name: String,
        val conflateKey: String?,
        val timeoutMs: Long,
        /** NOT_ACTIVE_DEVICE is expected (nothing loaded): log only, no snackbar. */
        val quietWhenInactive: Boolean,
        /** Replaced (under [lock]) when a newer value of the same conflatable command arrives. */
        var block: suspend () -> Unit,
    ) {
        val done = CompletableDeferred<Boolean>()
        var started = false
    }

    private val queue = Channel<Command>(Channel.UNLIMITED)
    private val lock = Any()
    /** The most recently queued command (guarded by [lock]); only it may absorb a newer value. */
    private var lastQueued: Command? = null

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
        val playing = playback.snapshot.value.let { it.status == PlaybackStatus.PLAYING || it.status == PlaybackStatus.LOADING }
        if (playing) send("player.togglePlay") else sendResuming("player.togglePlay")
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
        val s = playback.snapshot.value
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
        enqueue("catalog.radio", timeoutMs = LOAD_TIMEOUT_MS) {
            val radio = rpc.call<RadioContext>("catalog.radio", buildJsonObject { put("uri", uri) })
            // The station is normally a playlist context; the fallback station may be a bare track list.
            val request = when {
                radio.contextUri != null -> PlayRequest(contextUri = radio.contextUri)
                radio.trackUris.isNotEmpty() -> PlayRequest(trackUris = radio.trackUris)
                else -> throw NativeException(NativeErrorInfo(NativeErrorCode.NOT_FOUND, "Radio station is empty"))
            }
            rpc.callUnit("player.load", loadArgs(request))
        }
    }

    // ---- awaitable variants (used by the media session player) --------------------------------

    internal fun playAsync(request: PlayRequest): Deferred<Boolean> =
        send("player.load", loadArgs(request), startsPlayback = request.play, timeoutMs = LOAD_TIMEOUT_MS)

    /** Also used by the media session (play button, Bluetooth play after a cold start). */
    internal fun resumeAsync(): Deferred<Boolean> = sendResuming("player.play")

    internal fun pauseAsync(): Deferred<Boolean> = send("player.pause")

    internal fun nextAsync(): Deferred<Boolean> = send("player.next", quietWhenInactive = true)

    internal fun previousAsync(): Deferred<Boolean> = send("player.prev", quietWhenInactive = true)

    internal fun seekAsync(positionMs: Long): Deferred<Boolean> = send(
        "player.seek",
        buildJsonObject { put("positionMs", positionMs.coerceAtLeast(0)) },
        conflateKey = "seek",
        quietWhenInactive = true,
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
        val s = playback.snapshot.value
        if (!s.restrictions.canToggleShuffle) return CompletableDeferred(false)
        val current = pendingShuffle.validOr(s.shuffleMode)
        val target = PlaybackModes.nextShuffle(current, s.isSmartShuffleAvailable)
        pendingShuffle = Pending(target, System.nanoTime())
        val done = enqueue("cycleShuffle") {
            when (target) {
                ShuffleMode.SHUFFLE -> rpc.callUnit("player.setShuffle", enabledArgs(true))
                ShuffleMode.SMART -> rpc.callUnit("player.setSmartShuffle", enabledArgs(true))
                ShuffleMode.OFF -> {
                    if (current == ShuffleMode.SMART) rpc.callUnit("player.setSmartShuffle", enabledArgs(false))
                    rpc.callUnit("player.setShuffle", enabledArgs(false))
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
            uris.forEach { rpc.callUnit("queue.add", buildJsonObject { put("uri", it) }) }
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

    internal fun skipToAsync(uid: String): Deferred<Boolean> =
        send("queue.skipTo", buildJsonObject { put("uid", uid) }, startsPlayback = true)

    // ---- internals ------------------------------------------------------------------------------

    private fun send(
        method: String,
        args: JsonObject = NativeRpc.EMPTY,
        conflateKey: String? = null,
        startsPlayback: Boolean = false,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        quietWhenInactive: Boolean = false,
    ): Deferred<Boolean> {
        if (startsPlayback) onPlaybackRequested?.invoke()
        return enqueue(method, conflateKey, timeoutMs, quietWhenInactive) { rpc.callUnit(method, args) }
    }

    /** [method] (play / toggle) with the last-session fallback, in one queued command. */
    private fun sendResuming(method: String): Deferred<Boolean> {
        onPlaybackRequested?.invoke()
        return enqueue(method, timeoutMs = LOAD_TIMEOUT_MS) {
            resumeOrLoadLast(method, { m, args -> rpc.callUnit(m, args) }, resumeStore::read)
        }
    }

    private fun enqueue(
        name: String,
        conflateKey: String? = null,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        quietWhenInactive: Boolean = false,
        block: suspend () -> Unit,
    ): Deferred<Boolean> {
        synchronized(lock) {
            val last = lastQueued
            if (conflateKey != null && last != null && !last.started && last.conflateKey == conflateKey) {
                // A burst (seek bar drag, volume slider): only the newest value is sent. Merging
                // only with the last queued command keeps the order relative to other commands.
                last.block = block
                return last.done
            }
            val command = Command(name, conflateKey, timeoutMs, quietWhenInactive, block)
            lastQueued = command
            if (queue.trySend(command).isFailure) command.done.complete(false)
            return command.done
        }
    }

    private suspend fun execute(command: Command) {
        try {
            val block = synchronized(lock) { command.block }
            withTimeout(command.timeoutMs) { block() }
            command.done.complete(true)
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "${command.name} timed out")
            report(PlaybackErrorKind.TIMEOUT, null)
            command.done.complete(false)
        } catch (e: CancellationException) {
            command.done.complete(false)
            throw e
        } catch (e: NativeException) {
            Log.w(TAG, "${command.name} failed: ${e.code}")
            val quiet = command.quietWhenInactive && e.code == NativeErrorCode.NOT_ACTIVE_DEVICE
            if (!quiet) PlaybackErrorKind.fromCode(e.code)?.let { report(it, e.info.message) }
            command.done.complete(false)
        } catch (e: Exception) {
            Log.w(TAG, "${command.name} failed", e)
            report(PlaybackErrorKind.GENERIC, e.message)
            command.done.complete(false)
        }
    }

    private fun report(kind: PlaybackErrorKind, detail: String?) {
        _errors.tryEmit(errorMessages.message(kind, detail))
    }

    private fun <T> Pending<T>?.validOr(actual: T): T {
        val p = this ?: return actual
        return if (System.nanoTime() - p.atNanos < PENDING_VALID_NANOS) p.value else actual
    }

    private fun enabledArgs(enabled: Boolean) = buildJsonObject { put("enabled", enabled) }

    @Serializable
    private data class RadioContext(val contextUri: String? = null, val trackUris: List<String> = emptyList())

    internal companion object {
        private const val TAG = "PlayerController"
        private const val COMMAND_TIMEOUT_MS = 15_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val PENDING_VALID_NANOS = 2_000_000_000L

        /**
         * Runs [method] (`player.play` / `player.togglePlay`). When no device is active
         * (NOT_ACTIVE_DEVICE) it loads the [last] local session instead; the error is rethrown
         * only when there is nothing to resume.
         */
        suspend fun resumeOrLoadLast(
            method: String,
            call: suspend (method: String, args: JsonObject) -> Unit,
            last: suspend () -> ResumeState?,
        ) {
            try {
                call(method, NativeRpc.EMPTY)
            } catch (e: NativeException) {
                if (e.code != NativeErrorCode.NOT_ACTIVE_DEVICE) throw e
                val state = last() ?: throw e
                call("player.load", loadArgs(state.toPlayRequest()))
            }
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
