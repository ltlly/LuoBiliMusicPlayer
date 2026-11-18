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
import java.io.InputStream;
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
        write(backup, uri);
    }

    private void write(File source, Uri uri) throws IOException {
        // Open the rollback source first so a missing source cannot truncate the destination.
        try (FileInputStream input = new FileInputStream(source)) {
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
                if (written != source.length()) throw new IOException("旧音频尚未完整恢复");
            }
        }
    }

    void replace(String id, File original, Uri uri, File stage) throws IOException {
        if (journal.contains("path")) throw new IOException("有待恢复的旧音频");
        if (!stage.isFile() || stage.length() <= 0 || !original.isFile() || original.length() <= 0)
            throw new IOException("音频文件不完整");
        final long size = original.length();
        try (InputStream in = new FileInputStream(original); FileOutputStream out = new FileOutputStream(backup)) {
            in.transferTo(out);
            out.getFD().sync();
        }
        if (backup.length() != size) throw new IOException("无法完整备份音频");
        SharedPreferences meta = context.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE);
        boolean verified = meta.getLong("size:" + original, -1) == size;
        if (!journal.edit().putString("path", original.getPath()).putString("uri", uri.toString())
                .putString("id", id).putLong("size", size)
                .putString("label", verified ? meta.getString(original.getPath(), null) : null)
                .putInt("rank", verified ? meta.getInt("rank:" + original, 0) : 0).commit())
            throw new IOException("无法写入音频恢复记录");
        write(stage, uri);
        if (original.length() != stage.length()) throw new IOException("替换后音频大小不一致");
    }

    void finishUpgrade(String id, String path, String label, int rank) throws IOException {
        check(id, path);
        saveQuality(context, new File(path), label, rank);
        if (!journal.edit().clear().commit()) throw new IOException("无法提交音频替换");
        backup.delete();
    }

    static void saveQuality(Context context, File file, String label, int rank) throws IOException {
        if (!context.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE).edit()
                .putString(file.getPath(), label).putLong("size:" + file, file.length())
                .putInt("rank:" + file, rank).commit())
            throw new IOException("无法保存歌曲音质");
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
