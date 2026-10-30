package com.bilimusicplayer.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.bilimusicplayer.data.local.AppDatabase
import com.bilimusicplayer.data.model.Download
import com.bilimusicplayer.data.model.Song
import com.bilimusicplayer.network.RetrofitClient
import com.bilimusicplayer.network.bilibili.auth.BiliAuthRepository
import com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository
import com.bilimusicplayer.platform.DeleteResult
import com.bilimusicplayer.platform.LocalLibraryService
import com.bilimusicplayer.platform.ScanResult
import com.bilimusicplayer.service.download.AudioDownloadWorker
import com.bilimusicplayer.settings.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

class AndroidLocalLibraryService(
    private val context: Context,
    private val storage: AppStorage,
) : LocalLibraryService {

    private val database = AppDatabase.getDatabase()
    private val repository = BiliFavoriteRepository(RetrofitClient.biliFavoriteApi)
    private val authRepository = BiliAuthRepository(
        api = RetrofitClient.biliAuthApi,
        cookieJar = RetrofitClient.getCookieJar(),
        biliApi = RetrofitClient.biliApi,
        storage = storage,
    )

    /** Registered by MainActivity so the shared screen can request media access. */
    var permissionRequester: (suspend () -> Boolean)? = null

    override val canScanSystemLibrary: Boolean = true
    override val requiresStoragePermission: Boolean = true

    override fun downloadedSongs(): Flow<List<Song>> = database.songDao().getDownloadedSongs()
        .map { songs -> songs.filter { it.localPath?.let { path -> File(path).exists() } == true } }

    override fun downloads(): Flow<List<Download>> = database.downloadDao().getAllDownloads()

    override fun hasStoragePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    override suspend fun requestStoragePermission(): Boolean {
        if (hasStoragePermission()) return true
        return permissionRequester?.invoke() == true
    }

    override suspend fun scan(forceSync: Boolean): ScanResult = scanWithFavoriteSync(forceSync)

    override suspend fun countUnsynced(): Int = withContext(Dispatchers.IO) {
        database.songDao().countUnsyncedLocalSongs()
    }

    override suspend fun deleteSongs(songIds: List<String>): DeleteResult = withContext(Dispatchers.IO) {
        var files = 0
        var rows = 0
        songIds.forEach { id ->
            val song = database.songDao().getSongById(id) ?: return@forEach
            song.localPath?.let { path -> if (File(path).delete()) files++ }
            database.songDao().deleteSong(song)
            rows++
        }
        DeleteResult(files, rows)
    }

    override suspend fun deleteUnsynced(): DeleteResult = withContext(Dispatchers.IO) {
        val songs = database.songDao().getUnsyncedLocalSongs()
        var files = 0
        songs.forEach { song -> song.localPath?.let { if (File(it).delete()) files++ } }
        DeleteResult(files, database.songDao().deleteAllUnsyncedLocalSongs())
    }

    private suspend fun scanWithFavoriteSync(forceSync: Boolean): ScanResult =
        withContext(Dispatchers.IO) {
            var synced = false
            try {
                val cachedCount = database.cachedFavoriteMediaDao().getAll().size
                val unsyncedCount = database.songDao().countUnsyncedLocalSongs()
                val needsSync = forceSync || cachedCount == 0 || unsyncedCount > 10
                if (needsSync) {
                    val userId = authRepository.getUserId()
                    if (userId != null) {
                        android.util.Log.d("LibraryScan", "开始从B站同步收藏夹 (force=$forceSync, cached=$cachedCount, unsynced=$unsyncedCount)...")
                        synced = true
                        val foldersResponse = repository.getFavoriteFolders(userId)
                        if (foldersResponse.isSuccessful && foldersResponse.body()?.code == 0) {
                            val folders = foldersResponse.body()?.data?.list ?: emptyList()
                            val contentCacheRepo = com.bilimusicplayer.data.repository.FavoriteContentCacheRepository(database)
                            for (folder in folders) {
                                try {
                                    val allMedia = repository.getAllFavoriteVideos(folder.id)
                                    if (allMedia.isNotEmpty()) {
                                        contentCacheRepo.cacheMediaList(folder.id, allMedia)
                                    }
                                    kotlinx.coroutines.delay(300)
                                } catch (e: Exception) {
                                    android.util.Log.e("LibraryScan", "同步收藏夹失败: ${folder.title}", e)
                                }
                            }
                        } else {
                            android.util.Log.e("LibraryScan", "获取收藏夹列表失败")
                        }
                    } else {
                        android.util.Log.w("LibraryScan", "未登录, 跳过收藏夹同步")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LibraryScan", "收藏夹同步异常", e)
            }
            ScanResult(scanAndImportOrphanFiles(), synced)
        }

    private suspend fun scanAndImportOrphanFiles(): Int = withContext(Dispatchers.IO) {
        try {
            val biliDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                "BiliMusic",
            )
            if (!biliDir.isDirectory) return@withContext 0
            val files = biliDir.listFiles { f ->
                f.isFile && !f.isHidden && f.length() > 0 && f.name.endsWith(".m4a", ignoreCase = true)
            }?.toList() ?: return@withContext 0

            val songDao = database.songDao()
            val allSongs = songDao.getAllSongsOnce()
            val songsByPath = allSongs.filter { it.localPath != null }.associateBy { it.localPath!! }
            val songsById = allSongs.associateBy { it.id }
            val knownIds = allSongs.map { it.id }.toMutableSet()

            val cachedAll = database.cachedFavoriteMediaDao().getAll()
            val cacheByFilename = mutableMapOf<String, com.bilimusicplayer.data.model.CachedFavoriteMedia>()
            for (c in cachedAll) {
                cacheByFilename.putIfAbsent(AudioDownloadWorker.sanitizeFilename(c.title), c)
                cacheByFilename.putIfAbsent(AudioDownloadWorker.legacyStripFilename(c.title), c)
            }

            var imported = 0
            for (file in files) {
                val absPath = file.absolutePath
                val baseName = file.nameWithoutExtension
                val existing = songsByPath[absPath]
                val matched = cacheByFilename[baseName]
                if (existing != null && !existing.id.startsWith("local_") &&
                    existing.isDownloaded && existing.localPath == absPath
                ) continue

                if (matched != null) {
                    val existingByBv = songsById[matched.bvid]
                    val cover = matched.cover.let {
                        when {
                            it.startsWith("//") -> "https:$it"
                            it.startsWith("http://") -> it.replaceFirst("http://", "https://")
                            else -> it
                        }
                    }
                    val newSong = Song(
                        id = matched.bvid,
                        title = matched.title,
                        artist = matched.upperName.ifBlank { "本地音乐" },
                        album = null,
                        duration = matched.duration,
                        coverUrl = cover,
                        localPath = absPath,
                        audioUrl = existingByBv?.audioUrl,
                        cid = existingByBv?.cid ?: 0L,
                        bvid = matched.bvid,
                        aid = matched.id,
                        uploaderId = matched.upperMid,
                        uploaderName = matched.upperName,
                        pubDate = matched.pubtime,
                        addedDate = existingByBv?.addedDate ?: file.lastModified(),
                        isDownloaded = true,
                        fileSize = file.length(),
                    )
                    existing?.takeIf { it.id.startsWith("local_") && it.id != matched.bvid }
                        ?.let { songDao.deleteSongById(it.id) }
                    songDao.insertSong(newSong)
                    knownIds.add(matched.bvid)
                    imported++
                    continue
                }

                if (existing != null) continue
                val syntheticId = "local_${baseName.hashCode().toUInt().toString(16)}"
                if (syntheticId in knownIds) continue
                songDao.insertSongIfNotExists(
                    Song(
                        id = syntheticId,
                        title = baseName,
                        artist = "本地音乐",
                        album = null,
                        duration = 0,
                        coverUrl = "",
                        localPath = absPath,
                        audioUrl = null,
                        cid = 0L,
                        bvid = syntheticId,
                        aid = 0L,
                        uploaderId = 0L,
                        uploaderName = "",
                        pubDate = file.lastModified(),
                        addedDate = file.lastModified(),
                        isDownloaded = true,
                        fileSize = file.length(),
                    ),
                )
                knownIds.add(syntheticId)
                imported++
            }
            imported
        } catch (e: Exception) {
            android.util.Log.e("LibraryScan", "scanAndImportOrphanFiles failed", e)
            0
        }
    }
}
