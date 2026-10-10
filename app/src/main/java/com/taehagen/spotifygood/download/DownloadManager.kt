package com.taehagen.spotifygood.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
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
import coil3.intercept.Interceptor
import com.taehagen.spotifygood.R
import com.taehagen.spotifygood.data.db.RetryRow
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.data.LibraryEdit
import com.taehagen.spotifygood.data.SpotifyUris
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.db.DownloadCollectionEntity
import com.taehagen.spotifygood.data.db.DownloadEntity
import com.taehagen.spotifygood.data.db.DownloadFileRow
import com.taehagen.spotifygood.data.db.IndexRow
import com.taehagen.spotifygood.data.db.LocatedRow
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.engine.HolderType
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.engine.accountExplicitFilter
import com.taehagen.spotifygood.engine.reportedOnline
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.NativeErrorInfo
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.nativebridge.NativeErrorCode
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeException
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * What a download request did ([DownloadManager.downloadItems], [DownloadManager.downloadCollection]):
 * [queued] items are now in the queue, [waitingForCard] are downloaded on the chosen SD card while
 * it is away (nothing to do: they play again once it is back), and whether the download location
 * is available ([locationAvailable]; else the queue waits for the card).
 */
data class DownloadRequest(val queued: Int = 0, val waitingForCard: Int = 0, val locationAvailable: Boolean = true) {
    enum class Outcome { STARTED, LOCATION_MISSING, WAITING_FOR_CARD, NOTHING }

    val outcome: Outcome get() = DownloadRules.requestOutcome(queued, waitingForCard, locationAvailable)

    /** What to tell the user when the request did not simply start; null when it did. */
    @get:StringRes
    val notice: Int?
        get() = when (outcome) {
            Outcome.STARTED -> null
            Outcome.LOCATION_MISSING -> R.string.data_dl_request_location_missing
            Outcome.WAITING_FOR_CARD -> R.string.data_dl_error_volume
            Outcome.NOTHING -> R.string.data_dl_request_nothing
        }
}

/** A place downloads can be stored (Settings > Storage): internal storage or a mounted SD card. */
data class DownloadLocation(val id: String, val label: String, val freeBytes: Long, val removable: Boolean)

/**
 * Moving downloads to the chosen location: [moved] of [total] files while [moving]; [error] when a
 * pass stopped early (it resumes at the next start, mount or change of the location); [unreadable]
 * downloads whose file could not be read were left where they were and are downloaded again.
 */
data class DownloadRelocation(
    val moving: Boolean = false,
    val moved: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val unreadable: Int = 0,
)

/**
 * The download queue waits for Spotify's audio-key limit until [until] (epoch ms): Spotify limits
 * how fast an account gets audio keys, so downloads go at its pace (docs/ARCHITECTURE.md §9.7).
 */
data class DownloadPause(val reason: Reason, val until: Long) {
    enum class Reason {
        /** The engine's pacing: songs download in small batches as the key budget refills. */
        PACING,

