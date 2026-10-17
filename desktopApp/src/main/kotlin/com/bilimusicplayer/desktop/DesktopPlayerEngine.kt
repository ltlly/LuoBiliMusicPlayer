package com.bilimusicplayer.desktop

import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.AudioQuality
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.player.PlaybackState
import com.bilimusicplayer.player.PlayerEngine
import com.bilimusicplayer.player.RepeatMode
import com.bilimusicplayer.player.Track
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackState as MediampPlaybackState
import org.openani.mediamp.mpv.MpvMediampPlayer
import org.openani.mediamp.source.UriMediaData
import java.io.File

/**
 * Desktop engine: MPV handles AAC, FLAC and Dolby and accepts the Referer that
 * Bilibili's CDN expects. Queue/repeat/shuffle live here because MPV plays one
 * item at a time.
 */
class DesktopPlayerEngine(
    private val storage: AppStorage,
) : PlayerEngine {

    // MediaMP requires every call to happen on its main dispatcher. A single
    // parallel slot also serializes setMediaData calls from rapid double clicks.
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val playerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var playJob: kotlinx.coroutines.Job? = null

    private val player: MediampPlayer = MpvMediampPlayer(
        Unit,
        playerScope.coroutineContext,
        dispatcher,
    ) { handle ->
        handle.option("http-header-fields", "Referer: https://www.bilibili.com,User-Agent: Mozilla/5.0")
        handle.option("cache-secs", "120")
    }

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    override val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    private val _repeat = MutableStateFlow(RepeatMode.ALL)
    private val _shuffle = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    override val state: StateFlow<PlaybackState> = combine(
        player.playbackState,
        player.currentPositionMillis,
        player.mediaProperties,
        _index,
        _error,
    ) { playback, position, properties, index, error ->
        PlaybackState(
            current = _queue.value.getOrNull(index),
            isPlaying = playback == MediampPlaybackState.PLAYING,
            isBuffering = playback == MediampPlaybackState.PAUSED_BUFFERING,
            positionMs = position,
            durationMs = properties?.durationMillis ?: 0L,
            repeatMode = _repeat.value,
            shuffle = _shuffle.value,
            queueIndex = index,
            error = error,
        )
    }.stateIn(playerScope, SharingStarted.Eagerly, PlaybackState())

    override suspend fun initialize() {
        playerScope.launch {
            player.events.collect { event ->
                when (event) {
                    is PlaybackEvent.MediaEnded -> onEnded()
                    is PlaybackEvent.ErrorOccurred -> _error.value = event.toString()
                    else -> Unit
                }
            }
        }
    }

    override fun setQueue(tracks: List<Track>, startIndex: Int, startPositionMs: Long) {
        _queue.value = tracks
        if (tracks.isEmpty()) {
            _index.value = -1
            return
        }
        _index.value = startIndex.coerceIn(tracks.indices)
        playCurrent(startPositionMs)
    }

    override fun addToQueue(track: Track) {
        _queue.value = _queue.value + track
        if (_index.value < 0) {
            _index.value = 0
            playCurrent(0L)
        }
    }

    override fun play() {
        playerScope.launch { player.play() }
    }

    override fun pause() {
        playerScope.launch { player.pause() }
    }

    override fun toggle() {
        playerScope.launch {
            if (player.state.value.isPlaying) player.pause() else player.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        playerScope.launch { player.seekTo(positionMs) }
    }

    override fun skipToNext() {
        val queue = _queue.value
        if (queue.isEmpty()) return
        val next = if (_shuffle.value && queue.size > 1) {
            generateSequence { (queue.indices).random() }.first { it != _index.value }
        } else {
            (_index.value + 1) % queue.size
        }
        _index.value = next
        playCurrent(0L)
    }

    override fun skipToPrevious() {
        val queue = _queue.value
        if (queue.isEmpty()) return
        val previous = if (_shuffle.value && queue.size > 1) {
            generateSequence { (queue.indices).random() }.first { it != _index.value }
        } else {
            (_index.value - 1 + queue.size) % queue.size
        }
        _index.value = previous
        playCurrent(0L)
    }

    override fun skipTo(index: Int) {
        if (index !in _queue.value.indices) return
        _index.value = index
        playCurrent(0L)
    }

    override fun removeAt(index: Int) {
        val queue = _queue.value.toMutableList()
        if (index !in queue.indices) return
        queue.removeAt(index)
        _queue.value = queue
        _index.value = _index.value.coerceIn(-1, queue.lastIndex.coerceAtLeast(-1))
        if (queue.isEmpty()) playerScope.launch { player.stopPlayback() }
    }

    override fun move(fromIndex: Int, toIndex: Int) {
        val queue = _queue.value.toMutableList()
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        queue.add(toIndex, queue.removeAt(fromIndex))
        _queue.value = queue
        if (_index.value == fromIndex) _index.value = toIndex
    }

    override fun clear() {
        _queue.value = emptyList()
        _index.value = -1
        playerScope.launch { player.stopPlayback() }
    }

    override fun setRepeatMode(mode: RepeatMode) { _repeat.value = mode }

    override fun setShuffle(enabled: Boolean) { _shuffle.value = enabled }

    override fun saveState() {
        // Closing the window must never surface an exception dialog. The queue is
        // best-effort state; a failed write only means the next launch starts empty.
        runCatching {
            storage.playbackStateJson = _queue.value.joinToString("\n") {
                "${it.id}\t${it.title}\t${it.artist}\t${it.coverUrl}\t${it.uri}"
            }
        }.onFailure { System.err.println("Failed to save desktop queue: $it") }
    }

    private fun onEnded() {
        when (_repeat.value) {
            RepeatMode.ONE -> playCurrent(0L)
            RepeatMode.OFF -> if (_index.value >= _queue.value.lastIndex) playerScope.launch { player.stopPlayback() } else skipToNext()
            RepeatMode.ALL -> skipToNext()
        }
    }

    private fun playCurrent(positionMs: Long) {
        val track = _queue.value.getOrNull(_index.value) ?: return
        playJob?.cancel()
        playJob = playerScope.launch {
            _error.value = null
            try {
                val (uri, headers) = resolve(track)
                player.setMediaData(UriMediaData(uri, headers), true, positionMs)
            } catch (e: Exception) {
                _error.value = e.message ?: "播放失败"
            }
        }
    }

    private suspend fun resolve(track: Track): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        if (!track.uri.startsWith("bili://") && track.uri.isNotBlank() && !track.uri.startsWith("bili")) {
            return@withContext if (track.uri.startsWith("/")) {
                File(track.uri).toURI().toString() to emptyMap()
            } else {
                track.uri to emptyMap()
            }
        }
        val repository = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
        val detail = repository.getVideoDetail(track.id)
        val cid = detail.body()?.data?.cid ?: throw IllegalStateException("无法获取视频信息")
        val playUrl = repository.getPlayUrl(cid, track.id)
        val stream = repository.selectBestAudioStream(playUrl.body()?.data?.dash)
            ?: throw IllegalStateException("当前账号无法获取音轨")
        val url = AudioQuality.urls(stream).firstOrNull() ?: throw IllegalStateException("音轨地址为空")
        url to mapOf(
            "Referer" to "https://www.bilibili.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        )
    }
}