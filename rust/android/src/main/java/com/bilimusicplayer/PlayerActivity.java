package com.bilimusicplayer;

import android.Manifest;
import android.animation.ValueAnimator;
import android.os.Bundle;
import dev.asuka.AsukaActivity;
import android.content.Intent;
import android.content.pm.PackageManager;


import android.net.Uri;
import java.util.Collections;
import android.provider.MediaStore;
import java.io.File;

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
        getSplashScreen().setOnExitAnimationListener(splash -> {
            if (ValueAnimator.areAnimatorsEnabled()) {
                splash.animate().alpha(0f).setDuration(100).withEndAction(splash::remove).start();
            } else {
                splash.remove();
            }
        });
        upgradeMode = state == null ? null : state.getString("upgradeMode");
        requestingUpgrade = upgradeMode != null;
    }
    static native void mediaAction(int action, long value);
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
        super.onDestroy();
        unbindActivity();
    }
    public void openVideo(String bv) {
        if (!bv.matches("BV1[A-Za-z0-9]{9}")) return;
        runOnUiThread(() -> {
            Uri page = Uri.parse("https://www.bilibili.com/video/" + bv + "/");
            for (String app : new String[]{"tv.danmaku.bili", "tv.danmaku.bilibilihd", "com.bilibili.app.in"}) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, page).setPackage(app)); return; }
                catch (android.content.ActivityNotFoundException ignored) { }
            }
            try { startActivity(new Intent(Intent.ACTION_VIEW, page)); }
            catch (android.content.ActivityNotFoundException e) {
                android.widget.Toast.makeText(this, "未找到可打开视频的应用", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
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
    public boolean deleteOwnedMedia(String path) { return removal.deleteOwned(path); }
    public String mediaAliases(String path) { return removal.aliases(path); }
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
}
