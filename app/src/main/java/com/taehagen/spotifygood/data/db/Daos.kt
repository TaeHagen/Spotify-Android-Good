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

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM downloads WHERE state = 'completed'")
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

    @Query("UPDATE downloads SET state = 'preparing', quality = :quality, error = NULL WHERE uri = :uri")
    suspend fun markPreparing(uri: String, quality: Int)

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

    @Query("UPDATE downloads SET state = 'failed', retryAt = NULL, error = :error WHERE state IN ('queued','preparing','downloading')")
    suspend fun failAllPending(error: String?)

    @Query("UPDATE downloads SET state = 'cancelled', retryAt = NULL WHERE state IN ('queued','preparing','downloading')")
    suspend fun cancelAllPending()

    /** Puts failed / cancelled items back into the queue with a fresh attempt budget. */
    @Query("UPDATE downloads SET state = 'queued', attempts = 0, retryAt = NULL, error = NULL WHERE state IN ('failed','cancelled')")
    suspend fun requeueFailed(): Int

    @Query(
        "UPDATE downloads SET state = 'queued', attempts = 0, retryAt = NULL, error = NULL " +
            "WHERE uri IN (:uris) AND state IN ('failed','cancelled')",
    )
    suspend fun requeueFailed(uris: List<String>): Int

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

    /** Completed downloads not validated since [before] (re-validation of availability). */
    @Query("SELECT uri FROM downloads WHERE state = 'completed' AND COALESCE(lastValidatedAt, completedAt, addedAt) < :before")
    suspend fun completedUrisNotValidatedSince(before: Long): List<String>

    @Query("UPDATE downloads SET lastValidatedAt = :at WHERE uri IN (:uris)")
    suspend fun markValidated(uris: List<String>, at: Long)

    @Query("UPDATE downloads SET state = 'failed', error = :error, lastValidatedAt = :at WHERE uri IN (:uris) AND state = 'completed'")
    suspend fun markUnavailable(uris: List<String>, error: String, at: Long)

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

    /** Oldest sync time of any downloaded collection (never synced = 0), null without collections. */
    @Query("SELECT MIN(COALESCE(lastSyncedAt, 0)) FROM download_collections")
    suspend fun oldestSyncedAt(): Long?
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
