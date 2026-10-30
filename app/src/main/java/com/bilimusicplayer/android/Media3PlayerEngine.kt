package com.bilimusicplayer.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.bilimusicplayer.service.BiliAudioResolver
import com.bilimusicplayer.service.MusicPlayerController
import com.bilimusicplayer.settings.AppStorage
import com.bilimusicplayer.player.PlaybackState
import com.bilimusicplayer.player.PlayerEngine
import com.bilimusicplayer.player.RepeatMode
import com.bilimusicplayer.player.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Bridges the existing Media3 controller to the shared UI contract.
 * Keeping this adapter thin preserves background playback, media keys and watch support.
 */
class Media3PlayerEngine(
    context: Context,
    private val storage: AppStorage,
) : PlayerEngine {

    private val controller = MusicPlayerController(context, storage)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val state: StateFlow<PlaybackState> = controller.playbackState
        .map { it.toShared(controller.getCurrentMediaItemIndex()) }
        .stateIn(scope, SharingStarted.Eagerly, PlaybackState())

    override val queue: StateFlow<List<Track>> = controller.queue
        .map { items -> items.map(::toTrack) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override suspend fun initialize() = controller.initialize()

    override fun setQueue(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        controller.setMediaItems(tracks.map(::toMediaItem), startIndex)
    }

    override fun addToQueue(track: Track) = controller.addMediaItem(toMediaItem(track))

    override fun play() = controller.play()

    override fun pause() = controller.pause()

    override fun toggle() {
        if (state.value.isPlaying) controller.pause() else controller.play()
    }

    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)

    override fun skipToNext() = controller.skipToNext()

    override fun skipToPrevious() = controller.skipToPrevious()

    override fun skipTo(index: Int) = controller.skipToMediaItem(index)

    override fun removeAt(index: Int) = controller.removeMediaItem(index)

    override fun move(fromIndex: Int, toIndex: Int) = controller.moveMediaItem(fromIndex, toIndex)

    override fun clear() = controller.clearQueue()

    override fun setRepeatMode(mode: RepeatMode) = controller.setRepeatMode(
        when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        },
    )

    override fun setShuffle(enabled: Boolean) = controller.setShuffleMode(enabled)

    override fun saveState() = controller.saveStateSync()

    override fun suspendLocalFileUpdate(path: String): Pair<Long, Boolean>? =
        controller.suspendLocalFileUpdate(path)

    override fun resumeLocalFileUpdate(path: String, snapshot: Pair<Long, Boolean>?) =
        controller.resumeLocalFileUpdate(path, snapshot)

    private fun toMediaItem(track: Track): MediaItem {
        val uri = when {
            track.uri.startsWith("bili://") -> BiliAudioResolver.buildPlaceholderUri(track.id)
            track.uri.startsWith("file://") -> Uri.parse(track.uri)
            track.uri.startsWith("/") -> Uri.fromFile(File(track.uri))
            else -> Uri.parse(track.uri)
        }
        val artwork = track.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(artwork)
                    .build(),
            )
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    private fun toTrack(item: MediaItem): Track {
        val uri = item.localConfiguration?.uri?.toString().orEmpty()
        return Track(
            id = item.mediaId,
            title = item.mediaMetadata.title?.toString() ?: "未知歌曲",
            artist = item.mediaMetadata.artist?.toString() ?: "未知艺术家",
            coverUrl = item.mediaMetadata.artworkUri?.toString().orEmpty(),
            uri = uri,
        )
    }

    private fun com.bilimusicplayer.service.PlaybackState.toShared(index: Int): PlaybackState = PlaybackState(
        current = currentMediaItem?.let(::toTrack),
        isPlaying = isPlaying,
        isBuffering = playbackState == Player.STATE_BUFFERING,
        positionMs = currentPosition,
        durationMs = duration,
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> RepeatMode.OFF
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.ALL
        },
        shuffle = shuffleMode,
        queueIndex = index,
        error = errorMessage,
    )
}