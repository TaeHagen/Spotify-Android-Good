package com.taehagen.spotifygood.download

import android.app.Notification
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.room.withTransaction
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.auth.KeystoreUnavailableException
import com.taehagen.spotifygood.data.callWith
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.db.DownloadEntity
import com.taehagen.spotifygood.data.rpcArgs
import com.taehagen.spotifygood.data.settings.Settings
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.engine.HolderType
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.Episode
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.model.Track
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** How a download run ended; the host (job / worker) maps it to its reschedule semantics. */
internal enum class RunOutcome {
    /** The queue is drained. */
    FINISHED,

    /**
     * Work remains but cannot proceed now (offline, backoff, storage full, metered network not
     * allowed): reschedule with system backoff (the host's constraints include storage not low).
     */
    RESCHEDULE,

    /** Stopped for a reason the user must resolve (cancel, account, offline mode): do not reschedule. */
    STOPPED,
}

/** The execution environment of a run: a user-initiated job (API 34+) or a WorkManager worker. */
internal interface DownloadHost {
    /** Shows [notification] as the run's ongoing notification (called at most ~1/s). */
    suspend fun updateNotification(notification: Notification)

    /** Bytes transferred so far in this run (user-initiated jobs report them to the system). */
    fun reportTransferred(bytes: Long) {}
}

/** Memo of decrypted audio keys (uri → hex) so engine restarts do not hit the Keystore per row. */
internal class KeyCache {
    private val keys = ConcurrentHashMap<String, String>()
    operator fun get(uri: String): String? = keys[uri]
    operator fun set(uri: String, hex: String) {
        keys[uri] = hex
    }

    /**
     * Caches a key decrypted outside the mutation lock, unless one is cached already: that one came
     * from a newer commit of [uri] (removed and downloaded again while this one was decrypted).
     * Returns the cached key.
     */
    fun remember(uri: String, hex: String): String = keys.putIfAbsent(uri, hex) ?: hex
    fun remove(uris: Collection<String>) = uris.forEach { keys.remove(it) }
    fun clear() = keys.clear()
}

/**
 * Drains the download queue one item at a time (docs/ARCHITECTURE.md §9.7). Shared by
 * [DownloadJobService] and [DownloadWorker]; only one run is active per process ([run] returns
 * immediately when another run already owns the queue).
 *
 * A run holds the `DOWNLOAD` engine holder, waits ≤ 60 s for the session, then for each item: checks
 * free storage, calls `download.track` (coroutine cancellation cancels the native task; `.part`
 * files are kept and resumed natively, and the row records the file it writes via `download.fileId`
 * so garbage collection keeps that `.part` while the row exists), mirrors `download` events into the
 * database (≥ 500 ms apart) and the notification, stores the record with its key encrypted, and
 * registers it with `offline.add` (numbered with the commit, see [OfflineIndexSync]). Failures are
 * retried with exponential backoff up to [DownloadRules.MAX_ATTEMPTS]; a rate limit, or repeated
 * connectivity failures while online, pause the whole queue ([QueueBreaker]: every pending row is
 * held back, so the loop waits inline for a short pause and reschedules for a long one).
 *
 * Lock order: [runLock] before [commitLock] (the manager's mutation lock).
 */
