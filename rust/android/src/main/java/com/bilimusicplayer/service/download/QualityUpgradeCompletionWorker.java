package com.bilimusicplayer.service.download;

import android.content.Context;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.bilimusicplayer.QualityTasks;

/** Same class name as the old WorkManager queue entries. */
public final class QualityUpgradeCompletionWorker extends Worker {
    public QualityUpgradeCompletionWorker(Context context, WorkerParameters params) { super(context, params); }
    @Override public Result doWork() {
        if (!isStopped()) QualityTasks.get(getApplicationContext()).finish();
        return Result.success();
    }
}
