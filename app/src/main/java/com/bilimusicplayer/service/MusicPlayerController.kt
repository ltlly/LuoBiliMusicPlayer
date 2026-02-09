package com.bilimusicplayer.service

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.common.*
import androidx.media3.session.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Controller for managing music playback
 */
class MusicPlayerController(private val context: Context) {

    private var mediaController: MediaController? = null
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    /**
     * Initialize media controller connection
     */
    suspend fun initialize() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )

        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            setupPlayerListener()
        }, context.mainExecutor)
    }

    /**
     * Setup player state listener
     */
    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updatePlaybackState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState()
                startProgressUpdateIfPlaying()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlaybackState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlaybackState()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updatePlaybackState()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updatePlaybackState()
            }
        })

        // Start initial progress update
        startProgressUpdateIfPlaying()
    }

    /**
     * Start periodic progress updates when playing
     */
    private fun startProgressUpdateIfPlaying() {
        // Progress updates will be handled by the UI layer
        updatePlaybackState()
    }

    /**
     * Update playback state flow
     */
    private fun updatePlaybackState() {
        val controller = mediaController ?: return
        _playbackState.value = PlaybackState(
            isPlaying = controller.isPlaying,
            currentMediaItem = controller.currentMediaItem,
            currentPosition = controller.currentPosition,
            duration = controller.duration,
            repeatMode = controller.repeatMode,
            shuffleMode = controller.shuffleModeEnabled,
            playbackState = controller.playbackState
        )
    }

    /**
     * Play or resume playback
     */
    fun play() {
        mediaController?.play()
    }

    /**
     * Pause playback
     */
    fun pause() {
        mediaController?.pause()
    }

    /**
     * Skip to next track
     */
    fun skipToNext() {
        mediaController?.let { controller ->
            val currentIndex = controller.currentMediaItemIndex
            val totalItems = controller.mediaItemCount
            val shuffleEnabled = controller.shuffleModeEnabled

            Log.d(TAG, "skipToNext - 当前索引: $currentIndex, 总数: $totalItems, 随机模式: $shuffleEnabled")

            if (totalItems > 0) {
                // Let ExoPlayer handle next track with repeat mode
                controller.seekToNext()
                // Force prepare and play to ensure smooth transition
                if (!controller.isPlaying) {
                    controller.prepare()
                    controller.play()
                }
                Log.d(TAG, "skipToNext - 切换到下一首")
            }
        }
    }

    /**
     * Skip to previous track
     */
    fun skipToPrevious() {
        mediaController?.let { controller ->
            val currentIndex = controller.currentMediaItemIndex
            Log.d(TAG, "skipToPrevious - 当前索引: $currentIndex")

            // Let ExoPlayer handle previous track with repeat mode
            controller.seekToPrevious()
            if (!controller.isPlaying) {
                controller.prepare()
                controller.play()
            }
        }
    }

    /**
     * Seek to position
     */
    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    /**
     * Set media items and play
     */
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int = 0) {
        mediaController?.apply {
            setMediaItems(mediaItems, startIndex, 0)
            // Enable repeat all mode for continuous playback
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            play()
        }
    }

    /**
     * Add media item to queue
     */
    fun addMediaItem(mediaItem: MediaItem) {
        mediaController?.addMediaItem(mediaItem)
    }

    /**
     * Set repeat mode
     */
    fun setRepeatMode(repeatMode: Int) {
        mediaController?.repeatMode = repeatMode
    }

    /**
     * Set shuffle mode
     */
    fun setShuffleMode(enabled: Boolean) {
        mediaController?.let { controller ->
            Log.d(TAG, "设置随机播放: $enabled")
            controller.shuffleModeEnabled = enabled
            updatePlaybackState()
        }
    }

    /**
     * Release resources
     */
    fun release() {
        mediaController?.release()
        mediaController = null
    }

    /**
     * Check if controller is connected
     */
    fun isConnected(): Boolean {
        return mediaController != null
    }

    /**
     * Get current position
     */
    fun getCurrentPosition(): Long {
        return mediaController?.currentPosition ?: 0L
    }

    /**
     * Get duration
     */
    fun getDuration(): Long {
        return mediaController?.duration ?: 0L
    }

    /**
     * Get current media item index
     */
    fun getCurrentMediaItemIndex(): Int {
        return mediaController?.currentMediaItemIndex ?: 0
    }

    /**
     * Get total media item count
     */
    fun getMediaItemCount(): Int {
        return mediaController?.mediaItemCount ?: 0
    }

    /**
     * Get all media items in the queue
     */
    fun getAllMediaItems(): List<MediaItem> {
        val controller = mediaController ?: return emptyList()
        val items = mutableListOf<MediaItem>()
        for (i in 0 until controller.mediaItemCount) {
            items.add(controller.getMediaItemAt(i))
        }
        return items
    }

    /**
     * Remove media item at specified index
     */
    fun removeMediaItem(index: Int) {
        mediaController?.let { controller ->
            if (index >= 0 && index < controller.mediaItemCount) {
                Log.d(TAG, "从队列删除歌曲，位置: $index")
                controller.removeMediaItem(index)
                updatePlaybackState()
            } else {
                Log.w(TAG, "无效的索引: $index, 队列大小: ${controller.mediaItemCount}")
            }
        }
    }

    /**
     * Clear all media items from queue
     */
    fun clearQueue() {
        mediaController?.let { controller ->
            Log.d(TAG, "清空播放队列")
            controller.clearMediaItems()
            updatePlaybackState()
        }
    }

    /**
     * Move media item from one position to another
     */
    fun moveMediaItem(fromIndex: Int, toIndex: Int) {
        mediaController?.let { controller ->
            if (fromIndex >= 0 && fromIndex < controller.mediaItemCount &&
                toIndex >= 0 && toIndex < controller.mediaItemCount) {
                Log.d(TAG, "移动歌曲: $fromIndex -> $toIndex")
                controller.moveMediaItem(fromIndex, toIndex)
                updatePlaybackState()
            } else {
                Log.w(TAG, "无效的索引: from=$fromIndex, to=$toIndex, 队列大小: ${controller.mediaItemCount}")
            }
        }
    }

    /**
     * Skip to specific media item by index
     */
    fun skipToMediaItem(index: Int) {
        mediaController?.let { controller ->
            if (index >= 0 && index < controller.mediaItemCount) {
                Log.d(TAG, "跳转到歌曲: $index")
                controller.seekToDefaultPosition(index)
                if (!controller.isPlaying) {
                    controller.prepare()
                    controller.play()
                }
                updatePlaybackState()
            } else {
                Log.w(TAG, "无效的索引: $index, 队列大小: ${controller.mediaItemCount}")
            }
        }
    }

    companion object {
        private const val TAG = "MusicPlayerController"
    }
}

/**
 * Playback state data class
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentMediaItem: MediaItem? = null,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffleMode: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE
)
