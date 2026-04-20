package com.bilimusicplayer.data.local

import androidx.room.*
import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Song operations
 */
@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY addedDate DESC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs ORDER BY addedDate DESC")
    suspend fun getAllSongsOnce(): List<Song>

    @Query("SELECT * FROM songs WHERE isDownloaded = 1 ORDER BY addedDate DESC")
    fun getDownloadedSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: String): Song?

    @Query("SELECT * FROM songs WHERE id = :songId")
    fun getSongByIdFlow(songId: String): Flow<Song?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: Song)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongIfNotExists(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<Song>)

    @Update
    suspend fun updateSong(song: Song)

    @Delete
    suspend fun deleteSong(song: Song)

    @Query("DELETE FROM songs WHERE id = :songId")
    suspend fun deleteSongById(songId: String)

    @Query("UPDATE songs SET playCount = playCount + 1, lastPlayedAt = :timestamp WHERE id = :songId")
    suspend fun incrementPlayCount(songId: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE songs SET isDownloaded = :isDownloaded, localPath = :localPath, fileSize = :fileSize WHERE id = :songId")
    suspend fun updateDownloadStatus(songId: String, isDownloaded: Boolean, localPath: String?, fileSize: Long)

    @Query("SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artist LIKE '%' || :query || '%' OR album LIKE '%' || :query || '%'")
    fun searchSongs(query: String): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM songs")
    fun getSongCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM songs WHERE isDownloaded = 1")
    fun getDownloadedSongCount(): Flow<Int>

    /**
     * Get IDs of songs that are already downloaded (file exists check must be done in code)
     */
    @Query("SELECT id FROM songs WHERE id IN (:songIds) AND isDownloaded = 1 AND localPath IS NOT NULL")
    suspend fun getDownloadedSongIds(songIds: List<String>): List<String>

    /**
     * Get full Song objects for downloaded songs by bvid list
     */
    @Query("SELECT * FROM songs WHERE id IN (:songIds) AND isDownloaded = 1 AND localPath IS NOT NULL")
    suspend fun getDownloadedSongsByIds(songIds: List<String>): List<Song>

    /**
     * Drop "local_*" placeholder rows that point at the same file as a freshly
     * downloaded song, so the Library doesn't list the same file twice. `keepId`
     * is the row we want to preserve (the just-downloaded one with the real bvid).
     */
    @Query("DELETE FROM songs WHERE localPath = :path AND id LIKE 'local_%' AND id != :keepId")
    suspend fun deleteOrphanRowsForPath(path: String, keepId: String)

    /** Count of placeholder rows (id like 'local_*') — orphan files we couldn't match to B站 cache. */
    @Query("SELECT COUNT(*) FROM songs WHERE id LIKE 'local_%'")
    suspend fun countUnsyncedLocalSongs(): Int

    /** Get all placeholder rows so we can also delete the underlying files. */
    @Query("SELECT * FROM songs WHERE id LIKE 'local_%'")
    suspend fun getUnsyncedLocalSongs(): List<Song>

    /** Bulk delete all placeholder rows. Files must be deleted separately. */
    @Query("DELETE FROM songs WHERE id LIKE 'local_%'")
    suspend fun deleteAllUnsyncedLocalSongs(): Int
}
