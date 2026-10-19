package dev.airplaytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;
import android.util.DisplayMetrics;

import androidx.media3.exoplayer.ExoPlayer;

import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayServer;
import com.github.serezhka.airplay.server.discovery.MdnsAdvertiser;

import java.net.Inet4Address;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps the AirPlay receiver running and discoverable while the TV is
 * on, whatever app is in front. Audio plays from here directly; when mirroring or video casting
 * starts, the full-screen {@link MainActivity} is brought up to show it.
 */
public class AirPlayService extends Service {

    enum Mode { IDLE, MIRRORING, MEDIA }

    /** Receives state on the main thread while the player screen is bound. */
    interface Listener {
        void onState(Mode mode, boolean audioOnly);

        void onVideoSize(int width, int height);

        void onStatus(String status);
    }

    static final String EXTRA_AUTO_OPENED = "auto_opened";

    private static final String TAG = "AirPlayService";
    private static final String CHANNEL_ID = "receiver";
    private static final int NOTIFICATION_ID = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
    private final LocalBinder binder = new LocalBinder();

    private VideoRenderer videoRenderer;
    private AudioRenderer audioRenderer;
    private MediaPlayerController mediaController;
    private AirPlayServer server;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private String receiverName;
    private String ip;
    private String status = "";
    private Mode mode = Mode.IDLE;
    private boolean audioActive;
    private int videoWidth;
    private int videoHeight;
    private boolean playerVisible;
    private Listener listener;

    /** Starts the service (and so the receiver) if it isn't running. Safe to call repeatedly. */
    static void start(Context context) {
        Intent intent = new Intent(context, AirPlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException e) {
            // Android 12+ refuses background starts outside the allowed triggers (boot, UI).
            Log.w(TAG, "Could not start receiver service", e);
        }
    }

    final class LocalBinder extends Binder {
        AirPlayService service() {
            return AirPlayService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AirPlayTV:session");
        wakeLock.setReferenceCounted(false);
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        //noinspection deprecation: HIGH_PERF is the only mode that works with the screen off
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AirPlayTV:session");
        wifiLock.setReferenceCounted(false);

        videoRenderer = new VideoRenderer((w, h) -> main.post(() -> {
            videoWidth = w;
            videoHeight = h;
            if (listener != null) {
                listener.onVideoSize(w, h);
            }
        }));
        audioRenderer = new AudioRenderer();
        mediaController = new MediaPlayerController(new ExoPlayer.Builder(this).build(),
                new MediaPlayerController.Listener() {
                    @Override
                    public void onMediaStarted() {
                        setMode(Mode.MEDIA);
                    }

                    @Override
                    public void onMediaStopped() {
                        if (mode == Mode.MEDIA) {
                            setMode(Mode.IDLE);
                        }
                    }
                });

        receiverName = receiverName();
        ip = localIpv4();
        startServer();
        watchNetwork();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // restart if the system kills us for memory
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (networkCallback != null) {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(networkCallback);
        }
        stopServer();
        mediaController.release();
        serverExecutor.shutdown();
        releaseLocks();
    }

    // --- API for the player screen ------------------------------------------------------

    String receiverNameForDisplay() {
        return receiverName;
    }

    ExoPlayer player() {
        return mediaController.player();
    }

    void setSurface(Surface surface) {
        videoRenderer.setSurface(surface);
    }

    /** The player screen reports whether it's in front, so we know when to bring it up. */
    void setPlayerVisible(boolean visible) {
        playerVisible = visible;
    }

