package com.taehagen.spotifygood.download

import android.content.Context
import com.taehagen.spotifygood.auth.CredentialStore
import com.taehagen.spotifygood.data.db.AppDatabase
import com.taehagen.spotifygood.data.settings.SettingsRepository
import com.taehagen.spotifygood.engine.SpotifyEngine
import com.taehagen.spotifygood.model.DownloadState
import com.taehagen.spotifygood.model.OfflineTrackRecord
import com.taehagen.spotifygood.nativebridge.NativeEvents
import com.taehagen.spotifygood.nativebridge.NativeRpc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class CollectionType(val wire: String) { PLAYLIST("playlist"), ALBUM("album"), LIKED_SONGS("collection"), SHOW("show") }

data class CollectionRef(val uri: String, val type: CollectionType, val name: String, val imageUrl: String?)

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

/** Offline downloads (docs/ARCHITECTURE.md §9.7). */
class DownloadManager(
    context: Context,
    scope: CoroutineScope,
    database: AppDatabase,
    rpc: NativeRpc,
    events: NativeEvents,
    engine: SpotifyEngine,
    settings: SettingsRepository,
    credentialStore: CredentialStore,
) {
    /** URIs of completed downloads (hot). */
    val downloadedUris: StateFlow<Set<String>> get() = TODO()
    val items: Flow<List<DownloadItem>> get() = TODO()
    val usedBytes: Flow<Long> get() = TODO()
    val pendingCount: Flow<Int> get() = TODO()

    fun state(uri: String): Flow<DownloadState?> = TODO()
    fun collectionStatus(uri: String): Flow<CollectionDownloadStatus> = TODO()
    /** True while [uri] is a downloaded collection (toggle state on album/playlist screens). */
    fun isCollectionDownloaded(uri: String): Flow<Boolean> = TODO()

    suspend fun downloadCollection(ref: CollectionRef): Unit = TODO()
    suspend fun removeCollection(uri: String): Unit = TODO()
    suspend fun downloadItems(uris: List<String>): Unit = TODO()
    suspend fun removeItems(uris: List<String>): Unit = TODO()
    suspend fun removeAll(): Unit = TODO()
    suspend fun retryFailed(): Unit = TODO()

    /** Decrypted records of all completed downloads (engine pushes them to `offline.setIndex`). */
    suspend fun offlineRecords(): List<OfflineTrackRecord> = TODO()

    /** Re-syncs downloaded collections with their sources (new/removed items). */
    suspend fun syncCollections(): Unit = TODO()
}
