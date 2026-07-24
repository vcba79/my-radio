package biz.amjet.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionError;
import androidx.media3.session.SessionResult;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * Long-lived foreground service that owns the ExoPlayer instance.
 *
 * Exposes a custom session command {@link #CMD_GET_TRACK_INFO} so the
 * activity can ask for the real codec + bitrate that ExoPlayer has resolved
 * (format data is not reliably forwarded across the IPC boundary via
 * MediaController.getCurrentTracks()).
 */
@UnstableApi
public class PlaybackService extends MediaSessionService {

    /** Custom command sent by the activity to request codec/bitrate info. */
    public static final String CMD_GET_TRACK_INFO = "biz.amjet.radio.GET_TRACK_INFO";

    /** Keys in the reply Bundle. */
    public static final String KEY_CODEC   = "codec";
    public static final String KEY_BITRATE = "bitrate"; // int, bps; -1 if unknown

    private MediaSession mediaSession;
    private ExoPlayer    player;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    private static final String CHANNEL_ID = "playback_channel";
    private static final int    NOTIF_ID   = 1;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannel();

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

        player = new ExoPlayer.Builder(this)
                .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
                .setHandleAudioBecomingNoisy(true)
                .build();

        mediaSession = new MediaSession.Builder(this, player)
                .setCallback(new TrackInfoCallback())
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
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

    // ── Custom session callback ────────────────────────────────────────────────

    /**
     * Handles the CMD_GET_TRACK_INFO custom command.
     * Reads format directly from ExoPlayer (same process as the service),
     * so bitrate and codec are always populated when a track is selected.
     */
    private class TrackInfoCallback implements MediaSession.Callback {

        @Override
        @NonNull
        public MediaSession.ConnectionResult onConnect(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller) {
            // Accept all connections and advertise the custom command
            MediaSession.ConnectionResult result =
                    MediaSession.Callback.super.onConnect(session, controller);
            return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(
                            result.availableSessionCommands.buildUpon()
                                    .add(new SessionCommand(CMD_GET_TRACK_INFO, Bundle.EMPTY))
                                    .build())
                    .build();
        }

        @Override
        @NonNull
        public ListenableFuture<SessionResult> onCustomCommand(
                @NonNull MediaSession session,
                @NonNull MediaSession.ControllerInfo controller,
                @NonNull SessionCommand command,
                @NonNull Bundle args) {

            if (!CMD_GET_TRACK_INFO.equals(command.customAction)) {
                return Futures.immediateFuture(
                        new SessionResult(SessionError.ERROR_NOT_SUPPORTED));
            }

            Bundle reply = new Bundle();
            Format audioFmt = player.getAudioFormat();   // non-null only when audio is rendering
            Format videoFmt = player.getVideoFormat();   // non-null only when video is rendering
            Format fmt = audioFmt != null ? audioFmt : videoFmt;

            if (fmt != null) {
                reply.putString(KEY_CODEC,   fmt.codecs != null ? fmt.codecs : "");
                int bps = fmt.bitrate != Format.NO_VALUE
                        ? fmt.bitrate
                        : fmt.peakBitrate;
                reply.putInt(KEY_BITRATE, bps); // may still be NO_VALUE (-1) for live streams
            } else {
                reply.putString(KEY_CODEC,   "");
                reply.putInt(KEY_BITRATE, Format.NO_VALUE);
            }

            return Futures.immediateFuture(new SessionResult(SessionResult.RESULT_SUCCESS, reply));
        }
    }
}
