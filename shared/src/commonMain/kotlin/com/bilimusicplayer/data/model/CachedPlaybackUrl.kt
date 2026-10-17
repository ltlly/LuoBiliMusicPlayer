package com.bilimusicplayer.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached playback URL for online streaming
 * This is separate from Song table to avoid polluting the user's music library
 */
@Entity(tableName = "cached_playback_urls")
data class CachedPlaybackUrl(
    @PrimaryKey
    val bvid: String,           // Bilibili video ID
    val cid: Long,              // Video CID
    val audioUrl: String,       // Streaming URL
    val title: String,          // Song title (for reference)
    val artist: String,         // Artist name (for reference)
    val coverUrl: String,       // Cover image URL
    val duration: Int,          // Duration in seconds
    val cachedAt: Long = System.currentTimeMillis(),  // Cache timestamp
    val expiresAt: Long = System.currentTimeMillis() + 6 * 60 * 60 * 1000  // Expires in 6 hours (B站链接有效期)
)
