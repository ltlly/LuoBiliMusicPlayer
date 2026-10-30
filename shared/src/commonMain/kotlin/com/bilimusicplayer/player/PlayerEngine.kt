package com.bilimusicplayer.player

import kotlinx.coroutines.flow.StateFlow

interface PlayerEngine {
    val state: StateFlow<PlaybackState>
    val queue: StateFlow<List<Track>>

    suspend fun initialize()

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L)
    fun addToQueue(track: Track)

    /**
     * 只往队尾追加：不改当前 index，也不打断正在解析/播放的任务。
     * 队列异步补全（如收藏夹后台补全）必须用它，避免二次 setQueue 取消播放。
     */
    fun appendToQueue(tracks: List<Track>)

    /**
     * 按 id 就地更新队列中已有条目的元数据（title/artist/coverUrl/durationMs），
     * uri 保持原样，不改 index、不打断播放。用于占位曲目补到真实元数据后刷新。
     */
    fun updateTracks(tracks: List<Track>)
    fun play()
    fun pause()
    fun toggle()
    fun seekTo(positionMs: Long)
    fun skipToNext()
    fun skipToPrevious()
    fun skipTo(index: Int)
    fun removeAt(index: Int)
    fun move(fromIndex: Int, toIndex: Int)
    fun clear()
    fun setRepeatMode(mode: RepeatMode)
    fun setShuffle(enabled: Boolean)
    fun saveState()

    /** Optional hook used by Android's in-place quality replacement. */
    fun suspendLocalFileUpdate(path: String): Pair<Long, Boolean>? = null

    fun resumeLocalFileUpdate(path: String, snapshot: Pair<Long, Boolean>?) = Unit
}