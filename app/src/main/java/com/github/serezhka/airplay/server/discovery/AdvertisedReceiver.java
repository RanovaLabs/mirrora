package com.github.serezhka.airplay.server.discovery;

/**
 * Receiver advertisement profile (mDNS TXT + {@code GET /info}).
 * <p>
 * Android port: mirroring-only mask {@code 0x5A7FFEE6}, the value UxPlay uses. Upstream
 * advertised {@code 0x5A7FFFF7,0x1E}, which adds AirPlay Video (bit 0), HTTP Live Streams
 * (bit 4), screen rotate (bit 8) and the video play-queue bits in the high word for YouTube.
 * With those set, iOS hands Photos videos off to a separate /play stream (which also tears down
 * the mirror connection) instead of showing them in the mirror. Mirroring-only keeps photos,
 * videos and YouTube flowing through the mirror stream.
 */
public final class AdvertisedReceiver {

    public static final int FEATURES_LO = 0x5A7FFEE6;
    public static final int FEATURES_HI = 0;

    /** 64-bit features for {@code GET /info} plist. */
    public static final long FEATURES = ((long) FEATURES_HI << 32) | (FEATURES_LO & 0xFFFFFFFFL);

    public static final String FEATURES_TXT = FEATURES_HI == 0
            ? String.format("0x%X", FEATURES_LO)
            : String.format("0x%X,0x%X", FEATURES_LO, FEATURES_HI);

    public static final String SOURCE_VERSION = "220.68";
    public static final String MODEL = "AppleTV3,2";
    public static final int STATUS_FLAGS = 0x44;
    public static final String MDNS_FLAGS = "0x44";
    public static final int VV = 2;

    private AdvertisedReceiver() {
    }

    public static String airTunesServerHeader() {
        return "AirTunes/" + SOURCE_VERSION;
    }
}
