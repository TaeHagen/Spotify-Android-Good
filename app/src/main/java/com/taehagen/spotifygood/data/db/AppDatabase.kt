package com.taehagen.spotifygood.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.taehagen.spotifygood.model.DownloadState

class Converters {
    @TypeConverter
    fun fromState(state: DownloadState): String = when (state) {
        DownloadState.QUEUED -> "queued"
        DownloadState.PREPARING -> "preparing"
        DownloadState.DOWNLOADING -> "downloading"
        DownloadState.COMPLETED -> "completed"
        DownloadState.FAILED -> "failed"
        DownloadState.CANCELLED -> "cancelled"
    }

    @TypeConverter
    fun toState(value: String): DownloadState = when (value) {
        "queued" -> DownloadState.QUEUED
        "preparing" -> DownloadState.PREPARING
        "downloading" -> DownloadState.DOWNLOADING
        "completed" -> DownloadState.COMPLETED
        "failed" -> DownloadState.FAILED
        else -> DownloadState.CANCELLED
    }
}

@Database(
    entities = [DownloadEntity::class, DownloadCollectionEntity::class, RecentSearchEntity::class, ResponseCacheEntity::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    abstract fun collections(): DownloadCollectionDao
    abstract fun recentSearches(): RecentSearchDao
    abstract fun responseCache(): ResponseCacheDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "spotifygood.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
