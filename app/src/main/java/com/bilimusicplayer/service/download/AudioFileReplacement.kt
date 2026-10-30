package com.bilimusicplayer.service.download

import android.content.Context
import android.net.Uri
import com.bilimusicplayer.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Only one replacement at a time. A durable rollback copy survives process death. */
class AudioFileReplacement(private val context: Context) {
    private val prefs = context.getSharedPreferences("audio_replacement_journal", Context.MODE_PRIVATE)
    private val backup = File(context.filesDir, "audio_upgrade_rollback")
    suspend fun recover() = withContext(Dispatchers.IO + NonCancellable) {
        transactionMutex.withLock { restorePending() }
    }
    private suspend fun restorePending() {
        val path = prefs.getString("path", null) ?: return
        val uri = Uri.parse(prefs.getString("uri", null) ?: throw IOException("缺少恢复地址"))
        if (!backup.exists() || backup.length() != prefs.getLong("size", -1)) throw IOException("旧音频恢复副本不完整")
        write(backup, uri)
        val id = prefs.getString("id", null)!!
        AppDatabase.getDatabase().songDao().updateDownloadStatus(id, true, path, backup.length())
        val quality = context.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE).edit()
        quality.putLong("size:$path", backup.length()).putInt("rank:$path", prefs.getInt("rank", 0))
        val oldLabel = prefs.getString("label", null)
        if (oldLabel == null) quality.remove(path) else quality.putString(path, oldLabel)
        quality.commit()
        if (!prefs.edit().clear().commit()) throw IOException("无法提交恢复记录")
        backup.delete()
    }
    suspend fun replace(id: String, original: File, uri: Uri, downloaded: File, commit: suspend () -> Unit) {
        val requestingJob = currentCoroutineContext().job
        withContext(Dispatchers.IO + NonCancellable) {
            transactionMutex.withLock {
                requestingJob.ensureActive()
                restorePending()
                FileOutputStream(backup).use { out -> original.inputStream().use { it.copyTo(out) }; out.fd.sync() }
                if (backup.length() != original.length()) throw IOException("无法备份旧音频")
                val oldLabel = DownloadedAudioInfo(context).label(original.absolutePath, original.length())
                if (!prefs.edit().putString("path", original.absolutePath).putString("uri", uri.toString())
                        .putString("id", id).putLong("size", original.length()).putString("label", oldLabel)
                        .putInt("rank", DownloadedAudioInfo(context).rank(original)).commit()) {
                    throw IOException("无法保存恢复记录")
                }
                try {
                    write(downloaded, uri)
                    if (original.length() != downloaded.length()) throw IOException("替换后音频大小不一致")
                    commit()
                    if (!prefs.edit().clear().commit()) throw IOException("无法完成替换记录")
                    backup.delete()
                } catch (e: Exception) {
                    restorePending()
                    throw e
                }
            }
        }
    }
    private fun write(file: File, uri: Uri) {
        context.contentResolver.openFileDescriptor(uri, "rwt")?.use { descriptor ->
            FileOutputStream(descriptor.fileDescriptor).use { out ->
                file.inputStream().use { it.copyTo(out) }
                out.flush()
                out.fd.sync()
            }
        } ?: throw IOException("无法修改现有音频，请重新授权")
    }
    companion object { val transactionMutex = Mutex() }
}
