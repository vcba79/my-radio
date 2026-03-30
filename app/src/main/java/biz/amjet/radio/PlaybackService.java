package biz.amjet.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * Long-lived foreground service that owns the ExoPlayer instance.
 * The activity binds to this service via MediaController; playback
 * continues uninterrupted when the app is backgrounded or the screen
 * is turned off.
 *
 * A media notification with transport controls is shown automatically
 * by Media3 while the service is running.
 */
public class PlaybackService extends MediaSessionService {

    private MediaSession mediaSession;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    private static final String CHANNEL_ID = "playback_channel";
    private static final int    NOTIF_ID   = 1;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

        // On Android 8+ startForegroundService() requires startForeground()
        // to be called within 5 seconds of onCreate — do it immediately.
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_playback_ready))
                .setSmallIcon(R.drawable.ic_play_pause)
                .setOngoing(true)
                .build();
        startForeground(NOTIF_ID, notification);

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build();

        ExoPlayer player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        mediaSession = new MediaSession.Builder(this, player).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW   // silent — no sound/vibration
            );
            channel.setDescription(getString(R.string.notif_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Stop service (and notification) when app is swiped away from recents
        if (mediaSession != null) {
            mediaSession.getPlayer().stop();
        }
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.getPlayer().release();
            mediaSession.release();
            mediaSession = null;
        }
        super.onDestroy();
    }
}
