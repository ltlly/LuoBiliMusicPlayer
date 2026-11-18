package com.bilimusicplayer;

import android.content.*;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.work.*;
import com.bilimusicplayer.service.download.AudioDownloadWorker;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** Application-lifetime services for media playback and background downloads. */
public final class PlayerPlatform {
    private final Context context;
    PlayerPlatform(Context app) { context = app; bind(); }
    private native void bind();
    public static native long pump() throws IOException;
    public void schedule(boolean wifiOnly, boolean append) throws Exception {
        Constraints network = new Constraints.Builder().setRequiredNetworkType(
                wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AudioDownloadWorker.class)
                .setConstraints(network).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag("audio_download").build();
        WorkManager.getInstance(context).enqueueUniqueWork("rust_audio_downloads",
                append ? ExistingWorkPolicy.APPEND_OR_REPLACE : ExistingWorkPolicy.KEEP, request).getResult().get();
    }

    private static volatile boolean playbackStarted;
    static void serviceStopped() { playbackStarted = false; }
    public Context getApplicationContext() { return context; }
    public void playbackChanged(String title, String artist, boolean playing, long position, long duration) {
        if (!playing && !playbackStarted) return;
        if (title.isEmpty()) {
            context.stopService(new Intent(context, PlaybackService.class));
            playbackStarted = false;
            return;
        }
        Intent update = new Intent(context, PlaybackService.class).putExtra("title", title).putExtra("artist", artist)
            .putExtra("playing", playing).putExtra("position", position).putExtra("duration", duration);
        if (!playbackStarted) context.startForegroundService(update); else context.startService(update);
        playbackStarted = true;
    }
    public boolean supportsDolby() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if ("audio/eac3".equalsIgnoreCase(type) || "audio/eac3-joc".equalsIgnoreCase(type)) return true;
            }
        }
        return false;
    }

    public String musicDirectory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "BiliMusic").getAbsolutePath();
    }

    public boolean canDownload(boolean wifiOnly) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager.getActiveNetwork() != null && (!wifiOnly || !manager.isActiveNetworkMetered());
    }

    public String publishTrack(String source, String name) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BiliMusic/");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri pending = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (pending == null) throw new IOException("无法创建音频文件");
        boolean published = false;
        try {
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(pending, "w")) {
                if (out == null) throw new IOException("无法写入音频文件");
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            values.clear();
            values.put(MediaStore.Audio.Media.IS_PENDING, 0);
            if (resolver.update(pending, values, null, null) != 1) throw new IOException("无法发布音频文件");
            published = true;
            try (Cursor row = resolver.query(pending, new String[]{MediaStore.Audio.Media.DATA}, null, null, null)) {
                if (row != null && row.moveToFirst() && row.getString(0) != null) {
                    File file = new File(row.getString(0));
                    if (file.isFile() && file.length() == new File(source).length()) return file.getAbsolutePath();
                }
            }
            return pending.toString();
        } finally {
            if (!published) resolver.delete(pending, null, null);
        }
    }
}
