package dev.airplaytv;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hardware H.264 decoder for the AirPlay screen-mirroring stream.
 * <p>
 * Input is Annex-B: either an SPS+PPS config blob (sent on start and on every rotation /
 * resolution change) or a frame's NAL units. A new config recreates the decoder so the
 * output size always matches what the sender is producing.
 * <p>
 * iOS sends a keyframe (IDR) only when a stream starts or changes shape; every later frame is a
 * delta. So a decoder that starts mid-stream, or loses a single frame, would stay frozen until
 * the next rotation. To avoid that, every frame since the last IDR is kept, and whenever the
 * decoder (re)starts (surface attached, app switched back, decoder error) that run is replayed,
 * and live frames are never dropped.
 */
final class VideoRenderer {

    interface Listener {
        /** Called on the decoder thread when the decoded picture size is known or changes. */
        void onVideoSize(int width, int height);
    }

    private static final String TAG = "VideoRenderer";
    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    /** Beyond this the run since the last IDR is discarded; a restart then waits for an IDR. */
    private static final int MAX_GOP_BYTES = 24 * 1024 * 1024;
    /** How long to wait for a decoder input buffer before giving up on a frame. */
    private static final long LIVE_INPUT_TIMEOUT_US = 500_000;
    private static final long REPLAY_INPUT_TIMEOUT_US = 1_000_000;

    private final Listener listener;
    private final ExecutorService replayExecutor = Executors.newSingleThreadExecutor();

    private Surface surface;
    private MediaCodec codec;
    private Thread outputThread;
    private byte[] config;

    /** Frames since (and including) the last IDR. */
    private final ArrayList<byte[]> gop = new ArrayList<>();
    private int gopBytes;
    private boolean gopValid;

    VideoRenderer(Listener listener) {
        this.listener = listener;
    }

    /**
     * Called on the main thread. Detaching is synchronous (the decoder must stop drawing before
     * the surface is destroyed); attaching replays on a worker so a long replay can't freeze the UI.
     */
    void setSurface(Surface surface) {
        synchronized (this) {
            if (surface == this.surface) {
                return; // the activity reports the same surface from more than one callback
            }
            this.surface = surface;
            releaseCodec();
        }
        if (surface != null) {
            replayExecutor.execute(() -> {
                synchronized (this) {
                    if (this.surface == surface && codec == null && config != null) {
                        startAndReplay();
                    }
                }
            });
        }
    }

    synchronized void feed(byte[] annexB) {
        if (annexB.length < 5) {
            return;
        }
        if (isConfigOnly(annexB)) { // SPS + PPS: stream (re)configuration
            if (!Arrays.equals(annexB, config)) {
                config = annexB;
                clearGop();
                releaseCodec();
                if (surface != null) {
                    startCodec();
                }
            }
            return;
        }
        record(annexB);
        if (codec != null && !queue(annexB, LIVE_INPUT_TIMEOUT_US)) {
            Log.w(TAG, "Decoder stalled or failed; restarting from the last keyframe");
            releaseCodec();
            if (surface != null) {
                startAndReplay();
            }
        }
    }

    /** Stream ended: drop the decoder and forget the stream. */
    synchronized void stop() {
        releaseCodec();
        clearGop();
        config = null;
    }

    private void startAndReplay() {
        startCodec();
        for (byte[] frame : gop) {
            if (!queue(frame, REPLAY_INPUT_TIMEOUT_US)) {
                Log.w(TAG, "Replay failed; waiting for the next keyframe");
                releaseCodec();
                return;
            }
        }
    }

    /** @return false if the frame couldn't be handed to the decoder */
    private boolean queue(byte[] annexB, long timeoutUs) {
        if (codec == null) {
            return false;
        }
        try {
            int index = codec.dequeueInputBuffer(timeoutUs);
            if (index < 0) {
                return false;
            }
            ByteBuffer input = codec.getInputBuffer(index);
            if (input == null || input.capacity() < annexB.length) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                return false;
            }
            input.clear();
            input.put(annexB);
            codec.queueInputBuffer(index, 0, annexB.length, System.nanoTime() / 1000, 0);
            return true;
        } catch (IllegalStateException e) {
            Log.w(TAG, "Decoder rejected input", e);
            return false;
        }
    }

    private void startCodec() {
        int split = secondStartCode(config);
        if (split < 0) {
            Log.w(TAG, "Config without PPS, ignoring");
            return;
        }
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, 1920, 1080);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(Arrays.copyOfRange(config, 0, split)));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(Arrays.copyOfRange(config, split, config.length)));
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            MediaCodec c = MediaCodec.createDecoderByType(MIME);
            c.configure(format, surface, null, 0);
            c.start();
            codec = c;
            outputThread = new Thread(() -> drain(c), "mirror-video-out");
            outputThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start H.264 decoder", e);
            codec = null;
        }
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!Thread.currentThread().isInterrupted()) {
            int index;
            try {
                index = c.dequeueOutputBuffer(info, 20_000);
            } catch (IllegalStateException e) {
                return; // codec released
            }
            if (index >= 0) {
                try {
                    // Render immediately: mirroring is live, there is no clock to sync to.
                    c.releaseOutputBuffer(index, true);
                } catch (IllegalStateException e) {
                    return;
                }
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = c.getOutputFormat();
                int w = f.getInteger(MediaFormat.KEY_WIDTH);
                int h = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                    w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                }
                if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) {
                    h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                }
                Log.i(TAG, "Mirroring resolution " + w + "x" + h);
                listener.onVideoSize(w, h);
            }
        }
    }

    private void releaseCodec() {
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }
        if (codec != null) {
            try {
                codec.stop();
            } catch (IllegalStateException ignored) {
            }
            codec.release();
            codec = null;
        }
    }

    private void record(byte[] frame) {
        if (containsNal(frame, 5)) {
            clearGop();
            gopValid = true;
        }
        if (!gopValid) {
            return; // undecodable without its keyframe
        }
        if (gopBytes + frame.length > MAX_GOP_BYTES) {
            clearGop(); // a later restart will have to wait for the next IDR
            return;
        }
        gop.add(frame);
        gopBytes += frame.length;
    }

    private void clearGop() {
        gop.clear();
        gopBytes = 0;
        gopValid = false;
    }

    /** True for a packet made only of SPS (7) / PPS (8) NAL units. */
    private static boolean isConfigOnly(byte[] data) {
        boolean any = false;
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                int type = data[i + 4] & 0x1F;
                if (type != 7 && type != 8) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    /** Whether any NAL unit in this Annex-B packet has the given type (5 = IDR slice). */
    private static boolean containsNal(byte[] data, int nalType) {
        // Emulation-prevention bytes guarantee 00 00 01 never appears inside a NAL payload.
        for (int i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1
                    && (data[i + 4] & 0x1F) == nalType) {
                return true;
            }
        }
        return false;
    }

    /** Index of the start code that begins the PPS in {@code 00 00 00 01 SPS 00 00 00 01 PPS}. */
    private static int secondStartCode(byte[] data) {
        for (int i = 4; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1) {
                return i;
            }
        }
        return -1;
    }
}
