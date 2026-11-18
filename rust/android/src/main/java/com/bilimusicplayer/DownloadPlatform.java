package com.bilimusicplayer;

import android.content.*;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.work.*;
import com.bilimusicplayer.service.download.AudioDownloadWorker;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** A background download can start in a fresh process before any Activity exists. */
public final class DownloadPlatform {
    private final Context context;
    DownloadPlatform(Context app) { context = app; bind(); }
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
