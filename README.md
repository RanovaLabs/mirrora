# Mirrora

Screen mirroring from iPhone, iPad and Mac to Android TV and Google TV.

Mirrora runs on the TV as a receiver for the Screen Mirroring button in Control Center. It shows
your screen, photos and videos, and plays sound, including music sent straight to the TV.

- **Google Play:** https://play.google.com/store/apps/details?id=com.ranovalabs.mirror
- **Website, support and privacy:** https://ranovalabs.github.io/mirrora/

## Features
- **Screen mirroring:** hardware H.264 decoding. It follows rotation and letterboxes portrait
  screens. Frames since the last keyframe are kept, so the picture recovers instantly when the
  player reopens.
- **Sound:** AAC-ELD mirroring audio via MediaCodec. ALAC music is decoded by a built-in pure-Java
  decoder (`AlacDecoder`), since Android has none; it's tested bit-exact against Apple's encoder.
- **Always discoverable:** a foreground service starts at boot. It advertises over the system
  mDNS responder (NsdManager) and falls back to jmDNS on TVs whose responder never answers.
  Mirroring opens the full-screen player automatically when "Display over other apps" is
  allowed, and closes it when the session ends.
- **Mirroring only:** the receiver advertises UxPlay's feature mask (`0x5A7FFEE6`), so Photos,
  videos and YouTube all go through the mirror stream. The HLS/URL video path inherited from
  java-airplay is still in the code, but iOS won't use it.

## Build
Requires JDK 17 and the Android SDK (platform 36).

    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleDebug        # debug APK
    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew bundleRelease        # Play bundle
    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest    # tests

Release builds are signed with the upload key in `~/.mirrora-signing/keystore.properties` (or
the file named by the `mirroraSigningProperties` Gradle property). Without it they're unsigned.

The ALAC test fixtures in `app/src/test/resources/alac` were made with `afconvert -f caff -d alac`.

## Layout
- `app/src/main/java/com/ranovalabs/mirror`: the Android app. It contains the service, player
  screen, video and audio renderers, and the ALAC decoder.
- `app/src/main/java/com/github/serezhka/airplay`: the AirPlay protocol, vendored from
  [serezhka/java-airplay](https://github.com/serezhka/java-airplay) with Android changes marked
  "Android port".
- `docs/`: the website (GitHub Pages).
- `store/`: Play Store listing text and graphics, with their HTML sources.

## Limitations
- **Standby:** many TVs pause all apps in standby, so the TV can't be woken over the network
  the way an Apple TV can.
- **Copy protection:** Netflix, Apple TV+ and other services block their video from being
  mirrored to anything but an Apple TV.
- **Unofficial protocol:** the AirPlay support is reverse-engineered (legacy pairing, no PIN).
  It isn't Apple-certified and could break with a future iOS release.

## Licence
Mirrora is free software under the [GNU General Public License v3](LICENSE).
Copyright © 2026 Ranova Labs Ltd.

It includes code from java-airplay (MIT), whose FairPlay handshake derives from playfair (GPLv3),
and from curve25519-java (GPLv3). The full list of third-party notices is in
[`app/src/main/assets/open_source_licenses.txt`](app/src/main/assets/open_source_licenses.txt),
which the app shows under **About**.

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc. Mirrora is not affiliated with or
endorsed by Apple Inc.
