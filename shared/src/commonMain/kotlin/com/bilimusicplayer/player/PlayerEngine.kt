package com.bilimusicplayer.player

import kotlinx.coroutines.flow.StateFlow

interface PlayerEngine {
    val state: StateFlow<PlaybackState>
    val queue: StateFlow<List<Track>>

    suspend fun initialize()

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L)
    fun addToQueue(track: Track)
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