package com.taehagen.spotifygood.download

import android.app.Notification
import android.content.Context
import android.os.SystemClock
import android.text.format.Formatter
import android.util.Log
import androidx.room.withTransaction
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.data.callUnitOffMain
import com.taehagen.spotifygood.data.callWith
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.db.DownloadEntity
import com.taehagen.spotifygood.data.rpcArgs
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

/** How a download run ended; the host (job / worker) maps it to its reschedule semantics. */
internal enum class RunOutcome {
    /** The queue is drained. */
    FINISHED,

    /** Work remains but cannot proceed now (offline, backoff): reschedule with system backoff. */
    RESCHEDULE,

    /** Stopped for a reason the user must resolve (cancel, storage, account): do not reschedule. */
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
 * files are kept and resumed natively), mirrors `download` events into the database (≥ 500 ms apart)
 * and the notification, stores the record with its key encrypted, and registers it with
 * `offline.add`. Failures are retried with exponential backoff up to [DownloadRules.MAX_ATTEMPTS].
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
) {
    private val dao = database.downloads()
    private val json: Json = rpc.json
    private val runLock = Mutex()

    private class CurrentItem(val uri: String, val job: Deferred<ItemResult>)

    @Volatile private var current: CurrentItem? = null
    @Volatile private var session: Deferred<RunOutcome>? = null
    @Volatile private var cancelRequested = false

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
        val holder = engine.acquire(HolderType.DOWNLOAD)
        val receiver = notifications.registerCancelReceiver(::requestCancel)
        val stats = RunStats()
        _activity.value = DownloadActivity(running = true)
        try {
            dao.resetInterrupted()
            host.updateNotification(notifications.progress(null, 0, 0, 0f))
            if (settings.settings.value.offlineMode) return RunOutcome.STOPPED // resumed when offline mode ends
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
                val free = withContext(Dispatchers.IO) { storage.freeBytes() }
                if (free < DownloadRules.MIN_FREE_BYTES) {
                    val message = context.getString(
                        R.string.data_dl_error_storage,
                        Formatter.formatShortFileSize(context, DownloadRules.MIN_FREE_BYTES),
                    )
                    stats.stopMessage = message
                    notifications.showStopped(message)
                    return RunOutcome.STOPPED
                }
                when (val result = processItem(item, host, stats)) {
                    ItemResult.Done -> Unit
                    ItemResult.WaitForNetwork -> {
                        // Bounded: a flapping connection hands the retry over to the system backoff.
                        if (++networkWaits > MAX_NETWORK_WAITS || !engine.awaitOnline(ONLINE_TIMEOUT_MS)) return RunOutcome.RESCHEDULE
                    }
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
                if (stats.stopMessage == null) notifications.showSummary(stats.completed, stats.failed)
                _activity.value = DownloadActivity(lastError = stats.stopMessage)
            }
        }
    }

    private class RunStats {
        var completed = 0
        var failed = 0
        var transferredBytes = 0L
        var stopMessage: String? = null
        val processed get() = completed + failed
    }

    private sealed interface ItemResult {
        data object Done : ItemResult
        data object WaitForNetwork : ItemResult
        data class StopRun(val message: String) : ItemResult
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
        val quality = settings.settings.value.downloadQuality.kbps
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
            commit(item, record, quality)
            stats.completed++
            stats.transferredBytes += record.sizeBytes
            host.reportTransferred(stats.transferredBytes)
            ItemResult.Done
        } catch (e: CancellationException) {
            progress.cancel()
            // Back to the queue untouched (attempts unchanged); the native side keeps the .part file.
            withContext(NonCancellable) { dao.requeue(item.uri) }
            throw e
        } catch (e: Exception) {
            progress.cancelAndJoin()
            val error = e as? NativeException
                ?: NativeException(NativeErrorInfo(NativeErrorCode.INTERNAL, e.message ?: e.javaClass.simpleName))
            if (e !is NativeException) Log.w(TAG, "Download of ${item.uri} failed", e)
            handleFailure(item, error, stats)
        }
    }

    private suspend fun trackProgress(uri: String, title: String?, total: Int, host: DownloadHost, stats: RunStats) {
        var lastDbWrite = 0L
        var lastUiUpdate = 0L
        events.downloads.collect { p ->
            if (p.uri != uri) return@collect
            if (p.state != DownloadState.PREPARING && p.state != DownloadState.DOWNLOADING) return@collect
            val now = SystemClock.elapsedRealtime()
            if (now - lastDbWrite >= DB_THROTTLE_MS) {
                lastDbWrite = now
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

    /** Persists a finished download (key encrypted) unless the item was removed meanwhile. */
    private suspend fun commit(item: DownloadEntity, record: OfflineTrackRecord, quality: Int) {
        val keyHex = record.keyHex.lowercase()
        val encryptedKey = withContext(Dispatchers.IO) { credentialStore.encrypt(Hex.decode(keyHex)) }
        val recordJson = json.encodeToString(OfflineTrackRecord.serializer(), record.copy(keyHex = ""))
        val metadataJson = record.track?.let { json.encodeToString(Track.serializer(), it) }
            ?: record.episode?.let { json.encodeToString(Episode.serializer(), it) }
        val now = System.currentTimeMillis()
        val kept = commitLock.withLock {
            database.withTransaction {
                val row = dao.get(item.uri) ?: return@withTransaction false
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
                true
            }
        }
        if (!kept) {
            // Removed while downloading: drop the file (images are collected once the queue is idle).
            withContext(Dispatchers.IO) { storage.deleteAudio(record.path) }
            return
        }
        keys[item.uri] = keyHex
        try {
            withTimeoutOrNull(NATIVE_CALL_TIMEOUT_MS) {
                rpc.callUnitOffMain(
                    "offline.add",
                    rpcArgs { put("tracks", json.encodeToJsonElement(ListSerializer(OfflineTrackRecord.serializer()), listOf(record.copy(keyHex = keyHex)))) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The full index is pushed again (offline.setIndex) at the next engine start.
            Log.w(TAG, "offline.add failed for ${item.uri}", e)
        }
    }

    private suspend fun handleFailure(item: DownloadEntity, e: NativeException, stats: RunStats): ItemResult {
        val message = describe(e)
        return when (val action = DownloadRules.onFailure(e.code, item.attempts, engine.isOnline.value, e.info.retryAfterMs)) {
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
        }
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

    /** Must hold [runLock]. */
    private suspend fun collectGarbage() {
        withContext(NonCancellable + Dispatchers.IO) {
            commitLock.withLock {
                if (dao.pendingCount() == 0) storage.collectGarbage(dao.allPaths(), dao.allImagePaths())
            }
        }
    }

    private companion object {
        const val TAG = "DownloadRunner"
        const val ONLINE_TIMEOUT_MS = 60_000L
        const val MAX_INLINE_WAIT_MS = 2 * 60_000L
        const val MIN_WAIT_MS = 250L
        const val DB_THROTTLE_MS = 500L
        const val UI_THROTTLE_MS = 1_000L
        const val NATIVE_CALL_TIMEOUT_MS = 10_000L
        const val STOP_TIMEOUT_MS = 5_000L
        const val MAX_NETWORK_WAITS = 5
    }
}