internal class DownloadRunner(
    private val context: Context,
    private val database: AppDatabase,
    private val rpc: NativeRpc,
    private val events: NativeEvents,
    private val engine: SpotifyEngine,
    private val settings: SettingsRepository,
    private val credentialStore: CredentialStore,
    private val storage: DownloadStorage,
    private val notifications: DownloadNotifications,
    private val keys: KeyCache,
    private val commitLock: Mutex,
    private val index: OfflineIndexSync,
) {
    private val dao = database.downloads()
    private val json: Json = rpc.json
    private val runLock = Mutex()

    private class CurrentItem(val uri: String, val job: Deferred<ItemResult>)

    @Volatile private var current: CurrentItem? = null
    @Volatile private var session: Deferred<RunOutcome>? = null
    @Volatile private var cancelRequested = false

    /** Kept across runs (runLock orders them), so pauses keep growing while a condition lasts. */
    private val breaker = QueueBreaker()

    private val _activity = MutableStateFlow(DownloadActivity())
    val activity: StateFlow<DownloadActivity> = _activity.asStateFlow()

    /** True while a run owns the queue. */
    val isRunning: Boolean get() = runLock.isLocked

    /** Runs until the queue is drained or the run must stop. Safe to call concurrently. */
    suspend fun run(host: DownloadHost): RunOutcome {
        while (true) {
            if (!runLock.tryLock()) return RunOutcome.FINISHED // another run owns the queue
            val outcome = try {
                runSession(host)
            } finally {
                runLock.unlock()
            }
            if (outcome != RunOutcome.FINISHED) return outcome
            // Items enqueued while this run was finishing were skipped by schedule() (it saw us
            // running): pick them up instead of leaving them stranded.
            if (dao.nextPending() == null) return RunOutcome.FINISHED
        }
    }

    /** Cancel action: stops the run and marks everything pending as cancelled. */
    fun requestCancel() {
        cancelRequested = true
        session?.cancel()
    }

    /** Cancels the item being downloaded if it is one of [uris] and waits until it stopped. */
    suspend fun cancelItems(uris: Collection<String>) {
        val item = current ?: return
        if (item.uri in uris) item.job.cancelAndJoin()
    }

    /** Stops an active run without changing item states (logout / remove all), bounded wait. */
    suspend fun stop() {
        val active = session ?: return
        withTimeoutOrNull(STOP_TIMEOUT_MS) { active.cancelAndJoin() }
    }

    /**
     * [stop], then waits (bounded) until the run released the queue, so that a host scheduled next
     * does not find the queue still taken and finish at once.
     */
    suspend fun stopAndAwaitIdle() {
        stop()
        withTimeoutOrNull(STOP_TIMEOUT_MS) { runLock.withLock {} }
    }

    /** Deletes orphaned files when nothing is running or pending (after removals). */
    suspend fun collectGarbageIfIdle() {
        if (!runLock.tryLock()) return
        try {
            collectGarbage()
        } finally {
            runLock.unlock()
        }
    }

    // ---- run -----------------------------------------------------------------------------------

    private suspend fun runSession(host: DownloadHost): RunOutcome = coroutineScope {
        cancelRequested = false
        val body = async { sessionBody(host) }
        session = body
        try {
            body.await()
        } catch (e: CancellationException) {
            // Our own cancellation (user pressed Cancel) vs the host being stopped by the system.
            if (!isActive || !cancelRequested) throw e
            withContext(NonCancellable) { dao.cancelAllPending() }
            RunOutcome.STOPPED
        } finally {
            session = null
        }
    }

    private suspend fun sessionBody(host: DownloadHost): RunOutcome {
        dao.resetInterrupted()
        // Started for a queue that was emptied meanwhile (removals): no session, no notification.
        if (dao.pendingCount() == 0) return RunOutcome.FINISHED
        // Everything pending is held back (backoff, queue pause) for longer than a run waits: do not
        // bring the engine up only to find that out.
        val paused = pausedForMs(System.currentTimeMillis())
        if (paused != null && paused > MAX_INLINE_WAIT_MS) return RunOutcome.RESCHEDULE
        // The settings as stored: a cold-started job must not act on the defaults shown before load.
        if (meteredNotAllowed(settings.awaitLoaded())) return RunOutcome.RESCHEDULE
        val holder = engine.acquire(HolderType.DOWNLOAD)
        val receiver = notifications.registerCancelReceiver(::requestCancel)
        val stats = RunStats()
        _activity.value = DownloadActivity(running = true)
        try {
            host.updateNotification(notifications.progress(null, 0, 0, 0f))
            notifications.clearDone()
            if (settings.awaitLoaded().offlineMode) return RunOutcome.STOPPED // resumed when offline mode ends
            if (!engine.awaitOnline(ONLINE_TIMEOUT_MS)) {
                // Logged out: nothing will ever come online (logout wipes the queue anyway).
                return if (engine.isLoggedIn.value) RunOutcome.RESCHEDULE else RunOutcome.STOPPED
            }
            var networkWaits = 0
            withContext(Dispatchers.IO) { storage.ensureDirs() }
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                val item = dao.nextRunnable(now)
                if (item == null) {
                    val retryAt = dao.earliestRetryAt() ?: break // nothing pending at all
                    val wait = retryAt - now
                    if (wait > MAX_INLINE_WAIT_MS) return RunOutcome.RESCHEDULE
                    delay(wait.coerceAtLeast(MIN_WAIT_MS))
                    continue
                }
                if (meteredNotAllowed(settings.awaitLoaded())) {
                    // The host's network constraint is stale (it predates the setting): never use
                    // mobile data against the setting; the manager re-creates the work.
                    Log.i(TAG, "On a metered network with mobile data downloads off: rescheduling")
                    return RunOutcome.RESCHEDULE
                }
                val free = withContext(Dispatchers.IO) { storage.freeBytes() }
                if (free < DownloadRules.MIN_FREE_BYTES) {
                    val message = context.getString(
                        R.string.data_dl_error_storage,
                        Formatter.formatShortFileSize(context, DownloadRules.MIN_FREE_BYTES),
                    )
                    stats.stopMessage = message
                    notifications.showStopped(message)
                    // Resumed by the host once storage is no longer low (its constraint), or when the
                    // user comes back to the app / retries.
                    return RunOutcome.RESCHEDULE
                }
                when (val result = processItem(item, host, stats)) {
                    ItemResult.Done -> Unit
                    ItemResult.WaitForNetwork -> {
                        // Bounded: a flapping connection hands the retry over to the system backoff.
                        if (++networkWaits > MAX_NETWORK_WAITS || !engine.awaitOnline(ONLINE_TIMEOUT_MS)) return RunOutcome.RESCHEDULE
                    }
                    ItemResult.Reschedule -> return RunOutcome.RESCHEDULE
                    is ItemResult.StopRun -> {
                        dao.failAllPending(result.message)
                        stats.stopMessage = result.message
                        notifications.showStopped(result.message)
                        return RunOutcome.STOPPED
                    }
                }
            }
            collectGarbage()
            return RunOutcome.FINISHED
        } finally {
            withContext(NonCancellable) {
                notifications.unregister(receiver)
                holder.release()
                // The user's Cancel marks the queue cancelled after this block (runSession).
                val pending = dao.pendingCount()
                when (DownloadRules.runNotice(stats.stopMessage != null, cancelRequested, pending, stats.processed)) {
                    DownloadRules.RunNotice.COMPLETE -> notifications.showSummary(stats.completed, stats.failed)
                    DownloadRules.RunNotice.PAUSED ->
                        notifications.showStopped(context.getString(R.string.data_dl_paused, stats.completed, stats.completed + pending))
                    DownloadRules.RunNotice.NONE -> Unit
                }
                _activity.value = DownloadActivity(lastError = stats.stopMessage)
            }
        }
    }

    /** Mobile data downloads are off and the default network is metered (no network: false). */
    private fun meteredNotAllowed(prefs: Settings): Boolean {
        if (prefs.downloadOverCellular) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** How long until a pending item may run; null when one may run now or nothing is pending. */
    private suspend fun pausedForMs(now: Long): Long? {
        if (dao.nextRunnable(now) != null) return null
        return dao.earliestRetryAt()?.let { it - now }
    }

    private class RunStats {
        var completed = 0
        var failed = 0
        var transferredBytes = 0L
        var stopMessage: String? = null

        /** Rate-limit pauses imposed in this run ([DownloadRules.throttleBudgetSpent]). */
        var throttledMs = 0L
        val processed get() = completed + failed
    }

    private sealed interface ItemResult {
        data object Done : ItemResult
        data object WaitForNetwork : ItemResult
        data class StopRun(val message: String) : ItemResult

        /** End the run with [RunOutcome.RESCHEDULE] (the queue stays paused on its rows). */
        data object Reschedule : ItemResult
    }

    /** Runs one item as its own child so it can be cancelled alone (item removed meanwhile). */
    private suspend fun processItem(item: DownloadEntity, host: DownloadHost, stats: RunStats): ItemResult = coroutineScope {
        val job = async { downloadItem(item, host, stats) }
        current = CurrentItem(item.uri, job)
        try {
            job.await()
        } catch (e: CancellationException) {
            if (!isActive) throw e // the whole run is being stopped
            ItemResult.Done // only this item was cancelled (removed by the user)
        } finally {
            current = null
        }
    }

    private suspend fun downloadItem(item: DownloadEntity, host: DownloadHost, stats: RunStats): ItemResult = coroutineScope {
        val quality = settings.awaitLoaded().downloadQuality.kbps
        dao.markPreparing(item.uri, quality)
        val title = displayTitle(item.metadataJson)
        val total = stats.processed + dao.pendingCount()
        _activity.value = DownloadActivity(running = true, currentUri = item.uri, remaining = total - stats.processed)
        host.updateNotification(notifications.progress(title, stats.processed, total, 0f))
        val progress = launch(start = CoroutineStart.UNDISPATCHED) { trackProgress(item.uri, title, total, host, stats) }
        try {
            val record = rpc.callWith(
                OfflineTrackRecord.serializer(),
                "download.track",
                rpcArgs {
                    put("uri", item.uri)
                    put("bitrate", quality)
                    put("dir", storage.audioDir.absolutePath)
                    put("imageDir", storage.imageDir.absolutePath)
                },
            )
            progress.cancelAndJoin()
            if (commit(item, record, quality)) {
                breaker.onSuccess()
                stats.completed++
                stats.transferredBytes += record.sizeBytes
                host.reportTransferred(stats.transferredBytes)
            }
            ItemResult.Done
        } catch (e: CancellationException) {
            progress.cancel()
            // Back to the queue untouched (attempts unchanged); the native side keeps the .part file.
            withContext(NonCancellable) {
                dao.requeue(item.uri)
                withTimeoutOrNull(FILE_ID_CANCEL_TIMEOUT_MS) { recordFileId(item.uri) }
            }
            throw e
        } catch (e: KeystoreUnavailableException) {
            // Downloaded and verified, but the Keystore could not seal its key even after waiting
            // (sealKey): not the item's fault and nothing is lost. The file stays (its fileId is
            // recorded, download.track reuses it), the item is requeued without an attempt and the
            // whole queue waits for the device's Keystore.
            progress.cancelAndJoin()
            recordFileId(item.uri)
            onKeystoreBusy(item)
        } catch (e: Exception) {
            progress.cancelAndJoin()
            val error = e as? NativeException
                ?: NativeException(NativeErrorInfo(NativeErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName))
            if (e !is NativeException) Log.w(TAG, "Download of ${item.uri} failed", e)
            recordFileId(item.uri)
            handleFailure(item, error, stats)
        }
    }

    private suspend fun onKeystoreBusy(item: DownloadEntity): ItemResult {
        val pause = breaker.onKeystoreBusy()
        val until = System.currentTimeMillis() + pause
        Log.w(TAG, "Keystore unavailable while storing ${item.uri}; pausing the queue for $pause ms")
        dao.scheduleRetry(item.uri, item.attempts, until, context.getString(R.string.data_dl_error_keystore))
        dao.deferPending(until)
        return if (pause > MAX_INLINE_WAIT_MS) ItemResult.Reschedule else ItemResult.Done
    }

    /**
     * Encrypts an audio key with the Keystore, waiting out a short outage (1, 2, 4, 8 s between
     * tries) so that a finished download is stored without a new `download.track`. Throws
     * [KeystoreUnavailableException] when the Keystore stays unavailable.
     */
    private suspend fun sealKey(plain: ByteArray): ByteArray {
        var delayMs = SEAL_RETRY_MS
        repeat(SEAL_ATTEMPTS - 1) {
            try {
                return withContext(Dispatchers.IO) { credentialStore.encrypt(plain) }
            } catch (e: KeystoreUnavailableException) {
                Log.w(TAG, "Keystore unavailable while sealing a key, retrying in $delayMs ms")
                delay(delayMs)
                delayMs *= 2
            }
        }
        return withContext(Dispatchers.IO) { credentialStore.encrypt(plain) }
    }

    /**
     * Stores which file the download of [uri] writes (`download.fileId`), so garbage collection keeps
     * its `.part` while the row exists, also once it failed or was cancelled. Best effort.
     */
    private suspend fun recordFileId(uri: String) {
        try {
            val result = withTimeoutOrNull(NATIVE_CALL_TIMEOUT_MS) {
                withContext(Dispatchers.Default) { rpc.callRaw("download.fileId", rpcArgs { put("uri", uri) }) }
            } ?: return
            val fileId = result.jsonObject["fileId"]?.jsonPrimitive?.contentOrNull ?: return
            dao.setFileId(uri, fileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not record the file of $uri", e)
        }
    }

    private suspend fun trackProgress(uri: String, title: String?, total: Int, host: DownloadHost, stats: RunStats) {
        var lastDbWrite = 0L
        var lastDbState: DownloadState? = null
        var lastUiUpdate = 0L
        var fileRecorded = false
        events.downloads.collect { p ->
            if (p.uri != uri) return@collect
            if (p.state != DownloadState.PREPARING && p.state != DownloadState.DOWNLOADING) return@collect
            if (p.state == DownloadState.DOWNLOADING && !fileRecorded) {
                // Bytes are about to land in a .part: from now on it must survive a killed process.
                fileRecorded = true
                recordFileId(uri)
            }
            val now = SystemClock.elapsedRealtime()
            // Persisted on a state change and every few seconds only (resume / crash recovery): each
            // write wakes every observer of the table. Live bytes go through [activity].
            if (p.state != lastDbState || now - lastDbWrite >= DB_THROTTLE_MS) {
                lastDbWrite = now
                lastDbState = p.state
                try {
                    dao.updateActiveProgress(uri, p.state, p.bytes, p.totalBytes)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Progress is cosmetic; never let it abort the download itself.
                    Log.w(TAG, "Progress update failed", e)
                }
            }
            if (now - lastUiUpdate >= UI_THROTTLE_MS) {
                lastUiUpdate = now
                _activity.value = DownloadActivity(
                    running = true,
                    currentUri = uri,
                    bytes = p.bytes,
                    totalBytes = p.totalBytes,
                    remaining = total - stats.processed,
                )
                val fraction = if (p.totalBytes > 0) p.bytes.toFloat() / p.totalBytes else 0f
                host.updateNotification(notifications.progress(title, stats.processed, total, fraction))
                host.reportTransferred(stats.transferredBytes + p.bytes)
            }
        }
    }

    private sealed interface Commit {
        /** Stored as change [seq] of the offline index. */
        class Kept(val seq: Long) : Commit
        data object Removed : Commit
        data object FileGone : Commit
    }

    /**
     * Persists a finished download (key encrypted) unless the item was removed meanwhile; returns
     * whether it did.
     */
    private suspend fun commit(item: DownloadEntity, record: OfflineTrackRecord, quality: Int): Boolean {
        val keyHex = record.keyHex.lowercase()
        val encryptedKey = sealKey(Hex.decode(keyHex))
        val recordJson = json.encodeToString(OfflineTrackRecord.serializer(), record.copy(keyHex = ""))
        val metadataJson = record.track?.let { json.encodeToString(Track.serializer(), it) }
            ?: record.episode?.let { json.encodeToString(Episode.serializer(), it) }
        val now = System.currentTimeMillis()
        val outcome = commitLock.withLock {
            // Under the lock, like removals: a file download.track reused from another download
            // (relinking) is deleted when that download is removed before this row names the file.
            val present = withContext(Dispatchers.IO) { File(record.path).isFile }
            val result = database.withTransaction {
                val row = dao.get(item.uri) ?: return@withTransaction Commit.Removed
                if (!present) return@withTransaction Commit.FileGone
                dao.upsert(
                    row.copy(
                        state = DownloadState.COMPLETED,
                        quality = quality,
                        fileId = record.fileId,
                        format = record.format,
                        encryptedKey = encryptedKey,
                        path = record.path,
                        sizeBytes = record.sizeBytes,
                        bytesDone = record.sizeBytes,
                        recordJson = recordJson,
                        metadataJson = metadataJson ?: row.metadataJson,
                        imagePath = record.imagePath,
                        completedAt = now,
                        lastValidatedAt = now,
                        error = null,
                        retryAt = null,
                    ),
                )
                Commit.Kept(index.next())
            }
            // Removed while downloading: drop the file unless another download uses it (images and
            // partial files are collected once the queue is idle).
            if (result == Commit.Removed && dao.countFileUsers(record.fileId) == 0) {
                withContext(Dispatchers.IO) { storage.deleteAudio(record.path) }
            }
            result
        }
        val seq = when (outcome) {
            is Commit.Kept -> outcome.seq
            Commit.Removed -> return false
            Commit.FileGone -> {
                Log.w(TAG, "The file of ${item.uri} was removed with another download; downloading it again")
                dao.requeue(item.uri)
                return false
            }
        }
        keys[item.uri] = keyHex
        index.add(listOf(record.copy(keyHex = keyHex)), seq)
        return true
    }

    private suspend fun handleFailure(item: DownloadEntity, e: NativeException, stats: RunStats): ItemResult {
        val message = describe(e)
        val online = engine.isOnline.value
        val now = System.currentTimeMillis()
        val pauseMs = breaker.onFailure(e.code, online, e.info.retryAfterMs)
        val result = when (val action = DownloadRules.onFailure(e.code, item.attempts, online, e.info.retryAfterMs)) {
            DownloadRules.FailureAction.StopRun -> {
                dao.markFailed(item.uri, item.attempts + 1, message)
                stats.failed++
                ItemResult.StopRun(message)
            }
            DownloadRules.FailureAction.WaitForNetwork -> {
                dao.requeue(item.uri)
                ItemResult.WaitForNetwork
            }
            is DownloadRules.FailureAction.Retry -> {
                Log.i(TAG, "Retrying ${item.uri} in ${action.delayMs} ms (${e.code})")
                dao.scheduleRetry(item.uri, action.attempts, System.currentTimeMillis() + action.delayMs, message)
                ItemResult.Done
            }
            is DownloadRules.FailureAction.Fail -> {
                Log.w(TAG, "Giving up on ${item.uri} after ${action.attempts} attempts: ${e.message}")
                dao.markFailed(item.uri, action.attempts, message)
                stats.failed++
                ItemResult.Done
            }
            DownloadRules.FailureAction.Throttled -> {
                // Not an attempt: the item waits with the rest of the queue.
                val pause = pauseMs ?: DownloadRules.rateLimitPauseMs(e.info.retryAfterMs, 1)
                dao.scheduleRetry(item.uri, item.attempts, now + pause, message)
                stats.throttledMs += pause
                if (DownloadRules.throttleBudgetSpent(stats.throttledMs)) {
                    Log.i(TAG, "Rate limited for ${stats.throttledMs} ms in this run: rescheduling")
                    ItemResult.Reschedule
                } else {
                    ItemResult.Done
                }
            }
        }
        if (pauseMs != null && result !is ItemResult.StopRun) {
            // Hold every pending row back: the loop then waits (inline or rescheduled) instead of
            // starting the next item against a service that is refusing them all.
            Log.i(TAG, "Pausing the download queue for $pauseMs ms (${e.code})")
            dao.deferPending(now + pauseMs)
        }
        return result
    }

    private fun describe(e: NativeException): String = when (e.code) {
        NativeErrorCode.PREMIUM_REQUIRED -> context.getString(R.string.data_dl_error_premium)
        NativeErrorCode.PLAYBACK_REFUSED -> context.getString(R.string.data_dl_error_refused)
        NativeErrorCode.NOT_LOGGED_IN, NativeErrorCode.BAD_CREDENTIALS -> context.getString(R.string.data_dl_error_login)
        NativeErrorCode.NOT_FOUND, NativeErrorCode.UNAVAILABLE -> context.getString(R.string.data_dl_error_unavailable)
        NativeErrorCode.NETWORK, NativeErrorCode.NOT_CONNECTED -> context.getString(R.string.data_dl_error_network)
        NativeErrorCode.RATE_LIMITED -> context.getString(R.string.data_dl_error_rate_limited)
        else -> e.info.message.ifBlank { context.getString(R.string.data_dl_error_generic) }
    }

    private fun displayTitle(metadataJson: String?): String? {
        if (metadataJson == null) return null
        return runCatching { json.decodeFromString(Track.serializer(), metadataJson).name }.getOrNull()
            ?: runCatching { json.decodeFromString(Episode.serializer(), metadataJson).name }.getOrNull()
    }

    /** Must hold [runLock] (no download in flight). */
    private suspend fun collectGarbage() {
        withContext(NonCancellable + Dispatchers.IO) {
            commitLock.withLock {
                if (dao.pendingCount() == 0) {
                    storage.collectGarbage(dao.allPaths(), dao.allFileIds(), dao.unfinishedFileIds(), dao.allImagePaths())
                }
            }
        }
    }

    private companion object {
        const val TAG = "DownloadRunner"
        const val ONLINE_TIMEOUT_MS = 60_000L
        const val MAX_INLINE_WAIT_MS = 2 * 60_000L
        const val MIN_WAIT_MS = 250L
        const val DB_THROTTLE_MS = 5_000L
        const val UI_THROTTLE_MS = 1_000L
        const val NATIVE_CALL_TIMEOUT_MS = 10_000L
        const val FILE_ID_CANCEL_TIMEOUT_MS = 2_000L
        const val SEAL_RETRY_MS = 1_000L
        const val SEAL_ATTEMPTS = 5
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_NETWORK_WAITS = 5
    }
}
