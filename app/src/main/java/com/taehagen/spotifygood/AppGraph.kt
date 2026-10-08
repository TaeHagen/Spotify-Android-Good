package com.taehagen.spotifygood

import android.app.Application
import android.util.Log
import coil3.SingletonImageLoader
import com.taehagen.spotifygood.auth.AuthRepository
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.connect.DevicesRepository
import com.taehagen.spotifygood.connect.LocalDeviceDiscovery
import com.taehagen.spotifygood.data.CatalogRepository
import com.taehagen.spotifygood.data.HomeRepository
import com.taehagen.spotifygood.data.LibraryRepository
import com.taehagen.spotifygood.data.LyricsRepository
import com.taehagen.spotifygood.data.PlaylistEditor
import com.taehagen.spotifygood.data.ResponseCache
import com.taehagen.spotifygood.data.SearchRepository
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.download.DownloadManager
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.engine.WipeStep
import com.taehagen.spotifygood.engine.runWipeSteps
import com.taehagen.spotifygood.nativebridge.AudioSinkBridge
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import com.taehagen.spotifygood.playback.OutputRouteManager
import com.taehagen.spotifygood.playback.PlaybackRepository
import com.taehagen.spotifygood.playback.PlaybackServiceConnector
import com.taehagen.spotifygood.playback.PlayerController
import com.taehagen.spotifygood.playback.ResumeStore
import com.taehagen.spotifygood.playback.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
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

    val engine: SpotifyEngine by lazy {
        SpotifyEngine(
            app, appScope, rpc, events, settings, credentialStore, audioSink,
            onAccountChanged = { removePreviousAccountData() },
        ).also { engine ->
            engine.setOfflineIndexProvider { downloads.offlineRecords() }
        }
    }
    val auth: AuthRepository by lazy { AuthRepository(app, appScope, engine, credentialStore, httpClient) }

    val playback: PlaybackRepository by lazy { PlaybackRepository(appScope, events) }
    /** Last local session (playback resumption, cold-start play); one DataStore per process. */
    val resumeStore: ResumeStore by lazy { ResumeStore(app) }
    val player: PlayerController by lazy { PlayerController(appScope, rpc, playback, resumeStore, devices) }
    val devices: DevicesRepository by lazy {
        DevicesRepository(appScope, rpc, events, resumeStore::read).also { repo ->
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
    val catalog: CatalogRepository by lazy { CatalogRepository(rpc, responseCache) }
    val library: LibraryRepository by lazy { LibraryRepository(appScope, rpc, responseCache) }
    val search: SearchRepository by lazy { SearchRepository(rpc, database.recentSearches()) }
    val home: HomeRepository by lazy { HomeRepository(rpc, responseCache) }
    val lyrics: LyricsRepository by lazy { LyricsRepository(rpc) }
    val playlists: PlaylistEditor by lazy { PlaylistEditor(appScope, rpc, library, catalog) }

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
        val steps = accountDataSteps() + WipeStep("event replays") { events.resetAccountReplays() }
        runWipeSteps(steps) { step, t -> Log.e(TAG, "Account change: ${step.name} failed", t) }
    }

    /** What belongs to the account: downloads, the resume state, the caches, the history, the pending device. */
    private fun accountDataSteps(): List<WipeStep> = listOf(
        // Rows, files, the key vault and the native offline index.
        WipeStep("downloads") { downloads.removeAll() },
        // The playback service clears it too, but only while it runs.
        WipeStep("resume state") { resumeStore.clear() },
        WipeStep("response cache") { responseCache.clear() },
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
