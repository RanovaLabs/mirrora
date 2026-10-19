package dev.airplaytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

/**
 * Full-screen player and idle screen. The receiver itself lives in {@link AirPlayService}; this
 * screen just shows mirroring/video while it's in front. When the service opened it for a
 * session, it closes itself when that session ends, returning to whatever was on screen before.
 */
public class MainActivity extends Activity implements AirPlayService.Listener {

    private AspectFrameLayout mirrorFrame;
    private View idleOverlay;
    private TextView statusText;
    private TextView audioBadge;
    private TextView overlayHint;
    private Button overlayButton;
    private PlayerView playerView;

    private AirPlayService service;
    private Surface surface;
    private boolean autoOpened;
    private boolean sessionSeen;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AirPlayService.LocalBinder) binder).service();
            ((TextView) findViewById(R.id.name_text)).setText(service.receiverNameForDisplay());
            playerView.setPlayer(service.player());
            service.setSurface(surface);
            service.setPlayerVisible(true);
            service.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mirrorFrame = findViewById(R.id.mirror_frame);
        idleOverlay = findViewById(R.id.idle_overlay);
        statusText = findViewById(R.id.status_text);
        audioBadge = findViewById(R.id.audio_badge);
        overlayHint = findViewById(R.id.overlay_hint);
        overlayButton = findViewById(R.id.overlay_button);
        playerView = findViewById(R.id.player_view);
        overlayButton.setOnClickListener(v -> requestOverlayPermission());

        SurfaceView mirrorSurface = findViewById(R.id.mirror_surface);
        mirrorSurface.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                if (service != null) {
                    service.setSurface(surface);
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                surface = null;
                if (service != null) {
                    service.setSurface(null);
                }
            }
        });

        autoOpened = getIntent().getBooleanExtra(AirPlayService.EXTRA_AUTO_OPENED, false);
        AirPlayService.start(this); // first launch after install: switch the receiver on
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        autoOpened = intent.getBooleanExtra(AirPlayService.EXTRA_AUTO_OPENED, false);
        sessionSeen = false;
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, AirPlayService.class), connection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean needsPermission = !AirPlayService.canAutoOpen(this);
        overlayHint.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        overlayButton.setVisibility(needsPermission ? View.VISIBLE : View.GONE);
        if (needsPermission) {
            overlayButton.requestFocus();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (service != null) {
            service.setListener(null);
            service.setPlayerVisible(false);
            service.setSurface(null);
            service = null;
        }
        playerView.setPlayer(null);
        unbindService(connection);
    }

    // --- AirPlayService.Listener ----------------------------------------------------------

    @Override
    public void onState(AirPlayService.Mode mode, boolean audioOnly) {
        boolean media = mode == AirPlayService.Mode.MEDIA;
        boolean mirroring = mode == AirPlayService.Mode.MIRRORING;
        playerView.setVisibility(media ? View.VISIBLE : View.GONE);
        idleOverlay.setVisibility(media || mirroring ? View.GONE : View.VISIBLE);
        audioBadge.setVisibility(audioOnly ? View.VISIBLE : View.GONE);

        if (mode != AirPlayService.Mode.IDLE) {
            sessionSeen = true;
        } else if (autoOpened && sessionSeen) {
            finish(); // session over: hand the screen back
        }
    }

    @Override
    public void onVideoSize(int width, int height) {
        mirrorFrame.setAspectRatio(width, height);
    }

    @Override
    public void onStatus(String status) {
        statusText.setText(status);
    }

    // --- "Display over other apps" --------------------------------------------------------

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            try {
                // Some TV builds only have the list screen, not the per-app one.
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            } catch (ActivityNotFoundException e2) {
                // No settings screen at all on this TV: show the adb command instead.
                overlayHint.setText(getString(R.string.overlay_adb, getPackageName()));
                overlayButton.setVisibility(View.GONE);
            }
        }
    }
}
