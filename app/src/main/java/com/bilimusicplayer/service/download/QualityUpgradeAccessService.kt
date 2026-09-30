package com.bilimusicplayer.service.download

import android.app.*
import android.content.*
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/** Keeps Android's explicit media write grants valid for the background upgrade. */
class QualityUpgradeAccessService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "音质升级", NotificationManager.IMPORTANCE_LOW))
        startForeground(58, notification("正在检查已有歌曲音质"))
        scope.launch {
            QualityUpgradeManager(this@QualityUpgradeAccessService).state.collect {
                notifications.notify(58, notification("${it.done}/${it.total} 首 · 已升级 ${it.upgraded} 首"))
            }
        }
    }
    private fun notification(text: String): Notification {
        val intent = Intent(this, com.bilimusicplayer.MainActivity::class.java).putExtra("open_quality_upgrade", true)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("最高音质升级")
            .setContentText(text).setOnlyAlertOnce(true).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 58, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_REDELIVER_INTENT
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "quality_upgrade"
        fun start(context: Context, uris: List<Uri>) {
            val intent = Intent(context, QualityUpgradeAccessService::class.java)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri("音频升级", uris.first())
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.startForegroundService(intent)
        }
    }
}
