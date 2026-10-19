package com.ranovalabs.mirror;

/**
 * Pure-Java Apple Lossless (ALAC) decoder producing interleaved 16-bit little-endian PCM.
 * <p>
 * Android ships no ALAC decoder, and AirPlay sends music (as opposed to mirroring audio) as
 * ALAC, so we decode it ourselves. Implements the ALAC bitstream as published in Apple's
 * open-source reference decoder: adaptive Golomb-Rice entropy coding, adaptive FIR prediction
 * and mid/side stereo decorrelation.
 */
final class AlacDecoder {

    private static final int ID_SCE = 0; // single channel element
    private static final int ID_CPE = 1; // channel pair element
    private static final int ID_LFE = 3;
    private static final int ID_DSE = 4; // data stream element
    private static final int ID_FIL = 6; // fill element
    private static final int ID_END = 7;

    private static final int MAX_CHANNELS = 2;

    // ALACSpecificConfig
    private final int frameLength;
    private final int bitDepth;
    private final int pb;
    private final int mb;
    private final int kb;
    private final int numChannels;

    private final int[][] samples;
    private final int[][] errors;
    private final int[][] extraBits;
    private final int[] coefs = new int[32];

    private byte[] data;
    private int bitPos;
    private int bitLimit;

    /**
     * @param cookie ALACSpecificConfig (24 bytes), optionally wrapped in 'frma'/'alac' atoms
     */
    AlacDecoder(byte[] cookie) {
        int o = 0;
        if (cookie.length >= 12 + o && isAtom(cookie, o, "frma")) {
            o += 12;
        }
        if (cookie.length >= 12 + o && isAtom(cookie, o, "alac")) {
            o += 12;
        }
        if (cookie.length < o + 24) {
            throw new IllegalArgumentException("ALAC magic cookie too short");
        }
        frameLength = readInt(cookie, o);
        bitDepth = cookie[o + 5] & 0xFF;
        pb = cookie[o + 6] & 0xFF;
        mb = cookie[o + 7] & 0xFF;
        kb = cookie[o + 8] & 0xFF;
        numChannels = cookie[o + 9] & 0xFF;
        if (numChannels < 1 || numChannels > MAX_CHANNELS || bitDepth < 8 || bitDepth > 32
                || frameLength <= 0 || frameLength > 65536) {
            throw new IllegalArgumentException("Unsupported ALAC config: " + numChannels + "ch "
                    + bitDepth + "bit " + frameLength + " frames");
        }
        samples = new int[MAX_CHANNELS][frameLength];
        errors = new int[MAX_CHANNELS][frameLength];
        extraBits = new int[MAX_CHANNELS][frameLength];
    }

    int channels() {
        return numChannels;
    }

    int maxOutputBytes() {
        return frameLength * numChannels * 2;
    }

