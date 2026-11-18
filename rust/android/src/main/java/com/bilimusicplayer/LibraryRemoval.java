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
        LinkedHashSet<String> uris = new LinkedHashSet<>();
        JSONArray rows = new JSONArray(plan);
        // Look up only the requested audio items, including in large media libraries.
        for (int i = 0; i < rows.length(); i++) {
            AudioRow row = lookup(rows.getJSONObject(i).optString("path"));
            if (row != null) uris.add(row.uri.toString());
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
    private record AudioRow(Uri uri, String path, String owner) {}

    private AudioRow lookup(String raw) {
        Uri base = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        Uri uri = Uri.parse(raw);
        String[] args = null;
        if (raw.startsWith("/") || "file".equals(uri.getScheme())) {
            args = new String[]{"file".equals(uri.getScheme()) ? uri.getPath() : raw};
            uri = base;
        } else if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())
                || uri.getPath() == null || !uri.getPath().matches("/[^/]+/audio/media/[0-9]+")) return null;
        try (Cursor c = activity.getContentResolver().query(uri,
                new String[]{"_id", "_data", "volume_name", MediaStore.MediaColumns.OWNER_PACKAGE_NAME},
                args == null ? null : "_data=?", args, null)) {
            if (c == null || !c.moveToFirst()) return null;
            Uri item = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(c.getString(2)), c.getLong(0));
            return new AudioRow(item, c.getString(1), c.getString(3));
        }
    }
    String aliases(String raw) {
        AudioRow row = lookup(raw);
        if (row == null) return "[]";
        JSONArray names = new JSONArray();
        if (row.path != null) names.put(row.path);
        names.put(row.uri.toString());
        names.put(ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(row.uri)).toString());
        return names.toString();
    }
    boolean deleteOwned(String raw) {
        AudioRow row = lookup(raw);
        return row != null && activity.getPackageName().equals(row.owner) &&
                activity.getContentResolver().delete(row.uri, null, null) == 1;
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