        /** Spotify throttled (keys, or the CDN): a cool-down of minutes, then on by itself. */
        LIMITED,
    }
}

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
    /** The queue waits for Spotify's audio-key limit (while running, or rescheduled for it). */
    val pause: DownloadPause? = null,
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
 * * The explicit filter never touches download rows (it applies when they are shown and played).
 *   "Hide explicit content" never keeps an item from being downloaded; the account's own filter
 *   keeps explicit ones from being queued ([ExplicitFilterWatch], [DownloadRules.memberVerdict],
 *   [DownloadRules.rowVerdict]). When a filter goes off, what it may have hidden is looked up again
 *   at once ([onExplicitFilterOff]).
 * * Removal deletes files, rows and the native offline index entries.
 * * The native offline index follows the database through numbered changes ([OfflineIndexSync]):
 *   every commit and removal takes its number under [mutex] with its database write.
 * * Likes and playlist edits made in the app ([onLibraryEdit]) re-sync the affected downloaded
 *   collection a few seconds later (coalesced), instead of at the next scheduled sync.
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

    /** Which catalog answers say nothing about explicit items (the filter was or may have been on). */
    private val explicitFilter = ExplicitFilterWatch()
    private val resolver = CollectionResolver(rpc, json, explicitFilter)

    /** When [requestSync] last asked each collection to sync (in memory). */
    private val syncRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The app is in the foreground ([ProcessLifecycleOwner] started). */
    @Volatile private var foreground = false

    /** Downloaded covers for the image loader ([coverInterceptor]). */
    private val covers = OfflineCovers()

    /** The explicit filter went off while offline: look again at what it hid once online. */
    @Volatile private var recheckWhenOnline = false
    private val index = OfflineIndexSync(rpc)

    internal val storage = DownloadStorage(appContext)
    internal val notifications = DownloadNotifications(appContext)

    /** Audio keys are sealed with one Keystore-wrapped data key: one TEE operation per process. */
    private val vault = KeyVault(storage.keyFile, credentialStore::encrypt, credentialStore::decrypt)
    internal val runner = DownloadRunner(
        appContext, database, rpc, events, engine, settings, storage, notifications, keys, mutex, index, vault,
    )

    /** True while [DownloadJobService] runs a job (it must not be replaced then, see [scheduleExecution]). */
    @Volatile internal var jobExecuting = false

    /** Registers downloads an index push left out because the Keystore was busy ([registerLate]). */
    @Volatile private var lateKeys: Job? = null

    /** Bumped when a volume is mounted or goes ([onVolumesChanged]). */
    private val volumes = MutableStateFlow(0)

    /**
     * Completed downloads' files (one shared database observer): what moves with a change of the
     * download location, and what a card that goes takes with it.
     */
    private val completedLocated: SharedFlow<List<LocatedRow>> = dao.observeCompletedLocated()
        .shareIn(scope, SharingStarted.Eagerly, replay = 1)

    /**
     * uri → cover path (null: none) of completed downloads. Changes when the set of completed
     * downloads changes and when a cover moves to another location, never on progress writes.
     */
    private val completedImagePaths: Flow<Map<String, String?>> = completedLocated
        .map { rows -> rows.associate { it.uri to it.imagePath } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    /**
     * Completed downloads on a location that is not available (an SD card removed or unmounted):
     * they stay COMPLETED (nothing is failed or deleted) and come back with the card, but are shown
     * as not available and are left out of the offline index meanwhile.
     */
    private val missingCard: StateFlow<DownloadRules.MissingCard> = combine(
        completedLocated,
        volumes,
        settings.persisted.map { it.downloadLocation }.distinctUntilChanged(),
    ) { rows, _, chosen -> rows to chosen }
        .map { (rows, chosen) -> DownloadRules.missingCard(rows, storage.availability(), chosen, storage.locations.internalRoot.path) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
        .stateIn(scope, SharingStarted.Eagerly, DownloadRules.MissingCard())

    private val unavailable: StateFlow<Set<String>> = missingCard
        .map { it.all }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * How many downloads are on an SD card that is no longer used (it died, or another card was
     * chosen): Settings > Storage offers to download them again ([redownloadFromUnusedCard]).
     */
    val onUnusedCard: Flow<Int> = missingCard.map { it.stranded.size }.distinctUntilChanged()

    /**
     * URIs of completed downloads that can play (hot), iterating newest download first (Android Auto
     * queue order). Not the ones on a card that is not mounted.
     */
    val downloadedUris: StateFlow<Set<String>> = combine(dao.observeCompletedUris(), unavailable) { uris, gone ->
        if (gone.isEmpty()) LinkedHashSet(uris) else uris.filterTo(LinkedHashSet()) { it !in gone }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /**
     * uri → state of every row; one shared database observer for all per-item / collection flows.
     * A download on a card that is not mounted reads as FAILED (not available) meanwhile.
     */
    private val states: Flow<Map<String, DownloadState>> = combine(dao.observeStates(), unavailable) { rows, gone ->
        rows.associate { it.uri to if (it.state == DownloadState.COMPLETED && it.uri in gone) DownloadState.FAILED else it.state }
    }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    /**
     * Every row for the Downloads screens, with the live progress of the item being downloaded
     * (the database gets it only every few seconds). One shared database observer.
     */
    val items: Flow<List<DownloadItem>> = combine(
        dao.observeListRows().map { rows ->
            rows.map { DownloadItem(it.uri, it.state, it.bytesDone, it.sizeBytes, it.metadataJson, it.imagePath, it.error) }
        },
        missingCard,
        runner.activity.map { Triple(it.currentUri, it.bytes, it.totalBytes) }.distinctUntilChanged(),
    ) { items, missing, (uri, bytes, total) ->
        // On a card that is away: plays again once it is back. On one no longer used: "Retry" downloads it again.
        val waiting = DownloadRules.withUnavailable(items, missing.waiting, appContext.getString(R.string.data_dl_error_volume))
        val shown = DownloadRules.withUnavailable(waiting, missing.stranded, appContext.getString(R.string.data_dl_error_card_unused))
        DownloadRules.withLiveProgress(shown, uri, bytes, total)
    }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    /**
     * uri → cover path of completed downloads, for long-lived observers (the playback service): it
     * changes only when the set of completed downloads does, never on progress writes.
     */
    val downloadedImages: Flow<Map<String, String>> = combine(completedImagePaths, unavailable, DownloadRules::downloadedImagesOf)
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
    val usedBytes: Flow<Long> = dao.observeUsedBytes()
    val pendingCount: Flow<Int> = dao.observePendingCount()

    /**
     * Failed downloads split like [retryFailed] splits them (read-only): what a "Retry" button would
     * put back into the queue, and what stays failed because it is not playable here.
     */
    val failedCounts: Flow<FailedCounts> = combine(
        items,
        collectionDao.observeAll().map { list -> list.flatMapTo(HashSet()) { decodeItems(it.unavailableUrisJson) } },
        missingCard,
    ) { rows, unavailable, missing ->
        DownloadRules.failedCounts(
            rows.map { RetryRow(it.uri, it.state, it.error) },
            // Downloads on the chosen card while it is away are not retried: they come back with it.
            // The ones on a card no longer used are: "Retry" downloads them again.
            unavailable + missing.waiting,
            appContext.getString(R.string.data_dl_error_unplayable),
        )
    }.distinctUntilChanged().flowOn(Dispatchers.Default)

    /** Downloaded collections, newest first (one shared database observer). */
    val collections: Flow<List<DownloadedCollection>> = collectionDao.observeAll()
        .map { list -> list.map { it.toDownloadedCollection() } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(STATES_STOP_TIMEOUT_MS, replayExpirationMillis = 0), replay = 1)

    /**
     * For the image loader: serves the downloaded cover for the CDN image URLs of completed
     * downloads (and stands in for downloaded collections' images), offline too ([OfflineCovers]).
     */
    internal val coverInterceptor: Interceptor get() = covers.interceptor

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
                if (foreground) syncRemoteChanges()
                if (recheckWhenOnline) {
                    recheckWhenOnline = false
                    if (!recheckAfterFilterOff()) recheckWhenOnline = true
                }
                // Work waiting out a backoff from an unreachable network starts now.
                if (!runner.isRunning && dao.pendingCount() > 0) scheduleExecution(kick = true)
            }
        }
        scope.launch { followLocation() }
        scope.launch { followCovers() }
        scope.launch { watchExplicitFilter() }
        scope.launch { repairExplicitFailures() }
        scope.launch { repairThrottleFailures() }
        scope.launch(Dispatchers.Main) {
            // Back in the app: resume a queue that stopped (storage was full, retries ran out …).
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    scope.launch {
                        try {
                            if (!runner.isRunning && dao.pendingCount() > 0) scheduleExecution(kick = true)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Resuming downloads failed", e)
                        }
                        // Changes made on another device while the app was away (else once online).
                        if (engine.isOnline.value) syncRemoteChanges()
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
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
    suspend fun downloadCollection(ref: CollectionRef): DownloadRequest = scope.detached { downloadCollectionNow(ref) }

    private suspend fun downloadCollectionNow(ref: CollectionRef): DownloadRequest {
        val countryKnown = awaitCountry()
        val listed = requireNotNull(resolver.resolve(ref.type, ref.uri))
        // Without the country the catalog's `playable` is not trustworthy: queue everything and let
        // download.track (which waits for the country itself) decide.
        val found = if (countryKnown) listed else listed.copy(items = listed.items.map { it.copy(unavailable = false) })
        if (found.items.isEmpty() && !found.complete) {
            // Nothing listed and the lookup is not trustworthy: report it instead of storing an empty
            // collection that would show as downloaded.
            throw NativeException(NativeErrorInfo(NativeErrorCode.NETWORK, "Could not load the items of ${ref.name.ifBlank { ref.uri }}"))
        }
        val resolved = withCatalogInfo(found, known = emptySet())
        val result = mutex.withLock {
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
                unavailableCheckedAt = existing?.unavailableCheckedAt,
            )
            applyMembershipLocked(entity, resolved, userInitiated = true)
        }
        afterRemoval(listOf(result.removal))
        updateSyncSchedule(true)
        scheduleExecution(kick = true)
        return DownloadRequest(result.queued, result.waitingForCard, locationAvailable = storage.target.value !is DownloadStorage.Target.Missing)
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
            cancelWorkIfIdleLocked()
            Removal(toDelete, index.next())
        }
        afterRemoval(listOf(removed))
        if (collectionDao.count() == 0) updateSyncSchedule(false)
    }

    /**
     * Downloads single tracks / episodes (kept until removed, independent of collections), or
     * again: failed ones, and ones on an SD card that is no longer used ([DownloadRules.itemRequest]).
     * Says what it did: nothing starts for downloads on the chosen card while it is away.
     */
    suspend fun downloadItems(uris: List<String>): DownloadRequest = scope.detached { downloadItemsNow(uris) }

    private suspend fun downloadItemsNow(uris: List<String>): DownloadRequest {
        val targets = uris.filter(SpotifyUris::isPlayableItem).distinct()
        if (targets.isEmpty()) return DownloadRequest()
        // Names for the Downloads screen while queued, for new rows (existing ones have theirs);
        // best effort (the record brings them anyway).
        val known = targets.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.mapTo(HashSet()) { it.uri }
        val lacking = targets.filter { it !in known }
        val metadata = if (lacking.isEmpty()) emptyMap() else try {
            withTimeout(METADATA_TIMEOUT_MS) { resolver.metadata(lacking) }
        } catch (e: TimeoutCancellationException) {
            emptyMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap()
        }
        val (plan, queued, seq) = mutex.withLock {
            val now = System.currentTimeMillis()
            val quality = settings.awaitLoaded().downloadQuality.kbps
            val states = targets.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.associate { it.uri to it.state }
            val plan = DownloadRules.itemRequest(targets, states, missingCardOf(targets))
            database.withTransaction {
                insertRows(plan.newSingles.map { CollectionResolver.Item(it, metadata[it]) }, quality, individual = true, now = now)
                plan.requeue.chunked(SQL_CHUNK).forEach { dao.requeueFailed(it) }
                plan.redownload.chunked(SQL_CHUNK).forEach { dao.requeueFromUnusedCard(it) }
                metadata.forEach { (uri, meta) -> dao.fillMetadata(uri, meta) }
            }
            keys.remove(plan.redownload)
            val queued = targets.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.count { DownloadRules.isPending(it.state) }
            Triple(plan, queued, if (plan.redownload.isNotEmpty()) index.next() else null)
        }
        // Out of the index already (their card is not mounted); numbered, so the new download wins.
        seq?.let { index.remove(plan.redownload, it) }
        scheduleExecution(kick = true)
        return DownloadRequest(queued, plan.waiting.size, locationAvailable = storage.target.value !is DownloadStorage.Target.Missing)
    }

    /**
     * Downloads again every download on an SD card that is no longer used (Settings > Storage),
     * to the chosen location.
     */
    suspend fun redownloadFromUnusedCard(): DownloadRequest = scope.detached { downloadItemsNow(missingCard.value.stranded.toList()) }

    /** Must hold [mutex] (or accept a stale answer). [DownloadRules.MissingCard] of [uris] now. */
    private suspend fun missingCardOf(uris: List<String>): DownloadRules.MissingCard {
        val rows = uris.chunked(SQL_CHUNK).flatMap { dao.completedLocatedOf(it) }
        if (rows.isEmpty()) return DownloadRules.MissingCard()
        val chosen = settings.awaitLoaded().downloadLocation
        return withContext(Dispatchers.IO) {
            DownloadRules.missingCard(rows, storage.availability(), chosen, storage.locations.internalRoot.path)
        }
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
            // Removed by the user: not looked up (and downloaded) again as an unavailable member.
            adjustUnavailableLocked(gone = emptySet(), playableAgain = targets.toHashSet())
            cancelWorkIfIdleLocked()
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
            vault.reset() // its data key file went with the downloads
            keys.clear()
            Removal(all, index.next())
        }
        notifications.cancelAll()
        index.remove(removal.uris, removal.seq)
    }

    /**
     * Puts failed and cancelled downloads back into the queue and (re)starts the queue, also when only
     * pending items wait (a run stopped because storage was full). Downloads that are not playable
     * here ([DownloadRules.retryable]) stay failed: they would only fail again.
     */
    suspend fun retryFailed(): Unit = scope.detached { retryFailedNow() }

    private suspend fun retryFailedNow() {
        mutex.withLock {
            val unavailable = collectionDao.unavailableUrisJsons().flatMapTo(HashSet()) { decodeItems(it) }
            val uris = DownloadRules.retryable(dao.retryRows(), unavailable, appContext.getString(R.string.data_dl_error_unplayable))
            uris.chunked(SQL_CHUNK).forEach { dao.requeueFailed(it) }
        }
        // Downloads on an SD card that is no longer used count as failed: downloaded again.
        if (missingCard.value.stranded.isNotEmpty()) downloadItemsNow(missingCard.value.stranded.toList())
        scheduleExecution(kick = true)
    }

    /**
     * Decrypted records of all completed downloads: the snapshot the engine pushes with
     * `offline.setIndex`. It is announced natively (`offline.beginIndex`) with the number of the last
     * change it contains, so commits and removals made while it is built and sent survive the push.
     */
    suspend fun offlineRecords(): List<OfflineTrackRecord> = withContext(Dispatchers.IO) {
        val (seq, rows) = mutex.withLock { index.last() to dao.completedIndexRows() }
        val records = ArrayList<OfflineTrackRecord>(rows.size)
        val undecryptable = ArrayList<String>()
        val missing = ArrayList<DownloadRules.CheckedFile>()
        val keystoreBusy = ArrayList<String>()
        val legacy = ArrayList<Pair<IndexRow, String>>()
        val available = storage.availability()
        var unmounted = 0
        var keystoreDown = false
        for (row in rows) {
            when (val result = offlineRecord(row, skipKeystore = keystoreDown, available)) {
                is RecordResult.Ready -> {
                    records += result.record
                    if (result.legacyKey) legacy += row to result.record.keyHex
                }
                RecordResult.Unreadable -> undecryptable += row.uri
                RecordResult.Missing -> missing += DownloadRules.CheckedFile(row.uri, row.path, row.path ?: storedPath(row), row.completedAt)
                // Not failed: registered when the card is back (onVolumesChanged).
                RecordResult.Unmounted -> unmounted++
                RecordResult.KeystoreBusy -> {
                    keystoreBusy += row.uri
                    // One retry cycle per pass, not one per row: the rest wait for registerLate.
                    keystoreDown = true
                }
            }
        }
        val now = System.currentTimeMillis()
        undecryptable.chunked(SQL_CHUNK).forEach { dao.markUnavailable(it, appContext.getString(R.string.data_dl_error_key), now) }
        // Not from this (stale) read: a move may have switched the row to its copy meanwhile.
        markMissingIfUnchanged(missing, now)
        if (undecryptable.isNotEmpty() || missing.isNotEmpty() || keystoreBusy.isNotEmpty() || unmounted > 0) {
            Log.w(
                TAG,
                "Skipped ${undecryptable.size} undecryptable, ${missing.size} missing, $unmounted on storage that " +
                    "is not mounted and ${keystoreBusy.size} downloads whose key the Keystore could not decrypt right now",
            )
        }
        index.beginSnapshot(seq)
        lateKeys?.cancel()
        // Left out of this push only (still COMPLETED): registered as soon as the Keystore answers.
        if (keystoreBusy.isNotEmpty()) lateKeys = scope.launch { registerLate(keystoreBusy) }
        // Keys still sealed by the Keystore itself (from before the data key): moved over in the
        // background, so the next start opens them in software.
        if (legacy.isNotEmpty()) scope.launch { resealLegacy(legacy) }
        records
    }

    /**
     * Re-seals keys that were sealed with the Keystore key itself with the data key (one row at a
     * time, guarded so a newer commit of the row is never overwritten). Resumable: what is left over
     * moves at the next start.
     */
    private suspend fun resealLegacy(rows: List<Pair<IndexRow, String>>) = withContext(Dispatchers.IO) {
        var moved = 0
        for (chunk in rows.chunked(RESEAL_BATCH)) {
            ensureActive()
            val sealed = try {
                chunk.mapNotNull { (row, hex) -> row.completedAt?.let { Triple(row.uri, it, vault.seal(Hex.decode(hex), row.uri)) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Moving download keys to the data key stopped; retried at the next start", e)
                break
            }
            database.withTransaction { sealed.forEach { (uri, completedAt, key) -> dao.resealKey(uri, completedAt, key) } }
            moved += sealed.size
        }
        if (moved > 0) Log.i(TAG, "Moved $moved download keys to the data key")
    }

    /** The path the record of [row] names (where offlineRecord checks when the row has none). */
    private fun storedPath(row: IndexRow): String =
        row.recordJson?.let { runCatching { json.decodeFromString(OfflineTrackRecord.serializer(), it).path }.getOrNull() }.orEmpty()

    /**
     * Fails the downloads a snapshot found without their file ([missing]: checked outside the lock)
     * only while each row is still the one checked and its file is still gone, under [mutex], which
     * a move's switch to the copies holds too ([DownloadRules.stillMissing]; the update is guarded by
     * path and completedAt as well). Nothing is failed while a move runs.
     */
    private suspend fun markMissingIfUnchanged(missing: List<DownloadRules.CheckedFile>, now: Long) {
        if (missing.isEmpty()) return
        val error = appContext.getString(R.string.data_dl_error_missing_file)
        mutex.withLock {
            val current = missing.map { it.uri }.chunked(SQL_CHUNK).flatMap { dao.completedIndexRows(it) }
                .associate { it.uri to (it.path to it.completedAt) }
            val gone = withContext(Dispatchers.IO) {
                DownloadRules.stillMissing(missing, current, exists = { it.isNotEmpty() && File(it).isFile }, relocating = storage.relocating)
            }
            if (gone.isEmpty()) return@withLock
            database.withTransaction {
                gone.forEach { dao.markMissingIfUnchanged(it.uri, it.rowPath, it.completedAt, error, now) }
            }
        }
    }

    private sealed interface RecordResult {
        /** [legacyKey]: its key was sealed with the Keystore key itself (to be moved to the data key). */
        data class Ready(val record: OfflineTrackRecord, val legacyKey: Boolean = false) : RecordResult
        data object Unreadable : RecordResult
        data object Missing : RecordResult

        /** On a location (SD card) that is not mounted: left out, not failed. */
        data object Unmounted : RecordResult
        data object KeystoreBusy : RecordResult
    }

    /**
     * The decrypted index record of a COMPLETED [row], with its files where the row has them now
     * (moved between locations). Blocking (file check, Keystore). With [skipKeystore] (it was busy
     * for an earlier row of this pass) only a cached key is used. [available]: whether a location
     * root can be read ([DownloadStorage.availability]).
     */
    private fun offlineRecord(row: IndexRow, skipKeystore: Boolean, available: (String) -> Boolean): RecordResult {
        val stored = row.recordJson?.let { runCatching { json.decodeFromString(OfflineTrackRecord.serializer(), it) }.getOrNull() }
            ?: return RecordResult.Unreadable
        val path = row.path ?: stored.path
        if (DownloadRules.rootOf(path)?.let(available) == false) return RecordResult.Unmounted
        if (!File(path).isFile) return RecordResult.Missing
        val record = stored.copy(path = path, imagePath = row.imagePath ?: stored.imagePath)
        keys[row.uri]?.let { return RecordResult.Ready(record.copy(keyHex = it), legacyKey = row.keyVersion == 0) }
        if (skipKeystore) return RecordResult.KeystoreBusy
        return when (val key = decryptKey(row)) {
            // Not overwriting a key a newer commit of this URI cached meanwhile; this row's record
            // still gets this row's key.
            is KeyResult.Key -> RecordResult.Ready(record.copy(keyHex = key.hex.also { keys.remember(row.uri, it) }), key.legacy)
            KeyResult.Unreadable -> RecordResult.Unreadable
            KeyResult.KeystoreBusy -> RecordResult.KeystoreBusy
        }
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
            val (seq, rows) = mutex.withLock {
                index.next() to waiting.chunked(SQL_CHUNK).flatMap { dao.completedIndexRows(it) }
            }
            waiting = registerRows(rows, seq)
            if (waiting.isEmpty()) return
        }
        Log.w(TAG, "${waiting.size} downloads stay out of the offline index until the next engine start (Keystore unavailable)")
    }

    /**
     * Registers the completed [rows], read under [mutex] with change [seq], with `offline.add`.
     * Returns the ones whose key the Keystore could not open right now; marks the ones whose key is
     * unreadable for good failed. The Keystore is never called with the lock held.
     */
    private suspend fun registerRows(rows: List<IndexRow>, seq: Long): List<String> = withContext(Dispatchers.IO) {
        val stillBusy = ArrayList<String>()
        val records = ArrayList<OfflineTrackRecord>()
        val unreadable = ArrayList<IndexRow>()
        val available = storage.availability()
        var keystoreDown = false
        for (row in rows) {
            ensureActive() // registerLate: a newer push supersedes this one
            when (val result = offlineRecord(row, skipKeystore = keystoreDown, available)) {
                is RecordResult.Ready -> records += result.record
                RecordResult.Unreadable -> unreadable += row
                RecordResult.Missing -> Unit // the next push marks it
                RecordResult.Unmounted -> Unit // registered when the card is back
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

    // ---- download location -----------------------------------------------------------------------------

    /** The locations downloads can go to now, with their free space (Settings > Storage). */
    suspend fun locations(): List<DownloadLocation> = withContext(Dispatchers.IO) {
        storage.locations.list().map { DownloadLocation(it.id, it.label, it.freeBytes, it.removable) }
    }

    /** Emits when a volume is mounted or goes (the [locations] changed). */
    val volumeChanges: Flow<Int> = volumes

    private val _relocation = MutableStateFlow(DownloadRelocation())

    /** Moving the downloads to a newly chosen location ([setDownloadLocation]). */
    val relocation: StateFlow<DownloadRelocation> = _relocation.asStateFlow()

    /**
     * New downloads go to location [id] ([DownloadLocation.id]); the existing ones move there
     * ([relocate]: copied, verified, switched in the index, then deleted where they were).
     */
    suspend fun setDownloadLocation(id: String) {
        settings.update { it.copy(downloadLocation = id) }
    }

    /**
     * Follows the chosen location and the volumes: resolves where new downloads go, keeps the
     * offline index in step with what is mounted ([onVolumesChanged]) and moves downloads to the
     * chosen location ([relocate]). A mount comes as several broadcasts: handled once they settle.
     */
    private suspend fun followLocation() {
        combine(
            settings.persisted.map { it.downloadLocation }.distinctUntilChanged(),
            storage.locations.changes().onStart { emit(Unit) },
        ) { id, _ -> id }
            .collectLatest { id ->
                delay(MOUNT_SETTLE_MS)
                withContext(NonCancellable) { applyLocation(id) }
            }
    }

    private suspend fun applyLocation(id: String) {
        try {
            val root = withContext(Dispatchers.IO) { runCatching { storage.locations.rootFor(id) }.getOrNull() }
            val before = storage.target.value
            val next = if (root != null) DownloadStorage.Target.Ready(id, root) else DownloadStorage.Target.Missing(id)
            storage.setTarget(next)
            onVolumesChanged()
            if (before != next && before is DownloadStorage.Target.Ready && runner.isRunning) {
                // The run downloads into the old location: stopped; its item resumes in the new one.
                runner.stopAndAwaitIdle()
            }
            relocate()
            // Re-created: whether it waits for internal storage to be not low depends on the location.
            if (before != next && next is DownloadStorage.Target.Ready && dao.pendingCount() > 0) {
                scheduleExecution(replace = before is DownloadStorage.Target.Ready, kick = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Applying the download location failed", e)
        }
    }

    /** Availability of each location root holding completed downloads, as the index last saw it. */
    private var indexedRoots: Map<String, Boolean> = emptyMap()

    /**
     * A volume was mounted or went: downloads on a card that went leave the offline index (they
     * would not play), the ones on a card that is back are registered again; the lists follow
     * ([unavailable]). Nothing is failed or deleted.
     */
    private suspend fun onVolumesChanged() {
        val rows = dao.completedLocated()
        val available = storage.availability()
        val roots = withContext(Dispatchers.IO) { rows.mapNotNullTo(HashSet()) { row -> row.path?.let(DownloadRules::rootOf) }.associateWith(available) }
        val back = roots.filter { (root, now) -> now && indexedRoots[root] == false }.keys
        val gone = roots.filter { (root, now) -> !now && indexedRoots[root] != false }.keys
        indexedRoots = roots
        volumes.update { it + 1 }
        fun urisOn(of: Set<String>) = rows.filter { row -> row.path?.let(DownloadRules::rootOf) in of }.map { it.uri }
        if (gone.isNotEmpty()) {
            val uris = urisOn(gone)
            val seq = mutex.withLock { index.next() }
            index.remove(uris, seq)
            Log.i(TAG, "${uris.size} downloads are on storage that is not mounted: out of the offline index until it is back")
        }
        if (back.isNotEmpty()) {
            val uris = urisOn(back)
            val (seq, indexRows) = mutex.withLock { index.next() to uris.chunked(SQL_CHUNK).flatMap { dao.completedIndexRows(it) } }
            val busy = registerRows(indexRows, seq)
            if (busy.isNotEmpty()) scope.launch { registerLate(busy) }
            Log.i(TAG, "${uris.size} downloads are back with their storage")
        }
    }

    private val relocateLock = Mutex()
    @Volatile private var relocateAgain = false

    /**
     * Moves the downloads that are not on the chosen location there, one file at a time: copied and
     * verified ([copyVerified]), then the rows and the offline index switched to the copy, then the
     * original deleted. Resumable at every step (an interrupted pass leaves either the original in
     * use, or both copies with the rows on one of them; the next pass carries on). Downloads on a card
     * that is not mounted wait for it. Runs again after a change that arrives meanwhile.
     */
    private fun relocate() {
        scope.launch {
            if (!relocateLock.tryLock()) {
                relocateAgain = true
                return@launch
            }
            try {
                do {
                    relocateAgain = false
                    relocatePass()
                } while (relocateAgain)
            } finally {
                storage.relocating = false
                _relocation.update { it.copy(moving = false) }
                relocateLock.unlock()
            }
            // Leftovers of an interrupted copy, originals another row named until the switch.
            if (relocateAgain) relocate() else runner.collectGarbageIfIdle()
        }
    }

    private suspend fun relocatePass() {
        val target = storage.target.value as? DownloadStorage.Target.Ready ?: return
        val available = storage.availability()
        moveParts(target.root)
        // Files whose copy stopped a pass before (an error about the target) go last. Unreadable
        // originals are never in the plan again: they are downloaded again instead.
        val plan = DownloadRules.orderPlan(DownloadRules.relocationPlan(dao.locatedRows(), target.root.path, available), relocationFailures)
        if (plan.isEmpty()) {
            // Nothing left to move (or only what waits for a card): no stale "stopped" notice.
            if (_relocation.value.error != null) _relocation.value = DownloadRelocation()
            return
        }
        Log.i(TAG, "Moving ${plan.size} download files to ${target.root}")
        // Under the commit lock, which garbage collection holds while it runs: it never sees a copy
        // that no row names yet.
        mutex.withLock { storage.relocating = true }
        _relocation.value = DownloadRelocation(moving = true, moved = 0, total = plan.size)
        var moved = 0
        var unreadable = 0
        var copied = 0
        // Whether the old card is failing as a whole (not a cluster of damaged files).
        val health = SourceHealth()
        try {
            for (batch in plan.chunked(RELOCATE_BATCH)) {
                if (storage.target.value != target) return // changed again: the next pass moves there
                // The copies made before a stop (no space, an error about the target, the card gone or
                // failing) are switched all the same; a damaged original is skipped and downloaded again.
                copyThenSwitch(
                    batch,
                    copy = { move ->
                        withContext(Dispatchers.IO) { copyMove(move, health) }
                        copied++
                    },
                    onDone = {
                        moved++
                        _relocation.value = DownloadRelocation(moving = true, moved = moved, total = plan.size, unreadable = unreadable)
                    },
                    switch = { copies -> switchCopies(copies) },
                    skip = { damaged ->
                        dropUnreadable(damaged)
                        unreadable += damaged.count { !it.image }
                    },
                    onFailed = { move -> relocationFailures += move.from },
                    cardFailing = { health.failing },
                )
            }
            _relocation.value = DownloadRelocation(moved = moved, total = plan.size, unreadable = unreadable)
            Log.i(TAG, "Moved $moved download files to ${target.root} ($unreadable unreadable, downloaded again)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoSpaceException) {
            Log.w(TAG, "Moving downloads stopped: not enough space at ${e.dir}")
            _relocation.value = DownloadRelocation(moved = moved, total = plan.size, error = appContext.getString(R.string.data_dl_move_no_space), unreadable = unreadable)
        } catch (e: CardGoneException) {
            // Resumes when the card is mounted again (onVolumesChanged), or from Settings.
            Log.w(TAG, "Moving downloads stopped: ${e.message}")
            _relocation.value = DownloadRelocation(moved = moved, total = plan.size, error = appContext.getString(R.string.data_dl_move_card_gone), unreadable = unreadable)
        } catch (e: IOException) {
            Log.w(TAG, "Moving downloads stopped; retried later", e)
            val message = if (e is CardFailingException) R.string.data_dl_move_card_failing else R.string.data_dl_move_failed
            _relocation.value = DownloadRelocation(moved = moved, total = plan.size, error = appContext.getString(message), unreadable = unreadable)
            // A pass that got somewhere is tried again on its own (the files that stopped it last);
            // one that stopped at once waits for a start, a mount, a change, or Settings.
            if (copied + unreadable > 0) retryRelocationLater()
        } finally {
            // The damaged ones are queued: download them now.
            if (unreadable > 0) withContext(NonCancellable) { if (dao.pendingCount() > 0) scheduleExecution(kick = true) }
        }
    }

    /** Sources whose move failed in this process (unreadable, or a copy that stopped a pass): moved last. */
    private val relocationFailures: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    @Volatile private var relocateRetry: Job? = null

    /** Tries the move again a while after a pass that stopped on its way. */
    private fun retryRelocationLater() {
        relocateRetry?.cancel()
        relocateRetry = scope.launch {
            delay(RELOCATE_RETRY_MS)
            relocate()
        }
    }

    /**
     * Restarts a move that stopped (Settings > Storage), also for the same location: whatever is
     * still not on the chosen location is moved, a damaged original downloaded again.
     */
    fun retryMove() {
        relocateRetry?.cancel()
        relocate()
    }

    /**
     * Blocking. Copies one file of a move ([copyVerified]) and tells [health] how it went. An
     * original that cannot be read while its card is there and readable is a damaged file
     * ([SourceUnreadableException]: skipped, downloaded again); a card that went (unmounted,
     * removed) or whose folder cannot be read stops the move instead ([CardGoneException]), and it
     * resumes when the card is back.
     */
    private fun copyMove(move: DownloadRules.FileMove, health: SourceHealth) {
        try {
            copyVerified(File(move.from), File(move.to), storage::freeBytes, DownloadRules.MIN_FREE_BYTES)
            health.copied()
        } catch (e: SourceUnreadableException) {
            val root = DownloadRules.rootOf(move.from) ?: throw CardGoneException("No location for ${move.from}", e)
            if (!storage.locations.isAvailable(root)) throw CardGoneException("The location of ${move.from} went", e)
            if (File(root).list() == null || File(move.from).parentFile?.list() == null) {
                throw CardGoneException("The folder of ${move.from} cannot be read", e)
            }
            Log.w(TAG, "Could not read ${move.from}; it is downloaded again", e)
            health.unreadable()
            throw e
        }
    }

    /**
     * Damaged originals met while moving: downloads whose audio cannot be read go back into the
     * queue (downloaded fresh to the chosen location, out of the offline index with a numbered
     * change); an unreadable cover is dropped (the CDN image shows). Their old files are collected
     * with the rest.
     */
    private suspend fun dropUnreadable(moves: List<DownloadRules.FileMove>) {
        if (moves.isEmpty()) return
        val (uris, seq) = mutex.withLock {
            val uris = LinkedHashSet<String>()
            database.withTransaction {
                moves.forEach { move ->
                    if (move.image) {
                        dao.dropImage(move.from)
                    } else {
                        uris += dao.completedUrisWithPath(move.from)
                        dao.requeueUnreadable(move.from)
                        dao.forgetUnreadable(move.from)
                    }
                }
            }
            keys.remove(uris)
            uris.toList() to (if (uris.isNotEmpty()) index.next() else null)
        }
        seq?.let { index.remove(uris, it) }
    }

    /**
     * Points the rows at the verified [copies] and re-registers them with the offline index (the
     * index plays the copies from then on), then deletes the originals no row names any more.
     */
    private suspend fun switchCopies(copies: List<DownloadRules.FileMove>) {
        if (copies.isEmpty()) return
        val (seq, rows) = mutex.withLock {
            val uris = LinkedHashSet<String>()
            database.withTransaction {
                copies.forEach { move ->
                    if (move.image) dao.relocateImage(move.from, move.to) else dao.relocatePath(move.from, move.to)
                    uris += dao.completedUrisUsing(move.to)
                }
            }
            index.next() to uris.toList().chunked(SQL_CHUNK).flatMap { dao.completedIndexRows(it) }
        }
        val busy = registerRows(rows, seq)
        if (busy.isNotEmpty()) scope.launch { registerLate(busy) }
        mutex.withLock {
            val unused = copies.filter { dao.countPathUsers(it.from) == 0 }
            withContext(Dispatchers.IO) { unused.forEach { if (it.image) storage.deleteImage(it.from) else storage.deleteAudio(it.from) } }
        }
    }

    /**
     * Moves the `.part` files unfinished downloads resume from other mounted locations to [target],
     * while no download runs (none is being written then); a running download skips it (that item
     * then starts over in the new location). Takes the queue only when there is something to move,
     * and schedules the queue again afterwards (a run that started meanwhile found it taken).
     */
    private suspend fun moveParts(target: File) {
        val targetRoot = DownloadRules.normalizeRoot(target.path)
        val ids = dao.unfinishedFileIds().mapTo(HashSet()) { "${it.lowercase()}.part" }
        if (ids.isEmpty()) return
        val parts = withContext(Dispatchers.IO) {
            storage.locations.list()
                .filter { DownloadRules.normalizeRoot(it.root.path) != targetRoot }
                .flatMap { location -> storage.audioDir(location.root).listFiles()?.filter { it.isFile && it.name.lowercase() in ids }.orEmpty() }
        }
        if (parts.isEmpty()) return
        val ran = runner.whileIdle {
            withContext(Dispatchers.IO) {
                for (part in parts) {
                    val dst = File(storage.audioDir(target), part.name)
                    if (!part.isFile || dst.exists()) continue // gone, or the new location has progress for it
                    try {
                        copyVerified(part, dst, storage::freeBytes, DownloadRules.MIN_FREE_BYTES)
                        part.delete()
                    } catch (e: IOException) {
                        Log.w(TAG, "Could not move ${part.name}; that download starts over", e)
                    }
                }
            }
        }
        if (ran && dao.pendingCount() > 0) scheduleExecution(kick = true)
    }

    // ---- offline covers ------------------------------------------------------------------------------

    /**
     * Keeps [covers] in step with the completed downloads, their cover files (a move to another
     * location) and the downloaded collections. Each download's metadata is read once (memoised by
     * URI; a moved cover only changes its file); a download run that completes items one after
     * another updates the maps once it settles.
     */
    private suspend fun followCovers() {
        val known = HashMap<String, DownloadRules.CoverSource>()
        combine(completedImagePaths, unavailable, collectionDao.observeAll()) { paths, gone, collections -> Triple(paths, gone, collections) }
            .collectLatest { (paths, gone, collections) ->
                delay(COVERS_SETTLE_MS)
                try {
                    val maps = withContext(Dispatchers.Default) {
                        val fresh = DownloadRules.refreshCoverSources(known, paths, gone)
                        fresh.chunked(SQL_CHUNK).flatMap { dao.coverRows(it) }.forEach { row ->
                            known[row.uri] = DownloadRules.CoverSource(row.uri, row.imagePath, DownloadRules.coverUrls(json, row.metadataJson, row.recordJson))
                        }
                        // Not completed any more by the time it was read: looked at again next time.
                        DownloadRules.offlineCoverMaps(known.values, collections.map { it.imageUrl to decodeItems(it.itemUrisJson) })
                    }
                    covers.update(maps)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Updating the offline covers failed", e)
                }
            }
    }

    // ---- explicit filter -----------------------------------------------------------------------------

    /**
     * Follows "Hide explicit content" and the account's own filter for [explicitFilter] (the
     * catalog's `playable` includes both). When the effective filter goes off, what was looked up
     * while it was on is looked up again within seconds ([onExplicitFilterOff]).
     */
    private suspend fun watchExplicitFilter() {
        var settledFiltered: Boolean? = null
        combine(settings.persisted, engine.user) { prefs, user ->
            // Reported: as an online session reported it (unknown before); effective: else the
            // value last reported (a start without a network).
            ExplicitInputs(prefs.hideExplicit, user?.takeIf { it.reportedOnline }?.explicitFilter, accountExplicitFilter(user, prefs.accountExplicitFilter))
        }
            .distinctUntilChanged()
            .collectLatest { inputs ->
                explicitFilter.updateAccount(inputs.reportedAccount)
                val generation = explicitFilter.updateApp(inputs.hide)
                // The catalog answers with the new setting once the engine runs with it.
                engine.awaitSettingsApplied(EXPLICIT_APPLY_TIMEOUT_MS) { it.filterExplicit == inputs.hide }
                explicitFilter.settleApp(generation)
                val filtered = inputs.hide || inputs.account
                val before = settledFiltered
                settledFiltered = filtered
                if (before == true && !filtered) onExplicitFilterOff()
            }
    }

    private data class ExplicitInputs(val hide: Boolean, val reportedAccount: Boolean?, val account: Boolean)

    /**
     * The explicit filter went off: collections' unavailable members are due for a re-check (also
     * if this process ends first), and the re-validation runs now when online, else once online.
     */
    private suspend fun onExplicitFilterOff() {
        try {
            mutex.withLock { collectionDao.clearUnavailableChecked() }
            if (settings.awaitLoaded().offlineMode || !engine.isOnline.value) {
                recheckWhenOnline = true
            } else if (!recheckAfterFilterOff()) {
                recheckWhenOnline = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Re-checking downloads after the explicit filter went off failed", e)
        }
    }

    /** [revalidate] without collection listings, after a sync that is running. */
    private suspend fun recheckAfterFilterOff(): Boolean = try {
        sync(wait = true, revalidateOnly = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Re-check after the explicit filter went off failed", e)
        false
    }

    /**
     * Once per installation: earlier versions applied "Hide explicit content" to downloads. Restores
     * the explicit downloads re-validation failed as no longer playable (file and key were kept),
     * queues the explicit ones the downloader refused, and makes every collection's unavailable
     * members due for a re-check ([DownloadRules.explicitRepair]). Restored downloads are registered
     * with the offline index at once, and re-validated at the next sync. Only once an online session
     * reported the account without its own explicit filter: for a filtered account those downloads
     * stay as they are (explicit items are not downloaded for it), and the repair waits.
     */
    private suspend fun repairExplicitFailures() {
        try {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (withContext(Dispatchers.IO) { prefs.getInt(KEY_EXPLICIT_REPAIR, 0) } >= EXPLICIT_REPAIR_VERSION) return
            engine.user.first { it != null && it.reportedOnline && !it.explicitFilter }
            val unplayable = appContext.getString(R.string.data_dl_error_unplayable)
            val unavailable = appContext.getString(R.string.data_dl_error_unavailable)
            val (repair, restored, seq) = mutex.withLock {
                val failed = dao.failedRepairRows(listOf(unplayable, unavailable))
                val members = collectionDao.unavailableUrisJsons().flatMapTo(HashSet()) { decodeItems(it) }
                val repair = DownloadRules.explicitRepair(
                    failed.map { DownloadRules.RepairRow(it.uri, it.error, it.finished, DownloadRules.storedExplicit(json, it.metadataJson, it.recordJson)) },
                    unplayable,
                    unavailable,
                    members,
                )
                database.withTransaction {
                    repair.restore.chunked(SQL_CHUNK).forEach { dao.restoreCompleted(it) }
                    repair.requeue.chunked(SQL_CHUNK).forEach { dao.requeueFailedOnly(it) }
                    collectionDao.clearUnavailableChecked()
                }
                adjustUnavailableLocked(gone = emptySet(), playableAgain = repair.restore.toHashSet())
                Triple(repair, repair.restore.chunked(SQL_CHUNK).flatMap { dao.completedIndexRows(it) }, index.next())
            }
            val busy = registerRows(restored, seq)
            if (busy.isNotEmpty()) scope.launch { registerLate(busy) }
            withContext(Dispatchers.IO) { prefs.edit().putInt(KEY_EXPLICIT_REPAIR, EXPLICIT_REPAIR_VERSION).commit() }
            if (repair.restore.isNotEmpty() || repair.requeue.isNotEmpty()) {
                Log.i(TAG, "Explicit filter repair: restored ${repair.restore.size} downloads, queued ${repair.requeue.size}")
            }
            if (repair.requeue.isNotEmpty()) scheduleExecution(kick = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Explicit filter repair failed; retried at the next start", e)
        }
    }

    /**
     * Once per installation: earlier versions read Spotify's audio-key throttle (a playlist
     * downloaded too fast) as an account refusal, which failed every pending download, or as a
     * network error retried until the attempts ran out. Those failed downloads go back into the
     * queue ([DownloadRules.throttleRepair]), downloaded at the pace the engine now keeps.
     */
    private suspend fun repairThrottleFailures() {
        try {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (withContext(Dispatchers.IO) { prefs.getInt(KEY_THROTTLE_REPAIR, 0) } >= THROTTLE_REPAIR_VERSION) return
            val reasons = setOf(
                appContext.getString(R.string.data_dl_error_refused),
                appContext.getString(R.string.data_dl_error_network),
                appContext.getString(R.string.data_dl_error_rate_limited),
            )
            val requeued = mutex.withLock {
                val uris = DownloadRules.throttleRepair(dao.retryRows(), reasons)
                database.withTransaction { uris.chunked(SQL_CHUNK).sumOf { dao.requeueFailedOnly(it) } }
            }
            withContext(Dispatchers.IO) { prefs.edit().putInt(KEY_THROTTLE_REPAIR, THROTTLE_REPAIR_VERSION).commit() }
            if (requeued > 0) {
                Log.i(TAG, "Key throttle repair: queued $requeued failed downloads again")
                scheduleExecution(kick = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Key throttle repair failed; retried at the next start", e)
        }
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
    internal suspend fun sync(
        onlyDue: Boolean = false,
        only: Set<String>? = null,
        wait: Boolean = false,
        revalidateOnly: Boolean = false,
    ): Boolean = scope.detached { syncNow(onlyDue, only, wait, revalidateOnly) }

    /**
     * With [only], just those collections (no re-validation); with [revalidateOnly], just the
     * re-validation ([revalidate], no collection listings); with [wait], after a sync that is running
     * instead of skipping (it may have listed them before the edit).
     */
    private suspend fun syncNow(onlyDue: Boolean, only: Set<String>? = null, wait: Boolean = false, revalidateOnly: Boolean = false): Boolean {
        if (wait) syncMutex.lock() else if (!syncMutex.tryLock()) return true // another sync is running
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
                if (!awaitCountry()) {
                    // The catalog judges `playable` per country: before it is known, re-validation and
                    // the availability bookkeeping would fail good downloads. Retried later.
                    Log.i(TAG, "Sync postponed: the session has not reported its country yet")
                    return false
                }
                var added = 0
                val removed = ArrayList<Removal>()
                for (collection in all) {
                    if (revalidateOnly) break
                    if (only != null && collection.uri !in only) continue
                    val type = CollectionType.fromWire(collection.type) ?: continue
                    if (onlyDue && DownloadRules.nextSyncAt(collection.lastSyncedAt, collection.lastAttemptAt, collection.syncFailures) > startedAt) continue
                    val resolved = try {
                        resolver.resolve(type, collection.uri, collection.revision)
                            ?.let { withCatalogInfo(it, known = decodeItems(collection.itemUrisJson).toHashSet()) }
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
                if (only == null) added += revalidate()
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

    // ---- app-side library edits -------------------------------------------------------------------

    private val editSyncs = EditCoalescer(scope, EDIT_SYNC_DELAY_MS) { uris -> syncEdited(uris) }

    /**
     * A like / unlike or a playlist edit made in the app (wired in AppGraph from
     * [com.taehagen.spotifygood.data.LibraryRepository.edits]): the downloaded collection it affects
     * is synced [EDIT_SYNC_DELAY_MS] after the last such edit, so the download follows within
     * seconds instead of at the next scheduled sync.
     */
    internal suspend fun onLibraryEdit(edit: LibraryEdit) {
        try {
            val downloaded = collectionDao.syncStates()
            val targets = when (edit) {
                is LibraryEdit.LikedTracks -> downloaded.filter { it.type == CollectionType.LIKED_SONGS.wire }.map { it.uri }
                is LibraryEdit.PlaylistEdited -> downloaded.filter { it.uri == edit.uri }.map { it.uri }
                // Pull-to-refresh: whatever changed elsewhere (no revision to tell).
                LibraryEdit.Refreshed -> downloaded.filter { it.type in REMOTE_TYPES }.map { it.uri }
            }
            targets.forEach(editSyncs::offer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not follow a library edit", e)
        }
    }

    private suspend fun syncEdited(uris: Set<String>) {
        try {
            if (settings.awaitLoaded().offlineMode || !engine.isOnline.value) {
                // Not now: due at the next sync (when the session comes online).
                mutex.withLock { collectionDao.markSyncDue(uris.toList()) }
                return
            }
            // Not online after all: due at the next sync instead of lost.
            if (!sync(only = uris, wait = true)) mutex.withLock { collectionDao.markSyncDue(uris.toList()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync after a library edit failed", e)
        }
    }

    /**
     * A server page of [uri] was loaded (Liked Songs, a playlist with its [revision]): changes made
     * on another device show up there first. A downloaded [uri] then syncs within seconds, like after
     * an edit made here ([DownloadRules.syncRequestDue]: a playlist only when its revision is not the
     * downloaded one; otherwise at most every few minutes).
     */
    suspend fun requestSync(uri: String, revision: String? = null) {
        try {
            val entity = collectionDao.get(uri) ?: return
            val now = System.currentTimeMillis()
            if (!DownloadRules.syncRequestDue(revision, entity.revision, syncRequests[uri], entity.lastAttemptAt, now)) return
            syncRequests[uri] = now
            editSyncs.offer(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask $uri to sync", e)
        }
    }

    /**
     * Spotify pushed a change of Liked Songs (a song liked or unliked on another device): a
     * downloaded Liked Songs syncs, as [requestSync] paces it (at most every few minutes).
     */
    suspend fun requestLikedSongsSync() {
        try {
            collectionDao.syncStates().filter { it.type == CollectionType.LIKED_SONGS.wire }.forEach { requestSync(it.uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not ask Liked Songs to sync", e)
        }
    }

    /**
     * In the foreground and online: re-lists the downloaded Liked Songs and playlists whose last sync
     * is [DownloadRules.FOREGROUND_STALE_MS] old (they change on other devices; albums and shows keep
     * the daily cadence), so opening the app picks up remote changes also when the session stayed
     * online.
     */
    private suspend fun syncRemoteChanges() {
        try {
            if (settings.awaitLoaded().offlineMode) return
            val now = System.currentTimeMillis()
            val due = collectionDao.syncStates().filter {
                it.type in REMOTE_TYPES &&
                    DownloadRules.nextSyncAt(it.lastSyncedAt, it.lastAttemptAt, it.syncFailures, DownloadRules.FOREGROUND_STALE_MS) <= now
            }.mapTo(HashSet()) { it.uri }
            if (due.isNotEmpty()) sync(only = due)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Foreground sync failed", e)
        }
    }

    // ---- hooks for the job / workers --------------------------------------------------------------

    internal suspend fun hasCollections(): Boolean = collectionDao.count() > 0

    // ---- membership ----------------------------------------------------------------------------------

    /**
     * [added] items queued; for a user-initiated download also how many members are now [queued]
     * and how many wait for the chosen card that is away ([waitingForCard]).
     */
    private class MembershipResult(val added: Int, val removal: Removal, val queued: Int = 0, val waitingForCard: Int = 0)

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
        // Only members that came with catalog metadata say whether they are playable: an unchecked
        // URI-only member keeps its previous availability (it is not "revived").
        val (checked, availabilityComplete) = DownloadRules.availabilityOf(resolved.items, resolved.complete)
        val availability = DownloadRules.updateAvailability(
            old = decodeItems(entity.unavailableUrisJson).toHashSet(),
            listed = checked.mapTo(HashSet()) { it.uri },
            listedUnavailable = checked.filter { it.unavailable }.mapTo(HashSet()) { it.uri },
            complete = availabilityComplete,
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
        // Downloaded again when the user asks: members on an SD card that is no longer used.
        val missing = if (userInitiated) missingCardOf(newItems) else DownloadRules.MissingCard()
        val redownload = missing.stranded.filter { it !in availability.unavailable }
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
            if (userInitiated) {
                newItems.filter { it !in availability.unavailable }.chunked(SQL_CHUNK).forEach { dao.requeueFailed(it) }
                redownload.chunked(SQL_CHUNK).forEach { dao.requeueFromUnusedCard(it) }
            } else {
                DownloadRules.requeueOnSync(toQueue.map { it.uri }, availability.revived).chunked(SQL_CHUNK).forEach { dao.requeueFailedOnly(it) }
            }
            deleteRows(toDelete)
        }
        deleteFiles(files)
        keys.remove(redownload)
        if (toDelete.isNotEmpty()) cancelWorkIfIdleLocked()
        val queued = if (userInitiated) newItems.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.count { DownloadRules.isPending(it.state) } else 0
        // Downloads going again leave the index with the deleted ones (numbered: the new one wins).
        return MembershipResult(toQueue.size, Removal(toDelete + redownload, index.next()), queued, missing.waiting.size)
    }

    /**
     * [resolved] with catalog metadata (display metadata and whether it is playable here) for the
     * members that lack it (Liked Songs list URIs only; placeholders) and matter: those that will get
     * a new row (not in [known], the stored membership, since sync only queues new members; no row
     * yet) and those whose row FAILED (a greyed-out member is then recorded as unavailable instead of
     * failing again). Completed and pending rows are not looked up again. Best effort, batched and
     * bounded ([COLLECTION_METADATA_TIMEOUT_MS]): members not looked up keep their previous
     * availability, rows without metadata get it from the downloaded record.
     */
    private suspend fun withCatalogInfo(resolved: CollectionResolver.Resolved, known: Set<String>): CollectionResolver.Resolved {
        val lacking = resolved.items.filter { it.metadataJson == null }.map { it.uri }
        if (lacking.isEmpty()) return resolved
        val states = lacking.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.associate { it.uri to it.state }
        val wanted = lacking.filter { uri ->
            when (states[uri]) {
                null -> uri !in known
                DownloadState.FAILED -> true
                else -> false
            }
        }
        if (wanted.isEmpty()) return resolved
        val fetched = resolver.itemsBestEffort(wanted, COLLECTION_METADATA_TIMEOUT_MS)
        if (fetched.isEmpty()) return resolved
        // A fetched placeholder (lookup failed) stays unknown.
        return resolved.copy(items = resolved.items.map { item -> fetched[item.uri]?.takeIf { it.metadataJson != null } ?: item })
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
        // Completed files are named by their path (per location); unfinished downloads by the file
        // they write.
        val audio = DownloadRules.audioToDelete(rows.map { it.path }, dao.allPaths(), dao.unfinishedFileIds())
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

    /**
     * Re-validates downloads older than 30 days (unplayable ones are marked failed, keeping their
     * file) and queues failed downloads that still own their file and are playable again (back in the
     * home country; also a key that became unreadable): `download.track` reuses the file. Explicit
     * downloads the catalog reports unplayable while the explicit filter may have applied get no
     * verdict ([CollectionResolver.playability]): they stay as they are, and stale ones are
     * re-validated at a later sync. Returns how many were queued.
     */
    private suspend fun revalidate(): Int {
        val requeued = requeuePlayableAgain() + queueUnavailableAgain()
        val now = System.currentTimeMillis()
        val stale = dao.completedUrisNotValidatedSince(now - DownloadRules.REVALIDATE_AFTER_MS)
        if (stale.isEmpty()) return requeued
        val playable = try {
            resolver.playability(stale)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Re-validation failed", e)
            return requeued
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
                // So the collections containing them can still reach "Downloaded" (a URI-only listing
                // or an unchanged playlist revision would never report them).
                adjustUnavailableLocked(gone = gone.toHashSet(), playableAgain = emptySet())
                index.next()
            }
            index.remove(gone, seq)
        }
        return requeued
    }

    /**
     * Looks up the members collections record as unavailable once a day
     * ([DownloadRules.unavailableToRecheck]; batched and bounded like new members), and queues the ones
     * that are playable again: they leave every collection's unavailable set, get a row (with
     * metadata) or their FAILED row is requeued. Returns how many were queued.
     */
    private suspend fun queueUnavailableAgain(): Int {
        val now = System.currentTimeMillis()
        val sets = collectionDao.getAll().map {
            DownloadRules.UnavailableMembers(it.uri, decodeItems(it.unavailableUrisJson).toHashSet(), it.unavailableCheckedAt)
        }
        val all = sets.flatMapTo(HashSet()) { it.members }.toList()
        if (all.isEmpty()) return 0
        val states = all.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.associate { it.uri to it.state }
        val recheck = DownloadRules.unavailableToRecheck(sets, states, now)
        if (recheck.collections.isEmpty()) return 0
        val fetched = if (recheck.members.isEmpty()) {
            emptyMap()
        } else {
            resolver.itemsBestEffort(recheck.members.toList(), COLLECTION_METADATA_TIMEOUT_MS)
        }
        // Lookups that failed, and answers without a verdict (an explicit member while the explicit
        // filter may have applied), stay unknown: those collections are checked again at the next sync.
        val checked = sets.filter {
            it.collection in recheck.collections && (it.members intersect recheck.members).all { m -> fetched[m]?.checked == true }
        }
        val again = fetched.values.filter { it.checked && !it.unavailable }
        return mutex.withLock {
            // The lookup ran without the lock: re-read what the collections record now, before
            // adjustUnavailableLocked takes these members out of the sets.
            val live = collectionDao.getAll()
            val liveUris = live.mapTo(HashSet()) { it.uri }
            val stillChecked = checked.map { it.collection }.filter { it in liveUris }
            if (stillChecked.isNotEmpty()) collectionDao.markUnavailableChecked(stillChecked, now)
            val confirmed = DownloadRules.confirmRecheck(again.map { it.uri }, live.map { decodeItems(it.unavailableUrisJson) }).toHashSet()
            val queue = again.filter { it.uri in confirmed }
            if (queue.isEmpty()) return@withLock 0
            adjustUnavailableLocked(gone = emptySet(), playableAgain = confirmed)
            val current = queue.map { it.uri }.chunked(SQL_CHUNK).flatMap { dao.statesOf(it) }.associate { it.uri to it.state }
            val fresh = queue.filter { current[it.uri] == null }
            val quality = settings.awaitLoaded().downloadQuality.kbps
            database.withTransaction {
                insertRows(fresh, quality, individual = false, now = now)
                queue.filter { current[it.uri] == DownloadState.FAILED }.map { it.uri }.chunked(SQL_CHUNK).forEach { dao.requeueFailedOnly(it) }
            }
            Log.i(TAG, "${queue.size} members recorded as unavailable are playable again: queued")
            queue.size
        }
    }

    /** See [revalidate]. Independent of collection syncs (an unchanged playlist revision skips those). */
    private suspend fun requeuePlayableAgain(): Int {
        // Only rows still failed by re-validation or the key check: a row that failed again when it
        // was retried carries the attempt's reason and is not queued at every sync.
        val reasons = listOf(appContext.getString(R.string.data_dl_error_unplayable), appContext.getString(R.string.data_dl_error_key))
        val dormant = dao.failedWithFileUris(reasons)
        if (dormant.isEmpty()) return 0
        val playable = try {
            resolver.playability(dormant)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Checking failed downloads failed", e)
            return 0
        }
        val again = dormant.filter { playable[it] == true }
        if (again.isEmpty()) return 0
        val requeued = mutex.withLock {
            adjustUnavailableLocked(gone = emptySet(), playableAgain = again.toHashSet())
            again.chunked(SQL_CHUNK).sumOf { dao.requeueFailedOnly(it) }
        }
        if (requeued > 0) Log.i(TAG, "Queued $requeued failed downloads that are playable again")
        return requeued
    }

    /** Waits (≤ [COUNTRY_TIMEOUT_MS]) until the session reported its country; false if it did not. */
    private suspend fun awaitCountry(): Boolean =
        withTimeoutOrNull(COUNTRY_TIMEOUT_MS) { engine.user.first { !it?.country.isNullOrEmpty() } } != null

    /** Must hold [mutex]. Applies [DownloadRules.adjustUnavailable] to every downloaded collection. */
    private suspend fun adjustUnavailableLocked(gone: Set<String>, playableAgain: Set<String>) {
        if (gone.isEmpty() && playableAgain.isEmpty()) return
        for (collection in collectionDao.getAll()) {
            val members = decodeItems(collection.itemUrisJson)
            val updated = DownloadRules.adjustUnavailable(members, decodeItems(collection.unavailableUrisJson).toHashSet(), gone, playableAgain)
                ?: continue
            collectionDao.upsert(collection.copy(unavailableUrisJson = json.encodeToString(itemsSerializer, members.filter { it in updated })))
        }
    }

    /**
     * Must hold [mutex], after removals. Cancels download work scheduled for a queue that is now
     * empty, so it does not later bring the session up (and post a notification) for nothing. A later
     * enqueue inserts its rows under [mutex] and schedules again afterwards.
     */
    private suspend fun cancelWorkIfIdleLocked() {
        if (!DownloadRules.cancelIdleWork(dao.pendingCount(), runner.isRunning, jobExecuting)) return
        appContext.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        cancelWorker()
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
        /** [legacy]: sealed with the Keystore key itself (one TEE operation each). */
        data class Key(val hex: String, val legacy: Boolean) : KeyResult
        data object Unreadable : KeyResult
        data object KeystoreBusy : KeyResult
    }

    /**
     * Blocking. Opens a row's audio key: with the data key in software ([KeyVault]; the first one of
     * the process unseals the data key with the Keystore), or for keys from before the data key with
     * the Keystore itself. Tells a transient Keystore failure from a key that is unusable for good.
     */
    private fun decryptKey(row: IndexRow): KeyResult {
        val cipher = row.encryptedKey ?: return KeyResult.Unreadable
        return try {
            if (row.keyVersion >= 1) {
                KeyResult.Key(Hex.encode(vault.unseal(cipher, row.uri)), legacy = false)
            } else {
                KeyResult.Key(Hex.encode(credentialStore.decrypt(cipher)), legacy = true)
            }
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
            // The chosen card is not mounted: nothing could run (applyLocation schedules on its
            // return). Said once on the Downloads screen and in a notification instead.
            if (storage.target.value is DownloadStorage.Target.Missing) {
                runner.reportStopped(appContext.getString(R.string.data_dl_error_location))
                return@withContext
            }
            // Loaded from disk: the defaults before that would give the wrong network constraint.
            val current = settings.awaitLoaded()
            if (current.offlineMode) return@withContext
            val cellular = current.downloadOverCellular
            // "Storage not low" is about internal storage: not a condition for downloads to a card.
            val storageNotLow = needsInternalStorage()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val scheduler = appContext.getSystemService(JobScheduler::class.java)
                val job = scheduler?.getPendingJob(JOB_ID)
                val jobStale = job != null && (job.requiresUnmetered() == cellular || job.isRequireStorageNotLow != storageNotLow)
                // A pending (or just started) user-initiated job will drain the queue; a WorkManager
                // fallback next to it would only start and find the queue taken.
                if (job != null && DownloadRules.keepPendingJob(replace, kick, jobExecuting, jobStale)) return@withContext
                val estimate = DownloadRules.estimateBytes(pending, current.downloadQuality.kbps)
                // Scheduling with the same id replaces the pending job: new constraint, no backoff.
                if (isAppVisible() && scheduleUserInitiatedJob(cellular, estimate, storageNotLow)) {
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
            enqueueWorker(cellular, storageNotLow, replace, kick)
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

    /** New downloads go to internal storage (or where they go is not known yet). */
    private fun needsInternalStorage(): Boolean {
        val target = storage.target.value as? DownloadStorage.Target.Ready ?: return storage.target.value is DownloadStorage.Target.Unresolved
        return DownloadRules.normalizeRoot(target.root.path) == DownloadRules.normalizeRoot(storage.locations.internalRoot.path)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun scheduleUserInitiatedJob(cellular: Boolean, estimatedBytes: Long, storageNotLow: Boolean): Boolean {
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(JOB_ID, ComponentName(appContext, DownloadJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(if (cellular) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
            .setEstimatedNetworkBytes(estimatedBytes, 0)
            .setRequiresStorageNotLow(storageNotLow)
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
    private fun enqueueWorker(cellular: Boolean, storageNotLow: Boolean, replace: Boolean, kick: Boolean) {
        val network = if (cellular) NetworkType.CONNECTED else NetworkType.UNMETERED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(network)
            .setRequiresStorageNotLow(storageNotLow)
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
            stale = existing != null &&
                (existing.constraints.requiredNetworkType != network || existing.constraints.requiresStorageNotLow() != storageNotLow),
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
        private const val COUNTRY_TIMEOUT_MS = 10_000L
        private const val EDIT_SYNC_DELAY_MS = 5_000L
        private const val RESEAL_BATCH = 200
        private const val COVERS_SETTLE_MS = 1_000L
        private const val MOUNT_SETTLE_MS = 500L
        private const val RELOCATE_BATCH = 50
        private const val RELOCATE_RETRY_MS = 5 * 60_000L

        /** Collections that change on other devices (likes, playlist edits). */
        private val REMOTE_TYPES = setOf(CollectionType.LIKED_SONGS.wire, CollectionType.PLAYLIST.wire)

        /** Longest wait for the engine to apply a changed explicit filter (as Settings waits). */
        private const val EXPLICIT_APPLY_TIMEOUT_MS = 15_000L
        private const val PREFS = "downloads"
        private const val KEY_EXPLICIT_REPAIR = "explicitFilterRepair"
        private const val EXPLICIT_REPAIR_VERSION = 1
        private const val KEY_THROTTLE_REPAIR = "keyThrottleRepair"
        private const val THROTTLE_REPAIR_VERSION = 1
        private const val JOB_BACKOFF_MS = 30_000L
        private const val WORK_BACKOFF_S = 30L
        private const val SYNC_BACKOFF_MIN = 15L
    }
}
