package com.taehagen.spotifygood.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.data.SpotifyUris
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.db.DownloadCollectionEntity
import com.taehagen.spotifygood.data.db.DownloadEntity
import com.taehagen.spotifygood.data.db.DownloadFileRow
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.engine.HolderType
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.TimeUnit

enum class CollectionType(val wire: String) {
    PLAYLIST("playlist"), ALBUM("album"), LIKED_SONGS("collection"), SHOW("show");

    companion object {
        fun fromWire(wire: String): CollectionType? = entries.firstOrNull { it.wire == wire }
    }
}

data class CollectionRef(val uri: String, val type: CollectionType, val name: String, val imageUrl: String?)

/** A downloaded collection: what it is, its item URIs (collection order) and when it was downloaded. */
data class DownloadedCollection(val ref: CollectionRef, val itemUris: List<String>, val addedAt: Long) {
    val itemCount: Int get() = itemUris.size
}

sealed interface CollectionDownloadStatus {
    data object None : CollectionDownloadStatus
    /** [done] of [total] items downloaded; [active] while work is pending. */
    data class InProgress(val done: Int, val total: Int, val active: Boolean) : CollectionDownloadStatus
    data object Complete : CollectionDownloadStatus
}

data class DownloadItem(
    val uri: String,
    val state: DownloadState,
    val bytes: Long,
    val totalBytes: Long,
    /** Track or Episode display metadata JSON (model.Track / model.Episode). */
    val metadataJson: String?,
    val imagePath: String?,
    val error: String?,
)

