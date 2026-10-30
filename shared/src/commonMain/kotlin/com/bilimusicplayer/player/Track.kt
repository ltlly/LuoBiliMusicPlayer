package com.bilimusicplayer.player

enum class RepeatMode { OFF, ALL, ONE }

/**
 * UI-facing queue item. The URI can be a local path, an HTTP(S) URL, or a
 * "bili://BV..." placeholder that the Android engine resolves lazily.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val coverUrl: String = "",
    val uri: String,
    val durationMs: Long = 0L,
)

data class PlaybackState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val repeatMode: RepeatMode = RepeatMode.ALL,
    val shuffle: Boolean = false,
    val queueIndex: Int = -1,
    val error: String? = null,
)
