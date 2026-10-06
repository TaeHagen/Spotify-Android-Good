package com.taehagen.spotifygood.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.taehagen.spotifygood.model.DownloadState

/** One downloaded (or queued) playable item. [recordJson] = OfflineTrackRecord without the key. */
@Entity(tableName = "downloads", indices = [Index("state")])
data class DownloadEntity(
    @PrimaryKey val uri: String,
    val state: DownloadState,
    /** Kbps requested (96/160/320). */
    val quality: Int,
    val fileId: String? = null,
    val format: String? = null,
    /** Audio key encrypted with [com.taehagen.spotifygood.auth.CredentialStore.encrypt]. */
    val encryptedKey: ByteArray? = null,
    val path: String? = null,
    val sizeBytes: Long = 0,
    val bytesDone: Long = 0,
    /** OfflineTrackRecord JSON with keyHex blanked. */
    val recordJson: String? = null,
    /** Display metadata (Track or Episode JSON) for offline screens. */
    val metadataJson: String? = null,
    val imagePath: String? = null,
    val addedAt: Long,
    val completedAt: Long? = null,
    val lastValidatedAt: Long? = null,
    val attempts: Int = 0,
    val error: String? = null,
    /** True when the item was downloaded on its own (not only as part of a downloaded collection). */
    val individual: Boolean = false,
    /** Earliest time (epoch ms) of the next attempt after a transient failure (exponential backoff). */
    val retryAt: Long? = null,
) {
    override fun equals(other: Any?) = other is DownloadEntity && other.uri == uri && other.state == state &&
        other.bytesDone == bytesDone && other.completedAt == completedAt && other.error == error
    override fun hashCode() = uri.hashCode()
}

/** A downloaded collection (playlist, album, Liked Songs, show) kept in sync with its source. */
@Entity(tableName = "download_collections")
data class DownloadCollectionEntity(
    @PrimaryKey val uri: String,
    /** "playlist" | "album" | "collection" (Liked Songs) | "show" */
    val type: String,
    val name: String,
    val imageUrl: String? = null,
    /** JSON array of item URIs in collection order. */
    val itemUrisJson: String = "[]",
    val revision: String? = null,
    val addedAt: Long,
    val lastSyncedAt: Long? = null,
)

/** Recent searches: either a free-text query or a tapped result (MediaRef JSON). */
@Entity(tableName = "recent_searches", indices = [Index(value = ["key"], unique = true)])
data class RecentSearchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Dedupe key: "q:<query>" or the item URI. */
    val key: String,
    val query: String? = null,
    val mediaRefJson: String? = null,
    val timestamp: Long,
)

/** Last successful response of a browse call (stale-while-revalidate, offline browsing). */
@Entity(tableName = "response_cache")
data class ResponseCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)
