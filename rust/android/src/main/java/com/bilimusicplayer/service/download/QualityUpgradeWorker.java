package com.bilimusicplayer.service.download;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.bilimusicplayer.QualityTasks;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import org.json.JSONObject;

public final class QualityUpgradeWorker extends Worker {
    public QualityUpgradeWorker(Context context, WorkerParameters params) { super(context, params); }
    private native boolean fetch(String urls, String original, String stage) throws IOException;

    @Override public Result doWork() {
        String id = getInputData().getString("song_id");
        QualityTasks tasks = QualityTasks.get(getApplicationContext());
        if (id == null || tasks.outcome(id) != null) return Result.success();
        if (!tasks.running()) return Result.retry();
        File stage = new File(getApplicationContext().getCacheDir(), "quality-" + getId() + ".part");
        boolean slot = false;
        try {
            tasks.call("recover");
            JSONObject arg = new JSONObject().put("id", id);
            JSONObject song = (JSONObject)tasks.call("song", arg);
            tasks.current(song.getString("title"));
            File original = new File(song.getString("path"));
            if (!original.isFile() || original.length() == 0) throw new IOException("本地音频不存在，已保留歌曲记录");
            if (!song.getString("bvid").startsWith("BV")) throw new IOException("缺少 B 站视频编号，已保留原文件");
            Uri uri = tasks.writable(original.getPath());
            while (!isStopped() && tasks.running()) {
                if (Boolean.TRUE.equals(tasks.call("slot"))) { slot = true; break; }
                Thread.sleep(300);
            }
            if (!slot) return Result.retry();
            JSONObject stream = (JSONObject)tasks.call("resolve", arg);
            applyStream(tasks, id, original, uri, stage, stream);
            return Result.success();
        } catch (Exception e) {
            if (isStopped() || !tasks.running()) return Result.retry();
            String message = String.valueOf(e.getMessage());
            if (e instanceof SecurityException || message.contains("SecurityException")) {
                tasks.permission();
                return Result.retry();
            }
            String lower = message.toLowerCase(Locale.ROOT);
            boolean transientNetwork = lower.contains("timed out") || lower.contains("time out") || lower.contains("timeout")
                    || lower.contains("connect") || lower.contains("dns") || lower.contains("host") || lower.contains("resolve address");
            if (message.contains("签名") || message.contains("冷却") || (transientNetwork && getRunAttemptCount() < 3)) {
                tasks.error(e);
                return Result.retry();
            }
            tasks.record(id, "failed", message);
            return Result.success();
        } finally {
            stage.delete();
            if (slot) try { tasks.call("release"); } catch (Exception e) { tasks.error(e); }
        }
    }
    void applyStream(QualityTasks tasks, String id, File original, Uri uri, File stage, JSONObject stream) throws Exception {
        int rank = stream.getInt("rank");
        long bandwidth = stream.getLong("bandwidth");
        String label = rank == 5 ? "Hi-Res 无损 · FLAC" : rank == 4 ? "杜比音频 · E-AC-3"
                : bandwidth > 0 ? "AAC · " + (bandwidth / 1000) + " kbps" : "AAC";
        long[] existing;
        try { existing = probe(original); } catch (Exception e) { existing = new long[]{0, 0}; }
        long oldRank = Math.max(existing[0], tasks.savedRank(original));
        if (oldRank > rank || (oldRank <= 3 && rank <= 3 && bandwidth > 0 && existing[1] > bandwidth * 1.15)) {
            tasks.record(id, "unchanged", "现有音质高于可获取音轨，已保留");
            return;
        }
        boolean same = fetch(stream.getJSONArray("urls").toString(), original.getPath(), stage.getPath());
        if (isStopped() || !tasks.running()) throw new InterruptedException("升级已暂停");
        if (same) {
            tasks.saveQuality(original, label, rank);
            tasks.record(id, "unchanged", "");
        } else {
            probe(stage);
            JSONObject replacement = new JSONObject().put("id", id).put("path", original.getPath())
                    .put("uri", uri.toString()).put("stage", stage.getPath()).put("rank", rank).put("label", label);
            tasks.call("replace", replacement);
            tasks.record(id, "upgraded", "");
        }
    }
    private static long[] probe(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME, "");
                if (!mime.startsWith("audio/")) continue;
                long us = format.getLong(MediaFormat.KEY_DURATION, 0);
                long rate = us > 0 ? file.length() * 8_000_000L / us : 0;
                return new long[]{mime.contains("flac") ? 5 : mime.contains("eac3") ? 4
                        : rate > 192000 ? 3 : rate > 96000 ? 2 : rate > 0 ? 1 : 0, rate};
            }
            throw new IOException("文件不含音轨，已保留原文件");
        } finally { extractor.release(); }
    }
}
