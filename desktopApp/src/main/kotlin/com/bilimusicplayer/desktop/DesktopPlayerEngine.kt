package com.bilimusicplayer.desktop

import com.bilimusicplayer.core.AppPaths
import com.bilimusicplayer.core.Log
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.favorite.AudioQuality
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.player.PlaybackState
import com.bilimusicplayer.player.PlayerEngine
import com.bilimusicplayer.player.RepeatMode
import com.bilimusicplayer.player.Track
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.InternalForInheritanceMediampApi
import org.openani.mediamp.InternalMediampApi
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackState as MediampPlaybackState
import org.openani.mediamp.mpv.JvmMpvMediampPlayer
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.source.UriMediaData
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Desktop engine: MPV handles AAC, FLAC and Dolby and accepts the Referer that
 * Bilibili's CDN expects. Queue/repeat/shuffle live here because MPV plays one
 * item at a time.
 */
class DesktopPlayerEngine(
    private val storage: AppStorage,
) : PlayerEngine {

    private companion object {
        const val TAG = "DesktopPlayer"
    }

    // MediaMP 要求所有调用都在其 mainDispatcher 的同一个线程上。
    // mediamp 在构造时记录线程身份。EDT 会因 AWT auto-shutdown 被替换为同名新线程，
    // limitedParallelism(1) 也会换线程；只有专属单线程身份永远不变。
    private val dispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mediamp-player").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val playerScope = CoroutineScope(
        SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e ->
            // 没有 handler 的话，协程异常只会进未捕获异常处理器，UI 上毫无痕迹
            Log.e(TAG, "player coroutine failed", e)
            _error.value = e.message ?: "播放器内部错误"
        },
    )
    private var playJob: kotlinx.coroutines.Job? = null

    // 桌面 MpvMediampPlayer 在 Windows/Linux 上会把 openImpl 挂起在
    // awaitRenderContextForLoad，直到有渲染上下文就绪；纯音频场景永远不会 attach
    // 视频 surface，于是 setMediaData 永远不返回、loadfile 从不执行（mpv.log 为空、
    // 点歌无声）。JvmMpvMediampPlayer 基类的 ensureRenderContextForLoad 恒为 true，
    // 无头直接放行 open。
    private val player: MediampPlayer = HeadlessMpvPlayer(
        playerScope.coroutineContext,
        dispatcher,
        ::configureMpv,
    )

    private fun configureMpv(handle: MPVHandle) {
        handle.option("http-header-fields", "Referer: https://www.bilibili.com,User-Agent: Mozilla/5.0")
        handle.option("cache-secs", "120")
        // 纯音频播放器：不选视频轨，避免 vo=libmpv 在无渲染上下文时初始化视频输出
        handle.option("vid", "no")
        runCatching {
            val logFile = File(AppPaths.dataDir(), "mpv.log")
            handle.option("log-file", logFile.absolutePath)
        }
    }

    @OptIn(InternalMediampApi::class, InternalForInheritanceMediampApi::class)
    private class HeadlessMpvPlayer(
        parentCoroutineContext: CoroutineContext,
        mainDispatcher: CoroutineDispatcher,
        configureOptions: (MPVHandle) -> Unit,
    ) : JvmMpvMediampPlayer(Unit, parentCoroutineContext, mainDispatcher, configureOptions)

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    override val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _index = MutableStateFlow(-1)
    private val _repeat = MutableStateFlow(RepeatMode.ALL)
    private val _shuffle = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)

    // 把 _queue 也纳入 combine：updateTracks 就地改元数据后当前曲目立刻刷新，
    // 否则 current 只在其它流发射时才重读 _queue.value
    private val queueAtIndex = combine(_queue, _index) { q, i -> q to i }

    override val state: StateFlow<PlaybackState> = combine(
        player.playbackState,
        player.currentPositionMillis,
        player.mediaProperties,
        queueAtIndex,
        _error,
    ) { playback, position, properties, queueIndex, error ->
        val (queue, index) = queueIndex
        PlaybackState(
            current = queue.getOrNull(index),
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
                    is PlaybackEvent.ErrorOccurred -> {
                        Log.e(TAG, "MediampPlayer error: $event")
                        _error.value = event.toString()
                    }
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

    override fun appendToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _queue.value = _queue.value + tracks
    }

    override fun updateTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val byId = tracks.associateBy { it.id }
        _queue.value = _queue.value.map { old ->
            val patch = byId[old.id] ?: return@map old
            old.copy(
                title = patch.title,
                artist = patch.artist,
                coverUrl = patch.coverUrl,
                durationMs = patch.durationMs,
            )
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
            // 设置存储已是文件版（无 8KB 上限），直接写 playbackStateJson
            storage.playbackStateJson = _queue.value.joinToString("\n") {
                "${it.id}\t${it.title}\t${it.artist}\t${it.coverUrl}\t${it.uri}"
            }
        }.onFailure { Log.e(TAG, "Failed to save desktop queue", it) }
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
        Log.d(TAG, "playCurrent index=${_index.value} id=${track.id}")
        playJob?.cancel()
        playJob = playerScope.launch {
            _error.value = null
            try {
                val (uri, headers) = resolve(track)
                player.setMediaData(UriMediaData(uri, headers), true, positionMs)
                Log.d(TAG, "setMediaData ok: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "resolve/setMediaData failed: ${track.id}", e)
                _error.value = e.message ?: "播放失败"
            }
        }
    }

    private suspend fun resolve(track: Track): Pair<String, Map<String, String>> = withContext(Dispatchers.IO) {
        val uri = track.uri
        when {
            uri.startsWith("file://") -> return@withContext uri to emptyMap()
            uri.startsWith("http://") || uri.startsWith("https://") -> return@withContext uri to emptyMap()
            uri.isNotBlank() && !uri.startsWith("bili") -> {
                // 本地绝对路径（POSIX /x 或 Windows C:\x）存在则转 file URI；
                // 其余字符串按 URL 原样交给 mpv
                val file = File(uri)
                return@withContext if (file.isAbsolute && file.exists()) {
                    file.toURI().toString() to emptyMap()
                } else {
                    uri to emptyMap()
                }
            }
        }
        // 走到这里的是 bili:// 占位或空 uri，继续走 B站解析
        val database = com.bilimusicplayer.data.local.AppDatabase.getDatabase()

        // 已下载的本地文件直接播放（0 API）
        val song = database.songDao().getSongById(track.id)
        if (song != null && song.isDownloaded && song.localPath != null && File(song.localPath!!).exists()) {
            return@withContext File(song.localPath!!).toURI().toString() to emptyMap()
        }

        // 有效URL缓存直接播放（0 API）
        val cached = database.cachedPlaybackUrlDao().getCachedUrl(track.id)
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) {
            return@withContext cached.audioUrl to bilibiliHeaders()
        }

        val repository = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)

        // 复用缓存行里的CID（即使URL已过期）——1次 playurl 请求替代 view+playurl
        val knownCid = cached?.cid?.takeIf { it > 0 }
        val detail = if (knownCid == null) {
            repository.getVideoDetail(track.id).body()?.data
                ?: throw IllegalStateException("无法获取视频信息")
        } else null
        val cid = knownCid ?: detail!!.cid

        // 占位曲目（标题=BV号）走到 view 时顺手把真实元数据写回队列
        if (detail != null) {
            updateTracks(
                listOf(
                    track.copy(
                        title = detail.title,
                        artist = detail.owner.name,
                        coverUrl = detail.pic,
                        durationMs = detail.duration * 1000L,
                    )
                )
            )
        }

        val resolved = repository.getPlayUrlWithFreshCidFallback(cid, track.id, allowRefresh = knownCid != null)
        val playUrl = resolved.response
        val stream = repository.selectBestAudioStream(playUrl.body()?.data?.dash)
            ?: throw IllegalStateException("当前账号无法获取音轨")
        val url = AudioQuality.urls(stream).firstOrNull() ?: throw IllegalStateException("音轨地址为空")
        try {
            database.cachedPlaybackUrlDao().insertCachedUrl(
                com.bilimusicplayer.data.model.CachedPlaybackUrl(
                    bvid = track.id, cid = resolved.cid, audioUrl = url,
                    title = detail?.title ?: track.title,
                    artist = detail?.owner?.name ?: track.artist,
                    coverUrl = detail?.pic ?: track.coverUrl,
                    duration = detail?.duration ?: (track.durationMs / 1000).toInt()
                )
            )
        } catch (_: Exception) {}
        url to bilibiliHeaders()
    }

    private fun bilibiliHeaders() = mapOf(
        "Referer" to "https://www.bilibili.com",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
    )
}