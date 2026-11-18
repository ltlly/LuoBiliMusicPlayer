package com.bilimusicplayer;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.work.*;
import com.bilimusicplayer.service.download.QualityUpgradeWorker;
import com.bilimusicplayer.service.download.QualityUpgradeCompletionWorker;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Android owns durable scheduling, MediaStore grants and the legacy SharedPreferences ledger. */
public final class QualityTasks {
    static { System.loadLibrary("asuka"); }
    static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final String NAME = "quality_upgrade_all";
    private static QualityTasks instance;
    public static synchronized QualityTasks get(Context context) {
        if (instance == null) instance = new QualityTasks(context.getApplicationContext());
        return instance;
    }
    final SharedPreferences prefs;
    private final Context context;
    private final LegacyAudioReplacement journal;
    private final WorkManager work;
    private final Map<String, Long> labelSizes = new HashMap<>();
    private android.os.FileObserver labelObserver;
    private File labelDirectory;

    private synchronized void resetLabels() {
        labelSizes.clear();
        if (labelObserver != null) labelObserver.stopWatching();
        labelObserver = null;
        labelDirectory = null;
    }

    private synchronized JSONObject qualityLabels(JSONObject args) throws Exception {
        File dir = new File(args.getString("dir"));
        if (!dir.equals(labelDirectory)) {
            resetLabels();
            if (dir.isDirectory() && dir.canRead()) {
                final int changes = android.os.FileObserver.CREATE | android.os.FileObserver.MODIFY
                        | android.os.FileObserver.CLOSE_WRITE | android.os.FileObserver.DELETE | android.os.FileObserver.MOVED_FROM
                        | android.os.FileObserver.MOVED_TO | android.os.FileObserver.DELETE_SELF | android.os.FileObserver.MOVE_SELF;
                labelObserver = new android.os.FileObserver(dir, changes) {
                    @Override public void onEvent(int event, String name) {
                        synchronized (QualityTasks.this) {
                            if (labelObserver != this) return;
                            if (name == null) resetLabels();
                            else labelSizes.remove(name);
                        }
                    }
                };
                labelObserver.startWatching();
                labelDirectory = dir;
            }
        }
        JSONArray paths = args.getJSONArray("paths");
        SharedPreferences meta = context.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE);
        JSONObject labels = new JSONObject();
        for (int i = 0; i < paths.length(); i++) {
            String path = paths.getString(i);
            if (!meta.contains(path)) continue;
            File file = new File(path);
            boolean watched = labelDirectory != null && labelDirectory.equals(file.getParentFile());
            String name = file.getName();
            Long size = watched ? labelSizes.get(name) : null;
            if (size == null) {
                size = file.length();
                if (watched) labelSizes.put(name, size);
            }
            if (size > 0 && size == meta.getLong("size:" + path, -1))
                labels.put(path, meta.getString(path, ""));
        }
        return labels;
    }

    private QualityTasks(Context app) {
        context = app;
        prefs = app.getSharedPreferences("quality_upgrade", Context.MODE_PRIVATE);
        journal = new LegacyAudioReplacement(app);
        work = WorkManager.getInstance(app);
        bind(app.getFilesDir().getAbsolutePath());
        new PlayerPlatform(app);
    }
    private native void bind(String dir);
    private native String request(String request) throws IOException;
    public Object call(String op, JSONObject args) throws Exception {
        return new JSONTokener(request(args.put("op", op).toString())).nextValue();
    }
    public Object call(String op) throws Exception { return call(op, new JSONObject()); }

    public String perform(String op, String raw) throws Exception {
        if ("state".equals(op)) {
            String status = prefs.getString("status", "idle");
            int failed = prefs.getInt("failed", 0);
            StringBuilder summary = new StringBuilder();
            if (!"idle".equals(status)) summary.append(prefs.getInt("done", 0)).append(" / ")
                    .append(prefs.getInt("total", 0)).append(" 首 · 已升级 ").append(prefs.getInt("upgraded", 0))
                    .append(" · 保留 ").append(prefs.getInt("unchanged", 0)).append(" · 失败 ").append(failed);
            for (String key : new String[]{"current", "error"}) {
                String detail = prefs.getString(key, "");
                if (!detail.isEmpty()) { if (summary.length() > 0) summary.append("\n"); summary.append(detail); }
            }
            JSONObject errors = new JSONObject();
            if (failed > 0) {
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    String key = entry.getKey();
                    if (key.startsWith("result:") && "failed".equals(entry.getValue())) {
                        String id = key.substring(7);
                        errors.put(id, prefs.getString("reason:" + id, "无法升级"));
                    }
                }
            }
            return new JSONObject().put("errors", errors).put("status", status).put("failed", failed).put("summary", summary.toString()).toString();
        }
        if ("pending".equals(op)) { String pending = journal.pending(); return pending.isEmpty() ? "null" : pending; }
        if ("resetLabels".equals(op)) { resetLabels(); return "null"; }
        if ("labels".equals(op)) return qualityLabels(new JSONObject(raw)).toString();
        JSONObject args = new JSONObject(raw);
        if ("saveQuality".equals(op)) {
            saveQuality(new File(args.getString("path")), args.getString("label"), args.getInt("rank"));
            return "null";
        }
        String id = args.getString("id"), path = args.getString("path");
        switch (op) {
            case "restore": journal.restore(id, path); break;
            case "finishRestore": journal.finish(id, path); break;
            case "replace": if (!running()) throw new IOException("音质升级已暂停");
                journal.replace(id, new File(path), Uri.parse(args.getString("uri")), new File(args.getString("stage"))); break;
            case "finishReplace": journal.finishUpgrade(id, path, args.getString("label"), args.getInt("rank")); break;
            default: throw new IllegalArgumentException(op);
        }
        return "null";
    }

    private boolean hasWrite(Uri uri, String path) {
        return new File(path).canWrite() || context.checkUriPermission(uri, android.os.Process.myPid(),
                android.os.Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
    List<Uri> prepareUris() throws Exception {
        JSONArray songs = (JSONArray)call("list");
        Set<String> paths = new HashSet<>();
        for (int i = 0; i < songs.length(); i++) paths.add(songs.getJSONObject(i).getString("path"));
        Uri media = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : prefs.getAll().keySet()) if (key.startsWith("uri:")) edit.remove(key);
        List<Uri> needed = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(media, new String[]{"_id", "_data"}, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext()) {
                String path = cursor.getString(1);
                if (!paths.contains(path)) continue;
                Uri uri = ContentUris.withAppendedId(media, cursor.getLong(0));
                edit.putString("uri:" + path, uri.toString());
                if (!hasWrite(uri, path)) needed.add(uri);
            }
        }
        if (!edit.commit()) throw new IOException("无法保存媒体库地址");
        return needed;
    }
    void start(boolean retry) {
        EXEC.execute(() -> {
            try {
                if (running()) return;
                JSONObject settings = (JSONObject)call("start");
                call("recover");
                JSONArray songs = (JSONArray)call("list");
                String status = prefs.getString("status", "idle");
                SharedPreferences.Editor edit = prefs.edit();
                boolean continuing = "permission".equals(status) || "paused".equals(status);
                if (!retry && !continuing) {
                    for (String key : prefs.getAll().keySet()) if (!key.startsWith("uri:")) edit.remove(key);
                    edit.putInt("total", songs.length());
                } else if (retry) {
                    int retried = 0;
                    for (int i = 0; i < songs.length(); i++) {
                        String id = songs.getJSONObject(i).getString("id");
                        if ("failed".equals(outcome(id))) { edit.remove("result:" + id).remove("reason:" + id); retried++; }
                    }
                    edit.putInt("failed", Math.max(0, prefs.getInt("failed", 0) - retried));
                    edit.putInt("done", Math.max(0, prefs.getInt("done", 0) - retried));
                } else if (prefs.getInt("total", 0) == 0) edit.putInt("total", songs.length());
                if (!edit.commit()) throw new IOException("无法保存升级进度");
                Constraints constraints = new Constraints.Builder().setRequiredNetworkType(
                        settings.getBoolean("wifiOnly") ? NetworkType.UNMETERED : NetworkType.CONNECTED).build();
                WorkContinuation chain = null;
                ArrayList<Uri> grants = new ArrayList<>();
                for (int i = 0; i < songs.length(); i++) {
                    JSONObject song = songs.getJSONObject(i);
                    String id = song.getString("id");
                    if (outcome(id) != null) continue;
                    String uri = prefs.getString("uri:" + song.getString("path"), null);
                    if (uri != null) grants.add(Uri.parse(uri));
                    OneTimeWorkRequest job = new OneTimeWorkRequest.Builder(QualityUpgradeWorker.class)
                            .setInputData(new Data.Builder().putString("song_id", id).build())
                            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                            .addTag(NAME).build();
                    chain = chain == null ? work.beginUniqueWork(NAME, ExistingWorkPolicy.REPLACE, job) : chain.then(job);
                }
                if (chain == null) { finish(); return; }
                QualityAccessService.start(context, grants);
                prefs.edit().putString("status", "running").putString("error", "").commit();
                chain.then(new OneTimeWorkRequest.Builder(QualityUpgradeCompletionWorker.class).build()).enqueue().getResult().get();
            } catch (Exception e) {
                if (running()) prefs.edit().putString("status", "paused").commit();
                context.stopService(new Intent(context, QualityAccessService.class));
                error(e);
            }
        });
    }
    void pause() {
        EXEC.execute(() -> {
            prefs.edit().putString("status", "paused").commit();
            try { work.cancelUniqueWork(NAME).getResult().get(); } catch (Exception e) { error(e); }
            // Queue an actor barrier before revoking the URI grant of an in-flight replacement.
            try { call("list"); } catch (Exception e) { error(e); }
            context.stopService(new Intent(context, QualityAccessService.class));
        });
    }
    public boolean running() { return "running".equals(prefs.getString("status", "idle")); }
    public String outcome(String id) { return prefs.getString("result:" + id, null); }
    public synchronized void record(String id, String result, String reason) {
        if (outcome(id) != null) return;
        prefs.edit().putString("result:" + id, result).putString("reason:" + id, reason)
                .putInt("done", prefs.getInt("done", 0) + 1).putInt(result, prefs.getInt(result, 0) + 1)
                .putString("error", reason).commit();
    }
    public void current(String title) { prefs.edit().putString("current", title).apply(); }
    public void error(Exception error) { prefs.edit().putString("error", Objects.toString(error.getMessage(), "音质升级失败")).commit(); }
    public void permission() { prefs.edit().putString("status", "permission").putString("error", "需要授权修改已有音频，请点继续升级").commit(); }
    public void finish() {
        prefs.edit().putString("status", "finished").putString("current", "").commit();
        context.stopService(new Intent(context, QualityAccessService.class));
    }
    public Uri writable(String path) throws IOException {
        String value = prefs.getString("uri:" + path, null);
        if (value == null) throw new IOException("系统媒体库未收录此文件，已保留原文件");
        Uri uri = Uri.parse(value);
        if (!hasWrite(uri, path)) throw new SecurityException("缺少音频写入授权");
        return uri;
    }
    public int savedRank(File file) {
        SharedPreferences meta = context.getSharedPreferences("download_audio_quality", Context.MODE_PRIVATE);
        return meta.getLong("size:" + file, -1) == file.length() ? meta.getInt("rank:" + file, 0) : 0;
    }
    public void saveQuality(File original, String label, int rank) throws IOException {
        LegacyAudioReplacement.saveQuality(context, original, label, rank);
    }
}