    void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onStatus(status);
            listener.onState(mode, audioActive && mode == Mode.IDLE);
            if (videoWidth > 0) {
                listener.onVideoSize(videoWidth, videoHeight);
            }
        }
    }

    /** Whether we may open the player screen from the background (Android 10+ restriction). */
    static boolean canAutoOpen(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context);
    }

    // --- Receiver lifecycle -------------------------------------------------------------

    private void startServer() {
        setStatus(getString(R.string.status_starting));
        AndroidPlayback playback = new AndroidPlayback(videoRenderer, audioRenderer, mediaController,
                new AndroidPlayback.Ui() {
                    @Override
                    public void onMirroringStarted() {
                        main.post(() -> setMode(Mode.MIRRORING));
                    }

                    @Override
                    public void onMirroringStopped() {
                        main.post(() -> {
                            if (mode == Mode.MIRRORING) {
                                setMode(Mode.IDLE);
                            }
                        });
                    }

                    @Override
                    public void onAudioOnlyStarted() {
                        main.post(() -> setAudioActive(true));
                    }

                    @Override
                    public void onAudioStopped() {
                        main.post(() -> setAudioActive(false));
                    }
                });

        AirPlayConfig config = new AirPlayConfig();
        config.setServerName(receiverName);
        DisplayMetrics metrics = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(metrics);
        // Ask for at most 1080p: 4K mirroring is too heavy for most TV boxes' decoders.
        int w = Math.max(metrics.widthPixels, metrics.heightPixels);
        int h = Math.min(metrics.widthPixels, metrics.heightPixels);
        if (w > 1920) {
            h = h * 1920 / w;
            w = 1920;
        }
        config.setWidth(w);
        config.setHeight(h);
        config.setFps(60);

        AirPlayServer[] self = new AirPlayServer[1];
        MdnsAdvertiser advertiser = new MdnsAdvertiser(this, receiverName, deviceId(),
                (registered, problem) -> {
                    if (server != self[0]) {
                        return; // a receiver we've since replaced
                    }
                    // Only claim "Ready" once both records are live: iPhones list the TV under
                    // Screen Mirroring only if _airplay._tcp is advertised.
                    if (registered.size() == 2) {
                        setStatus(getString(R.string.status_ready, ip != null ? ip : getString(R.string.no_network)));
                    } else {
                        setStatus(getString(R.string.status_announcing,
                                problem != null ? problem : getString(R.string.status_announcing_wait)));
                    }
                });
        AirPlayServer s = new AirPlayServer(config, playback, advertiser);
        self[0] = s;
        server = s;
        serverExecutor.execute(() -> {
            try {
                s.start();
            } catch (Exception e) {
                Log.e(TAG, "Failed to start AirPlay server", e);
                main.post(() -> setStatus(getString(R.string.status_failed, e.getMessage())));
            }
        });
    }

    private void stopServer() {
        AirPlayServer s = server;
        server = null;
        if (s != null) {
            serverExecutor.execute(s::stop);
        }
        videoRenderer.stop();
        audioRenderer.stop();
        mediaController.stop();
    }

    /** Re-advertise when the TV's address changes (Wi-Fi reconnect, DHCP renewal, Ethernet). */
    private void watchNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                main.post(() -> {
                    String current = localIpv4();
                    if (current != null && !Objects.equals(current, ip)) {
                        Log.i(TAG, "Network changed (" + ip + " -> " + current + "), restarting receiver");
                        ip = current;
                        stopServer();
                        startServer();
                    }
                });
            }
        };
        cm.registerDefaultNetworkCallback(networkCallback);
    }

    // --- State ----------------------------------------------------------------------------

    private void setMode(Mode next) {
        if (mode == next) {
            return;
        }
        mode = next;
        if (next != Mode.IDLE && !playerVisible) {
            openPlayer();
        }
        onStateChanged();
    }

    private void setAudioActive(boolean active) {
        audioActive = active;
        onStateChanged();
    }

    private void onStateChanged() {
        boolean busy = mode != Mode.IDLE || audioActive;
        if (busy) {
            wakeLock.acquire(4 * 60 * 60 * 1000L); // bounded in case a session never ends cleanly
            wifiLock.acquire();
        } else {
            releaseLocks();
        }
        updateNotification();
        if (listener != null) {
            listener.onState(mode, audioActive && mode == Mode.IDLE);
        }
    }

    private void setStatus(String status) {
        this.status = status;
        if (listener != null) {
            listener.onStatus(status);
        }
    }

    private void openPlayer() {
        if (!canAutoOpen(this)) {
            Log.w(TAG, "Can't open the player from the background: 'Display over other apps' is off");
            return;
        }
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_AUTO_OPENED, true);
        startActivity(intent);
    }

    private void releaseLocks() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock.isHeld()) {
            wifiLock.release();
        }
    }

    // --- Notification ---------------------------------------------------------------------

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String text;
        switch (mode) {
            case MIRRORING:
                text = getString(R.string.notification_mirroring);
                break;
            case MEDIA:
                text = getString(R.string.notification_video);
                break;
            default:
                text = audioActive ? getString(R.string.audio_playing)
                        : getString(R.string.notification_idle, receiverName == null ? "" : receiverName);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return builder
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }

    // --- Identity -------------------------------------------------------------------------

    private String receiverName() {
        String name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        if (name == null || name.trim().isEmpty()) {
            name = Build.MODEL;
        }
        return name;
    }

    /**
     * AirPlay identifies receivers by a MAC-style device ID. Android hides the real MAC, so we
     * generate a locally administered one once and keep it, so iOS remembers this TV.
     */
    private String deviceId() {
        SharedPreferences prefs = getSharedPreferences("airplay", Context.MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null) {
            byte[] mac = new byte[6];
            new SecureRandom().nextBytes(mac);
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // unicast, locally administered
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mac.length; i++) {
                sb.append(String.format(Locale.US, "%02X", mac[i]));
                if (i < mac.length - 1) {
                    sb.append(':');
                }
            }
            id = sb.toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private String localIpv4() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        LinkProperties props = network != null ? cm.getLinkProperties(network) : null;
        if (props == null) {
            return null;
        }
        for (LinkAddress address : props.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address) {
                return address.getAddress().getHostAddress();
            }
        }
        return null;
    }
}
