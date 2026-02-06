package com.bilimusicplayer.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

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

        // Initialize ExoPlayer with custom data source
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(httpDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setShuffleOrder(DefaultShuffleOrder(0))
            .build()

        // Configure player for better shuffle behavior
        player.playWhenReady = true

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
                mediaItem.buildUpon()
                    .setUri(mediaItem.requestMetadata.mediaUri)
                    .build()
            }.toMutableList()
            return Futures.immediateFuture(updatedMediaItems)
        }
    }

    companion object {
        const val COMMAND_SET_REPEAT_MODE = "SET_REPEAT_MODE"
        const val COMMAND_SET_SHUFFLE_MODE = "SET_SHUFFLE_MODE"
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_SHUFFLE_MODE = "shuffle_mode"
    }
}
