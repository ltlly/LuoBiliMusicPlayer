package com.bilimusicplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;
import org.json.JSONException;
import org.json.JSONObject;

/** The previous release wrote this journal before opening an existing audio file for writing. */
final class LegacyAudioReplacement {
    private final Context context;
    private final SharedPreferences journal;
    private final File backup;

    LegacyAudioReplacement(Context context) {
        this.context = context;
        journal = context.getSharedPreferences("audio_replacement_journal", Context.MODE_PRIVATE);
        backup = new File(context.getFilesDir(), "audio_upgrade_rollback");
    }

    String pending() throws JSONException {
        String path = journal.getString("path", null);
        if (path == null) return "";
        return new JSONObject().put("path", path).put("id", journal.getString("id", ""))
            .put("size", journal.getLong("size", -1)).put("uri", journal.getString("uri", "")).toString();
    }

    private Uri check(String id, String path) throws IOException {
        if (!Objects.equals(id, journal.getString("id", null))
                || !Objects.equals(path, journal.getString("path", null))) {
            throw new IOException("音频恢复记录发生变化");
        }
        String uri = journal.getString("uri", null);
        if (uri == null || uri.isEmpty()) throw new IOException("恢复记录缺少音频地址");
        if (!backup.isFile() || backup.length() <= 0 || backup.length() != journal.getLong("size", -1)) {
            throw new IOException("旧音频恢复副本不完整");
        }
        return Uri.parse(uri);
    }

    void restore(String id, String path) throws IOException {
        Uri uri = check(id, path);
        // Open the rollback source first so a missing source cannot truncate the destination.
        try (FileInputStream input = new FileInputStream(backup)) {
            ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rwt");
            if (descriptor == null) throw new IOException("无法打开需要恢复的音频");
            try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                byte[] buffer = new byte[64 * 1024];
                long written = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    written += count;
                }
                output.flush();
                output.getFD().sync();
                if (written != backup.length()) throw new IOException("旧音频尚未完整恢复");
            }
        }
    }

    // Rust commits the song's location and byte count before acknowledging this journal.
    void finish(String id, String path) throws IOException {
        check(id, path);
        SharedPreferences.Editor quality = context.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE).edit();
        quality.putLong("size:" + path, backup.length()).putInt("rank:" + path, journal.getInt("rank", 0));
        String label = journal.getString("label", null);
        if (label == null) quality.remove(path); else quality.putString(path, label);
        if (!quality.commit() || !journal.edit().clear().commit()) throw new IOException("无法提交音频恢复记录");
        backup.delete();
    }
}
