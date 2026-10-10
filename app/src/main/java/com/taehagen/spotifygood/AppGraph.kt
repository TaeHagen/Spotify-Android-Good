package com.taehagen.spotifygood

import android.app.Application
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil3.SingletonImageLoader
import com.taehagen.spotifygood.auth.AuthRepository
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.connect.DevicesRepository
import com.taehagen.spotifygood.connect.LocalDeviceDiscovery
import com.taehagen.spotifygood.data.CatalogRepository
import com.taehagen.spotifygood.data.EpisodeProgressStore
import com.taehagen.spotifygood.data.HomeRepository
import com.taehagen.spotifygood.data.LibraryEdit
import com.taehagen.spotifygood.data.LibraryPushEffects
import com.taehagen.spotifygood.data.LibraryPushes
import com.taehagen.spotifygood.data.LibraryRepository
import com.taehagen.spotifygood.data.LyricsRepository
import com.taehagen.spotifygood.data.MOSAIC_SCAN
import com.taehagen.spotifygood.data.OwnLibraryEdits
import com.taehagen.spotifygood.data.PlaylistEditor
import com.taehagen.spotifygood.data.PlaylistMosaicStore
import com.taehagen.spotifygood.data.ResponseCache
import com.taehagen.spotifygood.data.SearchRepository
import com.taehagen.spotifygood.data.StoredPosition
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.download.DownloadManager
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.engine.WipeStep
import com.taehagen.spotifygood.engine.runWipeSteps
import com.taehagen.spotifygood.model.CollectionChangeItem
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import com.taehagen.spotifygood.playback.MosaicBitmaps
import com.taehagen.spotifygood.playback.OutputRouteManager
import com.taehagen.spotifygood.playback.PlaybackRepository
import com.taehagen.spotifygood.playback.PlaybackServiceConnector
import com.taehagen.spotifygood.playback.PlayerController
import com.taehagen.spotifygood.playback.PodcastSpeed
import com.taehagen.spotifygood.playback.ResumeStore
import com.taehagen.spotifygood.playback.SleepTimer
import com.taehagen.spotifygood.ui.screens.album.downloadedPageFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency graph (docs/ARCHITECTURE.md §9.1). Everything is a lazy process singleton.
 * Construction must stay cheap: no I/O on the main thread here.
 */
class AppGraph(val app: Application) {
    /** Process-lifetime scope for repositories (never cancelled). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    val rpc: NativeRpc by lazy { NativeRpc(json) }
    val events: NativeEvents by lazy { NativeEvents(json) }
    val audioSink: AudioSinkBridge by lazy { AudioSinkBridge(app) }

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val database: AppDatabase by lazy { AppDatabase.create(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app, appScope) }
    val credentialStore: CredentialStore by lazy { CredentialStore(app) }

    private val engineLazy = lazy {
        SpotifyEngine(
            app, appScope, rpc, events, settings, credentialStore, audioSink,
            onAccountChanged = { removePreviousAccountData() },
        ).also { engine ->
            engine.setOfflineIndexProvider { downloads.offlineRecords() }
            // Library changes made elsewhere come from the running engine: followed from its start.
            appScope.launch { libraryPushes }
        }
    }
    val engine: SpotifyEngine by engineLazy

    /** The engine if this process created it (an alarm must not create one just to stop it). */
    fun engineIfCreated(): SpotifyEngine? = if (engineLazy.isInitialized()) engine else null

    val auth: AuthRepository by lazy { AuthRepository(app, appScope, engine, credentialStore, httpClient) }

    /**
     * Where a play of an episode starts (docs §6.5): every load of the player and a transfer of the
     * stored session ([PlayerController.episodeResume], [DevicesRepository.episodeResume]).
     */
    private val episodeStart: suspend (String, StoredPosition?) -> Long? = { uri, stored ->
        episodeProgress.resumeOrLookUp(uri, { engine.isOnline.value }, stored) { catalog.episodes(listOf(uri)) }
    }

