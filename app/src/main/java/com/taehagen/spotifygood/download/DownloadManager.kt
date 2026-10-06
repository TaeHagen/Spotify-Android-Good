package com.taehagen.spotifygood.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
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
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
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
 *   downloaded collection stay removed until the collection is downloaded again.
 * * Removal deletes files, rows and the native offline index entries.
 * * The native offline index follows the database through numbered changes ([OfflineIndexSync]):
 *   every commit and removal takes its number under [mutex] with its database write.
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

    /** Network policy (`downloadOverCellular`) of what this process scheduled last; null = nothing. */
    @Volatile private var scheduledCellular: Boolean? = null

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
                // Resume after process death / reboot (a non-persisted job does not survive a reboot).
                if (dao.pendingCount() > 0) scheduleExecution()
                updateSyncSchedule(collectionDao.count() > 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Startup scheduling failed", e)
            }
        }
        scope.launch {
            // Network policy or offline mode changed: reschedule with the new constraints.
            settings.settings.map { it.downloadOverCellular to it.offlineMode }
                .distinctUntilChanged()
                .drop(1)
                .collect { (cellular, offline) ->
                    if (!offline) scheduleExecution(replace = scheduledCellular?.let { it != cellular } ?: false)
                }
        }
        scope.launch {
            engine.isOnline.filter { it }.collect { syncIfStale() }
        }
    }

    fun state(uri: String): Flow<DownloadState?> = states.map { it[uri] }.distinctUntilChanged()

    fun collectionStatus(uri: String): Flow<CollectionDownloadStatus> =
        combine(
            collectionDao.observe(uri).map { entity -> entity?.let { decodeItems(it.itemUrisJson) } }.distinctUntilChanged(),
            states,
        ) { items, states -> DownloadRules.collectionStatus(items, states) }
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
    suspend fun downloadCollection(ref: CollectionRef) {
        val resolved = requireNotNull(resolver.resolve(ref.type, ref.uri))
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
            )
            applyMembershipLocked(entity, resolved, userInitiated = true).removal
        }
        afterRemoval(listOf(removed))
        updateSyncSchedule(true)
        scheduleExecution()
    }

    /** Stops keeping [uri] offline; deletes its items unless another download still needs them. */
    suspend fun removeCollection(uri: String) {
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
    suspend fun downloadItems(uris: List<String>) {
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
            val quality = settings.settings.value.downloadQuality.kbps
            database.withTransaction {
                insertRows(targets.map { CollectionResolver.Item(it, metadata[it]) }, quality, individual = true, now = now)
                targets.chunked(SQL_CHUNK).forEach {
                    dao.setIndividual(it, true)
                    dao.requeueFailed(it)
                }
                metadata.forEach { (uri, meta) -> dao.fillMetadata(uri, meta) }
            }
        }
        scheduleExecution()
    }

    /** Deletes the given downloads (files, rows, offline index), whatever collection they belong to. */
    suspend fun removeItems(uris: List<String>) {
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
    suspend fun removeAll() {
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
        scheduledCellular = null
        notifications.cancelAll()
        index.remove(removal.uris, removal.seq)
    }

    /** Puts failed and cancelled downloads back into the queue. */
    suspend fun retryFailed() {
        if (dao.requeueFailed() > 0) scheduleExecution()
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
        for (row in rows) {
            val record = row.recordJson?.let { runCatching { json.decodeFromString(OfflineTrackRecord.serializer(), it) }.getOrNull() }
            if (record == null) {
                undecryptable += row.uri
                continue
            }
            val path = row.path ?: record.path
            if (!File(path).isFile) {
                missing += row.uri
                continue
            }
            val keyHex = keys[row.uri] ?: decryptKey(row)?.also { keys[row.uri] = it }
            if (keyHex == null) {
                undecryptable += row.uri
                continue
            }
            records += record.copy(keyHex = keyHex, path = path)
        }
        val now = System.currentTimeMillis()
        undecryptable.chunked(SQL_CHUNK).forEach { dao.markUnavailable(it, appContext.getString(R.string.data_dl_error_key), now) }
        missing.chunked(SQL_CHUNK).forEach { dao.markUnavailable(it, appContext.getString(R.string.data_dl_error_missing_file), now) }
        if (undecryptable.isNotEmpty() || missing.isNotEmpty()) {
            Log.w(TAG, "Skipped ${undecryptable.size} undecryptable and ${missing.size} missing downloads")
        }
        index.beginSnapshot(seq)
        records
    }

    /**
     * Re-syncs downloaded collections with their sources (new/removed items) and re-validates
     * downloads older than 30 days. Holds the engine (DOWNLOAD) meanwhile; does nothing when the
     * session cannot come online. Concurrent calls are coalesced.
     */
    suspend fun syncCollections() {
        sync()
    }

    /** [syncCollections]; false when it could not run because the session did not come online. */
    internal suspend fun sync(): Boolean {
        if (!syncMutex.tryLock()) return true // another sync is running
        try {
            if (settings.settings.value.offlineMode) return true
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
                    val resolved = try {
                        resolver.resolve(type, collection.uri, collection.revision)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Sync of ${collection.uri} failed", e)
                        continue
                    }
                    val result = mutex.withLock {
                        val current = collectionDao.get(collection.uri) ?: return@withLock null // removed meanwhile
                        if (resolved == null) {
                            collectionDao.upsert(current.copy(lastSyncedAt = System.currentTimeMillis())) // unchanged
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
        val newItems = resolved.items.map { it.uri }
        val diff = DownloadRules.diff(decodeItems(entity.itemUrisJson), newItems)
        val others = collectionDao.getAll().filter { it.uri != entity.uri }.map { decodeItems(it.itemUrisJson) }
        val toDelete = if (diff.dropped.isEmpty()) {
            emptyList()
        } else {
            DownloadRules.itemsToDelete(diff.dropped, others + listOf(newItems), dao.individualUris().toHashSet())
        }
        runner.cancelItems(toDelete)
        val files = fileRows(toDelete)
        val toQueue = if (userInitiated) resolved.items else diff.added.toHashSet().let { added -> resolved.items.filter { it.uri in added } }
        val now = System.currentTimeMillis()
        val quality = settings.settings.value.downloadQuality.kbps
        database.withTransaction {
            collectionDao.upsert(
                entity.copy(
                    itemUrisJson = json.encodeToString(itemsSerializer, newItems),
                    revision = resolved.revision ?: entity.revision,
                    lastSyncedAt = now,
                ),
            )
            insertRows(toQueue, quality, individual = false, now = now)
            if (userInitiated) newItems.chunked(SQL_CHUNK).forEach { dao.requeueFailed(it) }
            deleteRows(toDelete)
        }
        deleteFiles(files)
        return MembershipResult(toQueue.size, Removal(toDelete, index.next()))
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
            val oldest = collectionDao.oldestSyncedAt() ?: return
            if (System.currentTimeMillis() - oldest < SYNC_STALE_MS) return
            syncCollections()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Collection sync failed", e)
        }
    }

    private fun decryptKey(row: DownloadEntity): String? {
        val cipher = row.encryptedKey ?: return null
        return try {
            Hex.encode(credentialStore.decrypt(cipher))
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt the key of ${row.uri}", e)
            null
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
     * [replace] re-creates pending work with the current network constraints.
     */
    private suspend fun scheduleExecution(replace: Boolean = false): Unit = withContext(Dispatchers.IO) {
        try {
            if (runner.isRunning) return@withContext
            val pending = dao.pendingCount()
            if (pending == 0) return@withContext
            val current = settings.settings.value
            if (current.offlineMode) return@withContext
            val cellular = current.downloadOverCellular
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // A pending (or just started) user-initiated job will drain the queue; a WorkManager
                // fallback next to it would only start and find the queue taken.
                if (!replace && hasUserInitiatedJob()) return@withContext
                val estimate = DownloadRules.estimateBytes(pending, current.downloadQuality.kbps)
                if (isAppVisible() && scheduleUserInitiatedJob(cellular, estimate)) {
                    scheduledCellular = cellular
                    return@withContext
                }
            }
            enqueueWorker(cellular, replace)
            scheduledCellular = cellular
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Scheduling downloads failed", e)
        }
    }

    private suspend fun isAppVisible(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun hasUserInitiatedJob(): Boolean =
        appContext.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null

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

    private fun enqueueWorker(cellular: Boolean, replace: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WORK_BACKOFF_S, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
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
        private const val SYNC_ONLINE_TIMEOUT_MS = 60_000L
        private const val SYNC_STALE_MS = 12L * 60 * 60 * 1000
        private const val JOB_BACKOFF_MS = 30_000L
        private const val WORK_BACKOFF_S = 30L
        private const val SYNC_BACKOFF_MIN = 15L
    }
}
