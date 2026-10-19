package com.ranovalabs.mirror;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.github.serezhka.airplay.server.Playback;

/**
 * ExoPlayer wrapper for AirPlay URL playback. All player access happens on the main thread;
 * {@link #info()} is read from Netty threads, so position/duration are mirrored into volatiles.
 */
final class MediaPlayerController {

    interface Listener {
        void onMediaStarted();

        void onMediaStopped();
    }

    private static final String TAG = "MediaPlayer";
    private static final long POLL_MS = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExoPlayer player;
    private final Listener listener;

    private volatile Runnable onEnded = () -> { };
    private volatile double duration;
    private volatile double position;
    private volatile double rate;
    private boolean active;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            snapshot();
            if (active) {
                main.postDelayed(this, POLL_MS);
            }
        }
    };

    MediaPlayerController(ExoPlayer player, Listener listener) {
        this.player = player;
        this.listener = listener;
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                snapshot();
                if (state == Player.STATE_ENDED) {
                    onEnded.run();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Playback failed", error);
            }
        });
    }

    /** The player, for attaching a {@code PlayerView} while the player screen is open. */
    ExoPlayer player() {
        return player;
    }

    void setOnEnded(Runnable onEnded) {
        this.onEnded = onEnded;
    }

    @OptIn(markerClass = UnstableApi.class)
    void play(String uri, float volume) {
        main.post(() -> {
            Log.i(TAG, "Playing " + uri);
            MediaItem.Builder item = new MediaItem.Builder().setUri(Uri.parse(uri));
            if (isHls(uri)) {
                item.setMimeType(MimeTypes.APPLICATION_M3U8);
            }
            player.setMediaItem(item.build());
            player.setVolume(volume);
            player.prepare();
            player.play();
            if (!active) {
                active = true;
                main.post(poll);
            }
            listener.onMediaStarted();
        });
    }

    void stop() {
        main.post(() -> {
            active = false;
            player.stop();
            player.clearMediaItems();
            duration = position = rate = 0;
            listener.onMediaStopped();
        });
    }

    void pause() {
        main.post(() -> {
            player.pause();
            snapshot();
        });
    }

    void resume() {
        main.post(() -> {
            player.play();
            snapshot();
        });
    }

    void seek(double seconds) {
        main.post(() -> {
            player.seekTo((long) (seconds * 1000));
            snapshot();
        });
    }

    void setVolume(float volume) {
        main.post(() -> player.setVolume(volume));
    }

    Playback.Info info() {
        return new Playback.Info(duration, position, rate);
    }

    void release() {
        active = false;
        main.removeCallbacks(poll);
        player.release();
    }

    private void snapshot() {
        long d = player.getDuration();
        duration = d == C.TIME_UNSET ? 0 : d / 1000.0;
        position = Math.max(0, player.getCurrentPosition()) / 1000.0;
        rate = player.isPlaying() || player.getPlayWhenReady() ? 1 : 0;
    }

    private static boolean isHls(String uri) {
        String lower = uri.toLowerCase();
        return lower.contains(".m3u8") || lower.contains("/playlist") || lower.startsWith("mlhls");
    }
}