    val playback: PlaybackRepository by lazy { PlaybackRepository(appScope, events) }
    val podcastSpeed: PodcastSpeed by lazy { PodcastSpeed(app, appScope, playback, audioSink, rpc) }
    /** Last local session (playback resumption, cold-start play); one DataStore per process. */
    val resumeStore: ResumeStore by lazy { ResumeStore(app) }
    val player: PlayerController by lazy {
        PlayerController(appScope, rpc, playback, resumeStore, devices).also { it.episodeResume = episodeStart }
    }
    /** Podcast progress made on this phone (docs §6.5); records local episode playback. */
    val episodeProgress: EpisodeProgressStore by lazy {
        EpisodeProgressStore(File(app.filesDir, "episode_progress.json"), appScope).also { store ->
            appScope.launch {
                store.recordFrom(
                    playback.snapshot,
                    seek = { player.seekTo(it) },
                    online = { engine.isOnline.value },
                    lookUp = { catalog.episodes(listOf(it)) },
                )
            }
        }
    }
    val devices: DevicesRepository by lazy {
        DevicesRepository(appScope, rpc, events, resumeStore::read).also { repo ->
            repo.episodeResume = episodeStart
            // Coroutine timers don't count deep sleep: an expired pending target goes when the app
            // comes back.
            appScope.launch(Dispatchers.Main) {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : androidx.lifecycle.DefaultLifecycleObserver {
                        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = repo.expirePendingTarget()
                    },
                )
            }
        }
    }
    private val localDiscoveryLazy = lazy {
        LocalDeviceDiscovery(app, rpc) { devices.devices.value.devices.map { it.id }.toSet() }
    }

    /** Spotify Connect local-network discovery (the "send" side); runs only while the sheet is up. */
    val localDiscovery: LocalDeviceDiscovery by localDiscoveryLazy
    val outputs: OutputRouteManager by lazy {
        OutputRouteManager(app, appScope, audioSink, rpc).also { manager ->
            appScope.launch(Dispatchers.Main) {
                engine.isRunning.collect { running ->
                    if (running) manager.start() else manager.stop()
                }
            }
        }
    }
    val sleepTimer: SleepTimer by lazy { SleepTimer(appScope, player, playback) }
    val playbackConnector: PlaybackServiceConnector by lazy { PlaybackServiceConnector(app) }

    val responseCache: ResponseCache by lazy { ResponseCache(database.responseCache(), json) }
    val catalog: CatalogRepository by lazy { CatalogRepository(rpc, responseCache, episodeProgress) }
    val library: LibraryRepository by lazy {
        LibraryRepository(appScope, rpc, responseCache, engine.isOnline, episodeProgress, ownLibraryEdits).also { repo ->
            // The playlists' current revisions: a mosaic learned at another is learned again.
            repo.onRootlist = { rootlist, fetched ->
                playlistMosaics.noteRevisions(rootlist.flatPlaylists().mapNotNull { e -> e.uri?.let { u -> e.revision?.let { u to it } } }.toMap(), fetched)
            }
        }
    }

    /** The art of playlists without an image of their own (docs §9.8). */
    val playlistMosaics: PlaylistMosaicStore by lazy {
        PlaylistMosaicStore(
            scope = appScope,
            cache = responseCache,
            firstPage = { uri -> catalog.playlistPage(uri, 0, MOSAIC_SCAN) },
            downloadedItems = { uri -> downloadedPageFlow(uri).first()?.playlistItems() },
            online = { engine.isOnline.value },
        ).also { store ->
            // An edit made here: its first items may have changed.
            appScope.launch { library.edits.collect { if (it is LibraryEdit.PlaylistEdited) store.invalidate(it.uri) } }
            // Rows shown while the session was still connecting (a cold start) fill in once it is online.
            appScope.launch { engine.isOnline.filter { it }.collect { store.onOnline() } }
        }
    }
    val search: SearchRepository by lazy { SearchRepository(rpc, database.recentSearches(), episodeProgress) }
    val home: HomeRepository by lazy { HomeRepository(rpc, responseCache) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(rpc) }
    val playlists: PlaylistEditor by lazy { PlaylistEditor(appScope, rpc, library, catalog, ownLibraryEdits) }

    /** Library writes made here: their pushed echoes are not changes made elsewhere. */
    val ownLibraryEdits: OwnLibraryEdits by lazy { OwnLibraryEdits() }

    /** ProcessLifecycleOwner STARTED: an activity of the app is visible. */
    private val appForeground: StateFlow<Boolean> by lazy {
        MutableStateFlow(false).also { flow ->
            appScope.launch(Dispatchers.Main) {
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    object : DefaultLifecycleObserver {
                        override fun onStart(owner: LifecycleOwner) {
                            flow.value = true
                        }

                        override fun onStop(owner: LifecycleOwner) {
                            flow.value = false
                        }
                    },
                )
            }
        }
    }

    /**
     * Library changes made elsewhere, pushed by the engine (docs §5 `playlistChanged`,
     * `rootlistChanged`, `collectionChanged`; §9.8): cached rows go stale, open pages refresh, and
     * in the foreground the playlist list, mosaics and downloads follow.
     */
    val libraryPushes: LibraryPushes by lazy {
        LibraryPushes(appScope, appForeground, ownLibraryEdits, PushEffects()).also { pushes ->
            pushes.start(events.libraryPushes)
            // Logout or another account: what was pushed for the previous one is dropped.
            responseCache.addClearListener { pushes.clear() }
        }
    }

    private inner class PushEffects : LibraryPushEffects {
        override suspend fun playlistStale(uri: String) = catalog.markPlaylistStale(uri)

        override suspend fun rootlistStale(reload: Boolean) = library.onRemoteRootlistChange(reload)

        override suspend fun setStale(set: String, reload: Boolean) = library.onRemoteSetChange(set, reload)

        override fun savedChanged(items: List<CollectionChangeItem>) = library.applyRemoteSaved(items)

        override fun playlistRevisions(revisions: Map<String, String>) = playlistMosaics.noteRevisions(revisions)

        override suspend fun syncPlaylistDownload(uri: String, revision: String?) = downloads.requestSync(uri, revision)

        override suspend fun syncLikedSongsDownload() = downloads.requestLikedSongsSync()
    }

    val downloads: DownloadManager by lazy {
        DownloadManager(app, appScope, database, rpc, events, engine, settings, credentialStore).also { manager ->
            // Likes and playlist edits made in the app re-sync the downloaded collection they affect.
            appScope.launch { library.edits.collect(manager::onLibraryEdit) }
        }
    }

    /**
     * Logs the user out and wipes all account data (docs/ARCHITECTURE.md §9.3): the login flow and
     * its pending device code, the native session and credentials, downloads, the resume state,
     * the response and image caches, the database and the settings.
     *
     * Failure-safe: every step runs even if an earlier one failed, and the first failure is
     * rethrown at the end (callers report "Logout failed"). Not cancellable. No new login can
     * reach the engine before the wipe is done ([AuthRepository.whileLoggingOut]).
     */
    suspend fun logout(): Unit = withContext(NonCancellable) {
        auth.whileLoggingOut {
            val steps = listOf(
                // Browsing the LAN for the old account's Connect targets ends with it.
                WipeStep("local discovery") { if (localDiscoveryLazy.isInitialized()) localDiscovery.stop() },
                WipeStep("engine") { engine.logout() },
            ) + accountDataSteps() + listOf(
                WipeStep("settings") { settings.reset() },
                WipeStep("events") { events.reset() },
            )
            runWipeSteps(steps) { step, t -> Log.e(TAG, "Logout: ${step.name} failed", t) }
            // Only once everything went: a later login of another account wipes again otherwise.
            withContext(Dispatchers.IO) { credentialStore.forgetAccountOwner() }
        }
    }

    /**
     * The data part of [logout], for a login as another account than the one the data belongs to
     * (after rejected credentials, docs/ARCHITECTURE.md §9.3). The new login's credentials, the
     * session and the settings stay; the live session state is republished natively for the new
     * account, so only the account's event replays go. Every step runs; the first failure is
     * rethrown (the login then fails, and the next one tries again). Not cancellable.
     */
    private suspend fun removePreviousAccountData(): Unit = withContext(NonCancellable) {
        val steps = accountDataSteps() + listOf(
            WipeStep("event replays") { events.resetAccountReplays() },
            // The previous account's parental filter (the new account's arrives with its session).
            WipeStep("account explicit filter") { settings.update { it.copy(accountExplicitFilter = false) } },
        )
        runWipeSteps(steps) { step, t -> Log.e(TAG, "Account change: ${step.name} failed", t) }
    }

    /** What belongs to the account: downloads, the resume state, the caches, the history, the pending device. */
    private fun accountDataSteps(): List<WipeStep> = listOf(
        // Rows, files, the key vault and the native offline index.
        WipeStep("downloads") { downloads.removeAll() },
        // The playback service clears it too, but only while it runs.
        WipeStep("resume state") { resumeStore.clear() },
        WipeStep("response cache") { responseCache.clear() },
        WipeStep("playlist mosaics") { withContext(Dispatchers.IO) { MosaicBitmaps.clear(app) } },
        WipeStep("episode progress") { episodeProgress.clear() },
        // Recent searches, downloaded collections and the rest of the account's tables.
        WipeStep("database") { withContext(Dispatchers.IO) { database.clearAllTables() } },
        WipeStep("image cache") {
            val loader = SingletonImageLoader.get(app)
            loader.memoryCache?.clear()
            withContext(Dispatchers.IO) { loader.diskCache?.clear() }
        },
        // A device picked for the next play was the old account's.
        WipeStep("pending device") { devices.clearPendingTarget() },
    )

    private companion object {
        const val TAG = "AppGraph"
    }
}
