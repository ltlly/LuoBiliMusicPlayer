package com.bilimusicplayer.platform

import com.bilimusicplayer.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class FoldInfo(
    val horizontal: Boolean,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
)

interface AudioCapabilities {
    val supportsDolby: Boolean
    fun observeQualityLabel(path: String?): Flow<String?>
}

interface AppCache {
    suspend fun sizeBytes(): Long
    suspend fun clear()
}

interface FilePicker {
    fun pickDirectory(): String?
}

data class UpgradeProgress(
    val status: String = "idle",
    val done: Int = 0,
    val total: Int = 0,
    val upgraded: Int = 0,
    val unchanged: Int = 0,
    val failed: Int = 0,
    val current: String = "",
    val error: String = "",
)

interface QualityUpgradeService {
    val isSupported: Boolean
    val state: StateFlow<UpgradeProgress>

    suspend fun start(retryFailed: Boolean)
    suspend fun pause()
    suspend fun errors(): List<Pair<String, String>>
}

data class ScanResult(val imported: Int, val syncedFavorites: Boolean)
data class DeleteResult(val deletedFiles: Int, val deletedRows: Int)

interface LocalLibraryService {
    val canScanSystemLibrary: Boolean
    val requiresStoragePermission: Boolean

    fun downloadedSongs(): Flow<List<Song>>
    fun downloads(): Flow<List<com.bilimusicplayer.data.model.Download>>

    fun hasStoragePermission(): Boolean
    suspend fun requestStoragePermission(): Boolean
    suspend fun scan(forceSync: Boolean = false): ScanResult
    suspend fun countUnsynced(): Int
    suspend fun deleteSongs(songIds: List<String>): DeleteResult
    suspend fun deleteUnsynced(): DeleteResult
}