package com.taehagen.spotifygood.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.taehagen.spotifygood.model.DownloadState
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT uri FROM downloads WHERE state = 'completed'")
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
}
