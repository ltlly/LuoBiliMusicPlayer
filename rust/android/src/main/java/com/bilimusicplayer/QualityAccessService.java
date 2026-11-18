package com.bilimusicplayer;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.IBinder;
import java.util.List;

/** Android ties temporary MediaStore write grants to these service intents. */
public final class QualityAccessService extends Service {
    private static final String CHANNEL = "quality_upgrade";
    static void start(Context app, List<Uri> grants) {
        // Keep each binder transaction smaller than the system's Intent size limit.
        for (int i = 0; i < Math.max(grants.size(), 1); i += 250) {
            Intent intent = new Intent(app, QualityAccessService.class);
            if (i < grants.size()) {
                ClipData clip = ClipData.newRawUri("音频升级", grants.get(i));
                for (int j = i + 1; j < Math.min(i + 250, grants.size()); j++) clip.addItem(new ClipData.Item(grants.get(j)));
                intent.setClipData(clip);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            }
            app.startForegroundService(intent);
        }
    }
    private SharedPreferences progress;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if ("done".equals(key)) getSystemService(NotificationManager.class).notify(58, notification());
    };
    private Notification notification() {
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        open.putExtra("open_quality_upgrade", true);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("最高音质升级").setOnlyAlertOnce(true).setOngoing(true)
                .setContentText(progress.getInt("done", 0) + " / " + progress.getInt("total", 0) + " 首")
                .setContentIntent(PendingIntent.getActivity(this, 58, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build();
    }
    @Override public void onCreate() {
        super.onCreate();
        progress = getSharedPreferences("quality_upgrade", MODE_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "音质升级", NotificationManager.IMPORTANCE_LOW));
        startForeground(58, notification());
        progress.registerOnSharedPreferenceChangeListener(listener);
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) { return START_REDELIVER_INTENT; }
    @Override public void onTimeout(int startId, int type) { QualityTasks.get(this).permission(); stopSelf(); }
    @Override public void onDestroy() { progress.unregisterOnSharedPreferenceChangeListener(listener); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
