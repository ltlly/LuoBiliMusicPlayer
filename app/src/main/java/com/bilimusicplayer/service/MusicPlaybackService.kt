package com.bilimusicplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.File

/**
 * MediaSessionService for music playback
 * Supports media controls on phone, watch, and notification
 */
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()

        // Create HTTP data source factory with custom headers for Bilibili
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://www.bilibili.com"
                )
            )

        // Initialize cache for audio streaming (100MB cache)
        val cacheDir = File(cacheDir, "exoplayer_cache")
        val cache = SimpleCache(
            cacheDir,
            LeastRecentlyUsedCacheEvictor(100 * 1024 * 1024), // 100MB
            StandaloneDatabaseProvider(this)
        )

        // Create cache data source factory
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        // Create data source factory that supports both HTTP and local files with cache
        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)

        // Initialize ExoPlayer with cached data source
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Add error listener
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e(TAG, "========== 播放错误 ==========")
                android.util.Log.e(TAG, "错误消息: ${error.message}")
                android.util.Log.e(TAG, "错误代码: ${error.errorCode}")
                android.util.Log.e(TAG, "错误原因: ${error.cause?.message}")
                android.util.Log.e(TAG, "完整堆栈:", error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateString = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                android.util.Log.d(TAG, "播放状态变化: $stateString, 当前位置: ${player.currentPosition}, 时长: ${player.duration}")

                if (playbackState == Player.STATE_ENDED && player.duration == 0L) {
                    android.util.Log.e(TAG, "警告: 播放立即结束且时长为0，可能是文件无法解码")
                    android.util.Log.e(TAG, "当前媒体项: ${player.currentMediaItem?.localConfiguration?.uri}")
                    android.util.Log.e(TAG, "媒体项数量: ${player.mediaItemCount}")
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                android.util.Log.d(TAG, "媒体切换: ${mediaItem?.mediaMetadata?.title}")
                android.util.Log.d(TAG, "URI: ${mediaItem?.localConfiguration?.uri}")
                android.util.Log.d(TAG, "MIME类型: ${mediaItem?.localConfiguration?.mimeType}")
            }

            override fun onTracksChanged(tracks: Tracks) {
                android.util.Log.d(TAG, "轨道变化: ${tracks.groups.size} 个轨道组")
                tracks.groups.forEach { group ->
                    android.util.Log.d(TAG, "  轨道组: ${group.length} 个轨道, 类型: ${group.type}")
                }
            }
        })

        // Create MediaSession
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .setSessionActivity(createPendingIntent())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    /**
     * Create pending intent to open app when tapping notification
     */
    private fun createPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * MediaSession callback to handle custom commands
     */
    private inner class MediaSessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(COMMAND_SET_REPEAT_MODE, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SET_SHUFFLE_MODE, Bundle.EMPTY))
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            return when (customCommand.customAction) {
                COMMAND_SET_REPEAT_MODE -> {
                    val repeatMode = args.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
                    player.repeatMode = repeatMode
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                COMMAND_SET_SHUFFLE_MODE -> {
                    val shuffleMode = args.getBoolean(KEY_SHUFFLE_MODE, false)
                    player.shuffleModeEnabled = shuffleMode
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                else -> {
                    Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val updatedMediaItems = mediaItems.map { mediaItem ->
                // Use requestMetadata.mediaUri if available, otherwise keep the original URI
                val uri = mediaItem.requestMetadata.mediaUri ?: mediaItem.localConfiguration?.uri
                mediaItem.buildUpon()
                    .setUri(uri)
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(updatedMediaItems)
        }
    }

    companion object {
        private const val TAG = "MusicPlaybackService"
        const val COMMAND_SET_REPEAT_MODE = "SET_REPEAT_MODE"
        const val COMMAND_SET_SHUFFLE_MODE = "SET_SHUFFLE_MODE"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_SHUFFLE_MODE = "shuffle_mode"
    }
}
