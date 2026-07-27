package com.bilimusicplayer.service

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.*
import androidx.media3.session.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

private val Context.playbackStateDataStore: DataStore<Preferences> by preferencesDataStore(name = "playback_state")

/**
 * Controller for managing music playback
 * Handles playback state persistence and queue loading coordination
 */
class MusicPlayerController(private val context: Context) {

    private var mediaController: MediaController? = null
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    // Cancel previous queue-loading job to avoid duplicates
    private var queueLoadingJob: Job? = null

    // Persistence keys & constants
    companion object {
        private const val TAG = "MusicPlayerController"
        private val QUEUE_JSON = stringPreferencesKey("queue_json")
        private val CURRENT_INDEX = intPreferencesKey("current_index")
        private val CURRENT_POSITION = longPreferencesKey("current_position")
        private val REPEAT_MODE_KEY = intPreferencesKey("repeat_mode")
        private val SHUFFLE_MODE_KEY = booleanPreferencesKey("shuffle_mode")
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var saveJob: Job? = null

    /**
     * Initialize media controller connection and restore saved state
     */
    suspend fun initialize() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, MusicPlaybackService::class.java)
        )

        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                mediaController = controllerFuture.get()
                setupPlayerListener()
                // Restore saved playback state after connection
                scope.launch {
                    restorePlaybackState()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect MediaController", e)
            }
        }, context.mainExecutor)
    }

    /**
     * Setup player state listener
     */
    private fun setupPlayerListener() {
        mediaController?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                updatePlaybackState()
                scheduleSaveState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackState()
                startProgressUpdateIfPlaying()
                scheduleSaveState()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updatePlaybackState()
                scheduleSaveState()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updatePlaybackState()
                scheduleSaveState()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updatePlaybackState()
                scheduleSaveState()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updatePlaybackState()
                scheduleSaveState()
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
     * Skip to next track.
     * When repeat mode is ONE, manually advance to the next different song
     * (ExoPlayer's seekToNext stays on the same song in REPEAT_MODE_ONE).
     */
    fun skipToNext() {
        mediaController?.let { controller ->
            val currentIndex = controller.currentMediaItemIndex
            val totalItems = controller.mediaItemCount

            Log.d(TAG, "skipToNext - 当前索引: $currentIndex, 总数: $totalItems")

            if (totalItems <= 0) return@let

            if (controller.repeatMode == Player.REPEAT_MODE_ONE) {
                // In single-repeat mode, user pressing next expects to move to next song
                val nextIndex = if (controller.shuffleModeEnabled) {
                    // Random next: pick a random index that isn't current
                    if (totalItems <= 1) currentIndex
                    else (currentIndex + 1 + (Math.random() * (totalItems - 1)).toInt()) % totalItems
                } else {
                    (currentIndex + 1) % totalItems
                }
                controller.seekTo(nextIndex, 0)
            } else {
                controller.seekToNextMediaItem()
            }

            if (!controller.isPlaying) {
                controller.prepare()
                controller.play()
            }
            Log.d(TAG, "skipToNext - 切换到下一首")
        }
    }

    /**
     * Skip to previous track.
     * If position > 3s, restart current song; otherwise go to previous song.
     * When repeat mode is ONE, manually navigate to previous song.
     */
    fun skipToPrevious() {
        mediaController?.let { controller ->
            val currentIndex = controller.currentMediaItemIndex
            val totalItems = controller.mediaItemCount

            Log.d(TAG, "skipToPrevious - 当前索引: $currentIndex, 位置: ${controller.currentPosition}")

            if (totalItems <= 0) return@let

            // If we're past 3 seconds, restart the current song
            if (controller.currentPosition > 3000) {
                controller.seekTo(controller.currentMediaItemIndex, 0)
            } else if (controller.repeatMode == Player.REPEAT_MODE_ONE) {
                // In single-repeat mode, manually go back
                val prevIndex = if (controller.shuffleModeEnabled) {
                    if (totalItems <= 1) currentIndex
                    else (currentIndex - 1 + totalItems) % totalItems
                } else {
                    (currentIndex - 1 + totalItems) % totalItems
                }
                controller.seekTo(prevIndex, 0)
            } else {
                controller.seekToPreviousMediaItem()
            }

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
     * Cancel any in-progress queue loading job
     */
    fun cancelQueueLoading() {
        queueLoadingJob?.cancel()
        queueLoadingJob = null
        Log.d(TAG, "已取消之前的队列加载任务")
    }

    /**
     * Set the current queue loading job for cancellation tracking
     */
    fun setQueueLoadingJob(job: Job) {
        queueLoadingJob?.cancel()
        queueLoadingJob = job
    }

    /**
     * Set media items and play.
     * Cancels any previous queue-loading job to prevent duplicates.
     */
    fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int = 0) {
        // Cancel previous background queue loader
        cancelQueueLoading()

        mediaController?.apply {
            setMediaItems(mediaItems, startIndex, 0)
            // Preserve current repeat mode (don't force REPEAT_MODE_ALL every time)
            if (repeatMode == Player.REPEAT_MODE_OFF) {
                repeatMode = Player.REPEAT_MODE_ALL
            }
            prepare()
            play()
        }
    }

    /**
     * Add media item to queue (deduplicates by mediaId)
     */
    fun addMediaItem(mediaItem: MediaItem) {
        val controller = mediaController ?: return
        // Deduplicate: skip if mediaId already in queue
        val mediaId = mediaItem.mediaId
        if (mediaId.isNotEmpty()) {
            for (i in 0 until controller.mediaItemCount) {
                if (controller.getMediaItemAt(i).mediaId == mediaId) {
                    Log.d(TAG, "跳过重复歌曲: $mediaId")
                    return
                }
            }
        }
        controller.addMediaItem(mediaItem)
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
     * Save playback state immediately (call before release)
     */
    fun saveStateSync() {
        scope.launch { savePlaybackState() }
    }

    /**
     * Release resources
     */
    fun release() {
        saveStateSync()
        cancelQueueLoading()
        scope.cancel()
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

    // ==================== Persistence ====================

    /**
     * Schedule a debounced save (avoids saving too frequently during transitions)
     */
    private fun scheduleSaveState() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000) // debounce 2s
            savePlaybackState()
        }
    }

    /**
     * Persist current playback state to DataStore
     */
    private suspend fun savePlaybackState() {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) return

        try {
            val queueItems = mutableListOf<SavedMediaItem>()
            for (i in 0 until controller.mediaItemCount) {
                val item = controller.getMediaItemAt(i)
                queueItems.add(
                    SavedMediaItem(
                        mediaId = item.mediaId,
                        uri = item.localConfiguration?.uri?.toString() ?: "",
                        title = item.mediaMetadata.title?.toString() ?: "",
                        artist = item.mediaMetadata.artist?.toString() ?: "",
                        artworkUri = item.mediaMetadata.artworkUri?.toString() ?: ""
                    )
                )
            }

            val queueJson = gson.toJson(queueItems)

            context.playbackStateDataStore.edit { prefs ->
                prefs[QUEUE_JSON] = queueJson
                prefs[CURRENT_INDEX] = controller.currentMediaItemIndex
                prefs[CURRENT_POSITION] = controller.currentPosition
                prefs[REPEAT_MODE_KEY] = controller.repeatMode
                prefs[SHUFFLE_MODE_KEY] = controller.shuffleModeEnabled
            }
            Log.d(TAG, "播放状态已保存: index=${controller.currentMediaItemIndex}, pos=${controller.currentPosition}, queue=${queueItems.size}")
        } catch (e: Exception) {
            Log.e(TAG, "保存播放状态失败", e)
        }
    }

    /**
     * Restore playback state from DataStore on startup
     */
    private suspend fun restorePlaybackState() {
        try {
            val prefs = context.playbackStateDataStore.data.first()
            val queueJson = prefs[QUEUE_JSON] ?: return
            val savedIndex = prefs[CURRENT_INDEX] ?: 0
            val savedPosition = prefs[CURRENT_POSITION] ?: 0L
            val savedRepeatMode = prefs[REPEAT_MODE_KEY] ?: Player.REPEAT_MODE_ALL
            val savedShuffleMode = prefs[SHUFFLE_MODE_KEY] ?: false

            val type = object : TypeToken<List<SavedMediaItem>>() {}.type
            val savedItems: List<SavedMediaItem> = gson.fromJson(queueJson, type) ?: return

            if (savedItems.isEmpty()) return

            // Rebuild MediaItems — validate local files, skip expired URLs
            val mediaItems = mutableListOf<MediaItem>()
            val database = com.bilimusicplayer.data.local.AppDatabase.getDatabase(context)

            withContext(Dispatchers.IO) {
                for (saved in savedItems) {
                    val uri = saved.uri
                    // Local file: verify it still exists
                    if (uri.startsWith("file://") || uri.startsWith("/")) {
                        val path = if (uri.startsWith("file://")) uri.removePrefix("file://") else uri
                        if (!java.io.File(path).exists()) continue
                    } else if (uri.startsWith("bili://")) {
                        // Lazy-resolve placeholder — always valid, resolved at play time
                    } else if (uri.startsWith("http")) {
                        // Online URL: check if we have a non-expired cached URL
                        if (saved.mediaId.isNotEmpty()) {
                            val cached = database.cachedPlaybackUrlDao().getCachedUrl(saved.mediaId)
                            if (cached == null || cached.expiresAt < System.currentTimeMillis()) {
                                // URL expired — convert to lazy placeholder for on-demand resolution
                                val lazyUri = "bili://${saved.mediaId}"
                                mediaItems.add(
                                    MediaItem.Builder()
                                        .setMediaId(saved.mediaId)
                                        .setUri(lazyUri)
                                        .setMediaMetadata(
                                            MediaMetadata.Builder()
                                                .setTitle(saved.title)
                                                .setArtist(saved.artist)
                                                .setArtworkUri(
                                                    if (saved.artworkUri.isNotEmpty()) android.net.Uri.parse(saved.artworkUri) else null
                                                )
                                                .build()
                                        )
                                        .setRequestMetadata(
                                            MediaItem.RequestMetadata.Builder()
                                                .setMediaUri(android.net.Uri.parse(lazyUri))
                                                .build()
                                        )
                                        .build()
                                )
                                continue
                            }
                        }
                    } else {
                        continue
                    }

                    mediaItems.add(
                        MediaItem.Builder()
                            .setMediaId(saved.mediaId)
                            .setUri(uri)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(saved.title)
                                    .setArtist(saved.artist)
                                    .setArtworkUri(
                                        if (saved.artworkUri.isNotEmpty()) android.net.Uri.parse(saved.artworkUri) else null
                                    )
                                    .build()
                            )
                            .setRequestMetadata(
                                MediaItem.RequestMetadata.Builder()
                                    .setMediaUri(android.net.Uri.parse(uri))
                                    .build()
                            )
                            .build()
                    )
                }
            }

            if (mediaItems.isEmpty()) {
                Log.d(TAG, "恢复播放状态: 无有效歌曲可恢复")
                return
            }

            // Adjust index if some items were skipped
            val adjustedIndex = savedIndex.coerceIn(0, mediaItems.size - 1)

            withContext(Dispatchers.Main) {
                mediaController?.apply {
                    setMediaItems(mediaItems, adjustedIndex, savedPosition)
                    repeatMode = savedRepeatMode
                    shuffleModeEnabled = savedShuffleMode
                    prepare()
                    // Don't auto-play — user resumes manually
                }
            }

            Log.d(TAG, "播放状态已恢复: index=$adjustedIndex, pos=$savedPosition, queue=${mediaItems.size}, repeat=$savedRepeatMode, shuffle=$savedShuffleMode")
        } catch (e: Exception) {
            Log.e(TAG, "恢复播放状态失败", e)
        }
    }


}

/**
 * Serializable media item for persistence
 */
data class SavedMediaItem(
    val mediaId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val artworkUri: String
)

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
