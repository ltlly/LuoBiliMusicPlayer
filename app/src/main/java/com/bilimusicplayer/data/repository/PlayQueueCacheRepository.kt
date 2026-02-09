package com.bilimusicplayer.data.repository

import android.util.Log
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Playlist
import com.bilimusicplayer.data.model.PlaylistSongCrossRef
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.network.bilibili.favorite.FavoriteMedia

/**
 * Repository for managing play queue cache
 * Caches songs from Bilibili favorites to avoid re-fetching
 */
class PlayQueueCacheRepository(
    private val database: AppDatabase,
    private val biliRepository: BiliFavoriteRepository
) {

    private val playlistDao = database.playlistDao()
    private val songDao = database.songDao()

    /**
     * Get or create playlist for a Bilibili favorite folder
     */
    suspend fun getOrCreatePlaylist(
        biliFavoriteId: Long,
        folderName: String,
        folderCover: String
    ): Playlist {
        // Check if playlist already exists
        var playlist = playlistDao.getPlaylistByBiliFavoriteId(biliFavoriteId)

        if (playlist == null) {
            // Create new playlist
            val newPlaylist = Playlist(
                name = folderName,
                coverUrl = folderCover,
                biliFavoriteId = biliFavoriteId
            )
            val playlistId = playlistDao.insertPlaylist(newPlaylist)
            playlist = newPlaylist.copy(id = playlistId)
            Log.d(TAG, "创建新播放列表缓存: $folderName (ID: $playlistId)")
        } else {
            Log.d(TAG, "使用已存在的播放列表缓存: ${playlist.name} (ID: ${playlist.id})")
        }

        return playlist
    }

    /**
     * Get cached songs from playlist
     */
    suspend fun getCachedSongs(playlistId: Long): List<Song> {
        val songs = playlistDao.getPlaylistWithSongs(playlistId)?.songs ?: emptyList()
        Log.d(TAG, "从缓存加载 ${songs.size} 首歌曲")
        return songs
    }

    /**
     * Cache a song and add it to playlist
     */
    suspend fun cacheSong(
        playlistId: Long,
        song: Song,
        position: Int
    ) {
        // Insert or update song
        songDao.insertSong(song)

        // Add to playlist
        playlistDao.insertPlaylistSong(
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = song.id,
                position = position
            )
        )

        // Update playlist song count
        val count = playlistDao.getPlaylistSongCount(playlistId)
        playlistDao.updatePlaylistSongCount(playlistId, count)
    }

    /**
     * Batch cache songs
     */
    suspend fun cacheSongs(
        playlistId: Long,
        songs: List<Song>,
        startPosition: Int = 0
    ) {
        // Insert all songs
        songs.forEach { songDao.insertSong(it) }

        // Create cross references
        val crossRefs = songs.mapIndexed { index, song ->
            PlaylistSongCrossRef(
                playlistId = playlistId,
                songId = song.id,
                position = startPosition + index
            )
        }
        playlistDao.insertPlaylistSongs(crossRefs)

        // Update playlist song count
        val count = playlistDao.getPlaylistSongCount(playlistId)
        playlistDao.updatePlaylistSongCount(playlistId, count)

        Log.d(TAG, "批量缓存 ${songs.size} 首歌曲，起始位置: $startPosition")
    }

    /**
     * Clear playlist cache
     */
    suspend fun clearPlaylistCache(playlistId: Long) {
        playlistDao.deleteAllPlaylistSongs(playlistId)
        playlistDao.updatePlaylistSongCount(playlistId, 0)
        Log.d(TAG, "清空播放列表缓存: $playlistId")
    }

    /**
     * Get song count in cache
     */
    suspend fun getCachedSongCount(playlistId: Long): Int {
        return playlistDao.getPlaylistSongCount(playlistId)
    }

    /**
     * Convert FavoriteMedia to Song
     */
    fun favoriteMediaToSong(
        media: FavoriteMedia,
        cid: Long,
        audioUrl: String,
        coverUrl: String
    ): Song {
        return Song(
            id = media.bvid,
            title = media.title,
            artist = media.upper.name,
            duration = media.duration,
            coverUrl = coverUrl,
            audioUrl = audioUrl,
            cid = cid,
            bvid = media.bvid,
            aid = media.id,
            uploaderId = media.upper.mid,
            uploaderName = media.upper.name,
            pubDate = media.pubtime
        )
    }

    companion object {
        private const val TAG = "PlayQueueCacheRepo"
    }
}
