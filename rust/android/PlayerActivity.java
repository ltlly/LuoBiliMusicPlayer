package com.bilimusicplayer;

import android.Manifest;
import android.app.NativeActivity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class PlayerActivity extends NativeActivity {
    static { System.loadLibrary("bili_music_player"); }
    static native void mediaAction(int action, long value);
    private boolean playbackStarted;
    public void playbackChanged(String title, String artist, boolean playing, long position, long duration) {
        if (!playing && !playbackStarted) return;
        if (title.isEmpty()) {
            stopService(new Intent(this, PlaybackService.class));
            playbackStarted = false;
            return;
        }
        Intent update = new Intent(this, PlaybackService.class).putExtra("title", title).putExtra("artist", artist)
            .putExtra("playing", playing).putExtra("position", position).putExtra("duration", duration);
        if (!playbackStarted) startForegroundService(update); else startService(update);
        playbackStarted = true;
    }
    @Override protected void onPause() {
        mediaAction(7, 0);
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        mediaAction(7, 1);
    }
    @Override protected void onDestroy() {
        stopService(new Intent(this, PlaybackService.class));
        super.onDestroy();
    }
    public boolean requestLibraryAccess() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        runOnUiThread(() -> requestPermissions(new String[]{permission}, 2));
        return false;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request==2 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) mediaAction(6,0);
    }

    public String musicDirectory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "BiliMusic").getAbsolutePath();
    }

    public boolean canDownload(boolean wifiOnly) {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager.getActiveNetwork() != null && (!wifiOnly || !manager.isActiveNetworkMetered());
    }

    public void requestMusicAccess() {
        if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1));
        }
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

    // Before scoped storage, Rust can publish the completed file by an atomic rename.
    public String publishTrack(String source, String name) throws IOException {
        if (Build.VERSION.SDK_INT < 29) return "";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BiliMusic/");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        ContentResolver resolver = getContentResolver();
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