/** What the downloader is doing right now (Downloads screen header, banners). */
data class DownloadActivity(
    val running: Boolean = false,
    val currentUri: String? = null,
    val bytes: Long = 0,
    val totalBytes: Long = 0,
    /** Items still pending (including the current one) while running. */
    val remaining: Int = 0,
    /** Why the last run stopped early (storage, account …); null when it did not. */
    val lastError: String? = null,
) {
    /** 0..1 for [currentUri] while its size is known. */
    val progress: Float? get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/**
 * Offline downloads (docs/ARCHITECTURE.md §9.7).
 *
 * * Rows ([DownloadEntity]) are the queue and the source of truth; collections
 *   ([DownloadCollectionEntity]) record which items they contain. An item shared by several
 *   collections is downloaded once and only deleted when no downloaded collection contains it and
 *   it was not downloaded on its own ([DownloadEntity.individual]).
 * * Execution: API 34+ with the app visible → user-initiated data transfer job
 *   ([DownloadJobService]); otherwise (API < 34, or app in background) → unique WorkManager work
 *   `downloads` ([DownloadWorker], dataSync FGS). Both delegate to [DownloadRunner].
 * * Downloaded collections are re-synced daily ([DownloadSyncWorker]) and when the session comes
 *   online after 12 h: new items are queued, dropped ones removed (respecting shared membership),
 *   and downloads older than 30 days are re-validated. Items the user removed individually from a
 *   downloaded collection stay removed until the collection is downloaded again. Only a complete
 *   resolution drops items ([CollectionResolver.Resolved.complete]): a failed or short lookup never
 *   deletes downloads. A collection whose sync keeps failing is retried with a growing backoff
 *   ([DownloadRules.nextSyncAt]). Members the catalog reports as not playable here are kept in the
 *   membership but not queued, and do not hold the collection status back.
 * * Removal deletes files, rows and the native offline index entries.
 * * The native offline index follows the database through numbered changes ([OfflineIndexSync]):
 *   every commit and removal takes its number under [mutex] with its database write.
 * * Writes (download, remove, remove all, retry, sync; settings changes are observed on [scope])
 *   run on [scope], not in the caller ([detached]): a caller that goes away (a page popped) only
 *   stops waiting, so a write never stops between its database commit and the file deletion,
 *   `offline.add` / `offline.remove` and scheduling that must follow it.
 */
class DownloadManager(
    context: Context,
    private val scope: CoroutineScope,
    private val database: AppDatabase,
    private val rpc: NativeRpc,
    events: NativeEvents,
    private val engine: SpotifyEngine,
    private val settings: SettingsRepository,
    private val credentialStore: CredentialStore,
) {
    private val appContext = context.applicationContext
    private val dao = database.downloads()
    private val collectionDao = database.collections()
    private val json = rpc.json
    private val itemsSerializer = ListSerializer(String.serializer())

    /** Serializes membership changes, removals, the runner's commits and offline index snapshots. */
    private val mutex = Mutex()
    private val syncMutex = Mutex()
    private val keys = KeyCache()
    private val resolver = CollectionResolver(rpc, json)
    private val index = OfflineIndexSync(rpc)

    internal val storage = DownloadStorage(appContext)
    internal val notifications = DownloadNotifications(appContext)
    internal val runner = DownloadRunner(
        appContext, database, rpc, events, engine, settings, credentialStore, storage, notifications, keys, mutex, index,
    )

    /** True while [DownloadJobService] runs a job (it must not be replaced then, see [scheduleExecution]). */
    @Volatile internal var jobExecuting = false

    /** Registers downloads an index push left out because the Keystore was busy ([registerLate]). */
    @Volatile private var lateKeys: Job? = null

    /** URIs of completed downloads (hot), iterating newest download first (Android Auto queue order). */
    val downloadedUris: StateFlow<Set<String>> = dao.observeCompletedUris()
        .map<List<String>, Set<String>> { LinkedHashSet(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** uri → state of every row; one shared database observer for all per-item / collection flows. */
    private val states: Flow<Map<String, DownloadState>> = dao.observeStates()
        .map { rows -> rows.associate { it.uri to it.state } }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    val items: Flow<List<DownloadItem>> = dao.observeListRows()
        .map { rows ->
            rows.map { DownloadItem(it.uri, it.state, it.bytesDone, it.sizeBytes, it.metadataJson, it.imagePath, it.error) }
        }
        .flowOn(Dispatchers.Default)
    val usedBytes: Flow<Long> = dao.observeUsedBytes()
    val pendingCount: Flow<Int> = dao.observePendingCount()

    /** Downloaded collections, newest first (one shared database observer). */
    val collections: Flow<List<DownloadedCollection>> = collectionDao.observeAll()
        .map { list -> list.map { it.toDownloadedCollection() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    /** Live state of the downloader (current item, bytes, last stop reason). */
    val activity: StateFlow<DownloadActivity> get() = runner.activity

    init {
        scope.launch {
            try {
                // Resume after process death / reboot (a non-persisted job does not survive a reboot);
                // work left in a long backoff by an earlier process starts again now.
                if (dao.pendingCount() > 0) scheduleExecution(kick = true)
                updateSyncSchedule(collectionDao.count() > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Startup scheduling failed", e)
            }
        }
        scope.launch {
            // Network policy or offline mode changed: reschedule with the new constraints. From the
            // settings as stored (never the defaults shown before DataStore has loaded: on a cold start
            // that step would look like a mobile-data change and stop the job that started us).
            DownloadRules.policyChanges(settings.persisted.map { it.downloadOverCellular to it.offlineMode })
                .collect { change ->
                    if (change.offline) return@collect
                    if (change.cellularChanged && runner.isRunning) {
                        // The running job / worker keeps the old network constraint (and downloads over
                        // the mobile data the user just turned off): stop it; its item resumes from the
                        // .part under the new constraint.
                        runner.stopAndAwaitIdle()
                    }
                    scheduleExecution(replace = change.cellularChanged)
                }
        }
        scope.launch {
            engine.isOnline.filter { it }.collect {
                syncIfStale()
                // Work waiting out a backoff from an unreachable network starts now.
                if (!runner.isRunning && dao.pendingCount() > 0) scheduleExecution(kick = true)
            }
        }
        scope.launch(Dispatchers.Main) {
            // Back in the app: resume a queue that stopped (storage was full, retries ran out …).
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    scope.launch {
                        try {
                            if (!runner.isRunning && dao.pendingCount() > 0) scheduleExecution(kick = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Resuming downloads failed", e)
                        }
                    }
                }
            })
        }
    }

    fun state(uri: String): Flow<DownloadState?> = states.map { it[uri] }.distinctUntilChanged()

    fun collectionStatus(uri: String): Flow<CollectionDownloadStatus> =
        combine(
            collectionDao.observe(uri)
                .map { entity -> entity?.let { decodeItems(it.itemUrisJson) to decodeItems(it.unavailableUrisJson).toHashSet() } }
                .distinctUntilChanged(),
            states,
        ) { members, states -> DownloadRules.collectionStatus(members?.first, states, members?.second.orEmpty()) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)

    /** True while [uri] is a downloaded collection (toggle state on album/playlist screens). */
    fun isCollectionDownloaded(uri: String): Flow<Boolean> = collectionDao.observe(uri).map { it != null }.distinctUntilChanged()

    /**
     * Downloads a playlist / album / Liked Songs / show (newest [CollectionResolver.MAX_SHOW_EPISODES]
     * episodes) and keeps it in sync. Resolving the items needs the network; throws
     * [com.taehagen.spotifygood.nativebridge.NativeException] when that fails. Calling it again
     * re-queues failed and individually removed items.
     */
    suspend fun downloadCollection(ref: CollectionRef): Unit = scope.detached { downloadCollectionNow(ref) }

    private suspend fun downloadCollectionNow(ref: CollectionRef) {
        val found = requireNotNull(resolver.resolve(ref.type, ref.uri))
        if (found.items.isEmpty() && !found.complete) {
            // Nothing listed and the lookup is not trustworthy: report it instead of storing an empty
            // collection that would show as downloaded.
            throw NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "Could not load the items of ${ref.name.ifBlank { ref.uri }}"))
        }
        val resolved = withMetadata(found, known = emptySet())
        val removed = mutex.withLock {
            val existing = collectionDao.get(ref.uri)
            val now = System.currentTimeMillis()
            val entity = DownloadCollectionEntity(
                uri = ref.uri,
                type = ref.type.wire,
                name = ref.name.ifBlank { resolved.name ?: existing?.name ?: ref.name },
                imageUrl = ref.imageUrl ?: resolved.imageUrl ?: existing?.imageUrl,
                itemUrisJson = existing?.itemUrisJson ?: EMPTY_ITEMS,
                revision = existing?.revision,
                addedAt = existing?.addedAt ?: now,
                lastSyncedAt = existing?.lastSyncedAt,
                lastAttemptAt = existing?.lastAttemptAt,
                syncFailures = existing?.syncFailures ?: 0,
                unavailableUrisJson = existing?.unavailableUrisJson ?: EMPTY_ITEMS,
            )
            applyMembershipLocked(entity, resolved, userInitiated = true).removal
        }
        afterRemoval(listOf(removed))
        updateSyncSchedule(true)
        scheduleExecution(kick = true)
    }

    /** Stops keeping [uri] offline; deletes its items unless another download still needs them. */
    suspend fun removeCollection(uri: String): Unit = scope.detached { removeCollectionNow(uri) }

    private suspend fun removeCollectionNow(uri: String) {
        val removed = mutex.withLock {
            val entity = collectionDao.get(uri) ?: return
            val others = collectionDao.getAll().filter { it.uri != uri }.map { decodeItems(it.itemUrisJson) }
            val toDelete = DownloadRules.itemsToDelete(decodeItems(entity.itemUrisJson), others, dao.individualUris().toHashSet())
            runner.cancelItems(toDelete)
            val files = fileRows(toDelete)
            database.withTransaction {
                collectionDao.delete(uri)
                deleteRows(toDelete)
            }
            deleteFiles(files)
            Removal(toDelete, index.next())
        }
        afterRemoval(listOf(removed))
        if (collectionDao.count() == 0) updateSyncSchedule(false)
    }

    /** Downloads single tracks / episodes (kept until removed, independent of collections). */
    suspend fun downloadItems(uris: List<String>): Unit = scope.detached { downloadItemsNow(uris) }

    private suspend fun downloadItemsNow(uris: List<String>) {
        val targets = uris.filter(SpotifyUris::isPlayableItem).distinct()
        if (targets.isEmpty()) return
        // Names for the Downloads screen while queued; best effort (the record brings them anyway).
        val metadata = try {
            withTimeout(METADATA_TIMEOUT_MS) { resolver.metadata(targets) }
        } catch (e: TimeoutCancellationException) {
            emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        mutex.withLock {
            val now = System.currentTimeMillis()
            val quality = settings.awaitLoaded().downloadQuality.kbps
            database.withTransaction {
                insertRows(targets.map { CollectionResolver.Item(it, metadata[it]) }, quality, individual = true, now = now)
                targets.chunked(SQL_CHUNK).forEach {
                    dao.setIndividual(it, true)
                    dao.requeueFailed(it)
                }
                metadata.forEach { (uri, meta) -> dao.fillMetadata(uri, meta) }
            }
        }
        scheduleExecution(kick = true)
    }

    /** Deletes the given downloads (files, rows, offline index), whatever collection they belong to. */
    suspend fun removeItems(uris: List<String>): Unit = scope.detached { removeItemsNow(uris) }

    private suspend fun removeItemsNow(uris: List<String>) {
        val targets = uris.distinct()
        if (targets.isEmpty()) return
        val removed = mutex.withLock {
            runner.cancelItems(targets)
            val files = fileRows(targets)
            database.withTransaction { deleteRows(targets) }
            deleteFiles(files)
            Removal(files.map { it.uri }, index.next())
        }
        afterRemoval(listOf(removed))
    }

    /** Deletes every download and collection and cancels pending work (settings "Remove all", logout). */
    suspend fun removeAll(): Unit = scope.detached { removeAllNow() }

    private suspend fun removeAllNow() {
        cancelScheduledWork()
        runner.stop()
        val removal = mutex.withLock {
            val all = dao.allUris()
            database.withTransaction {
                dao.deleteAll()
                collectionDao.deleteAll()
            }
            withContext(Dispatchers.IO) { storage.deleteAll() }
            keys.clear()
            Removal(all, index.next())
        }
        notifications.cancelAll()
        index.remove(removal.uris, removal.seq)
    }

    /**
     * Puts failed and cancelled downloads back into the queue and (re)starts the queue, also when only
     * pending items wait (a run stopped because storage was full).
     */
    suspend fun retryFailed(): Unit = scope.detached { retryFailedNow() }

    private suspend fun retryFailedNow() {
        dao.requeueFailed()
        scheduleExecution(kick = true)
    }

    /**
     * Decrypted records of all completed downloads: the snapshot the engine pushes with
     * `offline.setIndex`. It is announced natively (`offline.beginIndex`) with the number of the last
     * change it contains, so commits and removals made while it is built and sent survive the push.
     */
    suspend fun offlineRecords(): List<OfflineTrackRecord> = withContext(Dispatchers.IO) {
        val (seq, rows) = mutex.withLock { index.last() to dao.withState(DownloadState.COMPLETED) }
        val records = ArrayList<OfflineTrackRecord>(rows.size)
        val undecryptable = ArrayList<String>()
        val missing = ArrayList<String>()
        val keystoreBusy = ArrayList<String>()
        var keystoreDown = false
        for (row in rows) {
            when (val result = offlineRecord(row, skipKeystore = keystoreDown)) {
                is RecordResult.Ready -> records += result.record
                RecordResult.Unreadable -> undecryptable += row.uri
                RecordResult.Missing -> missing += row.uri
                RecordResult.KeystoreBusy -> {
                    keystoreBusy += row.uri
                    // One retry cycle per pass, not one per row: the rest wait for registerLate.
                    keystoreDown = true
                }
            }
        }
        val now = System.currentTimeMillis()
        undecryptable.chunked(SQL_CHUNK).forEach { dao.markUnavailable(it, appContext.getString(R.string.data_dl_error_key), now) }
        missing.chunked(SQL_CHUNK).forEach { dao.markMissing(it, appContext.getString(R.string.data_dl_error_missing_file), now) }
        if (undecryptable.isNotEmpty() || missing.isNotEmpty() || keystoreBusy.isNotEmpty()) {
            Log.w(
                TAG,
                "Skipped ${undecryptable.size} undecryptable, ${missing.size} missing and " +
                    "${keystoreBusy.size} downloads whose key the Keystore could not decrypt right now",
            )
        }
        index.beginSnapshot(seq)
        lateKeys?.cancel()
        // Left out of this push only (still COMPLETED): registered as soon as the Keystore answers.
        if (keystoreBusy.isNotEmpty()) lateKeys = scope.launch { registerLate(keystoreBusy) }
        records
    }

    private sealed interface RecordResult {
        data class Ready(val record: OfflineTrackRecord) : RecordResult
        data object Unreadable : RecordResult
        data object Missing : RecordResult
        data object KeystoreBusy : RecordResult
    }

    /**
     * The decrypted index record of a COMPLETED [row]. Blocking (file check, Keystore). With
     * [skipKeystore] (it was busy for an earlier row of this pass) only a cached key is used.
     */
    private fun offlineRecord(row: DownloadEntity, skipKeystore: Boolean): RecordResult {
        val record = row.recordJson?.let { runCatching { json.decodeFromString(OfflineTrackRecord.serializer(), it) }.getOrNull() }
            ?: return RecordResult.Unreadable
        val path = row.path ?: record.path
        if (!File(path).isFile) return RecordResult.Missing
        val keyHex = keys[row.uri] ?: if (skipKeystore) return RecordResult.KeystoreBusy else when (val key = decryptKey(row)) {
            // Not overwriting a key a newer commit of this URI cached meanwhile; this row's record
            // still gets this row's key.
            is KeyResult.Key -> key.hex.also { keys.remember(row.uri, it) }
            KeyResult.Unreadable -> return RecordResult.Unreadable
            KeyResult.KeystoreBusy -> return RecordResult.KeystoreBusy
        }
        return RecordResult.Ready(record.copy(keyHex = keyHex, path = path))
    }

    /**
     * Registers the downloads [uris] that an index push left out because the Keystore could not
     * decrypt their keys right now, retrying with backoff (bounded; the next engine start pushes
     * everything again). Only reading the rows and numbering the change happen under [mutex]: a
     * removal after the read takes a later number and wins over the registration, so the Keystore
     * (with its retry sleeps) is never called with the lock held.
     */
    private suspend fun registerLate(uris: List<String>) {
        var waiting = uris
        var delayMs = LATE_KEY_RETRY_MS
        repeat(LATE_KEY_ATTEMPTS) {
            delay(delayMs)
            delayMs *= 4
            waiting = withContext(Dispatchers.IO) {
                val (seq, rows) = mutex.withLock {
                    index.next() to waiting.chunked(SQL_CHUNK).flatMap { dao.getAll(it) }.filter { it.state == DownloadState.COMPLETED }
                }
                val stillBusy = ArrayList<String>()
                val records = ArrayList<OfflineTrackRecord>()
                val unreadable = ArrayList<DownloadEntity>()
                var keystoreDown = false
                for (row in rows) {
                    ensureActive() // a newer push supersedes this one
                    when (val result = offlineRecord(row, skipKeystore = keystoreDown)) {
                        is RecordResult.Ready -> records += result.record
                        RecordResult.Unreadable -> unreadable += row
                        RecordResult.Missing -> Unit // the next push marks it
                        RecordResult.KeystoreBusy -> {
                            stillBusy += row.uri
                            keystoreDown = true
                        }
                    }
                }
                index.add(records, seq)
                val now = System.currentTimeMillis()
                val error = appContext.getString(R.string.data_dl_error_key)
                // Only the download that was read (not one removed and downloaded again meanwhile).
                unreadable.forEach { row -> row.completedAt?.let { dao.markUnavailableIfUnchanged(row.uri, it, error, now) } }
                stillBusy
            }
            if (waiting.isEmpty()) return
        }
        Log.w(TAG, "${waiting.size} downloads stay out of the offline index until the next engine start (Keystore unavailable)")
    }

    /**
     * Re-syncs downloaded collections with their sources (new/removed items) and re-validates
     * downloads older than 30 days. Holds the engine (DOWNLOAD) meanwhile; does nothing when the
     * session cannot come online. Concurrent calls are coalesced.
     */
    suspend fun syncCollections() {
        sync()
    }

    /**
     * [syncCollections]; false when it could not run because the session did not come online. With
     * [onlyDue], collections whose next sync ([DownloadRules.nextSyncAt]) is still ahead are skipped.
     */
    internal suspend fun sync(onlyDue: Boolean = false): Boolean = scope.detached { syncNow(onlyDue) }

    private suspend fun syncNow(onlyDue: Boolean): Boolean {
        if (!syncMutex.tryLock()) return true // another sync is running
        try {
            if (settings.awaitLoaded().offlineMode) return true
            val startedAt = System.currentTimeMillis()
            val all = collectionDao.getAll()
            if (all.isEmpty()) {
                updateSyncSchedule(false)
                return true
            }
            val holder = engine.acquire(HolderType.DOWNLOAD)
            try {
                if (!engine.awaitOnline(SYNC_ONLINE_TIMEOUT_MS)) return false
                var added = 0
                val removed = ArrayList<Removal>()
                for (collection in all) {
                    val type = CollectionType.fromWire(collection.type) ?: continue
                    if (onlyDue && DownloadRules.nextSyncAt(collection.lastSyncedAt, collection.lastAttemptAt, collection.syncFailures) > startedAt) continue
                    val resolved = try {
                        resolver.resolve(type, collection.uri, collection.revision)
                            ?.let { withMetadata(it, known = decodeItems(collection.itemUrisJson).toHashSet()) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync of ${collection.uri} failed", e)
                        mutex.withLock { collectionDao.recordSyncFailure(collection.uri, System.currentTimeMillis()) }
                        continue
                    }
                    if (resolved != null && !resolved.complete) Log.w(TAG, "Sync of ${collection.uri} was incomplete: nothing removed")
                    val result = mutex.withLock {
                        val current = collectionDao.get(collection.uri) ?: return@withLock null // removed meanwhile
                        if (resolved == null) {
                            // Unchanged playlist revision.
                            val now = System.currentTimeMillis()
                            collectionDao.upsert(current.copy(lastSyncedAt = now, lastAttemptAt = now, syncFailures = 0))
                            null
                        } else {
                            applyMembershipLocked(current, resolved, userInitiated = false)
                        }
                    } ?: continue
                    added += result.added
                    removed += result.removal
                }
                revalidate()
                afterRemoval(removed)
                if (added > 0) scheduleExecution()
                return true
            } finally {
                holder.release()
            }
        } finally {
            syncMutex.unlock()
        }
    }

    // ---- hooks for the job / workers --------------------------------------------------------------

    internal suspend fun hasCollections(): Boolean = collectionDao.count() > 0

    // ---- membership ----------------------------------------------------------------------------------

    private class MembershipResult(val added: Int, val removal: Removal)

    /** Downloads deleted by one change of the offline index ([OfflineIndexSync.next]). */
    private class Removal(val uris: List<String>, val seq: Long)

    /**
     * Must hold [mutex]. Stores [resolved] as the new membership of [entity], queues items (all of
     * them when [userInitiated], otherwise only the ones new since the last sync), re-queues failed
     * items when [userInitiated], and deletes downloads of dropped items nothing else references.
     */
    private suspend fun applyMembershipLocked(
        entity: DownloadCollectionEntity,
        resolved: CollectionResolver.Resolved,
        userInitiated: Boolean,
    ): MembershipResult {
        val diff = DownloadRules.updateMembership(decodeItems(entity.itemUrisJson), resolved.items.map { it.uri }, resolved.complete)
        val newItems = diff.items
        val availability = DownloadRules.updateAvailability(
            old = decodeItems(entity.unavailableUrisJson).toHashSet(),
            listed = resolved.items.mapTo(HashSet()) { it.uri },
            listedUnavailable = resolved.items.filter { it.unavailable }.mapTo(HashSet()) { it.uri },
            complete = resolved.complete,
        )
        val others = collectionDao.getAll().filter { it.uri != entity.uri }.map { decodeItems(it.itemUrisJson) }
        val toDelete = if (diff.dropped.isEmpty()) {
            emptyList()
        } else {
            DownloadRules.itemsToDelete(diff.dropped, others + listOf(newItems), dao.individualUris().toHashSet())
        }
        runner.cancelItems(toDelete)
        val files = fileRows(toDelete)
        // Not playable here: kept as members, never queued (the download would fail every time).
        // Members that became playable again are queued like new ones.
        val toQueue = if (userInitiated) {
            resolved.items.filter { !it.unavailable }
        } else {
            val wanted = diff.added.toHashSet() + availability.revived
            resolved.items.filter { it.uri in wanted && !it.unavailable }
        }
        val now = System.currentTimeMillis()
        val quality = settings.awaitLoaded().downloadQuality.kbps
        database.withTransaction {
            collectionDao.upsert(
                entity.copy(
                    itemUrisJson = json.encodeToString(itemsSerializer, newItems),
                    // Incomplete: try the whole listing again next time (the revision would skip it).
                    revision = if (resolved.complete) resolved.revision ?: entity.revision else entity.revision,
                    lastSyncedAt = if (resolved.complete) now else entity.lastSyncedAt,
                    lastAttemptAt = now,
                    syncFailures = if (resolved.complete) 0 else entity.syncFailures + 1,
                    unavailableUrisJson = json.encodeToString(itemsSerializer, newItems.filter { it in availability.unavailable }),
                ),
            )
            insertRows(toQueue, quality, individual = false, now = now)
            if (userInitiated) newItems.filter { it !in availability.unavailable }.chunked(SQL_CHUNK).forEach { dao.requeueFailed(it) }
            deleteRows(toDelete)
        }
        deleteFiles(files)
        return MembershipResult(toQueue.size, Removal(toDelete, index.next()))
    }

    /**
     * [resolved] with display metadata for the items that lack it (Liked Songs list URIs only) and will
     * get a new row: not in [known] (the stored membership; sync only queues new items) and without a
     * row yet. Best effort: rows without metadata get it from the downloaded record.
     */
    private suspend fun withMetadata(resolved: CollectionResolver.Resolved, known: Set<String>): CollectionResolver.Resolved {
        val candidates = resolved.items.filter { it.metadataJson == null && it.uri !in known }.map { it.uri }
        if (candidates.isEmpty()) return resolved
        val existing = candidates.chunked(SQL_CHUNK).flatMap { dao.existingUris(it) }.toHashSet()
        val wanted = candidates.filter { it !in existing }
        if (wanted.isEmpty()) return resolved
        val metadata = resolver.metadataBestEffort(wanted, COLLECTION_METADATA_TIMEOUT_MS)
        if (metadata.isEmpty()) return resolved
        return resolved.copy(items = resolved.items.map { item -> metadata[item.uri]?.let { item.copy(metadataJson = it) } ?: item })
    }

    private suspend fun insertRows(items: List<CollectionResolver.Item>, quality: Int, individual: Boolean, now: Long) {
        // addedAt offsets keep collection order in the FIFO queue.
        val rows = items.mapIndexed { i, item ->
            DownloadEntity(
                uri = item.uri,
                state = DownloadState.QUEUED,
                quality = quality,
                metadataJson = item.metadataJson,
                addedAt = now + i,
                individual = individual,
            )
        }
        rows.chunked(SQL_CHUNK).forEach { dao.insertIfAbsent(it) }
    }

    private suspend fun deleteRows(uris: List<String>) {
        uris.chunked(SQL_CHUNK).forEach { dao.delete(it) }
        keys.remove(uris)
    }

    private suspend fun fileRows(uris: List<String>): List<DownloadFileRow> = uris.chunked(SQL_CHUNK).flatMap { dao.fileRows(it) }

    /**
     * Must hold [mutex], after [rows] were deleted. Deletes their audio and images unless a remaining
     * download still uses them (two downloads can share one file: relinking, the same recording in
     * two releases). Partial files are left to garbage collection, which knows whether an unfinished
     * download still resumes them.
     */
    private suspend fun deleteFiles(rows: List<DownloadFileRow>) {
        if (rows.isEmpty()) return
        val audio = DownloadRules.audioToDelete(rows.map { it.path }, dao.allPaths(), dao.allFileIds())
        val remainingImages = dao.allImagePaths().toHashSet()
        withContext(Dispatchers.IO) {
            audio.forEach(storage::deleteAudio)
            rows.forEach { row ->
                if (row.imagePath != null && row.imagePath !in remainingImages) storage.deleteImage(row.imagePath)
            }
        }
    }

    /** After deletions (outside [mutex]): drop them from the native index and collect orphans. */
    private suspend fun afterRemoval(removals: List<Removal>) {
        val nonEmpty = removals.filter { it.uris.isNotEmpty() }
        if (nonEmpty.isEmpty()) return
        // Each with its own number: a later commit of one of these URIs (downloaded again) must win.
        nonEmpty.forEach { index.remove(it.uris, it.seq) }
        runner.collectGarbageIfIdle()
    }

    private suspend fun revalidate() {
        val now = System.currentTimeMillis()
        val stale = dao.completedUrisNotValidatedSince(now - DownloadRules.REVALIDATE_AFTER_MS)
        if (stale.isEmpty()) return
        val playable = try {
            resolver.playability(stale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Re-validation failed", e)
            return
        }
        val valid = stale.filter { playable[it] == true }
        val gone = stale.filter { playable[it] == false }
        valid.chunked(SQL_CHUNK).forEach { dao.markValidated(it, now) }
        if (gone.isNotEmpty()) {
            // Marked failed (not playable offline any more); the file stays until the user removes it.
            val message = appContext.getString(R.string.data_dl_error_unplayable)
            val seq = mutex.withLock {
                gone.chunked(SQL_CHUNK).forEach { dao.markUnavailable(it, message, now) }
                keys.remove(gone)
                index.next()
            }
            index.remove(gone, seq)
        }
    }

    private suspend fun syncIfStale() {
        try {
            val now = System.currentTimeMillis()
            val due = collectionDao.syncStates().any {
                CollectionType.fromWire(it.type) != null && DownloadRules.nextSyncAt(it.lastSyncedAt, it.lastAttemptAt, it.syncFailures) <= now
            }
            if (due) sync(onlyDue = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Collection sync failed", e)
        }
    }

    private sealed interface KeyResult {
        data class Key(val hex: String) : KeyResult
        data object Unreadable : KeyResult
        data object KeystoreBusy : KeyResult
    }

    /** Blocking (Keystore). Tells a transient Keystore failure from a key that is unusable for good. */
    private fun decryptKey(row: DownloadEntity): KeyResult {
        val cipher = row.encryptedKey ?: return KeyResult.Unreadable
        return try {
            KeyResult.Key(Hex.encode(credentialStore.decrypt(cipher)))
        } catch (e: Exception) {
            when (DownloadRules.keyFailure(e)) {
                DownloadRules.KeyFailure.RETRY_LATER -> {
                    Log.w(TAG, "Keystore unavailable for the key of ${row.uri}; trying again later")
                    KeyResult.KeystoreBusy
                }
                DownloadRules.KeyFailure.UNREADABLE -> {
                    Log.w(TAG, "Could not decrypt the key of ${row.uri}", e)
                    KeyResult.Unreadable
                }
            }
        }
    }

    private fun DownloadCollectionEntity.toDownloadedCollection() = DownloadedCollection(
        // Unknown types (rows from a newer app version) stay listed, and removable, as playlists.
        ref = CollectionRef(uri, CollectionType.fromWire(type) ?: CollectionType.PLAYLIST, name, imageUrl),
        itemUris = decodeItems(itemUrisJson),
        addedAt = addedAt,
    )

    private fun decodeItems(itemUrisJson: String): List<String> =
        try {
            json.decodeFromString(itemsSerializer, itemUrisJson)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable collection membership", e)
            emptyList()
        }

    // ---- scheduling ----------------------------------------------------------------------------------

    /**
     * Makes sure pending items get downloaded: nothing to do while a run is active (it picks new rows
     * up); otherwise a user-initiated job when allowed (API 34+, app visible), else WorkManager.
     *
     * Pending work is kept unless: [replace] (the network policy changed; the caller stopped a running
     * run first), its network constraint does not match the setting (scheduled by an earlier process),
     * or [kick] (a user action, app start, coming online) while it waits out a retry backoff, which
     * the system would otherwise let grow to hours. A job that is executing is never replaced except
     * for [replace].
     */
    private suspend fun scheduleExecution(replace: Boolean = false, kick: Boolean = false): Unit = withContext(Dispatchers.IO) {
        try {
            if (runner.isRunning) return@withContext
            val pending = dao.pendingCount()
            if (pending == 0) return@withContext
            // Loaded from disk: the defaults before that would give the wrong network constraint.
            val current = settings.awaitLoaded()
            if (current.offlineMode) return@withContext
            val cellular = current.downloadOverCellular
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val scheduler = appContext.getSystemService(JobScheduler::class.java)
                val job = scheduler?.getPendingJob(JOB_ID)
                val jobStale = job != null && job.requiresUnmetered() == cellular
                // A pending (or just started) user-initiated job will drain the queue; a WorkManager
                // fallback next to it would only start and find the queue taken.
                if (job != null && DownloadRules.keepPendingJob(replace, kick, jobExecuting, jobStale)) return@withContext
                val estimate = DownloadRules.estimateBytes(pending, current.downloadQuality.kbps)
                // Scheduling with the same id replaces the pending job: new constraint, no backoff.
                if (isAppVisible() && scheduleUserInitiatedJob(cellular, estimate)) {
                    if (replace) cancelWorker()
                    return@withContext
                }
                if (job != null) {
                    // Cannot re-create a user-initiated job from the background: keep a good one, and
                    // let the worker below take over from one with the wrong network constraint.
                    if (!replace && !jobStale) return@withContext
                    scheduler.cancel(JOB_ID)
                }
            }
            enqueueWorker(cellular, replace, kick)
            if (replace) appContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling downloads failed", e)
        }
    }

    /** True when the job may only run on an unmetered network. */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun JobInfo.requiresUnmetered(): Boolean =
        requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true

    private fun cancelWorker() {
        try {
            WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WorkManager unavailable", e)
        }
    }

    private suspend fun isAppVisible(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(cellular: Boolean, estimatedBytes: Long): Boolean {
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(JOB_ID, ComponentName(appContext, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(if (cellular) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
            .setEstimatedNetworkBytes(estimatedBytes, 0)
            .setRequiresStorageNotLow(true)
            .setBackoffCriteria(JOB_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        return try {
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        } catch (e: RuntimeException) {
            // E.g. not allowed right now (app left the foreground meanwhile); fall back to WorkManager.
            Log.w(TAG, "User-initiated job rejected", e)
            false
        }
    }

    /** Blocking (reads the pending work): call off the main thread. See [scheduleExecution]. */
    private fun enqueueWorker(cellular: Boolean, replace: Boolean, kick: Boolean) {
        val network = if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_S, TimeUnit.SECONDS)
            .build()
        val workManager = WorkManager.getInstance(appContext)
        val existing = try {
            workManager.getWorkInfosForUniqueWork(WORK_NAME).get().firstOrNull { !it.state.isFinished }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the pending download work", e)
            null
        }
        val recreate = DownloadRules.replaceWork(
            replace = replace,
            kick = kick,
            enqueued = existing?.state == WorkInfo.State.ENQUEUED,
            stale = existing != null && existing.constraints.requiredNetworkType != network,
            runAttempts = existing?.runAttemptCount ?: 0,
        )
        workManager.enqueueUniqueWork(WORK_NAME, if (recreate) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun updateSyncSchedule(enabled: Boolean) {
        try {
            val workManager = WorkManager.getInstance(appContext)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<DownloadSyncWorker>(1, TimeUnit.DAYS)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, SYNC_BACKOFF_MIN, TimeUnit.MINUTES)
                    .build()
                workManager.enqueueUniquePeriodicWork(SYNC_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            } else {
                workManager.cancelUniqueWork(SYNC_WORK_NAME)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WorkManager unavailable", e)
        }
    }

    private fun cancelScheduledWork() {
        appContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        try {
            WorkManager.getInstance(appContext).apply {
                cancelUniqueWork(WORK_NAME)
                cancelUniqueWork(SYNC_WORK_NAME)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "WorkManager unavailable", e)
        }
    }

    internal companion object {
        private const val TAG = "DownloadManager"
        const val WORK_NAME = "downloads"
        const val SYNC_WORK_NAME = "download-sync"
        const val JOB_ID = 0x5D0D
        private const val EMPTY_ITEMS = "[]"
        private const val SQL_CHUNK = 500
        private const val STATES_STOP_TIMEOUT_MS = 5_000L
        private const val METADATA_TIMEOUT_MS = 10_000L
        private const val COLLECTION_METADATA_TIMEOUT_MS = 60_000L
        private const val LATE_KEY_RETRY_MS = 5_000L
        private const val LATE_KEY_ATTEMPTS = 5
        private const val SYNC_ONLINE_TIMEOUT_MS = 60_000L
        private const val JOB_BACKOFF_MS = 30_000L
        private const val WORK_BACKOFF_S = 30L
        private const val SYNC_BACKOFF_MIN = 15L
    }
}
