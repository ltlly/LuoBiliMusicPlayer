package com.bilimusicplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Song entity for Room database
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey
    val id: String,  // BVID
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Int,  // Duration in seconds
    val coverUrl: String,
    val localPath: String? = null,  // Local file path if downloaded
    val audioUrl: String? = null,  // Online audio URL
    val cid: Long,  // Video CID for Bilibili
    val bvid: String,
    val aid: Long,  // AV ID
    val uploaderId: Long,
    val uploaderName: String,
    val pubDate: Long,  // Publication timestamp
    val addedDate: Long = System.currentTimeMillis(),  // Added to library timestamp
    val isDownloaded: Boolean = false,
    val fileSize: Long = 0,  // File size in bytes
    val playCount: Int = 0,
    val lastPlayedAt: Long = 0
)

/**
 * Playlist entity
 */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val songCount: Int = 0,
    val biliFavoriteId: Long? = null  // Link to Bilibili favorite folder ID
)

/**
 * Playlist-Song cross reference
 */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: String,  // BVID
    val position: Int = 0,  // Song position in playlist
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Download task entity
 */
@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey
    val songId: String,  // BVID
    val title: String,  // Song title
    val artist: String,  // Song artist
    val status: DownloadStatus,
    val progress: Int = 0,  // 0-100
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val audioUrl: String,
    val localPath: String? = null,
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

/**
 * Download status enum
 */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    CONVERTING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED
}

/**
 * Bilibili Favorite Folder Cache
 */
@Entity(tableName = "bili_favorite_folders")
data class BiliFavoriteFolder(
    @PrimaryKey
    val id: Long,  // Bilibili favorite folder ID
    val fid: Long,
    val mid: Long,  // User ID
    val title: String,
    val cover: String?,  // Cover can be null
    val mediaCount: Int,
    val attr: Int,
    val ctime: Long,
    val mtime: Long,
    val cachedAt: Long = System.currentTimeMillis(),  // When this was cached
    val updatedAt: Long = System.currentTimeMillis()  // Last update time
)

/**
 * Cached Favorite Media entity
 * Caches the content of a Bilibili favorite folder to avoid re-fetching on every open
 */
@Entity(
    tableName = "cached_favorite_medias",
    primaryKeys = ["folderId", "bvid"]
)
data class CachedFavoriteMedia(
    val folderId: Long,          // Which favorite folder this belongs to
    val id: Long,                // Bilibili media ID (aid)
    val type: Int,               // Media type
    val title: String,
    val cover: String,
    val bvid: String,
    val upperMid: Long,          // Uploader's mid
    val upperName: String,       // Uploader's name
    val upperFace: String,       // Uploader's avatar
    val duration: Int,           // Duration in seconds
    val intro: String,           // Introduction/description
    val ctime: Long,             // Creation time
    val pubtime: Long,           // Publish time
    val position: Int = 0,       // Position in the list for ordering
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Playlist with songs
 */
data class PlaylistWithSongs(
    @androidx.room.Embedded
    val playlist: Playlist,

    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = androidx.room.Junction(
            value = PlaylistSongCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "songId"
        )
    )
    val songs: List<Song> = emptyList()
)
