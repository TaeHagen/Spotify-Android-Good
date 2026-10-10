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
     * Work remains but cannot proceed now (offline, the session not coming online, internal storage
     * full, metered network not allowed): reschedule with system backoff (for internal storage the
     * host's constraints include storage not low).
     */
    RESCHEDULE,

    /**
     * The whole queue is held back until a known time, longer than a run waits
     * ([DownloadRules.MAX_INLINE_WAIT_MS]): Spotify's audio-key pacing or cool-down, a rate limit,
     * a connectivity or Keystore pause, a retry backoff (the rows' `retryAt`). The host finishes
     * without its backoff and [DownloadManager.scheduleResume] wakes the queue once, at the earliest
     * `retryAt`; it never counts against the host's retries.
     */
    PAUSED,

    /**
     * Stopped for a reason the user must resolve (cancel, account, offline mode, the chosen SD card
     * missing or full): do not reschedule. A mount, a change of location, the app coming back or
     * "Retry" schedule the queue again.
     */
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
 * held back, so the loop waits inline for a short pause and ends [RunOutcome.PAUSED] for a long one,
 * resumed at its end by [DownloadManager.scheduleResume]). So does
 * the engine's audio-key pacing ([DownloadRules.KEY_PACING], not a failure: Spotify limits how fast
 * an account gets keys, songs download in small batches), and its cool-down after Spotify throttled
 * keys ([DownloadRules.KEY_THROTTLED], minutes); both show as a [DownloadPause].
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
    private val storage: DownloadStorage,
    private val notifications: DownloadNotifications,
    private val keys: KeyCache,
    private val commitLock: Mutex,
    private val index: OfflineIndexSync,
    private val vault: KeyVault,
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

    /** The queue pause Spotify's audio-key limit imposed last ([DownloadRules.pauseFor]), until an item starts. */
    @Volatile private var keyPause: DownloadPause? = null

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

    /**
     * The queue cannot run for [message] (the chosen card is missing) while no run is active: shown
     * on the Downloads screen, and in a notification the first time.
     */
    fun reportStopped(message: String) {
        if (isRunning) return
        val shown = _activity.value.lastError == message
        _activity.value = DownloadActivity(lastError = message)
        if (!shown) notifications.showStopped(message)
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
        if (paused != null && paused > DownloadRules.MAX_INLINE_WAIT_MS) return RunOutcome.PAUSED
        // The settings as stored: a cold-started job must not act on the defaults shown before load.
        if (meteredNotAllowed(settings.awaitLoaded())) return RunOutcome.RESCHEDULE
        // Where the downloads go, before a session is brought up for them: a chosen card that is
        // missing or full stops the run, full internal storage waits for the host's constraint.
        when (val place = place()) {
            is Place.Ready -> Unit
            is Place.Blocked -> {
                notifications.showStopped(place.message)
                _activity.value = DownloadActivity(lastError = place.message)
                return place.outcome
            }
        }
        val holder = engine.acquire(HolderType.DOWNLOAD)
        val receiver = notifications.registerCancelReceiver(::requestCancel)
        val stats = RunStats()
        _activity.value = DownloadActivity(running = true)
        try {
            host.updateNotification(notifications.progress(null, 0, 0, 0f))
            notifications.clearDone()
            if (settings.awaitLoaded().offlineMode) return RunOutcome.STOPPED // resumed when offline mode ends
            if (!engine.awaitOnline(ONLINE_TIMEOUT_MS)) {
                // Logged out: nothing will ever come online (logout wipes the queue anyway). An
                // account Spotify refuses (no longer Premium) would be refused at every retry too.
                val refused = engine.state.value.error?.code in ACCOUNT_REFUSALS
                return if (engine.isLoggedIn.value && !refused) RunOutcome.RESCHEDULE else RunOutcome.STOPPED
            }
            var networkWaits = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                val item = dao.nextRunnable(now)
                if (item == null) {
                    when (val step = DownloadRules.idleStep(dao.earliestRetryAt(), now)) {
                        DownloadRules.IdleStep.Finish -> break // nothing pending at all
                        DownloadRules.IdleStep.Pause -> {
                            stats.endedForKeyLimit = keyPause != null
                            return RunOutcome.PAUSED
                        }
                        is DownloadRules.IdleStep.Wait -> {
                            showKeyPause(host, stats, now)
                            delay(step.ms)
                        }
                    }
                    continue
                }
                if (meteredNotAllowed(settings.awaitLoaded())) {
                    // The host's network constraint is stale (it predates the setting): never use
                    // mobile data against the setting; the manager re-creates the work.
                    Log.i(TAG, "On a metered network with mobile data downloads off: rescheduling")
                    return RunOutcome.RESCHEDULE
                }
                // The chosen location (setting) and its space, checked per item: a card can go or
                // fill up at any time.
                val root = when (val place = place()) {
                    is Place.Ready -> place.root
                    is Place.Blocked -> {
                        stats.stopMessage = place.message
                        notifications.showStopped(place.message)
                        return place.outcome
                    }
                }
                when (val result = processItem(item, root, host, stats)) {
                    ItemResult.Done -> Unit
                    ItemResult.WaitForNetwork -> {
                        // Bounded: a flapping connection hands the retry over to the system backoff.
                        if (++networkWaits > MAX_NETWORK_WAITS || !engine.awaitOnline(ONLINE_TIMEOUT_MS)) return RunOutcome.RESCHEDULE
                    }
                    ItemResult.Pause -> {
                        stats.endedForKeyLimit = keyPause != null
                        return RunOutcome.PAUSED
                    }
                    is ItemResult.StopRun -> {
                        // An account refusal leaves what was not tried queued (Resume tries again).
                        if (result.failPending) dao.failAllPending(result.message)
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
                val now = System.currentTimeMillis()
                // Only when the run ended for Spotify's key limit: a run the system stopped, or one
                // that ended for anything else, says that instead.
                val pause = keyPause?.takeIf { stats.endedForKeyLimit && it.until > now && pending > 0 }
                if (pause == null) keyPause = null
                when (DownloadRules.runNotice(stats.stopMessage != null, cancelRequested, pending, stats.processed)) {
                    DownloadRules.RunNotice.COMPLETE -> notifications.showSummary(stats.completed, stats.failed)
                    DownloadRules.RunNotice.PAUSED -> notifications.showStopped(
                        if (pause != null) {
                            context.getString(R.string.data_dl_paused_limited, stats.completed, stats.completed + pending, minutes(pause.until, now))
                        } else {
                            context.getString(R.string.data_dl_paused, stats.completed, stats.completed + pending)
                        },
                    )
                    DownloadRules.RunNotice.NONE -> Unit
                }
                // A pause for Spotify's key limit stays on the Downloads screen until the queue runs again.
                _activity.value = DownloadActivity(lastError = stats.stopMessage, pause = pause)
            }
        }
    }

    private sealed interface Place {
        /** Downloads can go to [root]. */
        class Ready(val root: File) : Place

        /** They cannot: [message] for the user, the run ends with [outcome] ([DownloadRules.storageOutcome]). */
        class Blocked(val message: String, val outcome: RunOutcome) : Place
    }

    /** The download location and its free space ([DownloadRules.storageCheck]); no session needed. */
    private suspend fun place(): Place {
        val root = storage.awaitRoot()
        val check = withContext(Dispatchers.IO) {
            DownloadRules.storageCheck(root?.path, storage.locations.internalRoot.path) {
                root?.let { storage.ensureDirs(it); storage.freeBytes(it) } ?: 0L
            }
        }
        val outcome = DownloadRules.storageOutcome(check) ?: return Place.Ready(checkNotNull(root))
        val message = if (check == DownloadRules.StorageCheck.LOCATION_MISSING) {
            context.getString(R.string.data_dl_error_location)
        } else {
            context.getString(R.string.data_dl_error_storage, Formatter.formatShortFileSize(context, DownloadRules.MIN_FREE_BYTES))
        }
        return Place.Blocked(message, outcome)
    }

    /** Mobile data downloads are off and the default network is metered (no network: false). */
    private fun meteredNotAllowed(prefs: Settings): Boolean {
        if (prefs.downloadOverCellular) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * The queue waits inline for Spotify's audio-key limit ([keyPause]): the Downloads screen and the
     * run's notification say so ("next songs in about N min") instead of the last item's progress.
     */
    private suspend fun showKeyPause(host: DownloadHost, stats: RunStats, now: Long) {
        val pause = keyPause?.takeIf { it.until > now } ?: return
        val pending = dao.pendingCount()
        _activity.value = DownloadActivity(running = true, remaining = pending, pause = pause)
        val text = context.getString(
            if (pause.reason == DownloadPause.Reason.PACING) R.string.data_dl_notif_paced else R.string.data_dl_notif_limited,
            minutes(pause.until, now),
        )
        host.updateNotification(notifications.waiting(text, stats.processed, stats.processed + pending))
    }

    /** "3 min" until [until] (at least 1). */
    private fun minutes(until: Long, now: Long): String {
        val minutes = DownloadRules.minutesUntil(until, now).coerceAtLeast(1)
        return context.resources.getQuantityString(R.plurals.data_dl_minutes, minutes, minutes)
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

        /** The run ended ([RunOutcome.PAUSED]) to wait for Spotify's audio-key limit ([keyPause]). */
        var endedForKeyLimit = false
        val processed get() = completed + failed
    }

    private sealed interface ItemResult {
        data object Done : ItemResult
        data object WaitForNetwork : ItemResult
        /** Stop the run for [message]; [failPending]: fail every pending row with it. */
        data class StopRun(val message: String, val failPending: Boolean = true) : ItemResult

        /** End the run with [RunOutcome.PAUSED] (the queue stays paused on its rows). */
        data object Pause : ItemResult
    }

    /**
     * Runs one item as its own child so it can be cancelled alone (item removed meanwhile): see
     * [awaitItem] for why the run then waits for the removal before it picks the next item.
     */
    private suspend fun processItem(item: DownloadEntity, root: File, host: DownloadHost, stats: RunStats): ItemResult = coroutineScope {
        val job = async { downloadItem(item, root, host, stats) }
        current = CurrentItem(item.uri, job)
        try {
            awaitItem(job, commitLock, cancelled = ItemResult.Done)
        } finally {
            current = null
        }
    }

    private suspend fun downloadItem(item: DownloadEntity, root: File, host: DownloadHost, stats: RunStats): ItemResult = coroutineScope {
        val quality = settings.awaitLoaded().downloadQuality.kbps
        // Removed since it was picked: nothing to download.
        if (dao.markPreparing(item.uri, quality) == 0) return@coroutineScope ItemResult.Done
        keyPause = null
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
                    put("dir", storage.audioDir(root).absolutePath)
                    put("imageDir", storage.imageDir(root).absolutePath)
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
        return if (pause > DownloadRules.MAX_INLINE_WAIT_MS) ItemResult.Pause else ItemResult.Done
    }

    /**
     * Seals an audio key with the downloads' data key ([KeyVault]: software; only the first seal of
     * the process needs the Keystore, to unseal or create the data key), waiting out a short Keystore
     * outage (1, 2, 4, 8 s between tries) so that a finished download is stored without a new
     * `download.track`. Throws [KeystoreUnavailableException] when the Keystore stays unavailable.
     */
    private suspend fun sealKey(plain: ByteArray, uri: String): ByteArray {
        var delayMs = SEAL_RETRY_MS
        repeat(SEAL_ATTEMPTS - 1) {
            try {
                return withContext(Dispatchers.IO) { vault.seal(plain, uri) }
            } catch (e: KeystoreUnavailableException) {
                Log.w(TAG, "Keystore unavailable while sealing a key, retrying in $delayMs ms")
                delay(delayMs)
                delayMs *= 2
            }
        }
        return withContext(Dispatchers.IO) { vault.seal(plain, uri) }
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
        val encryptedKey = sealKey(Hex.decode(keyHex), item.uri)
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
                        keyVersion = 1,
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
        val pauseMs = breaker.onFailure(e.code, online, e.info.retryAfterMs, e.info.context)
        val result = when (val action = DownloadRules.onFailure(e.code, item.attempts, online, e.info.retryAfterMs, e.info.context)) {
            DownloadRules.FailureAction.StopRun -> {
                dao.markFailed(item.uri, item.attempts + 1, message)
                stats.failed++
                ItemResult.StopRun(message)
            }
            DownloadRules.FailureAction.AccountRefused -> {
                Log.w(TAG, "Spotify refused the audio of ${item.uri} for the account: stopping, the rest stays queued")
                dao.markFailed(item.uri, item.attempts + 1, message)
                stats.failed++
                ItemResult.StopRun(message, failPending = false)
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
                    Log.i(TAG, "Rate limited for ${stats.throttledMs} ms in this run: pausing until it ends")
                    ItemResult.Pause
                } else {
                    ItemResult.Done
                }
            }
            DownloadRules.FailureAction.Paced -> {
                // Not an attempt and not a failure: the engine paces Spotify's audio keys (no key
                // was requested). The item waits with the rest of the queue for its turn.
                dao.scheduleRetry(item.uri, item.attempts, now + (pauseMs ?: DownloadRules.MIN_PACING_PAUSE_MS), message)
                ItemResult.Done
            }
        }
        if (pauseMs != null && result !is ItemResult.StopRun) {
            // Hold every pending row back: the loop then waits (inline or rescheduled) instead of
            // starting the next item against a service that is refusing them all.
            Log.i(TAG, "Pausing the download queue for $pauseMs ms (${e.code}${e.info.context?.let { " $it" } ?: ""})")
            dao.deferPending(now + pauseMs)
            keyPause = DownloadRules.pauseFor(e.code, e.info.context, pauseMs, now)
        }
        return result
    }

    private fun describe(e: NativeException): String = when (e.code) {
        NativeErrorCode.PREMIUM_REQUIRED -> context.getString(R.string.data_dl_error_premium)
        NativeErrorCode.PLAYBACK_REFUSED -> context.getString(R.string.data_dl_error_refused)
        NativeErrorCode.NOT_LOGGED_IN, NativeErrorCode.BAD_CREDENTIALS -> context.getString(R.string.data_dl_error_login)
        NativeErrorCode.NOT_FOUND, NativeErrorCode.UNAVAILABLE -> context.getString(
            if (e.info.context == DownloadRules.KEY_REFUSED) R.string.data_dl_error_refused_item else R.string.data_dl_error_unavailable,
        )
        NativeErrorCode.NETWORK, NativeErrorCode.NOT_CONNECTED -> context.getString(R.string.data_dl_error_network)
        NativeErrorCode.RATE_LIMITED -> context.getString(
            when (e.info.context) {
                DownloadRules.KEY_PACING -> R.string.data_dl_error_key_paced
                DownloadRules.KEY_THROTTLED -> R.string.data_dl_error_key_limited
                else -> R.string.data_dl_error_rate_limited
            },
        )
        else -> e.info.message.ifBlank { context.getString(R.string.data_dl_error_generic) }
    }

    private fun displayTitle(metadataJson: String?): String? {
        if (metadataJson == null) return null
        return runCatching { json.decodeFromString(Track.serializer(), metadataJson).name }.getOrNull()
            ?: runCatching { json.decodeFromString(Episode.serializer(), metadataJson).name }.getOrNull()
    }

    /** Must hold [runLock] (no download in flight). Not while downloads move between locations. */
    private suspend fun collectGarbage() {
        withContext(NonCancellable + Dispatchers.IO) {
            commitLock.withLock {
                if (dao.pendingCount() == 0 && !storage.relocating) {
                    storage.collectGarbage(dao.allPaths(), dao.unfinishedFileIds(), dao.allImagePaths())
                }
            }
        }
    }

    /**
     * Runs [block] while no run owns the queue (no download in flight, no garbage collection);
     * false without running it when a run does. A run that starts meanwhile finds the queue taken
     * and ends: the caller schedules again afterwards.
     */
    suspend fun whileIdle(block: suspend () -> Unit): Boolean {
        if (!runLock.tryLock()) return false
        try {
            block()
        } finally {
            runLock.unlock()
        }
        return true
    }

    private companion object {
        const val TAG = "DownloadRunner"
        val ACCOUNT_REFUSALS = setOf(NativeErrorCode.PREMIUM_REQUIRED, NativeErrorCode.PLAYBACK_REFUSED)
        const val ONLINE_TIMEOUT_MS = 60_000L
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

/**
 * Awaits one queue item's [job]. When only the item was cancelled (the run itself is still active),
 * that was a removal: the remover holds [removalLock] (the manager's mutation lock) from cancelling
 * the item until its rows are deleted, while the cancelled item has just put its row back into the
 * queue. Waiting for the lock before returning [cancelled] keeps the run's next pick from finding
 * that row again and downloading the removed item in full. No deadlock: the remover only waits for
 * [job], which is complete here. A stop of the whole run is rethrown.
 */
internal suspend fun <T> awaitItem(job: Deferred<T>, removalLock: Mutex, cancelled: T): T = try {
    job.await()
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    removalLock.withLock { }
    cancelled
}
