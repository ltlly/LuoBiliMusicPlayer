package com.bilimusicplayer;

import android.Manifest;
import android.os.Bundle;
import dev.asuka.AsukaActivity;
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
import java.util.Collections;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class PlayerActivity extends AsukaActivity {
    static { System.loadLibrary("asuka"); }
    private native void bindActivity();
    private native void unbindActivity();
    @Override protected void onCreate(Bundle state) {
        bindActivity();
        super.onCreate(state);
    }
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
        unbindActivity();
    }
    public boolean requestLibraryAccess() {
        String permission = Manifest.permission.READ_MEDIA_AUDIO;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        runOnUiThread(() -> requestPermissions(new String[]{permission}, 2));
        return false;
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request==2 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) mediaAction(6,0);
    }

    private static final int RESTORE_AUDIO = 41;
    public String pendingReplacement() throws org.json.JSONException {
        return new LegacyAudioReplacement(this).pending();
    }
    public void requestReplacementAccess(String path, String address) {
        Uri uri = Uri.parse(address);
        if (new File(path).canWrite()
                || checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(),
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            mediaAction(8, 1);
            return;
        }
        runOnUiThread(() -> {
            try {
                startIntentSenderForResult(MediaStore.createWriteRequest(getContentResolver(),
                        Collections.singletonList(uri)).getIntentSender(), RESTORE_AUDIO, null, 0, 0, 0);
            } catch (Exception ex) {
                mediaAction(8, 0);
            }
        });
    }
    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == RESTORE_AUDIO) mediaAction(8, resultCode == RESULT_OK ? 1 : 0);
    }
    public void restoreReplacement(String id, String path) throws IOException {
        new LegacyAudioReplacement(this).restore(id, path);
    }
    public void finishReplacement(String id, String path) throws IOException {
        new LegacyAudioReplacement(this).finish(id, path);
    }

    public String musicDirectory() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                        "BiliMusic").getAbsolutePath();
    }

    public boolean canDownload(boolean wifiOnly) {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager.getActiveNetwork() != null && (!wifiOnly || !manager.isActiveNetworkMetered());
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

    public String publishTrack(String source, String name) throws IOException {
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
