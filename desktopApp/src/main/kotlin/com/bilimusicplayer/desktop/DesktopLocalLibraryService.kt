package com.bilimusicplayer.desktop

import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.platform.DeleteResult
import com.bilimusicplayer.platform.LocalLibraryService
import com.bilimusicplayer.platform.ScanResult
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class DesktopLocalLibraryService(private val storage: AppStorage) : LocalLibraryService {

    private val database = AppDatabase.getDatabase()
    private val songDao = database.songDao()

    override val canScanSystemLibrary: Boolean = true
    override val requiresStoragePermission: Boolean = false

    override fun downloadedSongs(): Flow<List<Song>> = songDao.getDownloadedSongs()
        .map { songs -> songs.filter { it.localPath?.let { path -> File(path).exists() } == true } }

    override fun downloads(): Flow<List<Download>> = database.downloadDao().getAllDownloads()

    override fun hasStoragePermission(): Boolean = true

    override suspend fun requestStoragePermission(): Boolean = true

    override suspend fun scan(forceSync: Boolean): ScanResult = withContext(Dispatchers.IO) {
        val dir = File(storage.downloadSettings.value.downloadDir)
        if (!dir.isDirectory) return@withContext ScanResult(0, false)
        val known = songDao.getAllSongsOnce().mapNotNull { it.localPath }.toSet()
        var imported = 0
        dir.walkTopDown().filter { it.isFile && it.extension.equals("m4a", true) }.forEach { file ->
            if (file.absolutePath in known) return@forEach
            val id = "local_${file.absolutePath.hashCode().toUInt().toString(16)}"
            songDao.insertSongIfNotExists(
                Song(
                    id = id,
                    title = file.nameWithoutExtension,
                    artist = "本地音乐",
                    duration = 0,
                    coverUrl = "",
                    localPath = file.absolutePath,
                    cid = 0L,
                    bvid = id,
                    aid = 0L,
                    uploaderId = 0L,
                    uploaderName = "",
                    pubDate = file.lastModified(),
                    addedDate = file.lastModified(),
                    isDownloaded = true,
                    fileSize = file.length(),
                ),
            )
            imported++
        }
        ScanResult(imported, false)
    }

    override suspend fun countUnsynced(): Int = withContext(Dispatchers.IO) {
        songDao.countUnsyncedLocalSongs()
    }

    override suspend fun deleteSongs(songIds: List<String>): DeleteResult = withContext(Dispatchers.IO) {
        var files = 0
        var rows = 0
        songIds.forEach { id ->
            val song = songDao.getSongById(id) ?: return@forEach
            song.localPath?.let { if (File(it).delete()) files++ }
            songDao.deleteSong(song)
            rows++
        }
        DeleteResult(files, rows)
    }

    override suspend fun deleteUnsynced(): DeleteResult = withContext(Dispatchers.IO) {
        val songs = songDao.getUnsyncedLocalSongs()
        var files = 0
        songs.forEach { song -> song.localPath?.let { if (File(it).delete()) files++ } }
        DeleteResult(files, songDao.deleteAllUnsyncedLocalSongs())
    }
}
