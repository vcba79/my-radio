package biz.amjet.radio;

import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Stream player activity.
 *
 * Codec/bitrate is fetched via a custom MediaSession command so we read the
 * data from the real ExoPlayer instance inside PlaybackService, not from the
 * MediaController IPC proxy (where Format.bitrate is often NO_VALUE).
 */
@UnstableApi
public class StreamPlayerActivity extends AppCompatActivity {

    // ── Views ──────────────────────────────────────────────────────────────────
    private PlayerView  videoPlayerView;
    private PlayerView  audioControlsView;
    private View        videoSurface;
    private View        audioPanel;
    private View        topControls;
    private ProgressBar progressBar;
    private TextView    statusText;
    private TextView    streamTitle;
    private TextView    streamDescription;
    private TextView    videoTitle;
    private TextView    videoDescription;
    private TextView    trackCodecInfo;

    // ── State ──────────────────────────────────────────────────────────────────
    private StreamItem  streamItem;
    private boolean     isVideo;

    // ── Media3 ────────────────────────────────────────────────────────────────
    private ListenableFuture<MediaController> controllerFuture;
    private MediaController                   controller;

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stream_player);

        streamItem = getIntent().getParcelableExtra("stream_item");
        isVideo    = streamItem != null && streamItem.getType() == StreamType.VIDEO;

        bindViews();
        applyInsets();
        applyMode();
        populateInfo();
    }

    @Override
    protected void onStart() {
        super.onStart();
        connectToService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        detachPlayerViews();
        releaseController();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ── Service connection ─────────────────────────────────────────────────────

    private void connectToService() {
        android.content.Intent serviceIntent =
                new android.content.Intent(this, PlaybackService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        SessionToken token = new SessionToken(
                this, new ComponentName(this, PlaybackService.class));

        controllerFuture = new MediaController.Builder(this, token).buildAsync();
        controllerFuture.addListener(() -> {
            try {
                controller = controllerFuture.get();
                onControllerReady();
            } catch (Exception e) {
                Toast.makeText(this,
                        getString(R.string.error_service_connect),
                        Toast.LENGTH_SHORT).show();
            }
        }, MoreExecutors.directExecutor());
    }

    private void onControllerReady() {
        if (controller == null || streamItem == null) return;

        if (isVideo) {
            videoPlayerView.setPlayer(controller);
        } else {
            audioControlsView.setPlayer(controller);
        }

        controller.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                switch (state) {
                    case Player.STATE_BUFFERING:
                        progressBar.setVisibility(View.VISIBLE);
                        setStatus(getString(R.string.status_buffering));
                        break;
                    case Player.STATE_READY:
                        progressBar.setVisibility(View.GONE);
                        setStatus(getString(controller.isPlaying()
                                ? R.string.status_playing : R.string.status_paused));
                        // Ask the service for real format data now that rendering has started
                        requestTrackInfoFromService();
                        break;
                    case Player.STATE_ENDED:
                        progressBar.setVisibility(View.GONE);
                        setStatus(getString(R.string.status_ended));
                        break;
                    default:
                        progressBar.setVisibility(View.GONE);
                        setStatus(getString(R.string.status_idle));
                        break;
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                setStatus(getString(playing ? R.string.status_playing : R.string.status_paused));
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                progressBar.setVisibility(View.GONE);
                setStatus(getString(R.string.status_error));
                Toast.makeText(StreamPlayerActivity.this,
                        getString(R.string.error_playback, error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        });

        MediaMetadata metadata = new MediaMetadata.Builder()
                .setTitle(streamItem.getTitle())
                .setDescription(streamItem.getDescription())
                .build();
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(streamItem.getUrl())
                .setMediaMetadata(metadata)
                .build();
        controller.stop();
        controller.setMediaItem(mediaItem);
        controller.setPlayWhenReady(true);
        controller.prepare();
    }

    /**
     * Sends a custom command to PlaybackService asking for the real
     * audio/video Format (codec string + bitrate) from ExoPlayer directly.
     * The result is delivered on the main thread via the ListenableFuture.
     */
    private void requestTrackInfoFromService() {
        if (controller == null) return;
        SessionCommand cmd = new SessionCommand(PlaybackService.CMD_GET_TRACK_INFO, Bundle.EMPTY);
        ListenableFuture<SessionResult> future =
                controller.sendCustomCommand(cmd, Bundle.EMPTY);
        future.addListener(() -> {
            try {
                SessionResult result = future.get();
                if (result.resultCode == SessionResult.RESULT_SUCCESS
                        && result.extras != null) {
                    String codecRaw = result.extras.getString(PlaybackService.KEY_CODEC, "");
                    int    bps      = result.extras.getInt(PlaybackService.KEY_BITRATE,
                                                           Format.NO_VALUE);
                    showTrackInfo(codecRaw, bps);
                }
            } catch (Exception e) {
                // Service not yet ready or disconnected — hide the field silently
                if (trackCodecInfo != null) trackCodecInfo.setVisibility(View.GONE);
            }
        }, MoreExecutors.directExecutor());
    }

    /**
     * Builds and displays the codec · bitrate line.
     * Called on the main thread via the ListenableFuture listener.
     */
    private void showTrackInfo(String codecRaw, int bps) {
        if (trackCodecInfo == null) return;

        String codec   = formatCodecLabel(codecRaw);
        String bitrate = (bps != Format.NO_VALUE && bps > 0)
                ? (Math.round(bps / 1000f)) + " kbps"
                : null;

        if (codec.equals("Unknown") && bitrate == null) {
            trackCodecInfo.setVisibility(View.GONE);
            return;
        }

        String info = bitrate != null ? codec + "  ·  " + bitrate : codec;
        trackCodecInfo.setText(info);
        trackCodecInfo.setVisibility(View.VISIBLE);
    }

    /**
     * Maps raw codec strings (e.g. "mp4a.40.2") to short readable labels.
     */
    private String formatCodecLabel(String raw) {
        if (raw == null || raw.isEmpty()) return "Unknown";
        String s = raw.trim().toLowerCase();

        if (s.startsWith("mp4a.40.2"))  return "AAC-LC";
        if (s.startsWith("mp4a.40.5"))  return "HE-AAC";
        if (s.startsWith("mp4a.40.29")) return "HE-AACv2";
        if (s.startsWith("mp4a"))       return "AAC";
        if (s.startsWith("mp3") || s.equals(".mp3")) return "MP3";
        if (s.startsWith("opus"))       return "Opus";
        if (s.startsWith("vorbis"))     return "Vorbis";
        if (s.startsWith("flac"))       return "FLAC";
        if (s.startsWith("avc")  || s.startsWith("h264")) return "H.264";
        if (s.startsWith("hev")  || s.startsWith("h265")) return "H.265";
        if (s.startsWith("av01"))       return "AV1";
        if (s.startsWith("vp09"))       return "VP9";
        if (s.startsWith("vp08"))       return "VP8";

        return raw.length() > 12 ? raw.substring(0, 12).toUpperCase() : raw.toUpperCase();
    }

    private void detachPlayerViews() {
        if (videoPlayerView   != null) videoPlayerView.setPlayer(null);
        if (audioControlsView != null) audioControlsView.setPlayer(null);
    }

    private void releaseController() {
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
            controller = null;
        }
    }

    // ── View setup ─────────────────────────────────────────────────────────────

    private void bindViews() {
        videoPlayerView   = findViewById(R.id.playerView);
        audioControlsView = findViewById(R.id.audioControlsView);
        videoSurface      = findViewById(R.id.videoSurface);
        audioPanel        = findViewById(R.id.audioPanel);
        topControls       = findViewById(R.id.topControls);
        progressBar       = findViewById(R.id.progressBar);
        statusText        = findViewById(R.id.statusText);
        streamTitle       = findViewById(R.id.streamTitle);
        streamDescription = findViewById(R.id.streamDescription);
        videoTitle        = findViewById(R.id.videoTitle);
        videoDescription  = findViewById(R.id.videoDescription);
        trackCodecInfo    = findViewById(R.id.trackCodecInfo);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        ImageButton btnPip = findViewById(R.id.btnPip);
        if (isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            btnPip.setVisibility(View.VISIBLE);
            btnPip.setOnClickListener(v -> enterPip());
        } else {
            btnPip.setVisibility(View.GONE);
        }
    }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(topControls, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    v.getPaddingLeft(),
                    insets.top + getResources().getDimensionPixelSize(R.dimen.player_top_padding),
                    v.getPaddingRight(),
                    v.getPaddingBottom());
            return WindowInsetsCompat.CONSUMED;
        });

        ViewCompat.setOnApplyWindowInsetsListener(audioPanel, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(
                    v.getPaddingLeft(), v.getPaddingTop(),
                    v.getPaddingRight(), insets.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    private void applyMode() {
        if (isVideo) {
            videoSurface.setVisibility(View.VISIBLE);
            audioPanel.setVisibility(View.GONE);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            videoSurface.setVisibility(View.GONE);
            audioPanel.setVisibility(View.VISIBLE);
        }
    }

    private void populateInfo() {
        if (streamItem == null) return;
        streamTitle.setText(streamItem.getTitle());
        streamDescription.setText(streamItem.getDescription());
        videoTitle.setText(streamItem.getTitle());
        videoDescription.setText(streamItem.getDescription());
    }

    private void setStatus(String text) {
        if (!isVideo && statusText != null) statusText.setText(text);
    }

    // ── Picture-in-Picture ─────────────────────────────────────────────────────

    private void enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .build();
            enterPictureInPictureMode(params);
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, @NonNull Configuration config) {
        super.onPictureInPictureModeChanged(isInPip, config);
        topControls.setVisibility(isInPip ? View.GONE : View.VISIBLE);
    }
}
