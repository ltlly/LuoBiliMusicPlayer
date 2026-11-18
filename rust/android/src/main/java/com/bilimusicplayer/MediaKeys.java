package com.bilimusicplayer;

import android.app.ForegroundServiceStartNotAllowedException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

/** Active sessions receive Android's transport callbacks directly. This receiver resumes an idle one. */
public final class MediaKeys extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) return;
        KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) return;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                try {
                    context.startForegroundService(new Intent(context, PlaybackService.class).setAction(Intent.ACTION_MEDIA_BUTTON));
                } catch (ForegroundServiceStartNotAllowedException e) {
                    android.util.Log.w("BiliMusicPlayer", "Android declined background media resumption", e);
                }
            }
        }
    }
}
