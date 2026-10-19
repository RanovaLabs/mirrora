# AirPlay TV

An Android TV app that turns the TV into an AirPlay receiver for iPhone, iPad and Mac.

## What works
- **Screen mirroring:** hardware H.264 decoding. It follows rotation and letterboxes portrait phones.
- **Mirroring audio:** AAC-ELD.
- **Music-only AirPlay:** ALAC audio (e.g. from the Music app) is decoded by a built-in
  pure-Java decoder (`AlacDecoder`), since Android has none. It's tested bit-exact against Apple's
  encoder: 16-bit stereo and mono, plus 24-bit.
- **Always discoverable:** the receiver runs as a background service and starts when the TV boots.
  Music plays in the background; mirroring and video open the player automatically and close it
  when the session ends, returning to whatever was on screen.
- **Photos, videos and other apps:** shown through the mirror stream. The receiver advertises
  mirroring only (UxPlay's feature set), so iOS doesn't hand videos off to a separate stream.
  The HLS / URL video path inherited from java-airplay is still in the code but iOS won't use it.

## Install on the TV
1. On the TV, turn on Developer options and USB/network debugging.
2. `adb connect <tv-ip>:5555`
3. `adb install app/build/outputs/apk/release/app-release.apk`
4. Open **AirPlay TV** once from the TV's app list. This switches the receiver on; from then on
   it runs in the background and after every reboot.
5. Press **Allow** on that screen to enable "Display over other apps". This lets mirroring and
   video pop up on their own. Some TVs hide this setting; if so, run
   `adb shell appops set dev.airplaytv SYSTEM_ALERT_WINDOW allow`.
   Without it, music still works, but you have to open the app to see mirroring or video.
6. On the Apple device, open Control Center → Screen Mirroring and pick the TV.

The TV and the Apple device must be on the same network (and subnet).

## Build
Requires JDK 17 and the Android SDK (API 34).

    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew assembleRelease

## Tests

    JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest

The ALAC fixtures in `app/src/test/resources/alac` were made with `afconvert -f caff -d alac`.

## Limitations
- **Standby:** in deep standby many TVs suspend all apps, and the receiver can't be woken over
  the network the way an Apple TV can. This depends on the TV's power settings (look for
  "Quick start", "Network standby" or similar).
- **Battery savers:** aggressive TV "memory optimizers" may still kill background apps. The
  service asks to be restarted, but some firmware ignores that.
- **Protocol:** this is a reverse-engineered AirPlay implementation (legacy pairing, no password
  or PIN). It isn't Apple-certified and may break with future iOS releases.
- **Release signing:** release builds are signed with the debug key so they sideload easily.
  Use a real keystore before distributing.

## Credits
The AirPlay protocol code (`com.github.serezhka.airplay.*`) is vendored from
[serezhka/java-airplay](https://github.com/serezhka/java-airplay) (MIT, see
`THIRD_PARTY_LICENSE-java-airplay.txt`), with Android changes marked "Android port":
- mDNS via NsdManager
- NIO transport
- API-level fixes
- startup race fix
