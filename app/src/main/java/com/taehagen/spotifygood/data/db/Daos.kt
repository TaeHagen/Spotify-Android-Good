package com.taehagen.spotifygood.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.taehagen.spotifygood.model.DownloadState
import kotlinx.coroutines.flow.Flow

/** Light projection of a download row: just enough to compute collection / item states. */
data class DownloadStateRow(val uri: String, val state: DownloadState)

/** Projection used by the Downloads screen (no key material, no record JSON). */
data class DownloadListRow(
    val uri: String,
    val state: DownloadState,
    val bytesDone: Long,
    val sizeBytes: Long,
    val metadataJson: String?,
    val imagePath: String?,
    val error: String?,
)

/** A failed or cancelled download "Retry failed" may put back into the queue. */
data class RetryRow(val uri: String, val state: DownloadState, val error: String?)

/** Sync bookkeeping of a downloaded collection (without its member list). */
data class CollectionSyncRow(
    val uri: String,
    val type: String,
    val lastSyncedAt: Long?,
    val lastAttemptAt: Long?,
    val syncFailures: Int,
)

/** A download's files, by absolute path (any location, see `download.DownloadLocations`). */
data class LocatedRow(val uri: String, val path: String?, val imagePath: String?)

/** What the offline index needs of a completed download (no metadata JSON). */
data class IndexRow(
    val uri: String,
    val path: String?,
    val imagePath: String?,
    val recordJson: String?,
    val encryptedKey: ByteArray?,
    val keyVersion: Int,
    val completedAt: Long?,
) {
    override fun equals(other: Any?) = other is IndexRow && other.uri == uri && other.completedAt == completedAt
    override fun hashCode() = uri.hashCode()
}

/** A failed download as the explicit-filter repair reads it ([finished]: file, key and record remain). */
data class FailedRepairRow(
    val uri: String,
    val error: String?,
    val finished: Boolean,
    val metadataJson: String?,
    val recordJson: String?,
)

/** What the offline covers need of a completed download ([com.taehagen.spotifygood.download.OfflineCovers]). */
data class CoverRow(val uri: String, val imagePath: String?, val metadataJson: String?, val recordJson: String?)

/** Cover of a completed download. */
data class UriImage(val uri: String, val imagePath: String)

