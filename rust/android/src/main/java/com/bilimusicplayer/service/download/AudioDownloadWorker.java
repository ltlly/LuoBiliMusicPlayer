package com.bilimusicplayer.service.download;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.bilimusicplayer.DownloadPlatform;
import com.bilimusicplayer.QualityTasks;
import java.io.IOException;

/** Both legacy per-song jobs and new batches drive the same persisted Rust queue. */
public final class AudioDownloadWorker extends Worker {
    public AudioDownloadWorker(Context ctx, WorkerParameters params) { super(ctx, params); }
    @Override public Result doWork() {
        QualityTasks.get(getApplicationContext());
        try {
            while (!isStopped()) {
                long delay = DownloadPlatform.pump();
                if (delay == 0) return Result.success();
                Thread.sleep(delay);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            android.util.Log.w("AudioDownload", "无法继续后台下载", e);
        }
        return Result.retry();
    }
}