    /**
     * Decodes one ALAC packet.
     *
     * @return number of PCM bytes written to {@code out}, 0 if the packet is corrupt
     */
    int decode(byte[] packet, int length, byte[] out) {
        data = packet;
        bitPos = 0;
        bitLimit = length * 8;
        int channel = 0;
        int frames = 0;
        try {
            while (bitLimit - bitPos >= 3) {
                int tag = readBits(3);
                if (tag == ID_END) {
                    break;
                } else if (tag == ID_SCE || tag == ID_LFE || tag == ID_CPE) {
                    int count = tag == ID_CPE ? 2 : 1;
                    if (channel + count > numChannels) {
                        return 0;
                    }
                    frames = decodeElement(count);
                    writePcm(out, channel, count, frames);
                    channel += count;
                } else if (tag == ID_DSE) {
                    readBits(4); // element instance tag
                    boolean align = readBits(1) == 1;
                    int count = readBits(8);
                    if (count == 255) {
                        count += readBits(8);
                    }
                    if (align) {
                        bitPos = (bitPos + 7) & ~7;
                    }
                    bitPos += count * 8;
                } else if (tag == ID_FIL) {
                    int count = readBits(4);
                    if (count == 15) {
                        count += readBits(8) - 1;
                    }
                    bitPos += count * 8;
                } else {
                    return 0; // CCE / PCE are never produced by Apple encoders
                }
                if (channel >= numChannels) {
                    break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            return 0; // truncated packet
        }
        return frames * numChannels * 2;
    }

    /** Decodes one SCE/CPE into {@link #samples}; returns the number of frames. */
    private int decodeElement(int channels) {
        readBits(4);  // element instance tag
        readBits(12); // unused, always 0
        boolean hasSize = readBits(1) == 1;
        int bytesShifted = readBits(2);
        boolean uncompressed = readBits(1) == 1;
        int shift = bytesShifted * 8;
        int chanBits = bitDepth - shift + channels - 1;

        int frames = frameLength;
        if (hasSize) {
            frames = (readBits(16) << 16) | readBits(16);
            if (frames <= 0 || frames > frameLength) {
                throw new ArrayIndexOutOfBoundsException("bad frame count");
            }
        }

        int mixBits = 0;
        int mixRes = 0;
        if (!uncompressed) {
            mixBits = readBits(8);
            mixRes = (byte) readBits(8);

            int[] mode = new int[channels];
            int[] denShift = new int[channels];
            int[] pbFactor = new int[channels];
            int[] order = new int[channels];
            int[][] chCoefs = new int[channels][32];
            for (int ch = 0; ch < channels; ch++) {
                mode[ch] = readBits(4);
                denShift[ch] = readBits(4);
                pbFactor[ch] = readBits(3);
                order[ch] = readBits(5);
                for (int i = 0; i < order[ch]; i++) {
                    chCoefs[ch][i] = (short) readBits(16);
                }
            }

            if (shift != 0) {
                for (int i = 0; i < frames; i++) {
                    for (int ch = 0; ch < channels; ch++) {
                        extraBits[ch][i] = readBits(shift);
                    }
                }
            }

            for (int ch = 0; ch < channels; ch++) {
                riceDecompress(errors[ch], frames, chanBits, pb * pbFactor[ch] / 4);
                System.arraycopy(chCoefs[ch], 0, coefs, 0, order[ch]);
                if (mode[ch] == 0) {
                    predict(errors[ch], samples[ch], frames, chanBits, coefs, order[ch], denShift[ch]);
                } else {
                    // Mode 15: an extra first-order integration pass before the FIR filter.
                    predict(errors[ch], errors[ch], frames, chanBits, null, 31, 0);
                    predict(errors[ch], samples[ch], frames, chanBits, coefs, order[ch], denShift[ch]);
                }
            }
        } else {
            for (int i = 0; i < frames; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    samples[ch][i] = readSigned(bitDepth);
                }
            }
            shift = 0;
        }

        if (channels == 2 && mixRes != 0) {
            int[] u = samples[0];
            int[] v = samples[1];
            for (int i = 0; i < frames; i++) {
                int left = u[i] + v[i] - ((mixRes * v[i]) >> mixBits);
                u[i] = left;
                v[i] = left - v[i];
            }
        }

        if (shift != 0) {
            for (int ch = 0; ch < channels; ch++) {
                for (int i = 0; i < frames; i++) {
                    samples[ch][i] = (samples[ch][i] << shift) | extraBits[ch][i];
                }
            }
        }
        return frames;
    }

    /** Adaptive Golomb-Rice decoding of the prediction residuals. */
    private void riceDecompress(int[] out, int frames, int chanBits, int historyMult) {
        int history = mb;
        int signModifier = 0;
        for (int i = 0; i < frames; i++) {
            int k = Math.min(log2((history >>> 9) + 3), kb);
            int x = readScalar(k, chanBits) + signModifier;
            signModifier = 0;
            out[i] = (x >>> 1) ^ -(x & 1);

            if (x > 0xFFFF) {
                history = 0xFFFF;
            } else {
                history += x * historyMult - ((history * historyMult) >> 9);
            }

            // Runs of zeros are coded as a block length when the history gets small.
            if (history < 128 && i + 1 < frames) {
                k = Math.min(7 - log2(history) + ((history + 16) >> 6), kb);
                int blockSize = readScalar(k, 16);
                if (blockSize > 0) {
                    if (blockSize >= frames - i) {
                        blockSize = frames - i - 1;
                    }
                    java.util.Arrays.fill(out, i + 1, i + 1 + blockSize, 0);
                    i += blockSize;
                }
                if (blockSize <= 0xFFFF) {
                    signModifier = 1;
                }
                history = 0;
            }
        }
    }

    private int readScalar(int k, int escapeBits) {
        int x = 0;
        while (x < 9 && readBits(1) == 1) {
            x++;
        }
        if (x > 8) {
            return readBits(escapeBits);
        }
        if (k != 1) {
            int extra = peekBits(k);
            x = (x << k) - x;
            if (extra > 1) {
                x += extra - 1;
                bitPos += k;
            } else {
                bitPos += k - 1;
            }
        }
        return x;
    }

    /** Adaptive FIR predictor (sign-LMS coefficient adaptation). {@code in} may equal {@code out}. */
    private static void predict(int[] in, int[] out, int frames, int chanBits, int[] coefs,
                                int order, int denShift) {
        int chanShift = 32 - chanBits;
        out[0] = in[0];
        if (frames <= 1) {
            return;
        }
        if (order == 0) {
            if (in != out) {
                System.arraycopy(in, 1, out, 1, frames - 1);
            }
            return;
        }
        if (order == 31) {
            for (int j = 1; j < frames; j++) {
                out[j] = ((in[j] + out[j - 1]) << chanShift) >> chanShift;
            }
            return;
        }

        int limit = Math.min(order, frames - 1);
        for (int j = 1; j <= limit; j++) {
            out[j] = ((in[j] + out[j - 1]) << chanShift) >> chanShift;
        }

        int denHalf = denShift > 0 ? 1 << (denShift - 1) : 0;
        for (int j = order + 1; j < frames; j++) {
            int top = out[j - order - 1];
            int sum = 0;
            for (int k = 0; k < order; k++) {
                sum += coefs[k] * (out[j - 1 - k] - top);
            }
            int err = in[j];
            int del = err + top + ((sum + denHalf) >> denShift);
            out[j] = (del << chanShift) >> chanShift;

            if (err > 0) {
                for (int k = order - 1; k >= 0; k--) {
                    int dd = top - out[j - 1 - k];
                    int sgn = Integer.signum(dd);
                    coefs[k] -= sgn;
                    err -= (order - k) * ((sgn * dd) >> denShift);
                    if (err <= 0) {
                        break;
                    }
                }
            } else if (err < 0) {
                for (int k = order - 1; k >= 0; k--) {
                    int dd = top - out[j - 1 - k];
                    int sgn = Integer.signum(dd);
                    coefs[k] += sgn;
                    err -= (order - k) * ((-sgn * dd) >> denShift);
                    if (err >= 0) {
                        break;
                    }
                }
            }
        }
    }

    private void writePcm(byte[] out, int firstChannel, int count, int frames) {
        int down = bitDepth - 16; // 20/24-bit streams are reduced to 16-bit for AudioTrack
        for (int ch = 0; ch < count; ch++) {
            int[] s = samples[ch];
            int o = (firstChannel + ch) * 2;
            int stride = numChannels * 2;
            for (int i = 0; i < frames; i++, o += stride) {
                int v = down > 0 ? s[i] >> down : s[i] << -down;
                out[o] = (byte) v;
                out[o + 1] = (byte) (v >> 8);
            }
        }
    }

    // --- bit reading --------------------------------------------------------------------

    private int readBits(int n) {
        int v = peekBits(n);
        bitPos += n;
        return v;
    }

    private int readSigned(int n) {
        return (readBits(n) << (32 - n)) >> (32 - n);
    }

    private int peekBits(int n) {
        if (n == 0) {
            return 0;
        }
        if (bitPos + n > bitLimit) {
            throw new ArrayIndexOutOfBoundsException("read past end of packet");
        }
        long v = 0;
        int byteIndex = bitPos >> 3;
        int needed = ((bitPos & 7) + n + 7) >> 3;
        for (int i = 0; i < needed; i++) {
            int idx = byteIndex + i;
            v = (v << 8) | (idx < data.length ? data[idx] & 0xFF : 0);
        }
        int drop = needed * 8 - (bitPos & 7) - n;
        return (int) ((v >>> drop) & ((1L << n) - 1));
    }

    private static int log2(int x) {
        return x <= 0 ? 0 : 31 - Integer.numberOfLeadingZeros(x);
    }

    private static int readInt(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static boolean isAtom(byte[] b, int o, String type) {
        return b[o + 4] == type.charAt(0) && b[o + 5] == type.charAt(1)
                && b[o + 6] == type.charAt(2) && b[o + 7] == type.charAt(3);
    }
}
