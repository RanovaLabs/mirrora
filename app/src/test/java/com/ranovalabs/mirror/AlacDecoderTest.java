package com.ranovalabs.mirror;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Fixtures are encoded by Apple's own ALAC encoder ({@code afconvert -f caff -d alac}); the
 * {@code .raw} files are the source PCM (16-bit LE; the 24-bit source reduced to its top 16 bits).
 * ALAC is lossless, so decoding must be bit-exact.
 */
public class AlacDecoderTest {

    @Test
    public void decodesStereo16BitExactly() throws IOException {
        assertBitExact("stereo");
    }

    @Test
    public void decodesMono16BitExactly() throws IOException {
        assertBitExact("mono");
    }

    @Test
    public void decodesStereo24BitWithShiftedBytes() throws IOException {
        assertBitExact("hires");
    }

    @Test
    public void rejectsTruncatedPacketWithoutThrowing() throws IOException {
        Caf caf = Caf.parse(resource("stereo.caf"));
        AlacDecoder decoder = new AlacDecoder(caf.cookie);
        byte[] firstPacket = Arrays.copyOf(caf.audio, caf.packetSizes.get(0));
        int written = decoder.decode(firstPacket, firstPacket.length / 3, new byte[decoder.maxOutputBytes()]);
        assertTrue(written == 0);
    }

    private void assertBitExact(String name) throws IOException {
        Caf caf = Caf.parse(resource(name + ".caf"));
        byte[] expected = resource(name + ".raw");
        AlacDecoder decoder = new AlacDecoder(caf.cookie);

        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        byte[] out = new byte[decoder.maxOutputBytes()];
        int offset = 0;
        for (int size : caf.packetSizes) {
            byte[] packet = Arrays.copyOfRange(caf.audio, offset, offset + size);
            offset += size;
            int written = decoder.decode(packet, packet.length, out);
            assertTrue("packet at byte " + offset + " failed to decode", written > 0);
            pcm.write(out, 0, written);
        }
        assertArrayEquals(expected, pcm.toByteArray());
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = AlacDecoderTest.class.getResourceAsStream("/alac/" + name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, n);
            }
            return buffer.toByteArray();
        }
    }

    /** Minimal Core Audio Format reader: magic cookie, packet table and audio data. */
    private static final class Caf {
        byte[] cookie;
        byte[] audio;
        final List<Integer> packetSizes = new ArrayList<>();

        static Caf parse(byte[] file) {
            Caf caf = new Caf();
            ByteBuffer b = ByteBuffer.wrap(file);
            b.position(8); // 'caff', version, flags
            while (b.remaining() >= 12) {
                byte[] type = new byte[4];
                b.get(type);
                byte[] body = new byte[(int) b.getLong()];
                b.get(body);
                switch (new String(type, StandardCharsets.US_ASCII)) {
                    case "kuki":
                        caf.cookie = body;
                        break;
                    case "data":
                        caf.audio = Arrays.copyOfRange(body, 4, body.length); // skip edit count
                        break;
                    case "pakt":
                        ByteBuffer p = ByteBuffer.wrap(body);
                        long packets = p.getLong();
                        p.position(24); // valid frames, priming, remainder
                        for (long i = 0; i < packets; i++) {
                            int value = 0;
                            int octet;
                            do {
                                octet = p.get() & 0xFF;
                                value = (value << 7) | (octet & 0x7F);
                            } while ((octet & 0x80) != 0);
                            caf.packetSizes.add(value);
                        }
                        break;
                    default:
                        break;
                }
            }
            return caf;
        }
    }
}
