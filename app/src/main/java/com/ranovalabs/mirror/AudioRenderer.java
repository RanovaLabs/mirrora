package com.ranovalabs.mirror;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo.CompressionType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Decodes AirPlay audio into an AudioTrack: AAC-ELD / AAC via MediaCodec, ALAC with our own
 * {@link AlacDecoder} (Android has none), and raw PCM.
 */
final class AudioRenderer {

    private static final String TAG = "AudioRenderer";
    /** ~1.5 s of 352-frame ALAC packets; beyond that the sender is ahead and we drop. */
    private static final int PCM_QUEUE_PACKETS = 192;

    private MediaCodec codec;
    private AlacDecoder alac;
    private byte[] alacPcm;
    private BlockingQueue<byte[]> pcmQueue;
    private AudioTrack track;
    private Thread outputThread;
    private CompressionType compression;
    private volatile float volume = 1f;

    synchronized void start(AudioStreamInfo info) {
        stop();
        compression = info.getCompressionType();
        int sampleRate = sampleRate(info);
        int channels = channels(info);
        int spf = info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 352;
        Log.i(TAG, "Audio " + info + " -> " + sampleRate + "Hz x" + channels);

        track = createTrack(sampleRate, channels);
        track.setVolume(volume);
        track.play();

        if (compression == CompressionType.LPCM || compression == CompressionType.ALAC) {
            if (compression == CompressionType.ALAC) {
                alac = new AlacDecoder(alacCookie(sampleRate, channels, spf, bitDepth(info)));
                alacPcm = new byte[alac.maxOutputBytes()];
            }
            // Decoded/raw PCM goes through a queue so a burst never blocks the network thread.
            BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(PCM_QUEUE_PACKETS);
            pcmQueue = queue;
            AudioTrack t = track;
            outputThread = new Thread(() -> writePcm(queue, t), "airplay-audio-pcm");
            outputThread.start();
            return;
        }
        try {
            MediaFormat format;
            String mime;
            switch (compression) {
                case AAC_ELD:
                    mime = MediaFormat.MIMETYPE_AUDIO_AAC;
                    format = MediaFormat.createAudioFormat(mime, sampleRate, channels);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(aacEldConfig(sampleRate, channels, spf)));
                    break;
                case AAC:
                    mime = MediaFormat.MIMETYPE_AUDIO_AAC;
                    format = MediaFormat.createAudioFormat(mime, sampleRate, channels);
                    format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(aacLcConfig(sampleRate, channels)));
                    break;
                default:
                    Log.w(TAG, "Unsupported audio compression " + compression);
                    return;
            }
            if (!hasDecoder(mime)) {
                Log.w(TAG, "This device has no decoder for " + mime + "; audio will be silent");
                return;
            }
            MediaCodec c = MediaCodec.createDecoderByType(mime);
            c.configure(format, null, null, 0);
            c.start();
            codec = c;
            AudioTrack t = track;
            outputThread = new Thread(() -> drain(c, t), "airplay-audio-out");
            outputThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start audio decoder", e);
            codec = null;
        }
    }

    synchronized void feed(byte[] data) {
        if (track == null) {
            return;
        }
        if (compression == CompressionType.ALAC) {
            int n = alac.decode(data, data.length, alacPcm);
            if (n > 0) {
                pcmQueue.offer(java.util.Arrays.copyOf(alacPcm, n));
            }
            return;
        }
        if (compression == CompressionType.LPCM) {
            // AirPlay PCM is big-endian 16-bit; AudioTrack wants little-endian.
            for (int i = 0; i + 1 < data.length; i += 2) {
                byte b = data[i];
                data[i] = data[i + 1];
                data[i + 1] = b;
            }
            pcmQueue.offer(data);
            return;
        }
        if (codec == null) {
            return;
        }
        try {
            int index = codec.dequeueInputBuffer(10_000);
            if (index < 0) {
                return;
            }
            ByteBuffer input = codec.getInputBuffer(index);
            input.clear();
            input.put(data);
            codec.queueInputBuffer(index, 0, data.length, System.nanoTime() / 1000, 0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "Audio decoder rejected input", e);
        }
    }

    void setVolume(float linear) {
        volume = linear;
        AudioTrack t = track;
        if (t != null) {
            t.setVolume(linear);
        }
    }

    synchronized void stop() {
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
        alac = null;
        pcmQueue = null;
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
        }
    }

    private void drain(MediaCodec c, AudioTrack t) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] pcm = new byte[0];
        while (!Thread.currentThread().isInterrupted()) {
            try {
                int index = c.dequeueOutputBuffer(info, 20_000);
                if (index < 0) {
                    continue;
                }
                ByteBuffer out = c.getOutputBuffer(index);
                if (out != null && info.size > 0) {
                    if (pcm.length < info.size) {
                        pcm = new byte[info.size];
                    }
                    out.position(info.offset);
                    out.get(pcm, 0, info.size);
                    t.write(pcm, 0, info.size);
                }
                c.releaseOutputBuffer(index, false);
            } catch (IllegalStateException e) {
                return; // codec or track released
            }
        }
    }

    private static void writePcm(BlockingQueue<byte[]> queue, AudioTrack t) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] pcm = queue.take();
                t.write(pcm, 0, pcm.length);
            }
        } catch (InterruptedException | IllegalStateException ignored) {
            // stopped
        }
    }

    private static AudioTrack createTrack(int sampleRate, int channels) {
        int channelMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    private static boolean hasDecoder(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Parses e.g. {@code AAC_ELD_44100_2} / {@code PCM_48000_16_2}; defaults to 44.1 kHz stereo. */
    private static int sampleRate(AudioStreamInfo info) {
        if (info.getAudioFormat() == null) {
            return 44100;
        }
        for (String part : info.getAudioFormat().name().split("_")) {
            if (part.matches("\\d{4,5}")) {
                return Integer.parseInt(part);
            }
        }
        return 44100;
    }

    /** Parses the bit depth from e.g. {@code ALAC_44100_24_2}; defaults to 16. */
    private static int bitDepth(AudioStreamInfo info) {
        if (info.getAudioFormat() != null) {
            String[] parts = info.getAudioFormat().name().split("_");
            if (parts.length == 4 && (parts[2].equals("16") || parts[2].equals("24"))) {
                return Integer.parseInt(parts[2]);
            }
        }
        return 16;
    }

    private static int channels(AudioStreamInfo info) {
        if (info.getAudioFormat() == null) {
            return 2;
        }
        String name = info.getAudioFormat().name();
        return name.endsWith("_1") ? 1 : 2;
    }

    private static int frequencyIndex(int sampleRate) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == sampleRate) {
                return i;
            }
        }
        return 4;
    }

    /** AudioSpecificConfig for AAC-LC. */
    private static byte[] aacLcConfig(int sampleRate, int channels) {
        int bits = (2 << 11) | (frequencyIndex(sampleRate) << 7) | (channels << 3);
        return new byte[]{(byte) (bits >> 8), (byte) bits};
    }

    /**
     * AudioSpecificConfig for AAC-ELD (object type 39, escaped as 31+7). For 44.1 kHz stereo
     * with 480-sample frames this yields {@code F8 E8 50 00}, the value every AirPlay receiver uses.
     */
    private static byte[] aacEldConfig(int sampleRate, int channels, int samplesPerFrame) {
        long bits = 0;
        int n = 0;
        bits = (bits << 5) | 31; n += 5;                          // audioObjectType escape
        bits = (bits << 6) | (39 - 32); n += 6;                   // audioObjectTypeExt -> ELD
        bits = (bits << 4) | frequencyIndex(sampleRate); n += 4;
        bits = (bits << 4) | channels; n += 4;
        bits = (bits << 1) | (samplesPerFrame == 480 ? 1 : 0); n += 1; // frameLengthFlag
        bits = (bits << 3); n += 3;                               // section/scalefactor/spectral resilience
        bits = (bits << 1); n += 1;                               // ldSbrPresentFlag
        bits = (bits << 4); n += 4;                               // ELDEXT_TERM
        int pad = (8 - n % 8) % 8;
        bits <<= pad;
        n += pad;
        byte[] out = new byte[n / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (bits >> (8 * (out.length - 1 - i)));
        }
        return out;
    }

    /** ALACSpecificConfig ("magic cookie") matching what AirPlay senders use. */
    private static byte[] alacCookie(int sampleRate, int channels, int samplesPerFrame, int bitDepth) {
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        b.putInt(samplesPerFrame); // frameLength
        b.put((byte) 0);           // compatibleVersion
        b.put((byte) bitDepth);
        b.put((byte) 40);          // pb
        b.put((byte) 10);          // mb
        b.put((byte) 14);          // kb
        b.put((byte) channels);    // numChannels
        b.putShort((short) 255);   // maxRun
        b.putInt(0);               // maxFrameBytes
        b.putInt(0);               // avgBitRate
        b.putInt(sampleRate);
        return b.array();
    }
}
