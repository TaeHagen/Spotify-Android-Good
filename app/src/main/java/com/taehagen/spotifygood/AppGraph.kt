package com.taehagen.spotifygood

import android.app.Application
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        SpotifyEngine(app, appScope, rpc, events, settings, credentialStore, audioSink).also { engine ->
            engine.setOfflineIndexProvider { downloads.offlineRecords() }
        }
    }
    val auth: AuthRepository by lazy { AuthRepository(app, appScope, engine, credentialStore, httpClient) }

    val playback: PlaybackRepository by lazy { PlaybackRepository(appScope, events) }
    /** Last local session (playback resumption, cold-start play); one DataStore per process. */
    val resumeStore: ResumeStore by lazy { ResumeStore(app) }
    val player: PlayerController by lazy { PlayerController(appScope, rpc, playback, resumeStore) }
    val devices: DevicesRepository by lazy { DevicesRepository(appScope, rpc, events, resumeStore::read) }
    /** Spotify Connect local-network discovery (the "send" side); runs only while the sheet is up. */
    val localDiscovery: LocalDeviceDiscovery by lazy {
        LocalDeviceDiscovery(app, rpc) { devices.devices.value.devices.map { it.id }.toSet() }
    }
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
        DownloadManager(app, appScope, database, rpc, events, engine, settings, credentialStore)
    }

    /**
     * Logs the user out and wipes all account data: native session + credentials, downloads,
     * caches and the database. Settings are kept.
     */
    suspend fun logout() {
        engine.logout()
        downloads.removeAll()
        // The playback service clears it too, but only while it runs.
        resumeStore.clear()
        responseCache.clear()
        kotlinx.coroutines.withContext(Dispatchers.IO) { database.clearAllTables() }
        events.reset()
    }
}
