package com.bilimusicplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bilimusicplayer.data.model.*
import kotlinx.coroutines.Dispatchers

/**
 * Room database for BiliMusicPlayer. The same schema and version are used on
 * Android and desktop; only the file path differs.
 */
@Database(
    entities = [
        Song::class,
        Playlist::class,
        PlaylistSongCrossRef::class,
        Download::class,
        BiliFavoriteFolder::class,
        CachedPlaybackUrl::class,
        CachedFavoriteMedia::class
    ],
    version = 6,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao
    abstract fun biliFavoriteFolderDao(): BiliFavoriteFolderDao
    abstract fun cachedPlaybackUrlDao(): CachedPlaybackUrlDao
    abstract fun cachedFavoriteMediaDao(): CachedFavoriteMediaDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: createAppDatabaseBuilder()
                    .setDriver(BundledSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .fallbackToDestructiveMigrationFrom(false, 1, 2, 3, 4, 5)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

/** Type converters for Room. */
class Converters {
    @androidx.room.TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.name

    @androidx.room.TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = try {
        DownloadStatus.valueOf(value)
    } catch (_: IllegalArgumentException) {
        DownloadStatus.FAILED
    }
}