/** Files referenced by a download row (deletion / garbage collection). */
data class DownloadFileRow(val uri: String, val path: String?, val imagePath: String?, val individual: Boolean)

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY addedAt")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE uri = :uri")
    fun observe(uri: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE uri = :uri")
    suspend fun get(uri: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE uri IN (:uris)")
    suspend fun getAll(uris: List<String>): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state = :state ORDER BY addedAt")
    suspend fun withState(state: DownloadState): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state IN ('queued','preparing','downloading') ORDER BY addedAt LIMIT 1")
    suspend fun nextPending(): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('queued','preparing','downloading')")
    fun observePendingCount(): Flow<Int>

    /**
     * Bytes of downloaded audio on disk: every row that owns a finished file (also ones marked failed
     * later, which keep it until removed), each shared file once.
     */
    @Query(
        "SELECT COALESCE(SUM(size), 0) FROM " +
            "(SELECT MAX(sizeBytes) AS size FROM downloads WHERE path IS NOT NULL GROUP BY COALESCE(LOWER(fileId), path))",
    )
    fun observeUsedBytes(): Flow<Long>

    /** Newest download first (like the Downloads screen). */
    @Query("SELECT uri FROM downloads WHERE state = 'completed' ORDER BY addedAt DESC")
    fun observeCompletedUris(): Flow<List<String>>

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Upsert
    suspend fun upsertAll(entities: List<DownloadEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entities: List<DownloadEntity>): List<Long>

    @Query("UPDATE downloads SET state = :state, bytesDone = :bytes, sizeBytes = CASE WHEN :total > 0 THEN :total ELSE sizeBytes END WHERE uri = :uri")
    suspend fun updateProgress(uri: String, state: DownloadState, bytes: Long, total: Long)

    @Query("DELETE FROM downloads WHERE uri IN (:uris)")
    suspend fun delete(uris: List<String>)

    @Query("DELETE FROM downloads")
    suspend fun deleteAll()

    // ---- additions (data area) ----------------------------------------------------------------

    @Query("SELECT uri, state FROM downloads")
    fun observeStates(): Flow<List<DownloadStateRow>>

    @Query("SELECT uri, state, bytesDone, sizeBytes, metadataJson, imagePath, error FROM downloads ORDER BY addedAt")
    fun observeListRows(): Flow<List<DownloadListRow>>

    @Query("SELECT state FROM downloads WHERE uri = :uri")
    fun observeState(uri: String): Flow<DownloadState?>

    /** Next item whose backoff (if any) has elapsed; fewer attempts first, then FIFO. */
    @Query(
        "SELECT * FROM downloads WHERE state IN ('queued','preparing','downloading') " +
            "AND (retryAt IS NULL OR retryAt <= :now) ORDER BY attempts, addedAt LIMIT 1",
    )
    suspend fun nextRunnable(now: Long): DownloadEntity?

    @Query("SELECT MIN(retryAt) FROM downloads WHERE state IN ('queued','preparing','downloading') AND retryAt IS NOT NULL")
    suspend fun earliestRetryAt(): Long?

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('queued','preparing','downloading')")
    suspend fun pendingCount(): Int

    /** Items left PREPARING/DOWNLOADING by a killed run go back to the queue. */
    @Query("UPDATE downloads SET state = 'queued' WHERE state IN ('preparing','downloading')")
    suspend fun resetInterrupted()

    /** Returns the rows updated: 0 when the item was removed since it was picked. */
    @Query("UPDATE downloads SET state = 'preparing', quality = :quality, error = NULL WHERE uri = :uri")
    suspend fun markPreparing(uri: String, quality: Int): Int

    /** Progress update that never overrides a final state written concurrently. */
    @Query(
        "UPDATE downloads SET state = :state, bytesDone = :bytes, " +
            "sizeBytes = CASE WHEN :total > 0 THEN :total ELSE sizeBytes END " +
            "WHERE uri = :uri AND state IN ('queued','preparing','downloading')",
    )
    suspend fun updateActiveProgress(uri: String, state: DownloadState, bytes: Long, total: Long)

    @Query("UPDATE downloads SET state = 'queued' WHERE uri = :uri AND state IN ('preparing','downloading')")
    suspend fun requeue(uri: String)

    @Query("UPDATE downloads SET state = 'queued', attempts = :attempts, retryAt = :retryAt, error = :error WHERE uri = :uri")
    suspend fun scheduleRetry(uri: String, attempts: Int, retryAt: Long, error: String?)

    @Query("UPDATE downloads SET state = 'failed', attempts = :attempts, retryAt = NULL, error = :error WHERE uri = :uri")
    suspend fun markFailed(uri: String, attempts: Int, error: String?)

    /** Holds every pending item back until [until] (the whole queue pauses: rate limit, CDN outage). */
    @Query(
        "UPDATE downloads SET retryAt = :until WHERE state IN ('queued','preparing','downloading') " +
            "AND (retryAt IS NULL OR retryAt < :until)",
    )
    suspend fun deferPending(until: Long): Int

    @Query("UPDATE downloads SET state = 'failed', retryAt = NULL, error = :error WHERE state IN ('queued','preparing','downloading')")
    suspend fun failAllPending(error: String?)

    @Query("UPDATE downloads SET state = 'cancelled', retryAt = NULL WHERE state IN ('queued','preparing','downloading')")
    suspend fun cancelAllPending()

    @Query("SELECT uri, state, error FROM downloads WHERE state IN ('failed','cancelled')")
    suspend fun retryRows(): List<RetryRow>

    @Query(
        "UPDATE downloads SET state = 'queued', attempts = 0, retryAt = NULL, error = NULL " +
            "WHERE uri IN (:uris) AND state IN ('failed','cancelled')",
    )
    suspend fun requeueFailed(uris: List<String>): Int

    /** Like [requeueFailed] for failed items only: one the user cancelled stays cancelled. */
    @Query(
        "UPDATE downloads SET state = 'queued', attempts = 0, retryAt = NULL, error = NULL " +
            "WHERE uri IN (:uris) AND state = 'failed'",
    )
    suspend fun requeueFailedOnly(uris: List<String>): Int

    /**
     * Failed downloads that still own their finished file and were failed for one of [errors] (by
     * re-validation or the key check, not by a download attempt).
     */
    @Query("SELECT uri FROM downloads WHERE state = 'failed' AND path IS NOT NULL AND error IN (:errors)")
    suspend fun failedWithFileUris(errors: List<String>): List<String>

    @Query("UPDATE downloads SET individual = :individual WHERE uri IN (:uris)")
    suspend fun setIndividual(uris: List<String>, individual: Boolean)

    @Query("SELECT uri, path, imagePath, individual FROM downloads WHERE uri IN (:uris)")
    suspend fun fileRows(uris: List<String>): List<DownloadFileRow>

    @Query("SELECT uri FROM downloads WHERE individual = 1")
    suspend fun individualUris(): List<String>

    @Query("SELECT uri FROM downloads")
    suspend fun allUris(): List<String>

    @Query("SELECT path FROM downloads WHERE path IS NOT NULL")
    suspend fun allPaths(): List<String>

    /** Files (hex ids) of all rows: completed ones and the ones unfinished downloads are writing. */
    @Query("SELECT DISTINCT fileId FROM downloads WHERE fileId IS NOT NULL")
    suspend fun allFileIds(): List<String>

    /** Files whose `.part` an unfinished row (pending, failed, cancelled) may resume. */
    @Query("SELECT DISTINCT fileId FROM downloads WHERE fileId IS NOT NULL AND state != 'completed'")
    suspend fun unfinishedFileIds(): List<String>

    /** Rows using the file [fileId] (several downloads can share one file). */
    @Query("SELECT COUNT(*) FROM downloads WHERE fileId = :fileId COLLATE NOCASE")
    suspend fun countFileUsers(fileId: String): Int

    /** Records the file an unfinished download writes, so its `.part` survives garbage collection. */
    @Query("UPDATE downloads SET fileId = :fileId WHERE uri = :uri AND state != 'completed'")
    suspend fun setFileId(uri: String, fileId: String)

    @Query("SELECT DISTINCT imagePath FROM downloads WHERE imagePath IS NOT NULL")
    suspend fun allImagePaths(): List<String>

    @Query("SELECT uri FROM downloads WHERE uri IN (:uris)")
    suspend fun existingUris(uris: List<String>): List<String>

    @Query("SELECT uri, state FROM downloads WHERE uri IN (:uris)")
    suspend fun statesOf(uris: List<String>): List<DownloadStateRow>

    @Query("SELECT uri, path, imagePath, recordJson, encryptedKey, keyVersion, completedAt FROM downloads WHERE state = 'completed' ORDER BY addedAt")
    suspend fun completedIndexRows(): List<IndexRow>

    @Query("SELECT uri, path, imagePath, recordJson, encryptedKey, keyVersion, completedAt FROM downloads WHERE uri IN (:uris) AND state = 'completed'")
    suspend fun completedIndexRows(uris: List<String>): List<IndexRow>

    // ---- download locations ------------------------------------------------------------------------

    /** Every row's files (moving downloads to another location). */
    @Query("SELECT uri, path, imagePath FROM downloads WHERE path IS NOT NULL OR imagePath IS NOT NULL")
    suspend fun locatedRows(): List<LocatedRow>

    /** Completed downloads' files (which ones are on a location that is not mounted). */
    @Query("SELECT uri, path, imagePath FROM downloads WHERE state = 'completed'")
    fun observeCompletedLocated(): Flow<List<LocatedRow>>

    @Query("SELECT uri, path, imagePath FROM downloads WHERE state = 'completed'")
    suspend fun completedLocated(): List<LocatedRow>

    @Query("SELECT uri, path, imagePath FROM downloads WHERE uri IN (:uris) AND state = 'completed'")
    suspend fun completedLocatedOf(uris: List<String>): List<LocatedRow>

    /**
     * Downloads [uris] again: completed rows whose files are on an SD card that is no longer used
     * (it died or was replaced) go back into the queue, without their old file references, key and
     * record (the new download writes them). Metadata, membership and the individual flag stay.
     */
    @Query(
        "UPDATE downloads SET state = 'queued', attempts = 0, retryAt = NULL, error = NULL, path = NULL, " +
            "imagePath = NULL, fileId = NULL, recordJson = NULL, encryptedKey = NULL, keyVersion = 0, " +
            "completedAt = NULL, lastValidatedAt = NULL, bytesDone = 0, sizeBytes = 0 " +
            "WHERE uri IN (:uris) AND state = 'completed'",
    )
    suspend fun requeueFromUnusedCard(uris: List<String>): Int

    /** Points every row whose audio is [from] at its copy [to]; returns their URIs' count. */
    @Query("UPDATE downloads SET path = :to WHERE path = :from")
    suspend fun relocatePath(from: String, to: String): Int

    /** Points every row whose cover is [from] at its copy [to]. */
    @Query("UPDATE downloads SET imagePath = :to WHERE imagePath = :from")
    suspend fun relocateImage(from: String, to: String): Int

    @Query("SELECT uri FROM downloads WHERE (path = :path OR imagePath = :path) AND state = 'completed'")
    suspend fun completedUrisUsing(path: String): List<String>

    /** Rows naming [path] as their audio or cover. */
    @Query("SELECT COUNT(*) FROM downloads WHERE path = :path OR imagePath = :path")
    suspend fun countPathUsers(path: String): Int

    /**
     * Moves a Keystore-sealed key (version 0) to the data key, only while the row is still the
     * download that was read (not removed and downloaded again meanwhile).
     */
    @Query(
        "UPDATE downloads SET encryptedKey = :key, keyVersion = 1 " +
            "WHERE uri = :uri AND state = 'completed' AND completedAt = :completedAt AND keyVersion = 0",
    )
    suspend fun resealKey(uri: String, completedAt: Long, key: ByteArray)

    @Query("SELECT uri, imagePath, metadataJson, recordJson FROM downloads WHERE uri IN (:uris) AND state = 'completed'")
    suspend fun coverRows(uris: List<String>): List<CoverRow>

    @Query("SELECT uri, imagePath FROM downloads WHERE state = 'completed' AND imagePath IS NOT NULL")
    suspend fun completedImages(): List<UriImage>

    /** Completed downloads not validated since [before] (re-validation of availability). */
    @Query("SELECT uri FROM downloads WHERE state = 'completed' AND COALESCE(lastValidatedAt, completedAt, addedAt) < :before")
    suspend fun completedUrisNotValidatedSince(before: Long): List<String>

    @Query("UPDATE downloads SET lastValidatedAt = :at WHERE uri IN (:uris)")
    suspend fun markValidated(uris: List<String>, at: Long)

    @Query("UPDATE downloads SET state = 'failed', error = :error, lastValidatedAt = :at WHERE uri IN (:uris) AND state = 'completed'")
    suspend fun markUnavailable(uris: List<String>, error: String, at: Long)

    @Query(
        "SELECT uri, error, (path IS NOT NULL AND encryptedKey IS NOT NULL AND recordJson IS NOT NULL AND completedAt IS NOT NULL) AS finished, " +
            "metadataJson, recordJson FROM downloads WHERE state = 'failed' AND error IN (:errors)",
    )
    suspend fun failedRepairRows(errors: List<String>): List<FailedRepairRow>

    /**
     * Undoes [markUnavailable] for [uris] (file, key and record were kept): COMPLETED again, and due
     * for re-validation at the next sync.
     */
    @Query(
        "UPDATE downloads SET state = 'completed', error = NULL, retryAt = NULL, lastValidatedAt = 0 " +
            "WHERE uri IN (:uris) AND state = 'failed' AND path IS NOT NULL AND encryptedKey IS NOT NULL " +
            "AND recordJson IS NOT NULL AND completedAt IS NOT NULL",
    )
    suspend fun restoreCompleted(uris: List<String>): Int

    /**
     * [markUnavailable] for one row, only while it is still the download completed at [completedAt]
     * (not removed and downloaded again since it was read).
     */
    @Query(
        "UPDATE downloads SET state = 'failed', error = :error, lastValidatedAt = :at " +
            "WHERE uri = :uri AND state = 'completed' AND completedAt = :completedAt",
    )
    suspend fun markUnavailableIfUnchanged(uri: String, completedAt: Long, error: String, at: Long)

    /** Completed downloads whose file is gone: failed, and no longer counted as stored. */
    @Query(
        "UPDATE downloads SET state = 'failed', error = :error, lastValidatedAt = :at, path = NULL, sizeBytes = 0, bytesDone = 0 " +
            "WHERE uri IN (:uris) AND state = 'completed'",
    )
    suspend fun markMissing(uris: List<String>, error: String, at: Long)

    @Query("UPDATE downloads SET metadataJson = :metadataJson WHERE uri = :uri AND metadataJson IS NULL")
    suspend fun fillMetadata(uri: String, metadataJson: String)
}

@Dao
interface DownloadCollectionDao {
    @Query("SELECT * FROM download_collections ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<DownloadCollectionEntity>>

    @Query("SELECT * FROM download_collections")
    suspend fun getAll(): List<DownloadCollectionEntity>

    @Query("SELECT * FROM download_collections WHERE uri = :uri")
    fun observe(uri: String): Flow<DownloadCollectionEntity?>

    @Query("SELECT * FROM download_collections WHERE uri = :uri")
    suspend fun get(uri: String): DownloadCollectionEntity?

    @Upsert
    suspend fun upsert(entity: DownloadCollectionEntity)

    @Query("DELETE FROM download_collections WHERE uri = :uri")
    suspend fun delete(uri: String)

    @Query("DELETE FROM download_collections")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM download_collections")
    suspend fun count(): Int

    @Query("UPDATE download_collections SET unavailableCheckedAt = :at WHERE uri IN (:uris)")
    suspend fun markUnavailableChecked(uris: List<String>, at: Long)

    /** Every collection's unavailable members are looked up again at the next sync. */
    @Query("UPDATE download_collections SET unavailableCheckedAt = NULL")
    suspend fun clearUnavailableChecked()

    /** JSON arrays of the members each downloaded collection records as not playable here. */
    @Query("SELECT unavailableUrisJson FROM download_collections")
    suspend fun unavailableUrisJsons(): List<String>

    @Query("SELECT uri, type, lastSyncedAt, lastAttemptAt, syncFailures FROM download_collections")
    suspend fun syncStates(): List<CollectionSyncRow>

    /** Makes [uris] due for the next sync (an edit made while offline). */
    @Query("UPDATE download_collections SET lastSyncedAt = NULL, lastAttemptAt = NULL, syncFailures = 0 WHERE uri IN (:uris)")
    suspend fun markSyncDue(uris: List<String>)

    /** A sync of [uri] failed: counts towards its retry backoff. */
    @Query("UPDATE download_collections SET lastAttemptAt = :at, syncFailures = syncFailures + 1 WHERE uri = :uri")
    suspend fun recordSyncFailure(uri: String, at: Long)
}

@Dao
interface RecentSearchDao {
    @Query("SELECT * FROM recent_searches ORDER BY timestamp DESC LIMIT :limit")
    fun observe(limit: Int = 30): Flow<List<RecentSearchEntity>>

    @Query("DELETE FROM recent_searches WHERE `key` = :key")
    suspend fun deleteKey(key: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE id NOT IN (SELECT id FROM recent_searches ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 50)

    @Query("DELETE FROM recent_searches")
    suspend fun clear()
}

@Dao
interface ResponseCacheDao {
    @Query("SELECT * FROM response_cache WHERE `key` = :key")
    suspend fun get(key: String): ResponseCacheEntity?

    @Upsert
    suspend fun put(entity: ResponseCacheEntity)

    @Query("DELETE FROM response_cache WHERE fetchedAt < :olderThan")
    suspend fun prune(olderThan: Long)

    @Query("DELETE FROM response_cache")
    suspend fun clear()

    @Query("DELETE FROM response_cache WHERE `key` = :key")
    suspend fun delete(key: String)

    /**
     * Marks entries whose key starts with [prefix] as stale without dropping the data, so screens still
     * open instantly / offline but revalidate on the next collection. Stale rows store the negated fetch
     * time (`fetchedAt <= 0` means stale; `ABS(fetchedAt)` is still the original fetch time).
     */
    @Query("UPDATE response_cache SET fetchedAt = -ABS(fetchedAt) WHERE substr(`key`, 1, length(:prefix)) = :prefix")
    suspend fun markStale(prefix: String)

    @Query("UPDATE response_cache SET fetchedAt = -ABS(fetchedAt) WHERE `key` = :key")
    suspend fun markStaleKey(key: String)

    /** Drops entries fetched before [olderThan] (stale-marked rows included, by their original time). */
    @Query("DELETE FROM response_cache WHERE ABS(fetchedAt) < :olderThan")
    suspend fun pruneFetchedBefore(olderThan: Long)
}
