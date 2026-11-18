package com.bilimusicplayer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/** Media3 owns the HTTP range requests, on-disk spans and cache index used by version 1.5.4. */
public final class StreamingOutput {
    private static SimpleCache cache;
    private static synchronized SimpleCache cache(Context ctx) {
        if (cache == null) cache = new SimpleCache(new File(ctx.getCacheDir(), "exoplayer_cache"),
                new LeastRecentlyUsedCacheEvictor(100L * 1024 * 1024), new StandaloneDatabaseProvider(ctx));
        return cache;
    }
    static long cacheBytes(Context ctx, boolean clear) {
        SimpleCache c = cache(ctx);
        if (clear) for (String key : c.getKeys()) c.removeResource(key);
        return c.getCacheSpace();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExoPlayer player;
    StreamingOutput(Context ctx) throws Exception {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .setDefaultRequestProperties(Collections.singletonMap("Referer", "https://www.bilibili.com"));
        CacheDataSource.Factory sources = new CacheDataSource.Factory().setCache(cache(ctx))
                .setUpstreamDataSourceFactory(http).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(ctx, sources);
        player = onPlayerThread(() -> {
            ExoPlayer output = new ExoPlayer.Builder(ctx)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(ctx).setDataSourceFactory(data))
                    .setAudioAttributes(new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setUsage(C.USAGE_MEDIA).build(), false) // PlaybackService owns the audio focus request.
                    .setHandleAudioBecomingNoisy(true).build();
            output.addListener(new Player.Listener() {
                @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                        PlayerActivity.mediaAction(2, 0);
                }
            });
            return output;
        });
    }
    private <T> T onPlayerThread(Callable<T> operation) throws Exception {
        if (Looper.myLooper() == handler.getLooper()) return operation.call();
        FutureTask<T> task = new FutureTask<>(operation);
        if (!handler.post(task)) throw new IllegalStateException("播放器线程已关闭");
        try { return task.get(); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception) throw (Exception)error.getCause();
            throw error;
        }
    }
    public void load(String location) throws Exception {
        Uri uri = location.startsWith("/") ? Uri.fromFile(new File(location)) : Uri.parse(location);
        onPlayerThread(() -> { player.setMediaItem(MediaItem.fromUri(uri)); player.prepare(); player.play(); return null; });
    }
    public void seek(long ms) throws Exception {
        onPlayerThread(() -> { player.seekTo(ms); return null; });
    }
    public void pause(boolean paused) throws Exception {
        onPlayerThread(() -> { player.setPlayWhenReady(!paused); return null; });
    }
    public void stop() throws Exception {
        onPlayerThread(() -> { player.stop(); return null; });
    }
    public long[] progress() throws Exception {
        return onPlayerThread(() -> {
            if (player.getPlayerError() != null) throw player.getPlayerError();
            return new long[]{player.getCurrentPosition(), Math.max(0, player.getDuration()),
                    player.getPlaybackState() == Player.STATE_ENDED ? 1 : 0};
        });
    }
    public void release() throws Exception { onPlayerThread(() -> { player.release(); return null; }); }
}
