package com.bilimusicplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

public final class PlaybackService extends Service {
    static final String CHANNEL = "music_playback";
    static final int NOTIFICATION = 1;
    private MediaSession session;
    private AudioManager audio;
    private boolean resumeAfterFocus;
    private boolean playing;
    private final AudioManager.OnAudioFocusChangeListener focus = change -> {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (resumeAfterFocus) PlayerActivity.mediaAction(1, 0);
            resumeAfterFocus = false;
        } else if (playing) {
            resumeAfterFocus = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
            PlayerActivity.mediaAction(2, 0);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
            new NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "BiliMusicPlayer");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { PlayerActivity.mediaAction(1, 0); }
            @Override public void onPause() { resumeAfterFocus = false; PlayerActivity.mediaAction(2, 0); }
            @Override public void onSkipToNext() { PlayerActivity.mediaAction(3, 0); }
            @Override public void onSkipToPrevious() { PlayerActivity.mediaAction(4, 0); }
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
        String title = intent.getStringExtra("title");
        String artist = intent.getStringExtra("artist");
        boolean active = intent.getBooleanExtra("playing", false);
        boolean startingPlayback = active && !playing;
        playing = active;
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, intent.getLongExtra("duration", 0)).build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE |
                        PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
            .setState(active ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
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
        if (startingPlayback && audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) PlayerActivity.mediaAction(2, 0);
        return START_NOT_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!playing) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        PlayerPlatform.serviceStopped();
        audio.abandonAudioFocus(focus);
        session.release();
        stopForeground(true);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
