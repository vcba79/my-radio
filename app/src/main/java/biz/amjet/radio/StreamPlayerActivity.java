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
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.ui.PlayerView;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

/**
 * Stream player activity.
 *
 * Playback is owned by {@link PlaybackService} (a MediaSessionService).
 * This activity connects a {@link MediaController} to the service so that:
 *   - Audio/video keeps playing when the activity is backgrounded.
 *   - The system media notification shows transport controls.
 *   - Reconnecting (e.g. rotating screen) picks up the running session.
 */
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

    // ── State ──────────────────────────────────────────────────────────────────
    private StreamItem  streamItem;
    private boolean     isVideo;

    // ── Media3 controller (talks to PlaybackService) ───────────────────────────
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
        // Detach PlayerView from controller but leave service running
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
        // Explicitly start the service before connecting the controller.
        // On Android 8 (API 26) the system will not auto-start a MediaSessionService
        // from a foreground activity without this call, causing the controller
        // future to silently time out and drop all subsequent playback commands.
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

    /**
     * Called once the MediaController is connected to PlaybackService.
     * Attaches the controller to the UI and starts playback if needed.
     */
    private void onControllerReady() {
        if (controller == null || streamItem == null) return;

        // Attach controller to the correct PlayerView
        if (isVideo) {
            videoPlayerView.setPlayer(controller);
        } else {
            audioControlsView.setPlayer(controller);
        }

        // Observe playback state for the status label
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

        // Always load the requested stream unconditionally.
        // Attempting a URL comparison via localConfiguration is unreliable because
        // localConfiguration can be null on the MediaController side (IPC boundary),
        // causing the new stream to silently not load. stop() first so the service
        // cleanly releases the previous stream before preparing the new one.
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
