package com.bilimusicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;
import android.content.ComponentName;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;

public final class PlaybackService extends Service {
    static final String CHANNEL = "music_playback";
    static final int NOTIFICATION = 1;
    private MediaSession session;
    private String lastQueue = "";
    private final Map<Long, String> mediaIds = new HashMap<>();
    private static native void playMedia(String id);

    private long publishQueue(String json, String current) {
        if (json == null || current == null) return MediaSession.QueueItem.UNKNOWN_ID;
        if (!json.equals(lastQueue)) {
            try {
                JSONArray rows = new JSONArray(json);
                List<MediaSession.QueueItem> items = new ArrayList<>();
                mediaIds.clear();
                for (int i = 0; i < rows.length(); i++) {
                    JSONArray row = rows.getJSONArray(i);
                    String id = row.getString(0);
                    long key = queueId(id);
                    mediaIds.put(key, id);
                    MediaDescription entry = new MediaDescription.Builder()
                        .setMediaId(id).setTitle(row.getString(1)).setSubtitle(row.getString(2)).build();
                    items.add(new MediaSession.QueueItem(entry, key));
                }
                session.setQueue(items);
                lastQueue = json;
            } catch (JSONException e) { android.util.Log.w("BiliMusicPlayer", "Invalid media queue", e); }
        }
        return queueId(current);
    }
    private static long queueId(String id) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) hash = (hash ^ id.charAt(i)) * 0x100000001b3L;
        return hash & Long.MAX_VALUE;
    }
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private boolean resumeAfterFocus;
    private boolean playing;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeAfterFocus) PlayerActivity.mediaAction(1, 0);
            resumeAfterFocus = false;
        } else if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            resumeAfterFocus = playing && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
            if (playing) PlayerActivity.mediaAction(2, 0);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
            new NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "BiliMusicPlayer");
        session.setMediaButtonBroadcastReceiver(new ComponentName(this, MediaKeys.class));
        AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        session.setPlaybackToLocal(attributes);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focus).build();
        session.setQueueTitle("播放队列");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { PlayerActivity.mediaAction(1, 0); }
            @Override public void onPause() { resumeAfterFocus = false; PlayerActivity.mediaAction(2, 0); }
            @Override public void onSkipToNext() { PlayerActivity.mediaAction(3, 0); }
            @Override public void onSkipToPrevious() { PlayerActivity.mediaAction(4, 0); }
            @Override public void onStop() { resumeAfterFocus = false; PlayerActivity.mediaAction(13, 0); }
            @Override public void onFastForward() { PlayerActivity.mediaAction(14, 0); }
            @Override public void onRewind() { PlayerActivity.mediaAction(15, 0); }
            @Override public void onSkipToQueueItem(long key) {
                String id = mediaIds.get(key);
                if (id != null) playMedia(id);
            }
            @Override public void onPlayFromMediaId(String id, android.os.Bundle extras) { if (id != null) playMedia(id); }
            @Override public void onSeekTo(long position) { PlayerActivity.mediaAction(5, position); }
        });
        session.setActive(true);
    }

    private PendingIntent action(int id) {
        Intent intent = new Intent(this, PlaybackService.class).setAction("control").putExtra("id", id);
        return PendingIntent.getService(this, id, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if ("control".equals(intent.getAction())) {
            PlayerActivity.mediaAction(intent.getIntExtra("id", 0), 0);
            return START_NOT_STICKY;
        }
        boolean resuming = Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction());
        if (resuming && playing) return START_NOT_STICKY;
        String title = resuming ? "正在恢复播放" : intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        boolean active = intent.getBooleanExtra("playing", false);
        boolean startingPlayback = active && !playing;
        playing = active;
        String id = intent.getStringExtra("current");
        long queueId = publishQueue(intent.getStringExtra("queue"), id);
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, id)
            .putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, intent.getLongExtra("duration", 0)).build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActiveQueueItemId(queueId)
            .setActions(PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_STOP |
                        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
            .setState(resuming ? PlaybackState.STATE_BUFFERING : active ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                      intent.getLongExtra("position", 0), active ? 1f : 0f).build());
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session.setSessionActivity(open);
        Notification notice = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title).setContentText(artist).setContentIntent(open).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(active)
            .addAction(android.R.drawable.ic_media_previous, "上一首", action(4))
            .addAction(active ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                       active ? "暂停" : "播放", action(active ? 2 : 1))
            .addAction(android.R.drawable.ic_media_next, "下一首", action(3))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2)).build();
        startForeground(NOTIFICATION, notice);
        if (resuming) {
            // The foreground service must be started before a restored online track is resolved.
            PlayerPlatform.serviceStarted();
            QualityTasks.get(this);
            PlayerActivity.mediaAction(1, 0);
        } else if (startingPlayback && audio.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            PlayerActivity.mediaAction(2, 0);
        } else if (!active && !resumeAfterFocus) {
            audio.abandonAudioFocusRequest(focusRequest);
        }
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!playing) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        PlayerPlatform.serviceStopped();
        audio.abandonAudioFocusRequest(focusRequest);
        session.release();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
