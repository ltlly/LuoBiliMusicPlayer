package com.bilimusicplayer.service.download

import android.content.Context
import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.work.*
import com.bilimusicplayer.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit

data class QualityUpgradeState(
    val status: String = "idle", val total: Int = 0, val done: Int = 0,
    val upgraded: Int = 0, val unchanged: Int = 0, val failed: Int = 0,
    val current: String = "", val error: String = ""
)

/** Persisted local progress; opening the progress screen never requests Bilibili. */
class QualityUpgradeManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("quality_upgrade", Context.MODE_PRIVATE)
    private val work = WorkManager.getInstance(context)
    fun snapshot() = QualityUpgradeState(
        prefs.getString("status", "idle")!!, prefs.getInt("total", 0), prefs.getInt("done", 0),
        prefs.getInt("upgraded", 0), prefs.getInt("unchanged", 0), prefs.getInt("failed", 0),
        prefs.getString("current", "")!!, prefs.getString("error", "")!!
    )
    val state = callbackFlow {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(snapshot()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    suspend fun prepareUris(): List<Uri> = withContext(Dispatchers.IO) {
        val songs = AppDatabase.getDatabase(context).songDao().getAllSongsOnce().filter { it.isDownloaded }
        val paths = songs.mapNotNull { it.localPath }.toSet()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val found = mutableListOf<Uri>()
        val edit = prefs.edit()
        context.contentResolver.query(collection, arrayOf("_id", "_data"), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val path = cursor.getString(1)
                if (path in paths) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                    edit.putString("uri:$path", uri.toString())
                    found.add(uri)
                }
            }
        }
        edit.commit()
        found
    }
    fun uri(path: String): Uri? = prefs.getString("uri:$path", null)?.let(Uri::parse)
    fun current(title: String) { prefs.edit().putString("current", title).apply() }
    fun outcome(id: String): String? = prefs.getString("result:$id", null)
    fun record(id: String, result: String, reason: String = "") = synchronized(recordLock) {
        if (outcome(id) != null) return@synchronized
        prefs.edit().putString("result:$id", result).putString("reason:$id", reason)
            .putInt("done", prefs.getInt("done", 0) + 1)
            .putInt(result, prefs.getInt(result, 0) + 1).putString("error", reason).commit()
        val state = snapshot()
        android.util.Log.i("QualityUpgrade", "进度 ${state.done}/${state.total}，升级 ${state.upgraded}，保留 ${state.unchanged}，未能升级 ${state.failed}")
    }
    suspend fun errors(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).songDao().getAllSongsOnce()
            .filter { outcome(it.id) == "failed" }.map { it.title to prefs.getString("reason:${it.id}", "无法升级")!! }
    }
    suspend fun start(uris: List<Uri>, retryFailures: Boolean = false) = withContext(Dispatchers.IO) {
        if (snapshot().status == "running") return@withContext
        // Fail once, before clearing outcomes or enqueueing hundreds of workers.
        com.bilimusicplayer.network.bilibili.favorite.BiliFavoriteRepository(
            com.bilimusicplayer.network.RetrofitClient.biliFavoriteApi
        ).initWbiKeys()
        AudioFileReplacement(context).recover()
        val dao = AppDatabase.getDatabase(context).songDao()
        val songs = dao.getAllSongsOnce().filter { it.isDownloaded && it.localPath != null }
        // Old renamed titles sometimes produced a local_* duplicate. Prove identity
        // against an existing identified file, without a search or any server request.
        val identified = songs.filter { it.bvid.startsWith("BV") }.groupBy { File(it.localPath!!).length() }
        for (unknown in songs.filter { !it.bvid.startsWith("BV") }) {
            val old = File(unknown.localPath!!)
            if (old.length() <= 0) continue
            val candidates = identified[old.length()].orEmpty().filter { it.localPath != unknown.localPath }
            if (candidates.isEmpty()) continue
            val hash = fileHash(old)
            val matching = candidates.filter { fileHash(File(it.localPath!!)).contentEquals(hash) }.distinctBy { it.bvid }
            if (matching.size == 1) {
                val source = matching.single()
                val cid = source.cid.takeIf { it > 0 } ?: AppDatabase.getDatabase(context).cachedPlaybackUrlDao().getCachedUrl(source.bvid)?.cid ?: 0L
                dao.updateSource(unknown.id, source.bvid, cid)
            }
        }
        val status = snapshot().status
        if (status == "running") return@withContext
        if (status !in listOf("paused", "permission") && !retryFailures) {
            prefs.edit().clear().putInt("total", songs.size).commit()
            prepareUris()
        }
        if (retryFailures) {
            val failed = songs.filter { outcome(it.id) == "failed" }
            val edit = prefs.edit().putInt("failed", 0).putInt("done", snapshot().done - failed.size)
            failed.forEach { edit.remove("result:${it.id}").remove("reason:${it.id}") }
            edit.commit()
        }
        val remaining = songs.filter { outcome(it.id) == null }
        if (remaining.isEmpty()) { finish(); return@withContext }
        // URI grants travel with this service Intent, outliving the requesting Activity.
        QualityUpgradeAccessService.start(context, uris)
        val settings = DownloadSettingsManager(context)
        if (settings.getMaxConcurrentDownloads() > 2) settings.setMaxConcurrentDownloads(2)
        val constraints = Constraints.Builder().setRequiredNetworkType(
            if (settings.getWifiOnly()) NetworkType.UNMETERED else NetworkType.CONNECTED
        ).build()
        var chain: WorkContinuation? = null
        remaining.forEach { song ->
            val request = OneTimeWorkRequestBuilder<QualityUpgradeWorker>()
                .setInputData(workDataOf("song_id" to song.id))
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .addTag(WORK_NAME).build()
            chain = chain?.then(request) ?: work.beginUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
        prefs.edit().putString("status", "running").putString("error", "").commit()
        chain!!.then(OneTimeWorkRequestBuilder<QualityUpgradeCompletionWorker>().build()).enqueue().result.get()
    }
    suspend fun pause() = withContext(Dispatchers.IO) {
        prefs.edit().putString("status", "paused").commit()
        work.cancelUniqueWork(WORK_NAME).result.get()
        AudioFileReplacement.transactionMutex.withLock {
            context.stopService(Intent(context, QualityUpgradeAccessService::class.java))
        }
    }
    fun permissionRequired() { prefs.edit().putString("status", "permission").putString("error", "需要重新授权修改现有音频，请点继续").commit() }
    fun waitingForSignature(reason: String) {
        prefs.edit().putString("current", "签名服务暂不可用，稍后自动重试")
            .putString("error", reason).commit()
    }
    fun waitingForRateLimit(reason: String) {
        prefs.edit().putString("current", "B站请求限制，冷却后自动继续")
            .putString("error", reason).commit()
    }
    fun waitingForNetwork() {
        prefs.edit().putString("current", "网络连接超时，稍后自动重试")
            .putString("error", "当前歌曲暂时无法连接服务器，已保留原文件。可暂停后继续立即重试。")
            .commit()
        android.util.Log.w("QualityUpgrade", "网络连接未完成，等待重试；未修改当前歌曲")
    }
    fun finish() {
        prefs.edit().putString("status", "finished").putString("current", "").commit()
        android.util.Log.i("QualityUpgrade", "本轮完成：${snapshot()}")
        context.stopService(Intent(context, QualityUpgradeAccessService::class.java))
    }
    private fun fileHash(file: File): ByteArray {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val n = input.read(buffer); if (n == -1) break; hash.update(buffer, 0, n) }
        }
        return hash.digest()
    }
    companion object {
        const val WORK_NAME = "quality_upgrade_all"
        private val recordLock = Any()
    }
}

class QualityUpgradeCompletionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        QualityUpgradeManager(applicationContext).finish()
        return Result.success()
    }
}
