package com.bilimusicplayer;

import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Retain song identities until the media provider confirms which files it removed. */
final class LibraryRemoval {
    static final int REQUEST = 43;
    private final PlayerActivity activity;
    private final SharedPreferences prefs;
    LibraryRemoval(PlayerActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("pending_library_removal", 0);
    }
    String plan() { return prefs.getString("plan", "[]"); }
    void clear() { prefs.edit().clear().commit(); }
    void begin(String plan) throws Exception {
        if (prefs.contains("plan")) throw new IOException("上一批文件仍在等待系统确认");
        Set<String> paths = new HashSet<>();
        LinkedHashSet<String> uris = new LinkedHashSet<>();
        JSONArray rows = new JSONArray(plan);
        for (int i = 0; i < rows.length(); i++) {
            String path = rows.getJSONObject(i).optString("path");
            Uri uri = Uri.parse(path);
            if ("content".equals(uri.getScheme())) {
                if ("media".equals(uri.getAuthority())) uris.add(uri.toString());
            } else paths.add(path.startsWith("file://") ? uri.getPath() : path);
        }
        if (!paths.isEmpty()) {
            Uri audio = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            try (Cursor c = activity.getContentResolver().query(audio, new String[]{"_id","_data"}, null, null, null)) {
                if (c != null) while (c.moveToNext())
                    if (paths.contains(c.getString(1))) uris.add(ContentUris.withAppendedId(audio, c.getLong(0)).toString());
            }
        }
        if (uris.isEmpty()) throw new IOException("文件无法删除，已保留歌曲记录");
        if (!prefs.edit().putString("plan", plan).putString("uris", new JSONArray(uris).toString()).commit())
            throw new IOException("无法保存待确认的歌曲");
        activity.runOnUiThread(this::next);
    }
    private void next() {
        try {
            JSONArray uris = new JSONArray(prefs.getString("uris", "[]"));
            int pos = prefs.getInt("position", 0);
            if (pos >= uris.length()) { finish(true); return; }
            ArrayList<Uri> batch = new ArrayList<>();
            for (int i = pos; i < Math.min(uris.length(), pos + 250); i++) batch.add(Uri.parse(uris.getString(i)));
            if (!prefs.edit().putInt("next", pos + batch.size()).commit()) throw new IOException("无法保存授权进度");
            activity.startIntentSenderForResult(MediaStore.createDeleteRequest(activity.getContentResolver(), batch)
                    .getIntentSender(), REQUEST, null, 0, 0, 0);
        } catch (Exception e) { finish(false); }
    }
    void result(boolean accepted) {
        if (!accepted) { finish(false); return; }
        if (!prefs.edit().putInt("position", prefs.getInt("next", 0)).commit()) { finish(false); return; }
        next();
    }
    private void finish(boolean accepted) {
        prefs.edit().putBoolean("returned", true).commit();
        PlayerActivity.mediaAction(11, accepted ? 1 : 0);
    }
    void recover(boolean freshLaunch) {
        if (prefs.contains("plan") && (freshLaunch || prefs.getBoolean("returned", false))) finish(false);
    }
    boolean missing(String raw) {
        Uri uri = Uri.parse(raw);
        if (activity.checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        String selection = null;
        String[] args = null;
        if (raw.startsWith("/")) {
            uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            selection = "_data=?";
            args = new String[]{raw};
        } else if (!"media".equals(uri.getAuthority())) return false;
        try (Cursor c = activity.getContentResolver().query(uri, new String[]{"_id"}, selection, args, null)) {
            return c != null && !c.moveToFirst();
        } catch (RuntimeException e) { return false; }
    }
}
