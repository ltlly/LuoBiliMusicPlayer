package com.bilimusicplayer.data.local

import androidx.room.*
import com.bilimusicplayer.data.model.Playlist
import com.bilimusicplayer.data.model.PlaylistSongCrossRef
import com.bilimusicplayer.data.model.PlaylistWithSongs
import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Playlist operations
 */
@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY updatedAt DESC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    fun getPlaylistByIdFlow(playlistId: Long): Flow<Playlist?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylistById(playlistId: Long)

    // Playlist-Song operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSong(crossRef: PlaylistSongCrossRef)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistSongs(crossRefs: List<PlaylistSongCrossRef>)

    @Delete
    suspend fun deletePlaylistSong(crossRef: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun deleteAllPlaylistSongs(playlistId: Long)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Query("""
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs ON songs.id = playlist_songs.songId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC
    """)
    fun getPlaylistSongs(playlistId: Long): Flow<List<Song>>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistWithSongs(playlistId: Long): PlaylistWithSongs?

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getPlaylistSongCount(playlistId: Long): Int

    @Query("UPDATE playlists SET songCount = :count, updatedAt = :timestamp WHERE id = :playlistId")
    suspend fun updatePlaylistSongCount(playlistId: Long, count: Int, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT MAX(position) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getMaxPosition(playlistId: Long): Int?

    @Transaction
    suspend fun addSongToPlaylist(playlistId: Long, songId: String) {
        val maxPos = getMaxPosition(playlistId) ?: -1
        insertPlaylistSong(PlaylistSongCrossRef(playlistId, songId, maxPos + 1))
        val count = getPlaylistSongCount(playlistId)
        updatePlaylistSongCount(playlistId, count)
    }

    @Transaction
    suspend fun removeSongAndUpdateCount(playlistId: Long, songId: String) {
        removeSongFromPlaylist(playlistId, songId)
        val count = getPlaylistSongCount(playlistId)
        updatePlaylistSongCount(playlistId, count)
    }
}
