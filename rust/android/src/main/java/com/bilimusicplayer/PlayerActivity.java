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
        QualityTasks.get(this);
        removal = new LibraryRemoval(this);
        freshLaunch = state == null;
        super.onCreate(state);
        upgradeMode = state == null ? null : state.getString("upgradeMode");
        requestingUpgrade = upgradeMode != null;
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
        quality().prefs.registerOnSharedPreferenceChangeListener(progressListener);
        mediaAction(7, 1);
        openFromNotification(getIntent());
        removal.recover(freshLaunch);
        freshLaunch = false;
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openFromNotification(intent);
    }
    private void openFromNotification(Intent intent) {
        if (intent != null && intent.getBooleanExtra("open_quality_upgrade", false)) {
            intent.removeExtra("open_quality_upgrade");
            mediaAction(12, 0);
        }
    }
    @Override protected void onDestroy() {
        quality().prefs.unregisterOnSharedPreferenceChangeListener(progressListener);
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
        if (request == UPGRADE_ACCESS) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) prepareUpgrade();
            else finishGrant(false);
        }
        if (request==2 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) mediaAction(6,0);
    }

    private LibraryRemoval removal;
    private boolean freshLaunch;
    public void requestMediaRemoval(String plan) throws Exception { removal.begin(plan); }
    public boolean mediaMissing(String uri) { return removal.missing(uri); }
    public String removalPlan() { return removal.plan(); }
    public void clearRemoval() { removal.clear(); }
    private static final int RESTORE_AUDIO = 41;
    private static final int UPGRADE_ACCESS = 42;
    private QualityTasks quality() { return QualityTasks.get(this); }
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener progressListener =
            (prefs, key) -> { if ("status".equals(key) || "error".equals(key) || "done".equals(key) || "current".equals(key)) mediaAction(10, 0); };
    private String upgradeMode;
    private boolean requestingUpgrade;
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("upgradeMode", upgradeMode);
    }
    public void qualityControl(String mode) {
        runOnUiThread(() -> {
            if ("pause".equals(mode)) { quality().pause(); return; }
            if (requestingUpgrade) return;
            requestingUpgrade = true;
            upgradeMode = mode;
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_AUDIO}, UPGRADE_ACCESS);
            } else prepareUpgrade();
        });
    }
    private void prepareUpgrade() {
        QualityTasks.EXEC.execute(() -> {
            try {
                java.util.List<Uri> needed = quality().prepareUris();
                runOnUiThread(() -> {
                    try {
                        if (needed.isEmpty()) { finishGrant(true); return; }
                        // Android 16 limits a write request to 2,000 distinct media items.
                        startIntentSenderForResult(MediaStore.createWriteRequest(getContentResolver(),
                                needed.subList(0, Math.min(2000, needed.size()))).getIntentSender(),
                                UPGRADE_ACCESS, null, 0, 0, 0);
                    } catch (Exception e) { requestingUpgrade = false; quality().error(e); }
                });
            } catch (Exception e) { runOnUiThread(() -> requestingUpgrade = false); quality().error(e); }
        });
    }
    private void finishGrant(boolean accepted) {
        requestingUpgrade = false;
        String mode = upgradeMode;
        upgradeMode = null;
        if (accepted) quality().start("retry".equals(mode)); else quality().permission();
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
        if (requestCode == LibraryRemoval.REQUEST) removal.result(resultCode == RESULT_OK);
        if (requestCode == UPGRADE_ACCESS) {
            if (resultCode == RESULT_OK) prepareUpgrade(); else finishGrant(false);
        }
        if (requestCode == RESTORE_AUDIO) mediaAction(8, resultCode == RESULT_OK ? 1 : 0);
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
